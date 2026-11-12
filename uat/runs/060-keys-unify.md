# 060-keys-unify UAT 汇总（2026-11-11）

- 分支 `fix/060-keys-unify`：85607f806（用例登记）、b2ecea47f（= v0.6.0-beta3，KB-72/ST-104/ST-106 脚本修好）、7d8b95a56（上滑气泡显示首选本身的大小写）。
- 设备：模拟器 AVD youmo（android-36，emulator-5556）和 youmo29（android-29，emulator-5554），arm64 debug 包，文字包；
  前几条在 0.6.0-beta2-80-ge09d67a4a 上跑，其余在 0.6.0-beta3-1-g7d8b95a56 上跑。
- API 29 上 `uat.py run` 的用例都阻塞：测试页的浏览器 webview_shell 要 API 34+。29 上的检查改在 testbed（`./gradlew :testbed:installDebug`）的「types」页
  手工驱动（uiautomator 在 29 上不导出输入法窗口，用 dumper 读键）；下表「29」一栏就是这么跑的。
- 「JVM」表示只由 JVM 用例覆盖（`@Uat` 标注的测试，`:app:testDebugUnitTest` 和 `testDebugUnitTestAt29` 都跑）。

| 编号 | 36 | 29 | 看到的 / 依据 |
|---|---|---|---|
| KB-72 | 通过 | JVM | 看图：j、k 画「现在…」或图标，底部位置可用，紧凑把底部位置的方式放进第一个空角；a 上滑设在左下后 @ 去掉、画「你好」；j 下滑打「现在怎么样了」，a 下滑打「你好」 |
| KB-73 | 通过 | 通过 | 全键盘、14、18、注音全键盘、手写、注音九键两个 ， 的长按都是 `。？！：；“‘（）～`，焦点在 。，松手打 。，读作「，」 |
| KB-74 | JVM | JVM | KeysEverywhereUatTest |
| KB-75 | JVM | JVM | KeysEverywhereUatTest |
| KB-76 | 通过 | 通过 | 九键 1 读「，。？！，1」；按住列表 1 …… ： ； ～ 。 - @ 、 “；点打「，」，按住松手打「1」（首选） |
| KB-77 | JVM | JVM | KeysEverywhereUatTest |
| KB-78 | 通过 | 通过 | 注音九键两个 ， 的列表相同（同 KB-73） |
| KB-79 | JVM | JVM | KeysEverywhereUatTest |
| KB-80 | 通过 | JVM | ㄅ 的角标是 1，能打能画 |
| KB-81 | 通过 | JVM | 弹窗焦点「“」、键角「“」；上滑两次得「“”」（引擎交替，清空后接着交替） |
| KB-82 | JVM | JVM | PanelKeysUatTest |
| KB-83 | 通过 | JVM | 设备上发现并修好（7d8b95a56）：q 的首选设成 Q 后上滑打 Q，气泡却画「q」；修后气泡画「Q」、打 Q。JVM：KeysEverywhereUatTest.aLetterFirstSwipedShowsAsItTypes、DisplayIsTypedUatTest |
| PAN-25–28 | JVM | JVM | PanelKeysUatTest |
| ST-104 | 通过 | 阻塞 | 看图：跟随主题与自选间距、直角、无边框（键面不画）、回到主题、多巴胺无边框；29 无测试页 |
| ST-105 | 通过 | 阻塞 | 17 个主题名，横屏 17 个 |
| ST-106 | 通过 | 阻塞 | 对话框「竖屏显示为图标，横屏显示为「现…」」，页面一致；竖屏 j 画图标、横屏画「现…」。JVM：PopupOverridesFragmentTest |
| ST-107 | 通过 | 阻塞 | 拼音页无长按入口，九键页 5 的列表正常 |
| ST-108 | JVM | JVM | KeyListsUpgradeTest |
| ST-109 | JVM | JVM | PopupOverridesFragmentTest |
| SP-24 | 部分通过 | 部分通过 | 九键双拼微软：空闲 1 读「，。？！，1」、5 读「JKL，5」；打字时 1 读「分词;，1」，b 后点 1 得「bing」。其余 JVM：GroupedKeyboardUatTest、KeyboardHalvesUatTest |
| SP-28 | 通过 | 通过 | 同 SP-24 的设备检查；JVM：ShuangpinKeysUatTest、GroupedKeyboard18Test |
| T9-19 / T9-32 | JVM | JVM | NineKeysUatTest、NineKeysTapUatTest |
| T9-24 | 通过 | 通过 | 同 KB-76 |
| T9-29 | JVM | JVM | SettingsPagesUatTest |
| HW-39 | 通过 | 通过 | 手写的 。 与全键盘的 。 列表相同（，？！：；——%$……&） |
| AX-01 / 04 / 06 | 通过 | — | 35 / 22 / 27 个键，读的和框都对 |
| 读屏文字 | 通过 | 通过 | 全键盘 q「q，1」… m「m，、」，空格「空格，拼音」；关上滑后角标不读；关下滑不变；九键「ABC，2」，关上滑后「ABC」；注音九键组键默认无角标；回车随输入框读「前往」/「下一项」。都合 `KeyResolver.readings` |
| 读屏开着 | 通过 | — | 36 上开 TalkBack 后键盘照常弹出，35 个键读「q，1」「w，2」…（TalkBack 首次开要先给通知权限，否则权限框挡住测试页） |
| 编辑器（键盘弹着） | 见下 | 见下 | q 下滑选「文字」，用本输入法在框里打「okay」；收起键盘后按确定保存，回测试页 q 下滑打「okay」 |

## 没过的和说明

- **编辑器里键盘弹着时按不到「确定」**（36、29 都是）：对话框按 adjustPan 平移，确定在键盘下面，对话框里滚不出来；29 上 uiautomator 仍把它报成可见，点下去点到键盘的 o。
  收起键盘（返回）后确定就在屏上，保存生效。试过给对话框窗口设 `SOFT_INPUT_ADJUST_RESIZE`，36 上没用，已撤回；没改代码，交给 PopupOverridesFragment 的负责人定。
  因为保存时键盘不在（设置页没有输入框），「保存时键盘重建闪烁」在这条路径上看不到。
- **上滑气泡与上屏的来源**（开放问题）：按代码两者都取 `reach.first`（KeySwipes.preview / ran），具名键的 Output 不看主题的上滑，BuiltIn 两处都用主题的；
  内置布局都没写上滑（BuiltInLayoutLintTest），设备上要专门做主题才能看到，没做。
- 小问题：`SceneBehavior.cornerOf` 对写了上滑的具名键返回主题的上滑文字，和 SceneKeyDef 注释「its corner empty」不符；无害。
- 两台模拟器中途一起退出过一次，原因不明，`uat.py up` 后 `uat.py packs` 重装重导入。
