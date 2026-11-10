// 尺寸 (060-render-p3 §5.1 panel 8, v2 §15 主题管尺寸): the keyboard's height and its side and bottom room,
// portrait and landscape, set on `keyboard` alone (Values.KEYBOARD_SIZES, in the settings' own ranges), and the
// keys' gaps (`key inset=`). What the user picked in the app's settings is over these.
(function (root) {
  'use strict';
  const panels = root.YoumoPanels;
  const PARTS = [
    { id: 'keyboard', title: '键盘', select: 'keyboard', fixed: true, note: '用户在 App 设置里选了具体值的，会盖过这里。', props: [
      { prop: 'height', label: '高度', kind: 'twin', unit: '%', min: 20, max: 90, note: '屏幕高度的百分比' },
      { prop: 'side-padding', label: '两侧边距', kind: 'twin', unit: 'dp', min: 0, max: 300 },
      { prop: 'bottom-padding', label: '底部边距', kind: 'twin', unit: 'dp', min: 0, max: 100 },
    ] },
    { id: 'gap', title: '键缝', select: 'key', props: [{ prop: 'inset', label: '键缝（每个键四边缩进）', kind: 'inset' }] },
  ];

  panels.widgetTab({ id: 'size', label: '尺寸', parts: PARTS });
})(typeof window !== 'undefined' ? window : globalThis);
