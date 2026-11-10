// 栏 (060-render-p3 §5.1 panel 5): the candidate bar, its candidates and their parts, the lines between them, the
// expanded candidates' panel, the code being typed and the toolbar's buttons (T6a, T6b: what BarLook reads). The
// preview shows the bar above the keyboard (环境 → 候选栏样例).
(function (root) {
  'use strict';
  const panels = root.YoumoPanels;
  const fill = { prop: 'fill', label: '底色', kind: 'color' };
  const color = { prop: 'color', label: '文字颜色', kind: 'color' };
  const PARTS = [
    { id: 'bar', title: '候选栏', select: 'bar', props: [
      Object.assign({}, fill, { note: '颜色或 picture(…)（透出背景图）；渐变、图片画不出来' }),
      { prop: 'height', label: '高度', kind: 'percent', min: 80, max: 140, note: '按候选字号算出的高度的百分比' },
    ] },
    { id: 'candidate', title: '候选词', select: 'candidate', props: [
      fill, color, { prop: 'radius', label: '圆角', kind: 'radius' },
      { prop: 'inset', label: '底色内缩', kind: 'inset' },
      { prop: 'padding', label: '字两边留白', kind: 'inset', note: '只看左右' },
      { prop: 'gap', label: '间距', kind: 'dp', max: 24, step: 0.5 },
      { prop: 'weight', label: '字重', kind: 'choice', options: [['300', '细'], ['400', '常规'], ['700', '粗']] },
      { prop: 'label-style', label: '序号', kind: 'choice', options: [['none', '没有'], ['number', '数字'], ['dot', '圆点']] },
    ] },
    { id: 'first', title: '第一个候选', select: 'candidate:first', props: [fill, color] },
    { id: 'selected', title: '选中的候选（实体键盘）', select: 'candidate:selected', props: [fill, color] },
    { id: 'pressed', title: '按下的候选', select: 'candidate:pressed', props: [{ prop: 'overlay', label: '盖的颜色', kind: 'color' }] },
    { id: 'label', title: '候选的序号', select: 'candidate label', props: [color] },
    { id: 'comment', title: '候选的注释', select: 'candidate comment', props: [color] },
    { id: 'divider', title: '分隔线', select: 'candidates-divider', props: [Object.assign({}, fill, { label: '颜色' })] },
    { id: 'panel', title: '展开候选的面板', select: 'candidates-panel', props: [fill] },
    { id: 'preedit', title: '预编辑（正在打的字）', select: 'preedit', props: [
      color, { prop: 'underline', label: '下划线', kind: 'border' }, { prop: 'cursor-color', label: '光标颜色', kind: 'color' },
    ] },
    { id: 'toolbar', title: '工具栏按钮', select: 'toolbar', props: [fill, { prop: 'radius', label: '圆角', kind: 'radius', note: '不设是圆' }, { prop: 'icon-color', label: '图标颜色', kind: 'color' }] },
    { id: 'toolbar-pressed', title: '工具栏按钮按下', select: 'toolbar:pressed', props: [{ prop: 'overlay', label: '盖的颜色', kind: 'color' }] },
    { id: 'toolbar-on', title: '工具栏按钮开着', select: 'toolbar:selected', props: [fill, { prop: 'icon-color', label: '图标颜色', kind: 'color' }] },
  ];

  // the bar is one over every window form: only these scenes tell it apart (ThemeReader's unplacedBar)
  panels.widgetTab({ id: 'bar', label: '栏', parts: PARTS, scenes: ['compact', 'landscape', 'flat', 'dark', 'field=password'] });
})(typeof window !== 'undefined' ? window : globalThis);
