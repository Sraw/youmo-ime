"""uat/19-motion-notices.md. The .md is the source of truth; each function follows its case's
前提/操作/预期."""
from uat import case


case("MN-13", base="B0-设置", needs="manual:要 TalkBack 读屏，在模拟器（google_apis 镜像带 TalkBack）或真机上手动做")(lambda d: None)
