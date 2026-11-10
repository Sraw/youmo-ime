// 弹出 (060-render-p3 §5.1 panel 6): the key's bubble (`preview`, T4b's shape, tail, size and offset), the long
// press's keys (`popup`, the one in focus `:selected`) and the menu (`menu`). The preview shows each (环境 → 显示).
(function (root) {
  'use strict';
  const panels = root.YoumoPanels;
  const face = [
    { prop: 'fill', label: '底色', kind: 'color' },
    { prop: 'color', label: '文字颜色', kind: 'color' },
    { prop: 'radius', label: '圆角', kind: 'radius' },
    { prop: 'border', label: '描边', kind: 'border' },
    { prop: 'shadow', label: '阴影', kind: 'shadow' },
  ];
  const PARTS = [
    { id: 'preview', title: '按键气泡', select: 'preview', props: face.concat([
      { prop: 'shape', label: '形状', kind: 'choice', options: [['rect', '方块'], ['bubble', '气泡（带尾巴）']] },
      { prop: 'tail', label: '尾巴', kind: 'flag', note: '只对气泡形状' },
      { prop: 'size', label: '大小', kind: 'percent', min: 100, max: 160, step: 5 },
      { prop: 'offset', label: '上移', kind: 'dp', min: -24, max: 24, step: 0.5 },
    ]) },
    { id: 'popup', title: '长按弹出', select: 'popup', props: face },
    { id: 'popup-selected', title: '长按弹出里选中的', select: 'popup:selected', props: [face[0], face[1], face[2]] },
    { id: 'menu', title: '菜单', select: 'menu', props: [face[0], face[1], face[2]] },
    { id: 'menu-selected', title: '菜单里选中的', select: 'menu:selected', props: [face[0], face[1]] },
    { id: 'menu-disabled', title: '菜单里不能选的', select: 'menu:disabled', props: [face[1]] },
  ];

  panels.widgetTab({ id: 'popup', label: '弹出', parts: PARTS });
})(typeof window !== 'undefined' ? window : globalThis);
