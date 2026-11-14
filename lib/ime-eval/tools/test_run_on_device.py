"""run-on-device.sh puts back the keyboard chosen before it, however the run ends, and says it could
not only when there was one: run on a copy of the script beside a fake adb, gradlew and uat.py."""
import os
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent.parent / 'run-on-device.sh'
PKG = 'io.github.sraw.youmo.debug'
YOUMO = f'{PKG}/org.fcitx.fcitx5.android.input.FcitxInputMethodService'
OTHER = 'com.android.inputmethod.latin/.LatinIME'

# the device's keyboard in a file; `ime set null` fails as on a device (no such keyboard)
ADB = f'''#!/usr/bin/env bash
state="$FAKE_DIR/keyboard"
case "$*" in
  "shell settings get secure default_input_method") cat "$state" ;;
  "shell ime set null") exit 1 ;;
  "shell ime set "*) echo "${{4}}" > "$state" ;;
  "shell ime list -s") printf '%s\\n%s\\n' "{YOUMO}" "{OTHER}" ;;
  "get-serialno") echo emulator-5554 ;;
  "shell am instrument"*) echo "OK (1 test)" ;;
esac
exit 0
'''
# uat.py packs: the install makes this one the keyboard (ime set), then the import ends as FAKE_IMPORT says
UAT = f'''import os, sys
open(os.path.join(os.environ["FAKE_DIR"], "keyboard"), "w").write("{YOUMO}\\n")
sys.exit(int(os.environ["FAKE_IMPORT"]))
'''


def executable(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    path.chmod(path.stat().st_mode | stat.S_IXUSR)


class RunOnDeviceTest(unittest.TestCase):
    def run_script(self, before, import_status):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp) / 'repo'
            (repo / 'lib' / 'ime-eval').mkdir(parents=True)
            shutil.copy(SCRIPT, repo / 'lib' / 'ime-eval' / 'run-on-device.sh')
            executable(repo / 'gradlew', '#!/usr/bin/env bash\nexit 0\n')
            executable(repo / 'uat' / 'tools' / 'uat.py', UAT)
            executable(Path(tmp) / 'bin' / 'adb', ADB)
            (Path(tmp) / 'keyboard').write_text(before + '\n')
            env = dict(os.environ, PATH=f"{Path(tmp) / 'bin'}:{os.environ['PATH']}", FAKE_DIR=tmp,
                       FAKE_IMPORT=str(import_status))
            env.pop('INSTALL', None)
            r = subprocess.run(['bash', str(repo / 'lib' / 'ime-eval' / 'run-on-device.sh')],
                               env=env, capture_output=True, text=True, timeout=60)
            return r.returncode, (Path(tmp) / 'keyboard').read_text().strip(), r.stderr

    def test_the_keyboard_before_is_put_back_and_only_one_there_was_is_missed(self):
        rows = (
            # the trap was set after the install: an import failing after `ime set` left this one the keyboard
            (OTHER, 1, 'the install fails after making this one the keyboard: put back', OTHER, False),
            (OTHER, 0, 'a run that ends well: put back', OTHER, False),
            # a new emulator answers null; `ime set null` failed and the run warned of a keyboard not put back
            ('null', 0, 'none chosen before: nothing to put back, no warning', OTHER, False),
            ('null', 1, 'none chosen before, the install fails: no warning', YOUMO, False),
        )
        for before, import_status, why, after, warned in rows:
            with self.subTest(why):
                status, keyboard, err = self.run_script(before, import_status)
                self.assertEqual(import_status != 0, status != 0, err)
                self.assertEqual(after, keyboard, err)
                self.assertEqual(warned, 'not put back' in err, err)


if __name__ == '__main__':
    unittest.main()
