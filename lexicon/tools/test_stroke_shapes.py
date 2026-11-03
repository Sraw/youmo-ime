"""stroke_shapes.py tells cnchar's letters for two shapes apart by the part a stroke is in, keeps a line
corrected by hand, and fails on shapes not of a line's kinds."""
import unittest

import stroke_shapes

# cnchar's letters of a few characters, as stroke-order-jian.json has them
ORDERS = {
    '行': 'ssfjjg', '口': 'fcj', '己': 'cju', '乙': 'o', '九': 'so', '马': 'czj', '风': 'sosk', '岚': 'fbfsosk',
    '心': 'dykk', '戈': 'jysk', '那': 'rjjswf', '乃': 'ws', '山': 'fbf', '四': 'fcsbj', '牺': 'sjfijfcsbj',
    '又': 'el', '写': 'dejzj', '欠': 'sesl', '阙': 'kfrksjbfssesk', '尔': 'segsk', '子': 'egj', '孚': 'skksegj',
    '旁': 'kjksdekjrs', '买': 'ekkjsk', '予': 'ekeg', '皮': 'esfel', '凹': 'fvfcj', '朵': 'svjfsl', '专': 'jjxk',
    '鼎': 'fcjjjxjsfjfc', '虱': 'osfcjfjk', '鲺': 'sefcjfjiojfcjfjk', '匹': 'jsub', '机': 'jfskso', '敢': 'ejffjjisjsl', '牙': 'jngs', '辚': 'jnfiksjfslsekjnf', '执': 'jgisok', '丸': 'sok', '互': 'jnej', '发': 'nselk', '降': 'wfseljnf', '要': 'jfcffjmsj',
    '彖': 'nejstsssl', '承': 'egjjjesl', '皙': 'jfskssjffcjj', '江': 'kkijfj',
}
CHAIZI = ['岚\t山 风\n', '牺\t牛 西\n', '西\t覀 儿\n', '鲺\t鱼 虱\n', '虱\t乁 一 虫\n', '机\t木 几\n', '几\t乁 丿\n',
          '甲\t乙 丙\n', '乙\t丙 甲\n', '辚\t车 粦\n', '粦\t米 舛\n',
          '执\t扌 丸\n', '降\t阝 夅\n']


def converted(tsv):
    return stroke_shapes.convert(ORDERS, CHAIZI, tsv)


