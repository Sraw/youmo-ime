// 尺寸: the keyboard's height and its side and bottom room,
// portrait and landscape, set on `keyboard` alone (Values.KEYBOARD_SIZES, in the settings' own ranges), and the
// keys' gaps (`key inset=`). What the user picked in the app's settings is over these.
(function (root) {
  'use strict';
  const panels = root.YoumoPanels;
  const PARTS = [
    { id: 'keyboard', title: '键盘', select: 'keyboard', fixed: true, note: '用户在 App 设置里选了具体值的，会盖过这里。', props: [
      // written `%` both ways (the format's); 横 is read as a level, as the app's setting is: its rows' dp from
      // KeyboardHeightLevels' table, so the field says 档
      { prop: 'height', label: '高度', kind: 'twin', unit: '%', shown: ['%', '档'], min: 20, max: 90,
        note: '竖屏：屏幕高度的百分比。横屏：档位值，34 紧凑、42 矮、49 标准、55 高、60 很高，手机每行 34/41/48/54/59 dp，平板和折叠屏内屏 48/52/56/60/64 dp，档位之间按比例' },
      { prop: 'side-padding', label: '两侧边距', kind: 'twin', unit: 'dp', min: 0, max: 300 },
      { prop: 'bottom-padding', label: '底部边距', kind: 'twin', unit: 'dp', min: 0, max: 100 },
    ] },
    { id: 'gap', title: '键缝', select: 'key', props: [{ prop: 'inset', label: '键缝（每个键四边缩进）', kind: 'inset' }] },
  ];

  panels.widgetTab({ id: 'size', label: '尺寸', parts: PARTS });
})(typeof window !== 'undefined' ? window : globalThis);
