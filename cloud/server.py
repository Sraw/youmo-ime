# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
"""The scoring server the cloud build of 幽默输入法 can ask while the user pauses (see README.md).

POST /score    {"context": "...", "candidates": ["...", ...]}   -> {"scores": [log P, ...]}
GET  /health                                                    -> {"model": "...", "gpu_peak_gb": ...}
GET  /words                                                     -> {"name": "...", "text": "..."}: the word pack --words names, or 404

A score is log P(candidate | context) in nats, as the training measured it (dev/training/score_lists.py):
the context, a newline when there is none, and the candidate are tokenized apart and only the
candidate's tokens are summed. Tokenizing them as one string cost the 0.8B five points on chat.

It serves HTTPS with a key of its own, made at the first start, and prints that key's fingerprint:
the phone shows the key it sees when it is turned on, and trusts that one only, so the user
compares the two once. --plain serves HTTP, to 127.0.0.1 only, for ime-eval on the same machine:
the phone takes HTTPS only, even to 127.0.0.1 (adb reverse carries HTTPS as it is).
"""
import argparse
import datetime
import hashlib
import hmac
import json
import math
import os
import re
import ssl
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_CANDIDATES = 16
MAX_CHARS = 64
MAX_BODY = 16384
MAX_CONNECTIONS = 32
TIMEOUT = 15  # seconds a connection may be idle or slow, in the handshake as after it
LOWEST = -1e4  # a score below it is no likelier; -inf would not be JSON
TOKEN = re.compile(r'[!-~]*')  # what goes in a header as it is: printable ASCII, no spaces
WORDS_NAME = re.compile(r'([A-Za-z0-9_-][A-Za-z0-9._-]{0,63})\.words')  # a file name the phone keeps it under


class Model:
    def __init__(self, name, device, four_bit, context_chars):
        import torch
        from transformers import AutoModelForCausalLM, AutoTokenizer
        self.torch = torch
        self.name = name
        self.context_chars = context_chars
        self.device = device or ('cuda' if torch.cuda.is_available() else 'cpu')
        self.tok = AutoTokenizer.from_pretrained(name)
        options = {}
        if four_bit:
            from transformers import BitsAndBytesConfig
            options['quantization_config'] = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_compute_dtype=torch.bfloat16,
                                                                bnb_4bit_quant_type='nf4')
            options['device_map'] = self.device
        dtype = torch.bfloat16 if self.device != 'cpu' else torch.float32
        self.lm = AutoModelForCausalLM.from_pretrained(name, dtype=dtype, **options)
        if not four_bit:
            self.lm.to(self.device)
        self.lm.eval()
        # one request at a time: the model is the bottleneck, and batches of one keep latency flat
        self.lock = threading.Lock()

    def health(self):
        answer = {'model': self.name}
        if self.device.startswith('cuda'):  # what a card needs, at most so far
            answer['gpu_peak_gb'] = round(self.torch.cuda.max_memory_allocated() / 2**30, 2)
        return answer

    def prefix(self, context):
        return self.tok.encode(context[-self.context_chars:] or '\n', add_special_tokens=False)

    def score(self, context, candidates):
        torch = self.torch
        prefix = self.prefix(context)
        seqs = [(prefix + self.tok.encode(c, add_special_tokens=False), len(prefix)) for c in candidates]
        n = max(len(s) for s, _ in seqs)
        with self.lock, torch.no_grad():
            ids = torch.tensor([s + [0] * (n - len(s)) for s, _ in seqs], device=self.device)
            mask = torch.tensor([[1] * len(s) + [0] * (n - len(s)) for s, _ in seqs], device=self.device)
            cand = torch.tensor([[0] * st + [1] * (len(s) - st) + [0] * (n - len(s)) for s, st in seqs], device=self.device)
            logits = self.lm(input_ids=ids, attention_mask=mask).logits.float()
            lp = torch.log_softmax(logits[:, :-1], -1).gather(2, ids[:, 1:, None])[..., 0]
            return [round(x, 4) if math.isfinite(x) and x > LOWEST else LOWEST for x in (lp * cand[:, 1:]).sum(1).tolist()]


