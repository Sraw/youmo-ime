/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.keys

/**
 * The long presses built in by a key's character (upstream's PopupPreset, 060-keys-unify): a letter's
 * (its digit or mark first, its capital, its accented forms), a capital's, a digit's, a mark's. The
 * full keyboard's letters, a group's symbols and a layout's `popup from="preset"` read it; the symbol
 * panel's cells too, which no user setting changes (C1).
 */
object PresetLists {
    fun of(label: String): List<String>? = TABLE[label]

    // a line a key: its label, then its list; one string, not a map of lists, for the theme maker's engine's size
    val TABLE: Map<String, List<String>> by lazy {
        LINES.lineSequence().map { it.split(' ') }.associate { it.first() to it.drop(1) }
    }

    private val LINES = """
q 1 Q
w 2 W
e 3 E ê ë ē é ě è ė ę ȩ ḝ ə
r 4 R
t 5 T
y 6 Y ÿ ұ ү ӯ ў
u 7 U û ü ū ú ǔ ù
i 8 I î ï ī í ǐ ì į ı
o 9 O ô ö ō ó ǒ ò œ ø õ
p 0 P
a @ A â ä ā á ǎ à æ ã å ą
s * S ß ś š ş
d + D ð
f - F
g = G ğ
h / H
j # J
k ( K [ {
l ) L ] } ł ;
z ' Z ` ž ź ż
x : X ×
c " C ç ć č
v ? V ¿ ü ǖ ǘ ǚ ǜ
b ! B ¡
n ~ N ñ ń
m \ M
Q 1 q
W 2 w
E 3 e Ê Ë Ē É Ě È Ė Ę Ȩ Ḝ Ə
R 4 r
T 5 t
Y 6 y Ÿ Ұ Ү Ӯ Ў
U 7 u Û Ü Ū Ú Ǔ Ù
I 8 i Î Ï Ī Í Ǐ Ì Į ı
O 9 o Ô Ö Ō Ó Ǒ Ò Œ Ø Õ
P 0 p
A @ a Â Ä Ā Á Ǎ À Æ Ã Å Ą
S * s ẞ Ś Š Ş
D + d Ð
F - f
G = g Ğ
H / h
J # j
K ( k [ {
L ) l ] } Ł
Z ' z ` Ž Ź Ż
X : x ×
C " c Ç Ć Č
V ? v ¿ Ü Ǖ Ǘ Ǚ Ǜ
B ! b ¡
N ~ n Ñ Ń
M \ m
г ғ
е ё
и ӣ і
й ј
к қ ҝ
н ң һ
о ә ө
ч ҷ ҹ
ь ъ
Г Ғ
Е Ё
И Ӣ І
Й Ј
К Қ Ҝ
Н Ң Һ
О Ә Ө
Ч Ҷ Ҹ
Ь Ъ
ا أ إ آ ء
ب پ
ج چ
ز ژ
ف ڤ
ك گ
ل لا
و ؤ
ג ג׳
ז ז׳
ח ח׳ ױ
צ צ׳
ת ת׳
י ײַ ײ
ו װ
0 ∅ ⁿ ⁰
1 ¹ ½ ⅓ ¼ ⅕ ⅙ ⅐ ⅛ ⅑ ⅒
2 ² ⅖ ⅔
3 ³ ⅗ ¾ ⅜
4 ⁴ ⅘
5 ⁵ ⅝ ⅚
6 ⁶
7 ⁷ ⅞
8 ⁸
9 ⁹
. , ? ! : ; _ % ${'$'} ^ &
, . ? ! : ; " ' ( ) ~
- — – ·
? ¿ ‽
' ‘ ’ ‚ › ‹
! ¡
" “ ” „ » «
/ ÷
# №
% ‰ ℅
^ ↑ ↓ ← →
+ ±
< ≤ « ‹ ⟨
= ∞ ≠ ≈
> ≥ » › ⟩
° ′ ″ ‴
${'$'} ¢ € £ ¥ ₹ ₽ ₺ ₩ ₱ ₿
""".trim()
}
