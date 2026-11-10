// 面板和窗口 (060-render-p3 §5.1 panel 7): the window round the keys (T7a: the floating keyboard's frame and handle,
// the buttons beside a one-handed board, the split's gap, the navigation bar) and the app's panels (T7b: the
// clipboard, the symbols' and emoji's picker, the editing panel, the voice mask, the handwriting pad). What each
// reads is the engine's (ThemeReader's WINDOW_READS, PANEL_READS); the preview shows a sample (环境 → 面板样例).
(function (root) {
  'use strict';
  const panels = root.YoumoPanels;
  const fill = { prop: 'fill', label: '底色', kind: 'color' };
  const color = { prop: 'color', label: '文字颜色', kind: 'color' };
  const icon = { prop: 'icon-color', label: '图标颜色', kind: 'color' };
  const radius = { prop: 'radius', label: '圆角', kind: 'radius' };
  const PARTS = [
    { id: 'frame', title: '悬浮外框', select: 'frame', props: [radius, { prop: 'border', label: '描边', kind: 'border' },
      { prop: 'shadow', label: '阴影', kind: 'shadow', note: '只看模糊（系统的投影）' }] },
    { id: 'handle', title: '悬浮拖动条', select: 'handle', props: [fill, Object.assign({}, color, { label: '颜色' }), icon,
      { prop: 'width', label: '握把宽', kind: 'dp', max: 160 }, { prop: 'height', label: '握把高', kind: 'dp', max: 24 }] },
    { id: 'side-button', title: '单手、分体旁边的按钮', select: 'side-button', props: [fill, icon,
      { prop: 'shape', label: '形状', kind: 'choice', options: [['circle', '圆'], ['rect', '方块'], ['bar', '条']] }, radius] },
    { id: 'side-button-pressed', title: '旁边的按钮按下', select: 'side-button:pressed', props: [{ prop: 'overlay', label: '盖的颜色', kind: 'color' }] },
    { id: 'split-gap', title: '分体中间', select: 'split-gap', props: [fill, radius] },
    { id: 'navbar', title: '导航栏', select: 'navbar', props: [{ prop: 'color', label: '颜色', kind: 'color', note: '写 follow 是跟键盘底色' }] },
    { id: 'clipboard', title: '剪贴板的条目', select: 'clipboard', props: [fill, radius, color] },
    { id: 'picker', title: '符号、表情面板', select: 'picker', props: [fill, color] },
    { id: 'picker-categories', title: '符号、表情的分类栏', select: 'picker categories', props: [fill, color] },
    { id: 'picker-selected', title: '分类栏里选中的', select: 'picker:selected categories', props: [fill, color] },
    { id: 'editing', title: '编辑面板', select: 'editing', props: [fill, radius] },
    { id: 'voice', title: '语音', select: 'voice', props: [fill, { prop: 'blur', label: '毛玻璃', kind: 'dp', max: 25, step: 0.5 }, icon] },
    { id: 'voice-mic', title: '语音的话筒', select: 'voice mic', props: [fill, icon] },
    { id: 'pad', title: '手写板', select: 'handwriting/pad', props: [
      { prop: 'ink-color', label: '笔迹颜色', kind: 'color' }, { prop: 'ink-width', label: '笔迹粗细', kind: 'dp', min: 1, max: 8, step: 0.5 },
      { prop: 'grid-color', label: '格子颜色', kind: 'color' }, { prop: 'grid', label: '格子', kind: 'flag' },
    ] },
  ];

  panels.widgetTab({ id: 'window', label: '面板和窗口', parts: PARTS });
})(typeof window !== 'undefined' ? window : globalThis);
