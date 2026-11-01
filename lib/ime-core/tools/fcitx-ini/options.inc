namespace fcitx {

// The keys, names and texts of libime pinyin's config, so its translations serve here and a user's
// old settings carry over as they are (ime-core's LibimeMigration copies them). Only what the
// engine does: ime-core's EngineSettings reads these.
#undef FCITX_GETTEXT_DOMAIN
#define FCITX_GETTEXT_DOMAIN "fcitx5-chinese-addons"

enum class EngineShuangpinProfile { Ziranma, MS, Xiaohe };

FCITX_CONFIG_ENUM_NAME_WITH_I18N(EngineShuangpinProfile, N_("Ziranma"), N_("MS"), N_("Xiaohe"))

FCITX_CONFIGURATION(
        EngineFuzzyConfig,
        // here also a key slipped onto its neighbour
        Option<bool> commonTypo{this, "NG_GN", D_("fcitx5-android", "Typos and slipped keys"), true};
        Option<bool> v{this, "V_U", _("u <-> v"), false};
        Option<bool> an{this, "AN_ANG", _("an <-> ang"), false};
        Option<bool> en{this, "EN_ENG", _("en <-> eng"), false};
        Option<bool> ian{this, "IAN_IANG", _("ian <-> iang"), false};
        Option<bool> in{this, "IN_ING", _("in <-> ing"), false};
        Option<bool> ou{this, "U_OU", _("u <-> ou"), false};
        Option<bool> uan{this, "UAN_UANG", _("uan <-> uang"), false};
        Option<bool> c{this, "C_CH", _("c <-> ch"), false};
        Option<bool> f{this, "F_H", _("f <-> h"), false};
        Option<bool> l{this, "L_N", _("l <-> n"), false};
        Option<bool> r{this, "L_R", _("l <-> r"), false};
        Option<bool> s{this, "S_SH", _("s <-> sh"), false};
        Option<bool> z{this, "Z_ZH", _("z <-> zh"), false};)

// the 性能档位, in the app's own words on its page: the decoder alone, the small sentence model at
// every key, and the large one too at a pause (ime-core's ModelLevel)
#undef FCITX_GETTEXT_DOMAIN
#define FCITX_GETTEXT_DOMAIN "fcitx5-android"
enum class EngineModelLevel { Low, Medium, High };

FCITX_CONFIG_ENUM_NAME_WITH_I18N(EngineModelLevel, N_("Low"), N_("Medium"), N_("High"))
#undef FCITX_GETTEXT_DOMAIN
#define FCITX_GETTEXT_DOMAIN "fcitx5-chinese-addons"

enum class EngineOrderPolicy { No, Freq, Fast };

FCITX_CONFIG_ENUM_NAME_WITH_I18N(EngineOrderPolicy, N_("No"), N_("Freq"), N_("Fast"))

// libime table's keys and texts; the engine orders by use alike for Freq and Fast
#define ENGINE_TABLE_OPTIONS \
        Option<bool> autoSelect{this, "AutoSelect", _("Auto select candidate"), true}; \
        Option<bool> hint{this, "Hint", _("Display Hint for word"), true}; \
        OptionWithAnnotation<EngineOrderPolicy, EngineOrderPolicyI18NAnnotation> orderPolicy{ \
                this, "OrderPolicy", _("Order policy"), EngineOrderPolicy::No};

FCITX_CONFIGURATION(
        EngineTableConfig,
        ENGINE_TABLE_OPTIONS)

// what the keys of a table with roots show (ime-core's KeyLabels): the letters, the roots' names, the roots
enum class EngineKeyLabels { None, Names, Roots };

FCITX_CONFIG_ENUM_NAME_WITH_I18N(EngineKeyLabels, N_("None"), N_("Names"), N_("Roots"))

#define ENGINE_KEY_LABELS(labels) \
        OptionWithAnnotation<EngineKeyLabels, EngineKeyLabelsI18NAnnotation> keyLabels{ \
                this, "KeyLabels", D_("fcitx5-android", "Key labels"), EngineKeyLabels::labels};

// the tables with roots (五笔's and 仓颉's), each with its own default; 自然码 and 二笔 have none
FCITX_CONFIGURATION(
        EngineLetterTableConfig,
        ENGINE_TABLE_OPTIONS
        ENGINE_KEY_LABELS(None))

FCITX_CONFIGURATION(
        EngineNamedTableConfig,
        ENGINE_TABLE_OPTIONS
        ENGINE_KEY_LABELS(Names))

// each table's own defaults, those of ime-core's TableOptions presets (fcitx's .conf files) but
// OrderPolicy (调频): off for every table on a new install (U-28 D7). Those who typed with a table
// before keep it on: EngineMigration writes it into their config. A value read that is out of
// range falls back to EngineTableConfig's, not to these
template <typename T = EngineTableConfig>
inline T engineTable(bool hint) {
    T config;
    config.hint.setValue(hint);
    return config;
}

// The options AndroidEngineConfig shares with the pages below, each written once: a page shows the
// engine's default and range, so a value the engine takes is not lost by saving the page
#define ENGINE_SHUANGPIN_PROFILE \
    OptionWithAnnotation<EngineShuangpinProfile, EngineShuangpinProfileI18NAnnotation> shuangpinProfile{ \
            this, "ShuangpinProfile", _("Shuangpin Profile"), EngineShuangpinProfile::Xiaohe};
#define ENGINE_PAGE_SIZE \
    Option<int, IntConstrain> pageSize{this, "PageSize", _("Candidates Per Page"), 7, IntConstrain(3, 10)};
#define ENGINE_PREDICTION Option<bool> prediction{this, "Prediction", _("Enable Prediction"), true};
#define ENGINE_EMOJI Option<bool> emoji{this, "Emoji", D_("fcitx5-android", "Emoji among the candidates"), true};
#define ENGINE_CHAIZI Option<bool> chaizi{this, "Chaizi", D_("fcitx5-android", "Characters by their parts"), true};
#define ENGINE_BUILT_IN_PHRASES \
    Option<bool> builtInPhrases{this, "BuiltInPhrases", D_("fcitx5-android", "Built-in date and time"), true};
#define ENGINE_MODEL_LEVEL \
    OptionWithAnnotation<EngineModelLevel, EngineModelLevelI18NAnnotation> modelLevel{ \
            this, "ModelLevel", D_("fcitx5-android", "Performance level"), EngineModelLevel::High};
#define ENGINE_LATIN_WORDS \
    Option<bool> latinWords{this, "LatinWords", D_("fcitx5-android", "Type English words in pinyin"), true};
#define ENGINE_FUZZY Option<EngineFuzzyConfig> fuzzy{this, "Fuzzy", _("Fuzzy Pinyin")};
#define ENGINE_ENGLISH_SUGGEST \
    Option<bool> englishSuggest{this, "EnglishSuggest", D_("fcitx5-android", "Suggest English words"), true};

FCITX_CONFIGURATION(
        AndroidEngineConfig,
        ENGINE_SHUANGPIN_PROFILE
        ENGINE_PAGE_SIZE
        ENGINE_PREDICTION
        ENGINE_EMOJI
        ENGINE_CHAIZI
        ENGINE_BUILT_IN_PHRASES
        ENGINE_MODEL_LEVEL
        ENGINE_LATIN_WORDS
        ENGINE_FUZZY
        ENGINE_ENGLISH_SUGGEST
        // named as the input methods are listed (androidengine.cpp)
        Option<EngineLetterTableConfig> wubi{this, "Wubi", "五笔", engineTable<EngineLetterTableConfig>(true)};
        Option<EngineNamedTableConfig> cangjie{this, "Cangjie", "仓颉", engineTable<EngineNamedTableConfig>(false)};
        Option<EngineTableConfig> ziranma{this, "Ziranma", "自然码", engineTable(true)};
        Option<EngineTableConfig> erbi{this, "Erbi", "二笔", engineTable(false)};
        Option<EngineLetterTableConfig> wubiPinyin{this, "WubiPinyin", "五笔拼音", engineTable<EngineLetterTableConfig>(true)};
        // opened by name in the app (ConfigDescriptor), the uri only says whose they are
        ExternalOption dictmanager{this, "DictManager", _("Manage Dictionaries"),
                                   "fcitx://config/addon/androidengine/dictmanager"};
        ExternalOption customphrase{this, "CustomPhrase", _("Manage Custom Phrase"),
                                    "fcitx://config/addon/androidengine/customphrase"};)

// What each input method's own page shows of AndroidEngineConfig, under the same keys: a page
// with every table's options on it read as if pinyin had them all
FCITX_CONFIGURATION(
        EnginePinyinPage,
        ENGINE_PAGE_SIZE
        ENGINE_PREDICTION
        ENGINE_EMOJI
        ENGINE_CHAIZI
        ENGINE_BUILT_IN_PHRASES
        ENGINE_MODEL_LEVEL
        ENGINE_LATIN_WORDS
        ENGINE_FUZZY)

FCITX_CONFIGURATION(
        EngineShuangpinPage,
        ENGINE_SHUANGPIN_PROFILE
        ENGINE_PAGE_SIZE
        ENGINE_PREDICTION
        ENGINE_EMOJI
        ENGINE_MODEL_LEVEL
        ENGINE_FUZZY)

FCITX_CONFIGURATION(
        EngineEnglishPage,
        ENGINE_ENGLISH_SUGGEST)

// how the pad is written on is the app's own setting, the keyboard's; prediction and the
// performance level are pinyin's, the same options
FCITX_CONFIGURATION(
        EngineHandwritingPage,
        ENGINE_PAGE_SIZE
        ENGINE_PREDICTION
        ENGINE_MODEL_LEVEL)

// a table's page: its own options, under its group, and pinyin's page size, prediction, emoji and
// performance level, the same options (every Chinese input method has them)
#define ENGINE_TABLE_PAGE \
        ENGINE_PAGE_SIZE \
        ENGINE_PREDICTION \
        ENGINE_EMOJI \
        ENGINE_MODEL_LEVEL \
        ENGINE_TABLE_OPTIONS

FCITX_CONFIGURATION(
        EngineTablePage,
        ENGINE_TABLE_PAGE)

// a table with roots: its key labels too, its own default
FCITX_CONFIGURATION(
        EngineLetterTablePage,
        ENGINE_TABLE_PAGE
        ENGINE_KEY_LABELS(None))

FCITX_CONFIGURATION(
        EngineNamedTablePage,
        ENGINE_TABLE_PAGE
        ENGINE_KEY_LABELS(Names))

