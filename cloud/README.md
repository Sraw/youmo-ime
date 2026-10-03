# 幽默输入法的自建云端 · Your own server for Youmo IME

[中文](#中文) · [English](#english)

## 中文

云端版的“云端模型”会在你停顿时，把光标前的文字和前五个整句候选发到这个服务，由一个更大的语言模型
（Qwen3.5 的 Base 模型，Apache-2.0）给每个候选打分，手机按分数重新排序。
服务在你自己的电脑上运行，内容不经过任何第三方。打分方法与训练句子模型时完全相同（`dev/training/score_lists.py`）。

**要快。** 手机只等 300 毫秒，超时就当没有，所以要用显卡；CPU 上 0.8B 一次也要 1–3 秒。实测（RTX 3060 12 GB，
一次一个请求；准确率是手机打字测评，前五个整句由服务器重排）：

| 模型 | 显存峰值 | 每次 p50 / p99 | 主集 | 前文 | 聊天 |
|---|---|---|---|---|---|
| 不开（手机上的 4M+25M） | — | — | 91.1 | 92.7 | 73.3 |
| 0.8B | 2.3 GB | 85 / 112 ms | 90.0 | 97.6 | 75.7 |
| **4B，`--4bit`（推荐，8 GB 卡）** | 3.9 GB | 125 / 171 ms | 91.8 | 95.1 | 77.7 |
| 9B，`--4bit`（12 GB 以上的卡） | 8.1 GB | 142 / 277 ms | 90.0 | 95.1 | 78.2 |

联想不经服务器：试过让这些模型续写或给手机的联想重排，都不如手机自己的（见 `dev/TRAINING-PLAN.md` 10.6）。

### 安装与运行

```shell
python -m venv venv && . venv/bin/activate        # Windows：venv\Scripts\activate
pip install -r requirements.txt                    # 有 N 卡时先按 pytorch.org 装 CUDA 版的 torch
pip install bitsandbytes
python server.py --model Qwen/Qwen3.5-4B-Base --4bit --token <长口令，只用字母数字>
```

第一次运行会从 Hugging Face 下载模型，并在 `~/.config/youmo-cloud/` 生成服务器自己的密钥和自签证书。
默认监听 `0.0.0.0:8765`，用 HTTPS，启动时打印密钥指纹：

```
key: EDD3 27B9 4ACA 9ACF F2A6 7256 1FB6 E738 C3D0 879F 965B 3ED1 37B4 EC8F F30C C21A
```

在手机上：设置 → 云端模型，服务器地址填 `https://电脑的IP或域名:8765`（比如 `https://192.168.1.5:8765`），
访问令牌填上面的口令，再打开开关。手机会连上服务器、显示它出示的密钥指纹，**和上面打印的一致才点确定**；
确定后手机用令牌问一次服务器，提示“已连接：模型名”，或者说明哪里不对（令牌不对会直接说）。
之后手机只认这把密钥：换了网络、有人冒充这个地址，都连不上，也不会把文字发出去。改服务器地址会关掉开关、忘掉密钥，需要重新核对。

- 令牌只能用字母、数字和英文标点（它放在 HTTP 头里）。除非 `--host 127.0.0.1`，都必须设，否则不启动：
  能连到这个端口的设备都能用这个模型。确实不要令牌就加 `--no-token`。
- 已有正规证书时用 `--certfile`/`--keyfile`，或者放在 Caddy、nginx 之类反向代理后面（地址可以带路径前缀）；
  手机同样只认打开开关时核对过的那把密钥，证书续期时请保留原密钥（certbot 用 `--reuse-key`），换了密钥就重新打开一次开关。
- 手机只用 `https://`，连 `127.0.0.1` 也一样：用 USB 线 `adb reverse tcp:8765 tcp:8765` 连电脑时，服务器照常用
  HTTPS 启动，地址填 `https://127.0.0.1:8765`。明文不行，是因为线一拔、转发一断，手机上任何一个应用都能占住这个端口，
  收到令牌和你打的字。`--plain` 只给同一台电脑上的测评工具用。
- 连不上服务器、或令牌被拒时，手机 30 秒内不再试，免得一直唤醒网络。
- 云端设置只存在手机本地，不进备份，也不进“导出用户数据”。
- 服务只记录请求路径和耗时，不记录内容。

### 接口

| 请求 | 返回 |
|---|---|
| `POST /score` `{"context": "...", "candidates": ["...", ...]}` | `{"scores": [...]}`：每个候选的 log P（自然对数），上下文为空时用换行 |
| `GET /health` | `{"model": "...", "gpu_peak_gb": ...}`：显存峰值只在用显卡时有 |

有 `--token` 时每个请求都要带 `Authorization: Bearer <token>`。

## English

The cloud build's "Cloud model" setting sends, while you pause, the text before the cursor and the best five candidate
sentences to this server. A larger language model (a Qwen3.5 Base model, Apache-2.0) scores each candidate and the
phone reorders them by it. You run it on your own computer, and nothing
passes through anyone else. It scores exactly as the sentence models' training did (`dev/training/score_lists.py`).

**It has to be fast.** The phone waits 300 ms and then goes on without it, so use a GPU: on a CPU even the 0.8B takes 1–3 s.
Measured on an RTX 3060 12 GB, one question at a time (accuracy is the phone's typing evaluation, the best five sentences
reordered by the server):

| Model | GPU memory peak | Per question p50 / p99 | Main | Context | Chat |
|---|---|---|---|---|---|
| Off (the phone's 4M+25M) | — | — | 91.1 | 92.7 | 73.3 |
| 0.8B | 2.3 GB | 85 / 112 ms | 90.0 | 97.6 | 75.7 |
| **4B, `--4bit` (recommended, an 8 GB card)** | 3.9 GB | 125 / 171 ms | 91.8 | 95.1 | 77.7 |
| 9B, `--4bit` (a card of 12 GB or more) | 8.1 GB | 142 / 277 ms | 90.0 | 95.1 | 78.2 |

Suggestions after a commit stay the phone's: these models, continuing the text or reordering the phone's own suggestions,
did no better (`dev/TRAINING-PLAN.md` 10.6).

### Installing and running

```shell
python -m venv venv && . venv/bin/activate        # Windows: venv\Scripts\activate
pip install -r requirements.txt                    # with an NVIDIA card, install torch's CUDA build from pytorch.org first
pip install bitsandbytes
python server.py --model Qwen/Qwen3.5-4B-Base --4bit --token <a long passphrase, letters and digits>
```

The first run downloads the model from Hugging Face and makes the server's own key and a self-signed certificate in
`~/.config/youmo-cloud/`. It listens on `0.0.0.0:8765` over HTTPS and prints the key's fingerprint as it starts:

```
key: EDD3 27B9 4ACA 9ACF F2A6 7256 1FB6 E738 C3D0 879F 965B 3ED1 37B4 EC8F F30C C21A
```

On the phone: Settings → Cloud model. Set the server address to `https://<the computer's IP or name>:8765`
(`https://192.168.1.5:8765`, say) and the access token to the passphrase, then turn it on. The phone connects, shows the
fingerprint of the key the server presents, and **you say yes only if it is the one printed above**. It then asks the server
once, with the token, and says "Connected: <model>" or what went wrong (a wrong token is said so). From then on the phone
trusts that key alone: on another network, or with something else answering at that address, nothing connects and nothing is
sent. A new address turns it off and forgets the key, to be compared again.

- The token is letters, digits and ASCII punctuation (it goes in an HTTP header). Unless it is `--host 127.0.0.1`, the
  server will not start without one: anything that can reach the port could use the model. `--no-token` if
  that is meant.
- With a certificate from an authority, use `--certfile`/`--keyfile`, or a reverse proxy such as Caddy or nginx (the address may
  have a path prefix). The phone still trusts only the key compared when it was turned on: keep the key when the certificate is
  renewed (certbot's `--reuse-key`), or turn it on again after a new one.
- The phone takes `https://` only, even to `127.0.0.1`: with a computer on a USB cable through
  `adb reverse tcp:8765 tcp:8765`, start the server as usual (HTTPS) and set `https://127.0.0.1:8765`. Not plain HTTP, because
  once the cable is out and the forward gone, any app on the phone may take that port, and would be sent the token and the
  text. `--plain` is for the evaluation tools on the same computer only.
- A server that cannot be reached, or that refuses the token, is not tried again for 30 seconds, so as not to keep the radio awake.
- The cloud settings stay on the phone: not in its backups, not in "Export user data".
- The server logs request paths and timings, never what is sent.

### API

| Request | Answer |
|---|---|
| `POST /score` `{"context": "...", "candidates": ["...", ...]}` | `{"scores": [...]}`: each candidate's log P (natural log); an empty context reads as a newline |
| `GET /health` | `{"model": "...", "gpu_peak_gb": ...}`: the GPU memory peak, on a GPU only |

With `--token`, every request needs `Authorization: Bearer <token>`.
