"""apply.py writes a batch's decisions to the lists only when every word is decided once, with a
reading that is not the next line's slipped onto it, and writes nothing at all otherwise."""
import contextlib
import datetime
import io
import os
import re
import tempfile
import unittest
from unittest import mock

import apply

BATCH = ("# word\treading\tcount\tengine\n"
         "内卷\tnei'juan\t900\t内卷/内眷\n"
         "婠婠\tguan'guan\t50\t关关\n"
         "作为一\tzuo'wei'yi\t800\t作为一\n"
         "长效运\tchang'xiao'yun\t70\t长效运\n")
LIST = '# word\tkind\tdate\tnote\n'


class ApplyTest(unittest.TestCase):

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.lexicon = tmp.name
        self.write('add.tsv', LIST)
        self.write('reject.tsv', LIST)
        self.write('batch-001.tsv', BATCH)

    def write(self, name, text):
        path = os.path.join(self.lexicon, name)
        with open(path, 'w', encoding='utf-8') as f:
            f.write(text)
        return path

    def read(self, name):
        with open(os.path.join(self.lexicon, name), encoding='utf-8') as f:
            return f.read()

    def apply_decisions(self, decisions):
        """apply.py over batch-001.out.tsv, the lists those of the temporary lexicon: (exit code, stderr)."""
        out = self.write('batch-001.out.tsv', decisions)
        err = io.StringIO()
        with mock.patch.object(apply, 'HERE', self.lexicon), mock.patch('sys.argv', ['apply.py', out]), \
                contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
            try:
                apply.main()
            except SystemExit as e:
                return e.code, err.getvalue()
        return 0, err.getvalue()

    def assertNothingWritten(self):
        self.assertEqual(LIST, self.read('add.tsv'))
        self.assertEqual(LIST, self.read('reject.tsv'))
        self.assertFalse(os.path.exists(os.path.join(self.lexicon, 'batch-001.unsure.txt')))

    def test_a_batch_decided_rightly_goes_in_the_lists(self):
        code, err = self.apply_decisions("内卷\tadd\tword\tnei'juan\n"
                                         "婠婠\tadd\tname\twan'wan\t婠 is wan, not guan\n"
                                         "作为一\treject\tfragment\n"
                                         "长效运\tunsure\n")
        today = datetime.date.today().isoformat()
        self.assertEqual((0, ''), (code, err))
        self.assertEqual(f"{LIST}内卷\tnei'juan\tword\t{today}\t\n"
                         f"婠婠\twan'wan\tname\t{today}\t婠 is wan, not guan\n", self.read('add.tsv'))
        self.assertEqual(f'{LIST}作为一\tfragment\t{today}\t\n', self.read('reject.tsv'))
        self.assertEqual('长效运\n', self.read('batch-001.unsure.txt'))

    def test_a_reading_slipped_from_the_next_line_needs_a_note(self):
        code, err = self.apply_decisions("内卷\tadd\tword\tguan'guan\n"
                                         "婠婠\tadd\tname\tzuo'wei\n"
                                         "作为一\treject\tfragment\n"
                                         "长效运\tunsure\n")
        self.assertEqual(1, code)
        self.assertIn("内卷: reading \"guan'guan\" far from the suggested \"nei'juan\", with no note saying why", err)
        self.assertIn("婠婠: reading \"zuo'wei\" far from the suggested \"guan'guan\"", err)
        self.assertNothingWritten()

    def test_a_word_decided_twice_or_not_at_all_is_an_error(self):
        code, err = self.apply_decisions("内卷\tadd\tword\tnei'juan\n"
                                         "婠婠\tadd\tname\twan'wan\t婠 is wan, not guan\n"
                                         "内卷\treject\tphrase\n"
                                         "长效运\tunsure\n")
        self.assertEqual(1, code)
        self.assertIn('batch-001.out.tsv:3: 内卷: decided twice (', err)
        self.assertIn("1 words of the batch not decided, ['作为一']", err)
        self.assertNothingWritten()

    def test_the_kinds_are_the_readmes(self):
        """A kind the README gives (`case`, of the Latin words) is one a batch's decision may give too."""
        readme = os.path.join(os.path.dirname(os.path.abspath(apply.__file__)), '..', 'README.md')
        with open(readme, encoding='utf-8') as f:
            text = f.read()

        def kinds(label):
            paragraph = text.split(label, 1)[1].split('\n\n', 1)[0]
            return set(re.findall(r'`(\w+)` \(', paragraph))

        self.assertEqual(apply.TAKE, kinds('Taken:'))
        self.assertEqual(apply.LEAVE, kinds('Left out:'))