class StrokeShapesTest(unittest.TestCase):

    def shapes(self, c):
        return ' '.join(stroke_shapes.shapes(c, ORDERS[c], stroke_shapes.parts_of(CHAIZI)))

    def test_the_plain_letters_and_the_turning_strokes_each_their_shape(self):
        self.assertEqual('P P S H H SG', self.shapes('行'))
        self.assertEqual('S HZ H', self.shapes('口'))
        self.assertEqual('HZ H SWG', self.shapes('己'))
        self.assertEqual('HZ SZWG H', self.shapes('马'))
        self.assertEqual('D D T H S H', self.shapes('江'))

    def test_a_letter_of_two_shapes_by_the_part_its_stroke_is_in(self):
        # o: 风's 横斜钩, in 岚 too; 九 乙's 横折弯钩
        self.assertEqual(('HZWG', 'P HZWG', 'P HXG P D', 'S SZ S P HXG P D'),
                         (self.shapes('乙'), self.shapes('九'), self.shapes('风'), self.shapes('岚')))
        # y: 卧钩 after 心's first 点; w: 阝's 横撇弯钩 before its 竖, 乃's 横折折折钩
        self.assertEqual(('D BXG D D', 'H XG P D'), (self.shapes('心'), self.shapes('戈')))
        self.assertEqual(('HZG H H P HPWG S', 'HZZZG P'), (self.shapes('那'), self.shapes('乃')))
        # b: 四 西's 竖弯 (牺 has 西), 山's 竖折
        self.assertEqual(('S SZ S', 'S HZ P SW H', 'P H S T H S HZ P SW H'), (self.shapes('山'), self.shapes('四'), self.shapes('牺')))
        # 虱's 乁 as 卂's, in 鲺 too; 几's, in 机, a 横折弯
        self.assertEqual(('HXG P S HZ H S H D', 'HXG'), (self.shapes('虱'), self.shapes('鲺').split()[8]))
        self.assertEqual('H S P D P HZWG', self.shapes('机'))
        # v x: 凹 朵, 专 鼎
        self.assertEqual(('S HZZ S HZ H', 'P HZW H S P N'), (self.shapes('凹'), self.shapes('朵')))
        self.assertEqual(('H H SZP D', 'S HZ H H H SZZ H P S H S HZ'), (self.shapes('专'), self.shapes('鼎')))

    def test_the_standards_examples_over_the_letter(self):
        # GF 2001's table: 敢's 横折, 牙 彑's 竖折; 执's 横斜钩 宋体's only, 丸's 横折弯钩 as taught
        self.assertEqual(('HZ', 'H SZ SG P', 'SZ'), (self.shapes('敢').split()[0], self.shapes('牙'), self.shapes('彖').split()[0]))
        self.assertEqual(('H SG T P HZWG D', 'P HZWG D'), (self.shapes('执'), self.shapes('丸')))
        # 舛's 竖折, not 车's 撇折, in 辚
        self.assertEqual('H PZ S T D P H S P N P HP D H SZ S', self.shapes('辚'))
        self.assertEqual(('H SZ HP H', 'SZ P HP N D', 'HPWG S P HP N H SZ S', 'H S HZ S S H PD P H'),
                         tuple(self.shapes(c) for c in '互发降要'))
        # chaizi's □ (a part it cannot write, in thousands of lines) is no part: not every part through it
        self.assertEqual({'人'}, stroke_shapes.parts_of(['亥\t□ 人\n', '□\t彑 丸\n'])('亥'))

    def test_e_by_the_longest_part_it_is_in(self):
        self.assertEqual('HP N', self.shapes('又'))
        self.assertEqual('D HG H SZWG H', self.shapes('写'))
        self.assertEqual('P HG P N', self.shapes('欠'))
        # 欠 its 捺 a 点 in 阙: not 𠂊's 横撇
        self.assertTrue(self.shapes('阙').endswith('P P HG P D'))
        self.assertEqual(('P HG SG P D', 'HP SG H', 'P D D P HP SG H'), (self.shapes('尔'), self.shapes('子'), self.shapes('孚')))
        # 冖 before an 又-like part: 横钩, not 又's 横撇
        self.assertEqual('D H D P D HG D H HZG P', self.shapes('旁'))
        self.assertEqual(('HG D D H P D', 'HP D HG SG', 'HG P S HP N'), (self.shapes('买'), self.shapes('予'), self.shapes('皮')))

    def test_every_part_of_a_part_even_round_a_cycle(self):
        parts = stroke_shapes.parts_of(CHAIZI)
        self.assertEqual({'鱼', '虱', '乁', '一', '虫'}, parts('鲺'))
        # chaizi with a cycle: each its whole walk, whichever is asked first
        self.assertEqual(({'乙', '丙'}, {'丙', '甲'}), (parts('甲'), parts('乙')))

    def test_a_line_corrected_by_hand_and_the_kinds_checked(self):
        tsv = ['# head\n', '\n', '行\t332112\n', '皙\t1234331232511\n', '承\t52111534\n', '匹\t1355\n', '江\t441121\tsome note\n']
        lines, notes = converted(tsv)
        self.assertEqual(['# head', '', '行\tP P S H H SG', '皙\tH S P D P P H S P S HZ H H', '承\tHP SG H H H HP P N', '匹\tH P SW SZ',
                          '江\tD D T H S H\tsome note'], lines)
        self.assertEqual([], notes)
        # run again on what it made: the same
        self.assertEqual((lines, []), converted([line + '\n' for line in lines]))
        # a stroke corrected by hand since: kept, and said
        lines, notes = converted(['口\tS HZ H H\n'])
        self.assertEqual(['口\tS HZ H H'], lines)
        self.assertEqual(['口: kept as written, 251 by the rules'], notes)
        # a character cnchar has not, added by hand: kept
        self.assertEqual((['冖\tD HG'], []), converted(['冖\tD HG\n']))
        # the digits of other kinds than the rules make: a slip in the rules or the file
        with self.assertRaises(ValueError):
            converted(['口\t252\n'])

    def test_a_stroke_no_rule_settles_is_said(self):
        lines, notes = stroke_shapes.convert({'𠀀': 'je'}, [], ['𠀀\t15\n'])
        self.assertEqual(['𠀀\t15'], lines)
        self.assertEqual(['𠀀 je: stroke 2 settled by no rule'], notes)
        with self.assertRaises(ValueError):
            stroke_shapes.shapes('𠀀', 'jA', lambda c: set())


if __name__ == '__main__':
    unittest.main()
