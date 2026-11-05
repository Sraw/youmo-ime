"""A failed test shows up on the run page and the checks tab, not only inside the report.

Without a file and line GitHub cannot place it in the diff; it shows the first 10 a step. Reads every
JUnit XML result under the working directory (run from the repository root).
"""
import glob
import xml.etree.ElementTree as ET


def escape(s, prop=False):
    s = s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    return s.replace(":", "%3A").replace(",", "%2C") if prop else s


for path in glob.glob("**/build/test-results/**/TEST-*.xml", recursive=True):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:  # e.g. a test JVM killed mid-write
        print(f"::warning title=Unreadable test result::{escape(path)}")
        continue
    for case in root.iter("testcase"):
        for bad in list(case.findall("failure")) + list(case.findall("error")):
            title = escape(f"{case.get('classname')}.{case.get('name')}", prop=True)
            lines = (bad.get("message") or "").splitlines()
            text = escape(lines[0] if lines else bad.get("type", "failed"))
            print(f"::error title={title}::{text}")
