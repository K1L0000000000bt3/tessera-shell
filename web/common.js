/* Tessera 外壳的公共部分：原生事件总线、宿主接口、时间格式、应用与图标映射。
   桌面（shell.js）和其他应用上方的状态层（overlay.js）都用它。 */
(function () {
  'use strict';

  // ---------- 原生 → 页面：TS.emit(name, data) ----------
  var listeners = {};
  var TS = window.TS = {
    on: function (name, fn) { (listeners[name] = listeners[name] || []).push(fn); return function () { TS.off(name, fn); }; },
    off: function (name, fn) { listeners[name] = (listeners[name] || []).filter(function (f) { return f !== fn; }); },
    emit: function (name, data) { (listeners[name] || []).slice().forEach(function (f) { try { f(data); } catch (e) { console.error(e); } }); }
  };

  // ---------- 宿主（Android）；在电脑浏览器里预览时用假数据 ----------
  var host = window.TesseraHost;
  if (!host) {
    host = {
      apps: function () {
        return JSON.stringify([
          ['com.android.messaging', '信息'], ['com.android.dialer', '电话'], ['com.android.deskclock', '时钟'],
          ['org.fossify.calendar', '日历'], ['com.android.contacts', '联系人'], ['com.ailife.betterhealth', '健康追踪器'],
          ['org.lineageos.eleven', '音乐'], ['com.android.settings', '设置'], ['org.lineageos.recorder', '录音机'],
          ['com.ailife.rotatingcamera', '相机'], ['org.lineageos.glimpse', '相册'], ['com.ailife.onyxbrowser2', '浏览器']
        ].map(function (a) { return { id: a[0] + '/.Main', pkg: a[0], cls: '.Main', label: a[1] }; }));
      },
      notifications: function () {
        var now = Date.now();
        return JSON.stringify([
          { key: 'a', pkg: 'com.android.messaging', app: '信息', title: '林晓', text: '今晚七点，老地方见', when: now - 120000, actions: [{ label: '回复', input: true }, { label: '好的' }], clearable: true },
          { key: 'b', pkg: 'com.android.messaging', app: '信息', title: '妈妈', text: '到家了吗？', when: now - 3600000, actions: [], clearable: true }
        ]);
      },
      launch: function () {}, openSettings: function () {}, notifOpen: function () {}, notifDismiss: function () {},
      notifClear: function () {}, notifAction: function () {}, notifAccess: function () { return true; },
      battery: function () { return '{"level":82,"charging":false}'; }, steps: function () { return 6240; },
      nextAlarm: function () { return 0; }, is24Hour: function () { return true; },
      wifi: function () { return true; }, setWifi: function () {}, bluetooth: function () { return false; }, setBluetooth: function () {},
      brightness: function () { return 128; }, setBrightness: function () { return true; },
      dnd: function () { return false; }, setDnd: function () { return true; },
      vibrate: function () {}, log: function (m) { console.log(m); }, overlayTouch: function () {},
      // 原生表盘 / 原生网格在电脑预览里不存在：空实现，免得调用时报错
      faceSink: function () {}, faceShown: function () {}, faceActivity: function () {}, faceBlur: function () {},
      faceToastBlur: function () {}, faceToggleMode: function () {}, launchFade: function () {}, launchAt: function () {}
    };
  }
  TS.host = host;
  TS.json = function (s, fallback) { try { return JSON.parse(s); } catch (e) { return fallback; } };

  // ---------- 时间：一律 24 小时制；相对时间缩写 ----------
  var WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
  function pad(n) { return n < 10 ? '0' + n : '' + n; }
  TS.timeText = function (d) { d = d || new Date(); return d.getHours() + ':' + pad(d.getMinutes()); };
  TS.dateText = function (d) { d = d || new Date(); return (d.getMonth() + 1) + '月' + d.getDate() + '日' + WEEK[d.getDay()]; };
  TS.ago = function (when) {
    var diff = Date.now() - when, min = Math.floor(diff / 60000);
    if (min < 1) return '现在';
    if (min < 60) return min + '分';
    var hr = Math.floor(min / 60);
    var d = new Date(when), today = new Date();
    if (hr < 24 && d.getDate() === today.getDate()) return hr + '时';
    var y = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 1);
    if (d.getFullYear() === y.getFullYear() && d.getMonth() === y.getMonth() && d.getDate() === y.getDate()) return '昨天';
    return (d.getMonth() + 1) + '月' + d.getDate() + '日';
  };
  TS.num = function (n) { return Number(n || 0).toLocaleString('en-US'); };

  /** 每到整分钟回调一次（以及立即一次）。 */
  TS.everyMinute = function (fn) {
    var t;
    function tick() { fn(); t = setTimeout(tick, 60000 - (Date.now() % 60000) + 20); }
    tick();
    return function () { clearTimeout(t); };
  };

  // ---------- 应用：名字、图标（只用 Tessera 自己的图标，没有的就用名字第一个字） ----------
  TS.APPS = {
    'com.android.messaging': { icon: 'message', label: '信息' },
    'com.android.dialer': { icon: 'phone', label: '电话' },
    'com.android.contacts': { icon: 'people', label: '联系人' },
    'com.ailife.betterhealth': { icon: 'activity', label: '活动' },
    'com.android.deskclock': { icon: 'timer', label: '时钟' },
    'org.fossify.calendar': { icon: 'calendar', label: '日历' },
    'org.lineageos.eleven': { icon: 'music', label: '音乐' },
    'com.android.settings': { icon: 'settings', label: '设置' },
    'org.lineageos.recorder': { icon: 'mic', label: '录音机' },
    'com.android.email': { icon: 'mail', label: '邮件' }
  };
  /** 不放进开始屏的入口：旧桌面、旧工具和开发工具。 */
  TS.HIDDEN_PKGS = ['com.ailife.clox', 'com.s108.watchclock', 'com.android.shell', 'org.omnirom.logcat'];

  TS.appMeta = function (pkg, label) {
    var m = TS.APPS[pkg] || {};
    return { icon: m.icon || null, label: m.label || label || pkg, glyph: (m.label || label || '?').trim().charAt(0) };
  };

  /** 通知的图标：按应用映射，没有就用铃铛。 */
  TS.notifIcon = function (n) { return (TS.APPS[n.pkg] || {}).icon || 'bell'; };
})();
