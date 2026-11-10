// 按键音 (060-theme-sounds-icons §1.5): a sound file for each of the five kinds of key (<sounds>, <sound key src>),
// each checked by the engine's readSound (SoundFile: what the app imports, its formats and limits) before it goes
// in, listened to here by WebAudio alone (decodeAudioData fetches nothing, so the page's CSP stays as it is). Taps on
// the preview make no sound: the app plays them. A key the app does not know yet (a later app's) is kept and listed.
(function (root) {
  'use strict';
  const panels = root.YoumoPanels;
  const F = root.YoumoForm;
  const KEYS = [['letter', '字母键'], ['function', '功能键'], ['space', '空格'], ['enter', '回车'], ['backspace', '退格']];
  const MP3_NOTE = 'MP3 开头有约 25 ms 的空白，想按下就响用 WAV 或 Ogg';

  const ops = {
    KEYS,
    MP3_NOTE,
    /** [bytes] as the sound file [name] by the engine's rule: `{ok, info}` or `{ok: false, problems}` (readSound's). */
    read: (E, name, bytes) => E.readSound(name, bytes),

    /**
     * [d] with the file [name] ([bytes]) the sound of [key], kept as `sound-<key>.<its format>`; the file the key had
     * before goes where nothing else names it. Throws, saying why, where the app would refuse the file.
     */
    setSound(d, E, key, name, bytes) {
      const r = E.readSound(name, bytes);
      if (!r.ok) throw new Error(r.problems[0].message);
      let files = panels.filesOf(d);
      const file = panels.fileName(files, 'sound-', key, r.info.format);
      files[file] = bytes;
      const set = F.setSound(d.xml, key, file);
      files = panels.without(files, set.gone);
      return { xml: set.xml, files, layouts: d.layouts };
    },

    /** [d] without a sound for [key] (the app's own plays), its file gone where nothing else names it. */
    removeSound(d, key) {
      const set = F.removeSound(d.xml, key);
      return { xml: set.xml, files: panels.without(d.files, set.gone), layouts: d.layouts };
    },
  };

  // one AudioContext for the page, made on the first 试听 (a browser lets one start only after a click)
  let audio = null;
  async function listen(api, bytes) {
    try {
      audio = audio || new (root.AudioContext || root.webkitAudioContext)();
      // decodeAudioData takes (detaches) its buffer: a copy, the document's bytes kept
      const buffer = await audio.decodeAudioData(bytes.slice().buffer);
      const s = audio.createBufferSource();
      s.buffer = buffer;
      s.connect(audio.destination);
      s.start();
    } catch (e) {
      // some browsers decode no Ogg (Safari before 17): the file is fine for the app
      api.toast('这个浏览器放不了，App 里能放');
    }
  }

  const CHANNELS = { 1: '单声道', 2: '双声道' };
  const describe = (i) => i.format.toUpperCase() + '，' + i.ms + ' ms，' + (CHANNELS[i.channels] || i.channels + ' 声道') + '，' + i.rate + ' Hz';

  function render(el, api) {
    const { h, row } = panels.ui;
    const d = api.doc();
    el.append(h('p', { className: 'muted', textContent: '每类键一个声音：Ogg Vorbis、WAV（8/16 位 PCM）或 MP3，单个不超过 100 KB、1 秒，1–2 声道，8000–48000 Hz。' +
      '主题没给的类别用字母键的声音。App 里要在「声音与振动」开着按键音、按键音音色选「跟随主题」才会响；预览上点键不出声。' }));
    const own = F.sounds(d.xml);
    const known = KEYS.map((k) => k[0]);
    const rows = KEYS.concat(Object.keys(own).filter((k) => !known.includes(k)).map((k) => [k, k + '（这版 App 不认识，保留）']));
    for (const [key, label] of rows) {
      const file = own[key];
      const bytes = file ? d.files[file] : null;
      const r = bytes ? ops.read(api.engine, file, bytes) : null;
      const says = !file ? (key === 'letter' ? '没有（放系统按键音）' : '没有（用字母键的）') : !bytes ? file + '：缺少文件' : r.ok ? file + '：' + describe(r.info) : file + '：' + r.problems[0].message;
      el.append(row(label, h('span', { className: file && (!bytes || !r.ok) ? 'bad' : 'muted', textContent: says }),
        known.includes(key) ? h('button', { type: 'button', textContent: '选文件…', on: { click: async () => {
          const f = await api.pickFile('.ogg,.wav,.mp3,audio/ogg,audio/wav,audio/mpeg');
          if (!f) return;
          const picked = new Uint8Array(f.bytes);
          const got = ops.read(api.engine, f.name, picked);
          if (!got.ok) { api.toast(f.name + ' 用不了：' + got.problems[0].message); return; }
          api.apply((x) => ops.setSound(x, api.engine, key, f.name, picked));
          if (got.info.format === 'mp3') api.toast(MP3_NOTE);
        } } }) : null,
        bytes ? h('button', { type: 'button', textContent: '试听', on: { click: () => listen(api, bytes) } }) : null,
        file ? h('button', { type: 'button', textContent: '去掉', on: { click: () => api.apply((x) => ops.removeSound(x, key)) } }) : null));
      if (r && r.ok && r.info.format === 'mp3') el.append(h('p', { className: 'muted', textContent: '（' + label + '）' + MP3_NOTE }));
    }
  }

  panels.push({ id: 'sounds', label: '按键音', ops, render });
})(typeof window !== 'undefined' ? window : globalThis);