def word_pack(path):
    """The word pack at [path] as /words answers it, read again when the file changes; None without a path."""
    cache = {}

    def current():
        if not path:
            return None
        stamp = os.stat(path).st_mtime_ns
        if cache.get('stamp') != stamp:
            with open(path, encoding='utf-8') as f:
                text = f.read()
            if not text.startswith(('\ufeff# youmo words 1', '# youmo words 1')):
                raise ValueError(f'{path}: not a word pack')
            cache.update(stamp=stamp, answer={'name': WORDS_NAME.fullmatch(os.path.basename(path)).group(1), 'text': text.lstrip('\ufeff')})
        return cache['answer']

    return current


def handler(model, token, words=lambda: None):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'
        timeout = TIMEOUT

        def log_error(self, format, *args):
            # the phone keeps its connection for the next question: one let go after a pause is no error
            if not format.startswith('Request timed out'):
                super().log_error(format, *args)

        def reply(self, code, body):
            # after an error what is left of the request is not read: the connection cannot go on
            self.close_connection = self.close_connection or code != 200
            data = json.dumps(body, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def authorized(self):
            if not token:
                return True
            given = self.headers.get('Authorization', '')
            return hmac.compare_digest(given.encode(), f'Bearer {token}'.encode())

        def do_GET(self):
            if not self.authorized():
                return self.reply(401, {'error': 'unauthorized'})
            if self.path == '/health':
                return self.reply(200, model.health())
            if self.path == '/words':
                try:
                    pack = words()
                except (OSError, ValueError) as e:  # the file went, or was replaced by something else
                    self.log_message('/words failed: %r', e)
                    return self.reply(500, {'error': 'word pack unreadable'})
                return self.reply(200, pack) if pack else self.reply(404, {'error': 'no word pack'})
            self.reply(404, {'error': 'not found'})

        def do_POST(self):
            if not self.authorized():
                return self.reply(401, {'error': 'unauthorized'})
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= MAX_BODY:
                    return self.reply(413, {'error': 'body size'})
                body = json.loads(self.rfile.read(length))
                if not isinstance(body, dict):
                    raise ValueError('not an object')
                context = body.get('context', '')
                if not isinstance(context, str):
                    raise ValueError('context')
                started = time.monotonic()
                if self.path == '/score':
                    cands = body['candidates']
                    if not (isinstance(cands, list) and 0 < len(cands) <= MAX_CANDIDATES
                            and all(isinstance(c, str) and 0 < len(c) <= MAX_CHARS for c in cands)):
                        raise ValueError('candidates')
                    result = {'scores': model.score(context, cands)}
                else:
                    return self.reply(404, {'error': 'not found'})
                self.log_message('%s %.0f ms', self.path, (time.monotonic() - started) * 1000)
                self.reply(200, result)
            except (ValueError, KeyError, TypeError, OverflowError, json.JSONDecodeError) as e:
                self.reply(400, {'error': f'bad request: {e}'})
            except Exception as e:  # the model's (out of memory, say): an answer all the same
                self.log_message('%s failed: %r', self.path, e)
                self.reply(500, {'error': 'model failed'})

    return Handler


class Server(ThreadingHTTPServer):
    """Each connection's TLS handshake in its own thread: in accept(), one client that never
    finished it would hold up every other."""
    daemon_threads = True
    tls = None
    # each connection a thread, idle up to TIMEOUT: past this many, one more is closed at once
    slots = threading.BoundedSemaphore(MAX_CONNECTIONS)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()

    def finish_request(self, request, client_address):
        if not self.tls:
            return super().finish_request(request, client_address)
        request.settimeout(TIMEOUT)
        try:
            wrapped = self.tls.wrap_socket(request, server_side=True)
        except (ssl.SSLError, OSError) as e:
            # a phone that trusts another key ends up here: worth a line when nothing else arrives
            print(f'{client_address[0]}: no TLS handshake ({e})', file=sys.stderr, flush=True)
            return
        try:
            super().finish_request(wrapped, client_address)
        finally:
            wrapped.close()


def own_key(directory):
    """The server's key and a self-signed certificate for it in [directory], made the first time."""
    cert, key = os.path.join(directory, 'cert.pem'), os.path.join(directory, 'key.pem')
    if os.path.exists(cert) and os.path.exists(key):
        return cert, key
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    os.makedirs(directory, mode=0o700, exist_ok=True)
    private = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(x509.oid.NameOID.COMMON_NAME, 'youmo-cloud')])
    now = datetime.datetime.now(datetime.timezone.utc)
    certificate = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(private.public_key())
                   .serial_number(x509.random_serial_number()).not_valid_before(now - datetime.timedelta(days=1))
                   .not_valid_after(now + datetime.timedelta(days=36500)).sign(private, hashes.SHA256()))
    with open(os.open(key, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'wb') as f:
        f.write(private.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    with open(cert, 'wb') as f:
        f.write(certificate.public_bytes(serialization.Encoding.PEM))
    return cert, key


def fingerprint(certfile):
    """SHA-256 of the certificate's public key (SPKI), as the phone shows it (ServerKey.kt)."""
    from cryptography import x509
    from cryptography.hazmat.primitives import serialization
    with open(certfile, 'rb') as f:
        public = x509.load_pem_x509_certificate(f.read()).public_key()
    digest = hashlib.sha256(public.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).hexdigest().upper()
    return ' '.join(digest[i:i + 4] for i in range(0, len(digest), 4))


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--model', default='Qwen/Qwen3.5-4B-Base', help='a Hugging Face name or a local directory')
    p.add_argument('--host', default='0.0.0.0', help='where to listen; 0.0.0.0 is every network the computer is on')
    p.add_argument('--port', type=int, default=8765)
    p.add_argument('--device', help='cpu, cuda, ...; the GPU if there is one')
    p.add_argument('--4bit', dest='four_bit', action='store_true', help='load in 4 bits (bitsandbytes, GPU): the 4B in 3.9 GB, the 9B in 8.1 GB')
    p.add_argument('--context-chars', type=int, default=128, help='how much of the context is read, from its end (at least 1)')
    p.add_argument('--token', help='require "Authorization: Bearer <token>" on every request (printable ASCII, no spaces)')
    p.add_argument('--no-token', action='store_true', help='serve a network with no token: anyone who reaches the port may use the model')
    p.add_argument('--key-dir', default=os.path.join(os.path.expanduser('~'), '.config', 'youmo-cloud'),
                   help="where the server's own key and certificate are kept, made at the first start")
    p.add_argument('--certfile', help='serve HTTPS with this certificate (PEM) instead of its own')
    p.add_argument('--keyfile', help="the certificate's private key (PEM)")
    p.add_argument('--plain', action='store_true', help='serve plain HTTP: only with --host 127.0.0.1')
    p.add_argument('--threads', type=int, help='CPU threads for the model')
    p.add_argument('--words', help='a word pack (<name>.words, see WordPack.kt) to hand phones that ask for /words; read again whenever the file changes')
    args = p.parse_args()
    if args.plain and args.host != '127.0.0.1':
        p.error('--plain only with --host 127.0.0.1: on a network, what is typed would go in the clear')
    if args.token is not None and not (args.token and TOKEN.fullmatch(args.token)):
        p.error('--token: printable ASCII, no spaces (the phone sends it in a header as it is)')
    if bool(args.certfile) != bool(args.keyfile):
        p.error('--certfile and --keyfile go together')
    if args.context_chars < 1:
        p.error('--context-chars: at least 1')
    if args.words and not WORDS_NAME.fullmatch(os.path.basename(args.words)):
        p.error('--words: named <name>.words, the name letters, digits, . _ - (up to 64)')
    words = word_pack(args.words)
    if args.words:
        print(f'word pack: {args.words}, {len(words()["text"].splitlines())} lines', flush=True)
    if not args.token and args.host != '127.0.0.1' and not args.no_token:
        p.error('--token: on a network, anyone who reaches the port could use the model (--no-token if that is meant)')
    if not args.plain:
        certfile, keyfile = (args.certfile, args.keyfile) if args.certfile else own_key(args.key_dir)
        key = fingerprint(certfile)
    if args.threads:
        import torch
        torch.set_num_threads(args.threads)
    model = Model(args.model, args.device, args.four_bit, args.context_chars)
    model.score('', ['你好', '拟好'])  # warm up
    if model.device.startswith('cuda'):
        import torch
        print(f'GPU memory: {torch.cuda.max_memory_allocated() / 2**30:.1f} GB at most so far', flush=True)
    server = Server((args.host, args.port), handler(model, args.token, words))
    if not args.plain:
        server.tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        server.tls.load_cert_chain(certfile, keyfile)
    print(f'{args.model} on {model.device}, serving {"http" if args.plain else "https"}://{args.host}:{args.port}', flush=True)
    if not args.plain:
        print(f'key: {key}\n  (the phone shows the key it sees when you turn it on: it must be this one)', flush=True)
    server.serve_forever()


if __name__ == '__main__':
    main()
