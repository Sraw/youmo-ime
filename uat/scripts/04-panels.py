"""uat/04-panels.md. The .md is the source of truth; each function follows its case's 前提/操作/预期."""
from uat import case


case("PAN-01", needs="jvm:KeyboardUatTest.numberPadAndBack")(lambda d: None)


case("PAN-02", needs="jvm:PanelUatTest.numberAndPhoneFieldsOpenTheNumberPad")(lambda d: None)


case("PAN-03", needs="jvm:PanelUatTest.numberPadSymbolsKeyOpensThePanel")(lambda d: None)


case("PAN-04", needs="jvm:PanelUatTest.symbolPanelTypesAndCloses")(lambda d: None)


case("PAN-05", needs="jvm:PanelUatTest.lockedPanelStaysForSeveral")(lambda d: None)


case("PAN-07", needs="jvm:PanelUatTest.recentHasTheLastSymbolFirst")(lambda d: None)


case("PAN-08", needs="jvm:PanelUatTest.panelsNumbersKeyAndBack")(lambda d: None)


case("PAN-09", needs="jvm:PanelUatTest.emojiPanelTabsAndTyping")(lambda d: None)


case("PAN-10", needs="jvm:PanelUatTest.theDefaultSkinTone")(lambda d: None)


case("PAN-11", needs="jvm:PanelUatTest.emojiPanelBackToLetters")(lambda d: None)


case("PAN-12", needs="jvm:PanelUatTest.emojiSearchByPinyin")(lambda d: None)


case("PAN-14", needs="jvm:PanelUatTest.numberPadSpaceSlidesTheCursor")(lambda d: None)


case("PAN-15", needs="jvm:PanelUatTest.ownFieldsKeepNoRecentEmoji")(lambda d: None)


case("PAN-16", needs="jvm:FieldPadUatTest.dateAndTimeFieldsGetTheirSeparators")(lambda d: None)


case("PAN-17", needs="jvm:FieldPadUatTest.phoneFieldsGetPlusStarAndHash")(lambda d: None)


case("PAN-18", needs="jvm:FieldPadUatTest.pinFieldsGetDigitsOnly")(lambda d: None)


case("PAN-19", needs="jvm:FieldPadUatTest.decimalAndSignedFieldsKeepTheDotAndTheMinus")(lambda d: None)


case("PAN-20", needs="jvm:FieldPadUatTest.leavingANumberFieldGoesBackToTheLetters")(lambda d: None)


case("PAN-21", needs="jvm:PanelUatTest.aSymbolHeldShowsTheViewsBubble")(lambda d: None)
