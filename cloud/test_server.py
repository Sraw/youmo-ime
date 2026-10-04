# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
"""python3 -m unittest cloud/test_server.py: the parts of server.py that need no model."""
import hashlib
import http.client
import io
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import server  # noqa: E402

URL = 'https://x/words-202610/words-202610.words'
PACK = '# youmo words 1\n# layer new\n内卷\tnei\'juan\t-4.5\n'.encode()


def release(tag, name=None, data=PACK, digest=None, **more):
    name = name or f'{tag}.words'
    return dict(tag_name=tag, assets=[dict(name=name, browser_download_url=f'https://x/{tag}/{name}',
                                           digest=digest if digest is not None else 'sha256:' + hashlib.sha256(data).hexdigest())], **more)


class Fake:
    def __init__(self, releases, files):
        self.releases, self.files, self.asked = releases, files, []

    def __call__(self, request, timeout):
        self.asked.append(request.full_url)
        body = json.dumps(self.releases).encode() if request.full_url == server.RELEASES else self.files[request.full_url]
        return io.BytesIO(body)


class OfficialWordsTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.logged = []

    def words(self, fake):
        return server.OfficialWords(self.dir, urlopen=fake, log=lambda *a, **k: self.logged.append(a[0]))

    def test_the_newest_words_release_is_taken_and_served(self):
        fake = Fake([release('words-202609'), release('words-202610'), release('engine-data-20261004'),
                     release('words-202611', prerelease=True)],
                    {URL: PACK})
        w = self.words(fake)
        self.assertTrue(w.check())
        self.assertEqual(w.tag, 'words-202610')
        answer = server.word_pack(lambda: w.path)()
        # whatever the release calls it: a phone replaces a pack by name
        self.assertEqual(answer['name'], 'youmo-new')
        self.assertIn('内卷', answer['text'])
        # the same release again: not fetched again
        self.assertFalse(w.check())
        self.assertEqual(len(fake.asked), 3)

    def test_a_file_not_as_listed_is_refused_and_the_old_one_kept(self):
        old = os.path.join(self.dir, server.OFFICIAL_NAME)
        with open(old, 'wb') as f:
            f.write(PACK)
        with open(old + '.part', 'wb') as f:
            f.write(b'half')
        not_pack = b'not a pack'
        for bad, served in ((release('words-202610', digest='sha256:' + '0' * 64), PACK),
                            (release('words-202610', digest=''), PACK),
                            (release('words-202610', data=not_pack), not_pack),
                            ({'tag_name': 'words-202610', 'assets': [{'name': 'words-202610.words', 'browser_download_url': 'file:///etc/passwd',
                                                                      'digest': 'sha256:' + hashlib.sha256(PACK).hexdigest()}]}, PACK)):
            w = self.words(Fake([bad], {URL: served}))
            self.assertEqual(w.path, old)
            with self.assertRaises(ValueError) as e:
                w.check()
            self.assertEqual(w.path, old)
            self.assertEqual(os.listdir(self.dir), [server.OFFICIAL_NAME])
            self.logged.append(str(e.exception))
        self.assertIn('not a word pack', self.logged[2])
        self.assertIn('not over https', self.logged[3])

    def test_a_new_pack_replaces_the_one_before(self):
        with open(os.path.join(self.dir, server.OFFICIAL_NAME), 'wb') as f:
            f.write(b'# youmo words 1\nold\tjiu\t-5\n')
        w = self.words(Fake([release('words-202610')], {URL: PACK}))
        w.check()
        self.assertEqual(os.listdir(self.dir), [server.OFFICIAL_NAME])
        self.assertIn('内卷', server.word_pack(lambda: w.path)()['text'])

    def test_a_failed_look_is_logged_and_tried_again_sooner(self):
        class Stop(BaseException):
            pass
        waits = []

        def sleep(s):
            waits.append(s)
            raise Stop

        def cut_off(request, timeout):
            raise http.client.IncompleteRead(b'')
        w = self.words(cut_off)
        real, server.time.sleep = server.time.sleep, sleep
        try:
            with self.assertRaises(Stop):
                w.run()
        finally:
            server.time.sleep = real
        self.assertEqual(waits, [server.RETRY])
        self.assertIn('not fetched', self.logged[0])

    def test_a_release_with_two_packs_is_refused(self):
        r = release('words-202610')
        r['assets'] *= 2
        with self.assertRaises(ValueError):
            self.words(Fake([r], {})).check()


if __name__ == '__main__':
    unittest.main()
