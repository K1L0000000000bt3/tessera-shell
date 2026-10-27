/* Tessera 桌面：表盘 → 上滑开始屏（磁贴 = 全部应用）→ 点开应用（转门）；表盘下滑通知中心。
   画面全部用 window.Tessera 里的设计稿组件；设计稿没有画到的部分（通知中心、设置、编辑磁贴）
   按同一套规则先做成“胚胎”，样式在 shell.css。 */
(function () {
  'use strict';
  var T = window.Tessera, h = React.createElement, host = TS.host;
  var useState = React.useState, useEffect = React.useEffect, useRef = React.useRef;
  var EASE_OUT = 300, EASE_IN = 240, TURN = 300, TURN_STAGGER = 60;
  var FLIP_END = TURN + TURN_STAGGER * 3;   // 四列磁贴全部翻完的时刻（每轮由 staggerTiles 重算）

  // ---------- 小工具 ----------
  function store(v) {
    var subs = [];
    return {
      get: function () { return v; },
      set: function (n) { v = typeof n === 'function' ? n(v) : n; subs.forEach(function (f) { f(v); }); },
      sub: function (f) { subs.push(f); return function () { subs = subs.filter(function (x) { return x !== f; }); }; }
    };
  }
  function useStore(s) {
    var st = useState(s.get());
    useEffect(function () { st[1](s.get()); return s.sub(st[1]); }, [s]);
    return st[0];
  }
  function load(key, fallback) { try { var v = localStorage.getItem('ts.' + key); return v == null ? fallback : JSON.parse(v); } catch (e) { return fallback; } }
  function save(key, v) { try { localStorage.setItem('ts.' + key, JSON.stringify(v)); } catch (e) { /* 存不下就只在本次有效 */ } }
  function later(ms, fn) { return setTimeout(fn, ms); }

  // ---------- 数据 ----------
  var clock = store({ time: TS.timeText(), date: TS.dateText() });
  TS.everyMinute(function () { clock.set({ time: TS.timeText(), date: TS.dateText() }); });
  TS.on('time', function () { clock.set({ time: TS.timeText(), date: TS.dateText() }); });

  var notifs = store(TS.json(host.notifications(), []));
  TS.on('notifs', function (list) { notifs.set(Array.isArray(list) ? list : TS.json(host.notifications(), [])); });

  // 步数每分钟最多刷新一次，免得开始屏频繁重排
  var steps = store(Number(host.steps()) || 0), pendingSteps = null;
  TS.on('steps', function (d) { pendingSteps = d.today; });
  setInterval(function () { if (pendingSteps != null && pendingSteps !== steps.get()) steps.set(pendingSteps); pendingSteps = null; }, 60000);

  var apps = store(TS.json(host.apps(), []).filter(function (a) { return TS.HIDDEN_PKGS.indexOf(a.pkg) < 0; }));
  try {
    var withIcon = apps.get().filter(function (a) { return !!a.icon; }).length;
    host.log('apps=' + apps.get().length + ' withIcon=' + withIcon
      + ' sample=' + (apps.get()[0] && apps.get()[0].icon ? apps.get()[0].icon.slice(0, 34) : 'none'));
  } catch (e) {}
  TS.on('resume', function () {
    var list = TS.json(host.apps(), []).filter(function (a) { return TS.HIDDEN_PKGS.indexOf(a.pkg) < 0; });
    if (JSON.stringify(list) !== JSON.stringify(apps.get())) apps.set(list);
    steps.set(Number(host.steps()) || steps.get());
    notifs.set(TS.json(host.notifications(), notifs.get()));
  });

  // ---------- 外观：主题与主题色（设置里可改） ----------
  var ACCENTS = [
    { id: 'slate', name: '灰蓝', dark: ['#516b85', '#9db6cf'], light: ['#48617a', '#3d5670'] },
    { id: 'teal', name: '青灰', dark: ['#4f7377', '#9cc3c5'], light: ['#45666a', '#36585b'] },
    { id: 'mauve', name: '灰紫', dark: ['#625d82', '#b3add6'], light: ['#565177', '#48436a'] },
    { id: 'sage', name: '灰绿', dark: ['#57705e', '#a6c3ad'], light: ['#4c6453', '#3d5545'] },
    { id: 'sand', name: '暖灰', dark: ['#7a6a5c', '#cdb9a6'], light: ['#6b5c4f', '#594b3f'] }
  ];
  var look = store(load('look', { theme: 'dark', accent: 'slate' }));
  function applyLook(l) {
    var root = document.documentElement, a = ACCENTS.filter(function (x) { return x.id === l.accent; })[0] || ACCENTS[0];
    root.setAttribute('data-theme', l.theme);
    root.style.setProperty('--accent', a[l.theme][0]);
    root.style.setProperty('--accent-ink', a[l.theme][1]);
    save('look', l);
  }
  applyLook(look.get());
  look.sub(applyLook);

  // ---------- 开始屏布局：顺序、尺寸、明度 ----------
  var DEFAULTS = [
    ['com.android.messaging', 'm', 'accent'], ['com.android.dialer', 's', 'base'], ['com.android.deskclock', 's', 'base'],
    ['org.fossify.calendar', 's', 'base'], ['com.android.contacts', 's', 'base'], ['com.ailife.betterhealth', 'w', 'base'],
    ['org.lineageos.eleven', 's', 'base'], ['com.android.settings', 's', 'base'], ['org.lineageos.recorder', 's', 'soft'],
    ['com.ailife.rotatingcamera', 's', 'soft'], ['org.lineageos.glimpse', 's', 'soft'], ['com.ailife.onyxbrowser2', 's', 'soft']
  ];
  var layout = store(load('layout', { order: [], cfg: {} }));
  layout.sub(function (l) { save('layout', l); });

  function orderedApps(list, l) {
    var byId = {}, out = [];
    list.forEach(function (a) { byId[a.id] = a; });
    l.order.forEach(function (id) { if (byId[id]) { out.push(byId[id]); delete byId[id]; } });
    // 新装的应用：按默认优先级插入，其余放到最后
    var rest = Object.keys(byId).map(function (k) { return byId[k]; });
    rest.sort(function (a, b) { return rank(a.pkg) - rank(b.pkg) || a.label.localeCompare(b.label, 'zh'); });
    return out.concat(rest);
  }
  function rank(pkg) { for (var i = 0; i < DEFAULTS.length; i++) if (DEFAULTS[i][0] === pkg) return i; return 999; }
  function cfgFor(app, l) {
    var c = l.cfg[app.id];
    if (c) return c;
    var d = DEFAULTS[rank(app.pkg)];
    return d ? { size: d[1], tone: d[2] } : { size: 's', tone: 'soft' };
  }

  // ---------- 通知归类 ----------
  function byPkg(list, pkg) { return list.filter(function (n) { return n.pkg === pkg && !n.ongoing; }); }
  // 不算"实时活动"的常驻通知：录音这类只是"我在跑"的占位通知，
  // 认成实时活动会把卡片长期占住。后台服务类（category=service）一律不算。
  var LIVE_EXCLUDE_PKG = ['org.lineageos.recorder'];
  function isLiveNotif(n) {
    if (!n.ongoing) return false;
    if (n.fgs) return false;                       // 前台服务通知：录音、运动记录……不该占卡片
    if (LIVE_EXCLUDE_PKG.indexOf(n.pkg) >= 0) return false;
    if (n.category === 'service') return false;
    return n.progress >= 0 || /stopwatch|alarm|progress|navigation|transport|workout/.test(n.category || '');
  }
  function liveActivity(list) {
    var media = list.filter(function (n) { return n.media; })[0];
    if (media) return { key: media.key, icon: 'music', title: media.app, value: media.title || '正在播放', lines: media.text ? [media.text] : [] };
    var run = list.filter(isLiveNotif)[0];
    if (run) return { key: run.key, icon: run.progress >= 0 ? 'timer' : TS.notifIcon(run), title: run.app, value: run.progress >= 0 ? run.progress + '%' : (run.title || run.app), lines: run.text ? [run.text] : [] };
    return null;
  }
  var GOAL = 8000;

  // ---------- 导航 ----------
  // screen: face | start | notifs | page；page 是开始屏里打开的 Tessera 自己的页面（设置、编辑磁贴）
  var nav = store({ screen: 'face', anim: null, page: null, turn: null });
  // 拖动跟随：null = 没在拖；{ dy, from } = 手指的竖直位移（CSS 像素），页面按它实时位移
  var dragState = store(null);
  var dragFrom = null;
  /** 拖动中只改 CSS 变量和 class，不碰 React：每帧都能跟手，也不会触发整树重渲染。 */
  /** 直接写目标层的 transform：不再往 <html> 上写自定义属性——
      自定义属性会让整棵文档重新计算样式，一帧几百个节点，比变换本身贵得多。 */
  function dragApply(from, dy) {
    var html = document.documentElement;
    if (dragFrom !== from) {
      // 原生开始屏：手指从上拉改成下拉（换成拉通知中心）时，把跟到一半的网格收回去
      if (NATIVE_START && dragFrom === 'face') host.gridPose(html.clientHeight || 200, 0, 0, true);
      dragFrom = from;
      dragState.set({ from: from });          // 只为了让对应的层保持可见
      html.classList.add('sh-dragging');
      host.faceShown(true);                   // 原生表盘：开始露出来，恢复走秒
    }
    var H = html.clientHeight || 200;
    var rising = (from === 'face' || from === 'down-notifs');
    var p = rising ? Math.min(1, Math.abs(dy) / H) : Math.max(0, 1 - Math.abs(dy) / H);
    var face = document.querySelector('.sh-face-layer');
    var start = document.querySelector('.sh-start-layer');
    var nf = document.querySelector('.sh-notifs-layer');
    var rising2 = (from === 'face' || from === 'down-notifs');
    if (from === 'face') {
      if (NATIVE_START) host.gridPose(H + dy, 0, 0, false);     // 原生网格：整层跟着手指从下面推上来
      else if (start) start.style.transform = 'translateY(calc(100% + ' + dy + 'px))';
    }
    else if (from === 'start') { if (start) start.style.transform = 'translateY(' + dy + 'px)'; }
    else if (from === 'down-notifs') { if (nf) nf.style.transform = 'translateY(calc(-100% + ' + dy + 'px))'; }
    else if (from === 'up-notifs') { if (nf) nf.style.transform = 'translateY(' + dy + 'px)'; }
    // 表盘是原生的：下沉交给 FaceView（同样的 11px / 0.93 / 0.85），页面这层只是透明的手势层
    host.faceSink(p, 0);
  }
  var FLING = 0.35;   // 设计像素/毫秒（真机 480 宽上约 840px/s）
  /** 松手：过 35% 或顺着方向甩出去就补完，否则弹回；都用 CSS 过渡补最后一段。 */
  function dragRelease(dy, vy) {
    var from = dragFrom;
    if (!from) return;
    var html = document.documentElement, H = html.clientHeight || 200;
    // 两件事分开算：covering = 表盘是不是正在被盖住（下沉进度 0→1）；
    // sign = 这一拖"该往哪个方向"才算数（上滑为 -1）。从表盘下拉通知中心是"盖住"但手指往下，
    // 以前把两者混成一个 rising，下拉的 dy 是正数，永远过不了 dy < -35% 的判断，通知中心就一直弹回去。
    var covering = (from === 'face' || from === 'down-notifs');
    var sign = (from === 'face' || from === 'up-notifs') ? -1 : 1;
    // 惯性：顺着方向快速一甩（≥ FLING 设计像素/毫秒）哪怕没拖到 35% 也算；反方向一甩哪怕过了 35% 也弹回
    var v = (vy || 0) * sign;
    var commit = v >= FLING ? dy * sign > 8 : v <= -FLING ? false : dy * sign > H * 0.35;
    // 先撤掉 sh-dragging 的"无过渡"，同一帧挂上 sh-settle 再改 transform，过渡才会生效
    html.classList.remove('sh-dragging');
    html.classList.add('sh-settle');
    var start = document.querySelector('.sh-start-layer');
    var nf = document.querySelector('.sh-notifs-layer');
    var target = commit ? sign * H : 0;
    var pEnd = covering ? (commit ? 1 : 0) : (commit ? 0 : 1);
    if (from === 'face' || from === 'start') { if (start) start.style.transform = from === 'face' ? 'translateY(calc(100% + ' + target + 'px))' : 'translateY(' + target + 'px)'; }
    else { if (nf) nf.style.transform = from === 'down-notifs' ? 'translateY(calc(-100% + ' + target + 'px))' : 'translateY(' + target + 'px)'; }
    if (NATIVE_START && from === 'face') host.gridPose(commit ? 0 : H, 200, 0, !commit);   // 原生网格同样 200ms 补完 / 弹回
    host.faceSink(pEnd, 200);                 // 与 html.sh-settle 的 200ms 过渡一致
    later(210, function () {
      html.classList.remove('sh-dragging', 'sh-settle', 'drag-face', 'drag-start', 'drag-notifs-down', 'drag-notifs-up');
      ['.sh-face-layer', '.sh-start-layer', '.sh-notifs-layer'].forEach(function (sel) {
        var el = document.querySelector(sel);
        if (!el) return;
        el.style.transform = '';
        el.style.opacity = '';
      });
      dragFrom = null;
      dragState.set(null);
      if (!commit) return;
      if (from === 'face') nav.set({ screen: 'start', anim: null, page: null, turn: null });
      else if (from === 'down-notifs') nav.set({ screen: 'notifs', anim: null, page: null, turn: null });
      else nav.set({ screen: 'face', anim: null, page: null, turn: null });
    });
  }
  var foregroundAt = Date.now();

  function go(screen, opts) {
    opts = opts || {};
    var cur = nav.get();
    if (cur.screen === screen && !opts.page) return;
    if (screen === 'start' && cur.screen === 'face') nav.set({ screen: 'start', anim: 'enter-up', page: null, turn: null });
    else if (screen === 'face' && cur.screen === 'start') leave('leave-up', 'face');
    else if (screen === 'notifs') nav.set({ screen: 'notifs', anim: 'enter-down', page: null, turn: null });
    else if (screen === 'face' && cur.screen === 'notifs') leave('leave-down', 'face');
    else if (screen === 'page') {
      // 和打开应用一样的节奏：磁贴先从左往右翻完，页面再翻进来。
      // 之前是立刻 nav.set 切页面，开始屏整层被隐藏，磁贴根本没机会演动画。
      turnOut(function () {});
      later(FLIP_END, function () { nav.set({ screen: 'page', anim: 'page-in', page: opts.page, turn: 'out' }); });
    }
    else if (screen === 'start' && cur.screen === 'page') {
      nav.set(Object.assign({}, cur, { anim: 'page-out' }));
      later(EASE_IN, function () { nav.set({ screen: 'start', anim: null, page: null, turn: 'in' }); later(TURN + 200, clearTurn); });
    } else nav.set({ screen: screen, anim: null, page: null, turn: null });
  }
  function leave(anim, to) {
    nav.set(Object.assign({}, nav.get(), { anim: anim }));
    later(EASE_IN, function () { nav.set({ screen: to, anim: null, page: null, turn: null }); });
  }
  function clearTurn() { var c = nav.get(); if (c.turn === 'in') nav.set(Object.assign({}, c, { turn: null })); }
  function back() {
    var s = nav.get().screen;
    if (toast.get()) { dismissToast(); return; }
    if (s === 'page') go('start');
    else if (s === 'start' || s === 'notifs') go('face');
  }
  /** 转门：开始屏的磁贴按"列"从左往右依次给延迟（reverse 时从右往左）。 */
  function staggerTiles(reverse) {
    var tiles = Array.prototype.slice.call(document.querySelectorAll('.sh-start-layer .ts-tile'));
    // 用布局坐标（offsetLeft）：不受滚动、不受正在播放的位移/缩放影响，
    // getBoundingClientRect 会被这些干扰，导致列分组时有时无、翻的顺序看起来是乱的。
    var lefts = tiles.map(function (t) { return t.offsetLeft; });
    var cols = lefts.slice().sort(function (a, b) { return a - b; }).filter(function (v, i, arr) { return i === 0 || v - arr[i - 1] > 1; });
    var delays = tiles.map(function (t, i) {
      var col = cols.indexOf(lefts[i]);
      var order = reverse ? cols.length - 1 - col : col;
      return (order < 0 ? 0 : order) * TURN_STAGGER;
    });
    // 每块自己的动画时长：以最窄的磁贴为基准，按宽度的平方根放大（1× / 1.41× / 2×）。
    // 同一时长下宽卡片远端要走几倍的距离，看起来就"翻得快"，所以按时长补偿；
    // 用开方而不是线性，是因为线性的话整行那块要 4 倍时长，等它翻完应用才启动，太慢。
    var w0 = tiles.reduce(function (m, t) { return Math.min(m, t.offsetWidth || 1e9); }, 1e9);
    if (!isFinite(w0) || w0 <= 0) w0 = 1;
    var end = 0;
    tiles.forEach(function (t, i) {
      // 以中间为轴之后，宽卡的两边各走一半，差别没那么夸张，补偿降下来一点
      // （1 列 = 1.0，2 列 = 1.2，整行 = 1.6），免得大卡拖到别人都翻完了还在转
      var ratio = 1 + 0.2 * (Math.max(1, (t.offsetWidth || w0) / w0) - 1);
      var dur = Math.round(TURN * ratio);
      t.style.setProperty('--turn-dur', dur + 'ms');
      end = Math.max(end, delays[i] + dur);
      t.style.setProperty('--turn-delay', delays[i] + 'ms');
      // 只有整块滚出屏幕很远的磁贴才跳过动画（省 GPU）。阈值给得很宽，
      // 免得屏幕边缘那块被判出去、直接"啪"地消失，看起来像漏了一拍。
      var r = t.getBoundingClientRect();
      var far = r.bottom < -150 || r.top > 900;
      t.style.animation = far ? 'none' : '';
      t.style.willChange = far ? 'auto' : 'transform, opacity';
    });
    FLIP_END = end || (TURN + TURN_STAGGER * 3);
    try { host.log('turn reverse=' + !!reverse + ' cols=' + cols.join('/') + ' delays=' + delays.join(',') + ' end=' + FLIP_END); } catch (e) {}
  }
  function turnOut(then) {
    staggerTiles(false);
    nav.set(Object.assign({}, nav.get(), { turn: 'out' }));
    later(TURN, then);
  }
  /** 卡片放大铺满屏幕：把这张卡片克隆一份浮在最上层，从它的矩形一路放大到整屏（图标、底色一起放大），
      铺满之后再淡入拉起应用——应用是从这张卡片里"化"出来的，而不是整屏缩放到卡片。 */
  var lastTileEl = null;   // 按下时记住是哪块卡片：bundle 里的 tile.onClick 不保证带 event
  var zoomClone = null;
  var pendingZoom = null;   // 记住这次是从哪张卡片出去的，返回时从整屏缩回它
  /** 从应用返回：整屏铺一张卡片，再缩回它原来的位置，露出网格。 */
  function returnZoom(pz) {
    var el = document.querySelector('.sh-start-layer .ts-tile[data-id="' + pz.id + '"]');
    var W = document.documentElement.clientWidth, H = document.documentElement.clientHeight;
    if (!el || !W || !H) return;
    var clone = el.cloneNode(true);
    clone.classList.add('sh-zoom-card');
    clone.style.transition = 'none';
    clone.style.left = '0px';
    clone.style.top = '0px';
    clone.style.width = W + 'px';
    clone.style.height = H + 'px';
    clone.style.transform = 'none';
    clone.style.borderRadius = '0px';
    clone.style.zIndex = '30';
    document.getElementById('root').appendChild(clone);
    var rr = pz.rect;
    var tx = (rr.x + rr.w / 2) - W / 2;
    var ty = (rr.y + rr.h / 2) - H / 2;
    var sx = rr.w / W, sy = rr.h / H;
    requestAnimationFrame(function () {
      requestAnimationFrame(function () {
        clone.style.transition = '';
        clone.style.transform = 'translate(' + tx + 'px,' + ty + 'px) scale(' + sx + ',' + sy + ')';
        clone.style.borderRadius = 'var(--radius-card, 12px)';
      });
    });
    later(260, function () { if (clone.parentNode) clone.parentNode.removeChild(clone); });
  }

  function clearZoom() {
    if (zoomClone && zoomClone.parentNode) zoomClone.parentNode.removeChild(zoomClone);
    zoomClone = null;
    document.documentElement.classList.remove('sh-zoom-out');
  }
  function openApp(app, el) {
    el = el || lastTileEl;
    clearZoom();
    var W = document.documentElement.clientWidth, H = document.documentElement.clientHeight;
    var r = el && el.getBoundingClientRect ? el.getBoundingClientRect() : null;
    var zoomed = false;
    if (r && r.width > 4 && r.height > 4) {
      var clone = el.cloneNode(true);
      clone.classList.add('sh-zoom-card');
      clone.style.left = r.left + 'px';
      clone.style.top = r.top + 'px';
      clone.style.width = r.width + 'px';
      clone.style.height = r.height + 'px';
      document.getElementById('root').appendChild(clone);
      zoomClone = clone;
      var tx = W / 2 - (r.left + r.width / 2);
      var ty = H / 2 - (r.top + r.height / 2);
      var sx = W / r.width, sy = H / r.height;
      requestAnimationFrame(function () {
        requestAnimationFrame(function () {
          clone.style.transform = 'translate(' + tx + 'px,' + ty + 'px) scale(' + sx + ',' + sy + ')';
          clone.style.borderRadius = '0px';
        });
      });
      document.documentElement.classList.add('sh-zoom-out');
      pendingZoom = { id: app.id, rect: { x: r.left, y: r.top, w: r.width, h: r.height } };
      zoomed = true;
    }
    // 卡片铺满（240ms）之后再拉起应用，应用用 200ms 淡入，接在卡片的底色上
    later(zoomed ? 230 : 60, function () { host.launchFade(app.pkg, app.cls); });
    later(1500, clearZoom);   // 兜底：应用没起来时把桌面恢复
  }

  TS.on('back', back);
  TS.on('resume', function () {
    foregroundAt = Date.now();
    document.documentElement.classList.remove('sh-launching');
    document.documentElement.classList.remove('sh-zoom-out');
    if (zoomClone && zoomClone.parentNode) zoomClone.parentNode.removeChild(zoomClone);
    if (pendingZoom) { var pz = pendingZoom; pendingZoom = null; if (nav.get().screen === 'start') returnZoom(pz); }
    var c = nav.get();
    if (c.turn === 'out' && c.screen === 'start') {
      staggerTiles(false);
      nav.set(Object.assign({}, c, { turn: 'in' }));
      later(TURN + 300, clearTurn);
    }
  });
  // 侧键 / 主页：从别的应用回来 → 表盘；已在桌面时，表盘 ↔ 开始屏
  TS.on('home', function () {
    dismissToast();
    if (Date.now() - foregroundAt < 800 || nav.get().screen !== 'face') nav.set({ screen: 'face', anim: null, page: null, turn: null });
    else go('start');
  });
  // 抬腕看到的永远是表盘
  TS.on('screen', function (d) { if (!d.on) { nav.set({ screen: 'face', anim: null, page: null, turn: null }); dismissToast(true); } });
  // 表冠：当前屏纵向滚动
  TS.on('crown', function (d) {
    var s = nav.get().screen;
    if (s === 'face') return;
    if (scrollers[s]) scrollers[s].halt();   // 先停下手指甩出去的惯性
    var body = document.querySelector('.sh-layer.is-current .ts-body');
    if (body) body.scrollBy({ top: d.delta * 51, behavior: 'smooth' });
  });
  TS.on('key', function (d) {
    host.log('key ' + d.code + ' ' + d.name);
    if (d.name === 'KEYCODE_F9') TS.emit('home');
  });

  // ---------- 通知横幅（桌面在前台时由桌面画） ----------
  var toast = store(null);
  var toastTimer = null;
  TS.on('notify', function (n) {
    var cur = toast.get();
    var count = cur && !cur.leaving ? cur.count + 1 : 1;
    toast.set({ n: n, count: count, leaving: false, id: Date.now() });
    clearTimeout(toastTimer);
    toastTimer = later(6000, dismissToast);
    host.vibrate(30);
  });
  function dismissToast(now) {
    clearTimeout(toastTimer);
    var cur = toast.get();
    if (!cur) return;
    if (now === true) toast.set(null); else toast.set(Object.assign({}, cur, { leaving: true }));
  }

  // ---------- 手势 ----------
  /** 统一的滑动识别。用触摸事件：页面滚动时指针会被取消，但 touchend 一定会来。鼠标（电脑预览）走指针事件。 */
  function useSwipe(handlers) {
    var ref = useRef(null);
    function start(x, y) { var now = Date.now(); ref.current = { x: x, y: y, t: now, fired: false, py: y, pt: now, vy: 0 }; }
    /** 拖动过程中一旦竖直方向超过 12px 就先触发一次：不等抬手，跟手一点。 */
    function move(x, y) {
      var s = ref.current;
      if (!s) return;
      // 抬手速度（设计像素/毫秒）：最近两次 move 的位移平滑一下，松手时用来判断"甩"
      var now = Date.now();
      if (now > s.pt) { s.vy = 0.6 * ((y - s.py) / (now - s.pt)) + 0.4 * s.vy; s.py = y; s.pt = now; }
      var dx = x - s.x, dy = y - s.y, adx = Math.abs(dx), ady = Math.abs(dy);
      if (ady > 12 && ady > adx) {
        if (!s.fired) {
          s.fired = true;
          // 到阈值先触发一次（通知中心、页面切换用）；方向要对上
          if (dy < 0) { if (handlers.up) handlers.up(); }
          else if (handlers.down) handlers.down();
        }
        // 逐帧跟随：位移直接写到 CSS 变量上（不经过 React），所以每个 move 都能上报
        if (handlers.dragging) handlers.dragging({ dx: dx, dy: dy });
      }
    }
    function end(x, y, e) {
      var s = ref.current; ref.current = null;
      if (!s) return;
      // 手指停住一会儿再抬（>80ms 没动），就不算甩
      if (s.fired) { if (handlers.release) handlers.release({ dx: x - s.x, dy: y - s.y, vy: Date.now() - s.pt > 80 ? 0 : s.vy }); return; }
      var dx = x - s.x, dy = y - s.y, adx = Math.abs(dx), ady = Math.abs(dy);
      if (s.x < 14 && dx > 40 && ady < 40 && handlers.edgeBack) return handlers.edgeBack();
      if (ady > 28 && ady > adx) return dy < 0 ? handlers.up && handlers.up(e) : handlers.down && handlers.down(e);
      if (adx > 36 && adx > ady) return dx < 0 ? handlers.left && handlers.left(e) : handlers.right && handlers.right(e);
      if (adx < 8 && ady < 8 && handlers.tap) handlers.tap({ clientX: x, clientY: y });
    }
    return {
      onTouchStart: function (e) { var t = e.touches[0]; if (e.touches.length === 1) start(t.clientX, t.clientY); else ref.current = null; },
      onTouchMove: function (e) { var t = e.touches[0]; if (t) move(t.clientX, t.clientY); },
      onTouchEnd: function (e) { var t = e.changedTouches[0]; if (t) end(t.clientX, t.clientY, e); },
      onTouchCancel: function () { ref.current = null; },
      onPointerDown: function (e) { if (e.pointerType === 'mouse') start(e.clientX, e.clientY); },
      onPointerMove: function (e) { if (e.pointerType === 'mouse') move(e.clientX, e.clientY); },
      onPointerUp: function (e) { if (e.pointerType === 'mouse') end(e.clientX, e.clientY, e); }
    };
  }

  // ---------- 表盘 ----------
  var FACES = ['plain', 'activity'];
  var faceIndex = store(load('face', 0));
  faceIndex.sub(function (i) { save('face', i); });

  function Face() {
    var c = useStore(clock), list = useStore(notifs), st = useStore(steps), fi = useStore(faceIndex);
    var live = liveActivity(list);
    var activity = live;
    if (!activity && FACES[fi] === 'activity') {
      var pct = Math.min(100, Math.round(st * 100 / GOAL));
      activity = { icon: 'activity', title: '活动', value: pct + '%', lines: [TS.num(st) + ' 步', '目标 ' + TS.num(GOAL)] };
    }
    var swipe = useSwipe({
      // 上滑不再"抬手才切"，而是逐帧跟随：手指到哪，开始屏就跟着到哪
      dragging: function (d) {
        if (d.dy < 0) dragApply('face', d.dy);
        else if (d.dy > 0) dragApply('down-notifs', d.dy);
      },
      release: function (d) { dragRelease(d.dy, d.vy); },
      left: function () { faceIndex.set((fi + 1) % FACES.length); },
      right: function () { faceIndex.set((fi + FACES.length - 1) % FACES.length); },
      tap: function (e) {
        // 先交给原生表盘：点在卡片上就翻转那张卡片，不当作"点通知"或"打开应用"
        if (host.faceTap(e.clientX, e.clientY)) return;
        if (live && live.key && e.clientY > 110) host.notifOpen(live.key);
      }
    });
    // 双指：按住 → 时间逐渐隐藏；快速点一下（<500ms）→ 所有卡片翻回原色
    var twoAt = useRef(0), fingers = useRef(0);
    // 表盘本身由原生 FaceView 画（WebView 下面）：这里只把即时活动交给它，自己留一层透明的手势区
    var actJson = activity ? JSON.stringify({ icon: activity.icon, title: activity.title, value: activity.value, lines: activity.lines || [] }) : '';
    useEffect(function () { host.faceActivity(actJson); }, [actJson]);
    return h('div', Object.assign({ className: 'sh-face-hit' }, swipe, {
      onTouchStart: function (e) {
        // 双指：点一下切换"可翻"状态（进入时时间逐渐隐藏，退出时时间回来并把卡片全部翻回原色）
        if (e.touches.length === 2 && fingers.current !== 2) {
          fingers.current = 2;
          twoAt.current = Date.now();
        }
        swipe.onTouchStart(e);
      },
      onTouchEnd: function (e) {
        if (fingers.current === 2 && e.touches.length === 0) {
          if (Date.now() - twoAt.current < 600) host.faceToggleMode();
          fingers.current = 0;
        }
        swipe.onTouchEnd(e);
      }
    }));
  }

  // ---------- 开始屏：磁贴网格就是全部应用 ----------
  function buildTiles(appList, l, list, st, c) {
    var flip = 0;
    return orderedApps(appList, l).map(function (app) {
      var meta = TS.appMeta(app.pkg, app.label), cfg = cfgFor(app, l);
      var t = { id: app.id, size: cfg.size, tone: cfg.tone, label: meta.label };
      if (meta.icon) {
        t.icon = meta.icon;
      } else if (app.icon) {
        // 第三方应用：不显示文字，把真实应用图标当磁贴底图居中铺上去（Windows 8 那种）。
        // 设计稿的 Tile 只认它自己的图标名/文字，所以这里记下来，渲染后统一贴背景。
        t.label = '';
        t._imgIcon = app.icon;
      } else {
        t.count = meta.glyph;
      }
      var mine = byPkg(list, app.pkg);
      switch (app.pkg) {
        case 'com.android.messaging':
          if (mine.length) {
            t.count = mine.length;
            if (cfg.size !== 's') t.back = { title: mine[0].title, body: mine[0].text };
          }
          break;
        case 'com.android.dialer':
          if (mine.length) { t.count = mine.length; if (cfg.size === 's') delete t.icon; }
          break;
        case 'org.fossify.calendar':
          if (cfg.size === 's') delete t.icon;
          t.count = new Date().getDate();
          break;
        case 'com.ailife.betterhealth': {
          var left = Math.max(0, GOAL - st);
          if (cfg.size === 'w') {
            delete t.icon;
            var l1 = left > 0 ? ('还差 ' + TS.num(Math.ceil(left / 10) * 10) + ' 步') : (TS.num(st) + ' 步');
            var l2 = left > 0 ? ('饭后走 ' + Math.max(1, Math.ceil(left / 100)) + ' 分钟就够') : '今天的目标完成了';
            t._lines = [l1, l2];          // 同一份文字也给原生网格用
            t.content = left > 0
              ? h('div', null, h('div', null, l1), h('div', null, l2))
              : h('div', null, h('div', null, l1), h('div', null, l2));
          } else if (cfg.size === 'm') {
            t.count = TS.num(st);
          }
          break;
        }
        case 'org.lineageos.eleven': {
          var playing = list.filter(function (n) { return n.media; })[0];
          if (playing && cfg.size !== 's') t.back = { title: playing.title, body: playing.text };
          break;
        }
        case 'com.android.deskclock': {
          var at = Number(host.nextAlarm()) || 0;
          if (at && cfg.size !== 's') t.back = { title: '闹钟', body: TS.timeText(new Date(at)) };
          break;
        }
      }
      if (t.back) { t.flipDelay = (flip * 3) + 's'; flip++; }
      t.onClick = app.pkg === 'com.android.settings'
        ? function () { go('page', { page: { kind: 'settings' } }); }
        : function (e) { openApp(app, e && e.currentTarget); };
      t._app = app;
      return t;
    });
  }

  // ---------- 开始屏列表滚动：惯性 + 边缘橡皮筋 + 回弹（watchOS 手感） ----------
  // 原生滚动在边缘是硬停的，而且和"在顶上下拉回表盘"是两套逻辑各抢各的（下滑再上滑会打架）。
  // 这里整块接管：CSS 里 touch-action: none 关掉原生触摸滚动；位置照样写进 scrollTop（合成线程滚动，便宜，
  // 设计稿组件也读它），超出边缘的部分画成 .ts-start 的 translateY。
  // 在顶上继续往下拉，无缝交给 pull（拖开始屏回表盘）；推回去又无缝变回滚动。
  var DECEL = 0.998;      // 惯性：每毫秒保留的速度比例（iOS normal 减速率）
  var SPRING_W = 0.02;    // 回弹：临界阻尼弹簧的角频率（1/ms），约 300ms 回到边缘
  var BOUNCE_V = 1.5;     // 甩到边缘时带进回弹的最大速度（设计像素/ms），最大过冲约 20px
  var SLOP = 6;           // 手指先动这么多才判断横竖
  var scrollers = {};     // 当前挂着的列表滚动器，按屏幕名（start / notifs / page）；表冠滚动前先停惯性
  function rubber(x, d) { return (1 - 1 / (x * 0.55 / d + 1)) * d; }
  function unrubber(y, d) { return y >= d * 0.99 ? d * 180 : (1 / (1 - y / d) - 1) * d / 0.55; }
  function clampV(v) { return Math.max(-BOUNCE_V, Math.min(BOUNCE_V, v)); }

  /** pull：越过 pullEdge 那条边继续拉时，把整层交给它拖（开始屏 'top' 拖回表盘，通知中心 'bottom' 拉回表盘）；
   *  不传 pull 时两头都是橡皮筋回弹（设置、编辑页）。 */
  function ListScroller(root, pull, pullEdge) {
    var topEdge = (pullEdge || 'top') === 'top';
    var body = null, content = null, t = null, cur = 0;
    var shown = 0;          // 当前显示位置（设计像素，可能超出 [0, max]）
    function max() { return Math.max(0, body.scrollHeight - body.clientHeight); }
    function paint(y) {
      shown = y;
      var m = max(), inRange = y < 0 ? 0 : y > m ? m : y, o = y - inRange;
      if (Math.abs(body.scrollTop - inRange) >= 0.5) body.scrollTop = inRange;
      var tr = o ? 'translateY(' + (-o).toFixed(2) + 'px)' : '';
      if (content.style.transform !== tr) content.style.transform = tr;
    }
    function stop() { cur++; }
    /** 逐帧跑 step(dt)，返回 true 继续；新的 run / stop 会让旧的自动停下 */
    function run(step) {
      var me = ++cur, last = 0;
      requestAnimationFrame(function f(now) {
        if (me !== cur) return;
        var dt = last ? Math.min(40, now - last) : 16; last = now;
        if (step(dt) && me === cur) requestAnimationFrame(f);
      });
    }
    /** 从过冲 x0 带着速度 v0 回到边缘：临界阻尼 x(t) = (x0 + (v0 + w·x0)·t)·e^(-w·t)，不会来回晃 */
    function springTo(edge, x0, v0) {
      var tt = 0;
      run(function (dt) {
        tt += dt;
        var x = (x0 + (v0 + SPRING_W * x0) * tt) * Math.exp(-SPRING_W * tt);
        if (tt > 60 && Math.abs(x) < 0.3) { paint(edge); return false; }
        paint(edge + x);
        return true;
      });
    }
    /** 松手：在边缘外就回弹；否则按速度惯性滑行，撞到边缘带着剩余速度回弹一下 */
    function settle(v) {
      var m = max();
      if (shown < 0) return springTo(0, shown, clampV(v));
      if (shown > m) return springTo(m, shown - m, clampV(v));
      if (Math.abs(v) < 0.02) return;
      var y = shown;
      run(function (dt) {
        y += v * dt;
        v *= Math.pow(DECEL, dt);
        var m2 = max();
        if (y < 0 || y > m2) { var e = y < 0 ? 0 : m2; springTo(e, y - e, clampV(v)); return false; }
        paint(y);
        return Math.abs(v) >= 0.02;
      });
    }
    function onStart(e) {
      t = null;
      if (e.touches.length !== 1) return;
      body = root.querySelector('.ts-body');
      content = body && body.firstElementChild;
      if (!content) return;
      stop();
      // 列表可能被表冠、重新挂载改过：没有过冲时以 scrollTop 为准
      if (!content.style.transform) shown = body.scrollTop;
      var m = max(), D = body.clientHeight || 200;
      var raw = shown < 0 ? -unrubber(-shown, D) : shown > m ? m + unrubber(shown - m, D) : shown;
      var p = e.touches[0];
      t = { x: p.clientX, y: p.clientY, raw: raw, lock: 0, atEdge: topEdge ? shown <= 1 : shown >= m - 1,
            pulling: false, pulled: false, pullDy: 0, s: [[e.timeStamp, p.clientY]] };
    }
    function onMove(e) {
      if (!t) return;
      var p = e.touches[0];
      if (!p) return;
      var dx = p.clientX - t.x, dy = p.clientY - t.y;
      if (!t.lock) {
        if (Math.abs(dx) < SLOP && Math.abs(dy) < SLOP) return;
        if (Math.abs(dx) > Math.abs(dy)) { t = null; settle(0); return; }   // 横向：交给左边缘返回等
        t.lock = 1;
        t.y += dy > 0 ? SLOP : -SLOP;   // 吃掉判定用的那几像素，开始滚动时不跳
      }
      // 磁贴被拿起（长按）：让组件自己拖，列表回到范围内
      if (document.querySelector('.ts-ghost')) { endPull(); t = null; settle(0); return; }
      t.s.push([e.timeStamp, p.clientY]);
      while (t.s.length > 2 && e.timeStamp - t.s[0][0] > 100) t.s.shift();
      var raw = t.raw - (p.clientY - t.y), m = max(), D = body.clientHeight || 200;
      // 越过"拉出边"的距离：开始屏是顶边（往下拉），通知中心是底边（往上拉）
      var over = !pull ? 0 : topEdge ? -raw : raw - m;
      if (over > 0) {                    // 整层跟着手指走，露出底下的表盘
        t.pulling = t.pulled = true;
        t.pullDy = topEdge ? over : -over;
        paint(topEdge ? 0 : m);
        pull.move(t.pullDy);
        return;
      }
      if (t.pulling) { t.pulling = false; t.pullDy = 0; pull.move(0); }
      paint(raw < 0 ? -rubber(-raw, D) : raw > m ? m + rubber(raw - m, D) : raw);
    }
    function endPull() { if (t && t.pulled) { t.pulled = t.pulling = false; pull.end(0, 0); } }
    /** 手指速度（设计像素/ms，向下为正）；抬手前停住超过 80ms 算 0 */
    function fingerV(now) {
      var s = t.s, a = s[0], b = s[s.length - 1];
      if (s.length < 2 || now - b[0] > 80 || b[0] <= a[0]) return 0;
      return (b[1] - a[1]) / (b[0] - a[0]);
    }
    function onEnd(e) {
      if (!t) return;
      var tt = t, v = e.type === 'touchcancel' ? 0 : fingerV(e.timeStamp);
      t = null;
      if (!tt.lock) return;              // 轻点：交给点击
      if (tt.pulling) {
        // 只有一开始就在那条边上的拉才认"甩"：从中间一路滚到边的惯性不该把整层甩走
        pull.end(tt.pullDy, tt.atEdge ? v : 0);
        return;
      }
      if (tt.pulled) pull.end(0, 0);     // 拉出去又推回来了：开始屏归位，列表照常惯性滚动
      settle(-v);
    }
    var opts = { passive: true };
    root.addEventListener('touchstart', onStart, opts);
    root.addEventListener('touchmove', onMove, opts);
    root.addEventListener('touchend', onEnd, opts);
    root.addEventListener('touchcancel', onEnd, opts);
    return {
      /** 停下惯性/回弹并收回过冲（表冠滚动前调用） */
      halt: function () { stop(); if (content && body) paint(Math.max(0, Math.min(max(), shown))); },
      detach: function () {
        stop();
        root.removeEventListener('touchstart', onStart, opts);
        root.removeEventListener('touchmove', onMove, opts);
        root.removeEventListener('touchend', onEnd, opts);
        root.removeEventListener('touchcancel', onEnd, opts);
      }
    };
  }

  /** 把 ListScroller 挂到组件根节点上（返回给根节点用的 ref）；key 是表冠用的屏幕名。 */
  function useListScroller(key, pull, pullEdge) {
    var ref = useRef(null);
    useEffect(function () {
      if (!ref.current) return;
      var sc = ListScroller(ref.current, pull, pullEdge);
      scrollers[key] = sc;
      return function () { sc.detach(); if (scrollers[key] === sc) delete scrollers[key]; };
    }, []);
    return ref;
  }

  function Start() {
    var appList = useStore(apps), l = useStore(layout), list = useStore(notifs), st = useStore(steps), n = useStore(nav);
    var tiles = buildTiles(appList, l, list, st);
    // AppList 只在挂载时读一次 apps：数据变化时换 key 重新挂载（拖动中不换，避免打断）
    var sig = JSON.stringify(tiles.map(function (t) { return [t.id, t.size, t.tone, t.count, t.back, t.label, t.content ? st : 0]; }));
    var keyRef = useRef({ sig: sig, n: 0 }), holdRef = useRef(null), dragging = useRef(false);
    if (keyRef.current.sig !== sig && !dragging.current) keyRef.current = { sig: sig, n: keyRef.current.n + 1 };

    // 把整份磁贴布局推给原生网格（TileGridView 迁移期用：尺寸/色调/标签/图标都由这里决定）
    // 颜色按设计稿的 TONES 从 tokens 里取（--tile-bg / --tile-fg），跟着主题和主题色走；
    // 第三方应用磁贴上不显示名字，但搜索页要用，所以另外带上 name。
    var lk = useStore(look);
    useEffect(function () {
      try {
        var cs = getComputedStyle(document.documentElement);
        var v = function (n) { return cs.getPropertyValue(n).trim(); };
        var TONE = { accent: [v('--accent'), v('--on-accent')], base: [v('--tile-base'), v('--on-tile')],
                     soft: [v('--tile-soft'), v('--on-tile')], danger: [v('--danger'), v('--on-accent')] };
        // tok：原生搜索页要用的设计稿颜色（跟着深浅主题走）
        var tok = {};
        ['--ink', '--ink-muted', '--ink-subtle', '--surface', '--surface-2', '--line-strong', '--accent-ink', '--on-accent'].forEach(function (n) { tok[n.slice(2)] = v(n); });
        var payload = { bg: v('--bg'), accent: v('--accent'), tok: tok, tiles: tiles.map(function (t) {
          var c = TONE[t.tone] || TONE.base, app = t._app || {};
          return { id: t.id, pkg: app.pkg || '', cls: app.cls || '', kind: app.pkg === 'com.android.settings' ? 'settings' : '',
                   size: t.size || 's', tone: t.tone || 'base', bg: c[0], fg: c[1], label: t.label || '', name: app.label || t.label || '',
                   icon: t._imgIcon || '', glyph: t.icon || '', sglyph: (TS.appMeta(app.pkg || '', app.label || '').icon) || '', count: t.count != null ? String(t.count) : '',
                   lines: t._lines || [], back: t.back || null, flipDelay: parseFloat(t.flipDelay) || 0 };
        }) };
        if (host.gridTiles) host.gridTiles(JSON.stringify(payload));
      } catch (e) {}
    }, [sig, lk.theme, lk.accent]);

    // 第三方应用磁贴：把真实图标贴成背景，并藏掉设计稿自带的那三个点
    useEffect(function () {
      var map = {};
      buildTiles(appList, l, list, st).forEach(function (t) { if (t._imgIcon) map[t.id] = t._imgIcon; });
      var els = document.querySelectorAll('.sh-start-layer .ts-tile[data-id]');
      Array.prototype.forEach.call(els, function (el) {
        var uri = map[el.getAttribute('data-id')];
        if (!uri) return;
        var face = el.querySelector('.ts-face') || el;
        face.style.backgroundImage = 'url("' + uri + '")';
        face.style.backgroundSize = '62%';
        face.style.backgroundPosition = 'center';
        face.style.backgroundRepeat = 'no-repeat';
        var g = el.querySelector('.ts-icon');
        if (g) g.style.display = 'none';
      });
    }, [keyRef.current.n, sig]);

    // 拿起后不移动直接松手 → 弹出编辑（组件只负责拿起和放下）
    function onDown(e) {
      var el = e.target.closest && e.target.closest('[data-id]');
      lastTileEl = el || null;
      holdRef.current = el ? { id: el.getAttribute('data-id'), x: e.clientX, y: e.clientY, t: Date.now(), moved: false } : null;
      dragging.current = true;
    }
    function onMove(e) {
      var hr = holdRef.current;
      if (hr && (Math.abs(e.clientX - hr.x) > 8 || Math.abs(e.clientY - hr.y) > 8)) hr.moved = true;
    }
    function onUp() {
      var hr = holdRef.current; holdRef.current = null;
      dragging.current = false;
      if (hr && !hr.moved && Date.now() - hr.t >= 400) later(320, function () { go('page', { page: { kind: 'edit', id: hr.id } }); });
    }
    // 纵向手势（滚动、在顶上下拉回表盘）全部交给 ListScroller；这里只留左边缘右滑返回
    var swipe = useSwipe({ edgeBack: function () { go('face'); } });
    var rootRef = useListScroller('start', {
      move: function (dy) { dragApply('start', dy); },
      end: function (dy, vy) { dragRelease(dy, vy); }
    }, 'top');

    var cls = 'sh-turn' + (n.turn === 'out' ? ' sh-turn-out' : n.turn === 'in' ? ' sh-turn-in' : '');
    return h('div', Object.assign({}, swipe, {
      ref: rootRef,
      className: cls,
      onPointerDown: function (e) { onDown(e); swipe.onPointerDown(e); },
      onPointerMove: function (e) { onMove(e); if (swipe.onPointerMove) swipe.onPointerMove(e); },
      onPointerUp: function (e) { onUp(e); swipe.onPointerUp(e); },
      onPointerCancel: function () { holdRef.current = null; dragging.current = false; }
    }),
      h(T.Watch, null, h(T.AppList, {
        key: keyRef.current.n,
        apps: tiles,
        onReorder: function (ids) { layout.set(function (cur) { return Object.assign({}, cur, { order: ids }); }); }
      })));
  }

  // ---------- 通知中心（胚胎）：表盘下滑拉出 ----------
  function Notifs() {
    var list = useStore(notifs), c = useStore(clock);
    var gone = useState({}), goneMap = gone[0];
    // 前台服务类通知（"正在记录健康"这种）不进通知列表，也不进实时活动——
    // 它们只是"服务在跑"的占位，留着会一直占着一行。
    var items = list.filter(function (n) {
      return n.pkg !== 'com.tessera.shell' && !n.media && !n.fgs
        && LIVE_EXCLUDE_PKG.indexOf(n.pkg) < 0 && (n.title || n.text) && !goneMap[n.key];
    })
      .sort(function (a, b) { return b.when - a.when; });
    var hasAccess = host.notifAccess();
    // 列表滚动交给 ListScroller：滚到底再往上拉，整层才跟着手指退出（过 35% 或快甩才真退出）；
    // 以前只要往上拉整层就走，列表长的时候根本滚不到下面。左右滑交给 SwipeRow（删除）和左边缘返回。
    var swipe = useSwipe({ edgeBack: function () { go('face'); } });
    var rootRef = useListScroller('notifs', {
      move: function (dy) { dragApply('up-notifs', dy); },
      end: function (dy, vy) { dragRelease(dy, vy); }
    }, 'bottom');
    function remove(n) {
      var m = Object.assign({}, goneMap); m[n.key] = true; gone[1](m);
      later(200, function () { host.notifDismiss(n.key); });
    }
    var content;
    if (!hasAccess) {
      content = h('div', { className: 'ts-list' }, h(T.ListItem, {
        title: '允许读取通知', subtitle: '开始屏和通知中心要用到', icon: 'bell', tone: 'accent',
        onClick: function () { host.openSettings('android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS'); }
      }));
    } else if (!items.length) {
      content = h('div', { className: 'sh-empty' }, h(T.Icon, { name: 'check', size: 22, className: 'sh-empty-icon' }), '没有新通知');
    } else {
      content = h(React.Fragment, null,
        h('div', { className: 'ts-list' }, items.map(function (n) {
          // 全部行都可以左滑删掉（常驻通知也能从列表里划掉；系统随后可能会重新投递）
          return h(SwipeRow, { key: n.key, onSwipe: function () { remove(n); } },
            h(T.ListItem, { title: n.title || n.app, subtitle: n.text, meta: TS.ago(n.when), icon: TS.notifIcon(n), tone: 'soft', onClick: function () { host.notifOpen(n.key); } }));
        })),
        items.some(function (n) { return n.clearable; })
          ? h('div', { className: 'sh-clear' }, h(T.Button, { block: true, onClick: function () { host.notifClear(); } }, '全部清除'))
          : null);
    }
    return h('div', Object.assign({ className: 'sh-notifs', ref: rootRef }, swipe),
      h(T.Watch, { title: '通知', time: c.time }, content));
  }

  /** 列表行左滑删除：跟手拖动，松手过 60px 就滑出去并删掉这一条。 */
  function SwipeRow(p) {
    var s = useState(0), dx = s[0], ref = useRef(null);
    var lv = useState(false), leaving = lv[0], setLeaving = lv[1];
    function finish() {
      if (!p.onSwipe) { s[1](0); return; }
      setLeaving(true);
      later(200, function () { p.onSwipe(); });
    }
    return h('div', {
      className: 'sh-swipe' + (leaving ? ' is-gone' : ''),
      style: !leaving && dx < 0 ? { transform: 'translateX(' + dx + 'px)', transition: 'none' } : null,
      onPointerDown: function (e) { if (!p.onSwipe) return; ref.current = { x: e.clientX, y: e.clientY }; },
      onPointerMove: function (e) {
        var r = ref.current; if (!r || !p.onSwipe || leaving) return;
        var d = e.clientX - r.x;
        if (Math.abs(e.clientY - r.y) < 16 && d < 0) s[1](Math.max(d, -140));
      },
      onPointerUp: function () { ref.current = null; if (leaving) return; if (dx < -60) finish(); else s[1](0); },
      onPointerCancel: function () { ref.current = null; if (!leaving) s[1](0); }
    }, p.children);
  }

  // ---------- 设置（胚胎） ----------
  function Settings() {
    var c = useStore(clock), l = useStore(look);
    var r = useState(0), refresh = function () { r[1](r[0] + 1); };
    var bri = Math.round(host.brightness() * 100 / 255);
    function toggle(get, set, fallbackAction) {
      return function (on) { var ok = set(on); if (ok === false && fallbackAction) host.openSettings(fallbackAction); later(400, refresh); };
    }
    function stepBri(d) { if (!host.setBrightness(Math.round(Math.max(5, Math.min(100, bri + d)) * 2.55))) host.openSettings('android.settings.action.MANAGE_WRITE_SETTINGS'); refresh(); }
    var swipe = useSwipe({ edgeBack: function () { go('start'); } });
    var rootRef = useListScroller('page');   // 惯性 + 两头橡皮筋回弹
    return h('div', Object.assign({ ref: rootRef }, swipe), h(T.Watch, { title: '设置', time: c.time },
      h('div', { className: 'sh-section' }, '连接'),
      h(T.Toggle, { label: 'WLAN', checked: host.wifi(), onChange: toggle(host.wifi, function (v) { host.setWifi(v); }) }),
      h(T.Toggle, { label: '蓝牙', checked: host.bluetooth(), onChange: toggle(host.bluetooth, function (v) { host.setBluetooth(v); }) }),
      h(T.Toggle, { label: '勿扰', checked: host.dnd(), onChange: toggle(host.dnd, function (v) { return host.setDnd(v); }, 'android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS') }),
      h('div', { className: 'sh-section' }, '显示'),
      h('div', { className: 'sh-row' },
        h('span', { className: 'sh-row-label' }, '亮度', h('span', { className: 'sh-row-sub' }, bri + '%')),
        h('div', { className: 'sh-pills' },
          h(T.Button, { onClick: function () { stepBri(-10); } }, '暗'),
          h(T.Button, { onClick: function () { stepBri(10); } }, '亮'))),
      h(T.Toggle, { label: '深色', checked: l.theme === 'dark', onChange: function (on) { look.set(Object.assign({}, l, { theme: on ? 'dark' : 'light' })); } }),
      h('div', { className: 'sh-row' }, h('span', { className: 'sh-row-label' }, '主题色',
        h('span', { className: 'sh-row-sub' }, (ACCENTS.filter(function (a) { return a.id === l.accent; })[0] || ACCENTS[0]).name))),
      h('div', { className: 'sh-swatches' }, ACCENTS.map(function (a) {
        return h('button', { key: a.id, type: 'button', 'aria-label': a.name, className: 'sh-swatch' + (a.id === l.accent ? ' is-on' : ''),
          style: { background: a[l.theme][0] }, onClick: function () { look.set(Object.assign({}, l, { accent: a.id })); } });
      })),
      h('div', { className: 'sh-section' }, '系统'),
      h('div', { className: 'ts-list' },
        h(T.ListItem, { title: '性能', subtitle: '省电 · 均衡 · 性能', icon: 'flame', tone: 'soft',
          onClick: function () { host.launch('com.android.settings', 'com.android.settings.Settings$PerformanceProfileSettingsActivity'); } }),
        h(T.ListItem, { title: '更多设置', subtitle: '系统设置', icon: 'settings', tone: 'soft', onClick: function () { host.openSettings(''); } }),
        h(T.ListItem, { title: 'Tessera', subtitle: '0.1 · 胚胎', icon: 'more', tone: 'soft' }))));
  }

  // ---------- 编辑磁贴（胚胎）：拿起后不移动直接松手 ----------
  var SIZES = [['s', '小'], ['m', '中'], ['w', '宽']], TONES = [['accent', '重点'], ['base', '普通'], ['soft', '安静']];
  function Edit(p) {
    var c = useStore(clock), l = useStore(layout), appList = useStore(apps), list = useStore(notifs), st = useStore(steps);
    var app = appList.filter(function (a) { return a.id === p.id; })[0];
    // hook 要在提前返回之前调用，两种分支也都挂在同一个根节点上
    var swipe = useSwipe({ edgeBack: function () { go('start'); } });
    var rootRef = useListScroller('page');   // 惯性 + 两头橡皮筋回弹
    var rootProps = Object.assign({ ref: rootRef }, swipe);
    if (!app) return h('div', rootProps, h(T.Watch, { title: '编辑', time: c.time }, h('div', { className: 'sh-empty' }, '应用已卸载')));
    var cfg = cfgFor(app, l);
    var tile = buildTiles([app], { order: [], cfg: (function () { var o = {}; o[app.id] = cfg; return o; })() }, list, st)[0];
    delete tile.back; tile.onClick = null;
    var accents = orderedApps(appList, l).filter(function (a) { return a.id !== app.id && cfgFor(a, l).tone === 'accent'; }).length;
    function setCfg(patch) {
      layout.set(function (cur) {
        var next = Object.assign({}, cur.cfg); next[app.id] = Object.assign({}, cfgFor(app, cur), patch);
        var order = cur.order.length ? cur.order : orderedApps(appList, cur).map(function (a) { return a.id; });
        return { order: order, cfg: next };
      });
    }
    return h('div', rootProps, h(T.Watch, { title: '编辑', time: c.time },
      h('div', { className: 'sh-preview' }, h(T.TileGrid, null, h(T.Tile, Object.assign({}, tile, { key: cfg.size + cfg.tone })))),
      h('div', { className: 'sh-section' }, '尺寸'),
      h('div', { className: 'sh-pills' }, SIZES.map(function (s) {
        return h(T.Button, { key: s[0], variant: cfg.size === s[0] ? 'accent' : 'default', onClick: function () { setCfg({ size: s[0] }); } }, s[1]);
      })),
      h('div', { className: 'sh-section' }, '明度'),
      h('div', { className: 'sh-pills' }, TONES.map(function (t) {
        return h(T.Button, { key: t[0], variant: cfg.tone === t[0] ? 'accent' : 'default', onClick: function () { setCfg({ tone: t[0] }); } }, t[1]);
      })),
      cfg.tone === 'accent' && accents >= 2 ? h('div', { className: 'sh-note' }, '每屏的重点最多 2 块') : null,
      h('div', { className: 'sh-clear' }, h(T.Button, { variant: 'accent', block: true, onClick: function () { go('start'); } }, '完成'))));
  }

  // ---------- 横幅层 ----------
  function ToastLayer() {
    var t = useStore(toast), swipeRef = useRef(null), screen = useStore(nav).screen;
    // 表盘上的横幅：磨砂由原生表盘画（backdrop-filter 看不到 WebView 下面的原生层），
    // 横幅在的时候逐帧把它的位置和圆角报过去（进出场动画都跟得上），30 帧不变就歇着；不在表盘上时报 0。
    useEffect(function () {
      var alive = true, last = null, still = 0;   // null：第一帧一定上报（横幅刚消失时要把磨砂撤掉）
      (function tick() {
        if (!alive) return;
        var el = t && document.querySelector('.sh-toast-host .ts-toast');
        var r = el && document.documentElement.classList.contains('sh-on-face') ? el.getBoundingClientRect() : null;
        var key = r ? [r.left, r.top, r.right, r.bottom].map(function (v) { return v.toFixed(1); }).join(',') : '';
        if (key !== last) {
          last = key; still = 0;
          if (r) host.faceToastBlur(r.left, r.top, r.right, r.bottom, parseFloat(getComputedStyle(el).borderTopLeftRadius) || 0);
          else host.faceToastBlur(0, 0, 0, 0, 0);
        } else still++;
        if (t && still < 30) requestAnimationFrame(tick);
      })();
      return function () { alive = false; };
    }, [t ? t.id : 0, t ? t.leaving : false, screen]);
    if (!t) return null;
    var n = t.n;
    var multi = t.count > 1;
    var actions = multi ? [] : (n.actions || []).slice(0, 2).map(function (a, i) {
      return { label: a.label, onClick: function (e) { if (e) e.stopPropagation(); host.notifAction(n.key, i); dismissToast(); } };
    });
    return h('div', { className: 'sh-layer sh-toast-host', style: { zIndex: 9, pointerEvents: 'none' } },
      h(T.Watch, { bare: true },
        h('div', { className: 'ts-toast-layer', style: { pointerEvents: 'auto' },
          onPointerDown: function (e) { swipeRef.current = e.clientY; },
          onPointerUp: function (e) { var y0 = swipeRef.current; swipeRef.current = null; if (y0 != null && e.clientY - y0 < -16) dismissToast(); },
          onClick: function (e) { if (e.target.closest('button')) return; if (!multi) host.notifOpen(n.key); else go('notifs'); dismissToast(); } },
          h(T.Toast, {
            key: t.id,
            app: n.app, icon: TS.notifIcon(n), time: TS.ago(n.when),
            title: multi ? t.count + ' 条新消息' : (n.title || n.app),
            body: multi ? (n.title ? n.title + '：' + n.text : n.text) : n.text,
            actions: actions,
            leaving: t.leaving,
            onLeft: function () { if (toast.get() && toast.get().leaving) toast.set(null); }
          }))));
  }

  // ---------- 根 ----------
  function Layer(p) {
    var anim = p.anim ? ' sh-' + p.anim : '';
    var cls = 'sh-layer ' + p.className + (p.current ? ' is-current' : '') + anim + (p.live ? ' sh-live' : '');
    return h('div', { className: cls, style: Object.assign({ zIndex: p.z }, p.style || {}), hidden: !p.visible }, p.children);
  }

  // 拖动时 App 每动一下都会重渲染；表盘和开始屏是重组件（开始屏每次渲染还要重算整张磁贴表），
  // 用 memo 包一层，避免被拖动的状态带着一起重画。
  var FaceMemo = React.memo(Face);
  var StartMemo = React.memo(Start);

  function App() {
    var n = useStore(nav);
    var dg = useStore(dragState);
    var s = n.screen;
    // 位移完全由 CSS 变量（--dy / --p）驱动，App 不再因为拖动而重渲染
    var dragging = false;
    var fromFace = !!dg && dg.from === 'face';
    var fromStart = !!dg && dg.from === 'start';
    var fromNotifsDown = !!dg && dg.from === 'down-notifs';
    var fromNotifsUp = !!dg && dg.from === 'up-notifs';
    var startVisible = s === 'start' || s === 'page' || (s === 'face' && n.anim === 'leave-up') || fromFace || fromStart;
    var startStyle = null, faceStyle = null;
    var notifsVisible = s === 'notifs' || fromNotifsDown || fromNotifsUp;
    // 从表盘下拉时通知中心这层是拖动开始后才挂载的，dragApply 第一帧找不到它；
    // 挂载时先放在屏幕上方，免得第一帧整屏闪一下（之后的位移由 dragApply 直接写行内样式）
    var notifsStyle = fromNotifsDown ? { transform: 'translateY(-100%)' } : null;

    // 原生表盘跟着导航下沉/回位（拖动中由 dragApply 逐帧控制，这里不插手）；
    // 被开始屏或通知中心完全盖住 350ms 后停止走秒，露出来前立刻恢复。
    var onFace = s === 'face', faceVisibleNow = onFace || !!dg || n.anim === 'leave-up' || n.anim === 'leave-down';
    useEffect(function () {
      document.documentElement.classList.toggle('sh-on-face', onFace);
      if (!dg) host.faceSink(onFace ? 0 : 1, 300);
    }, [onFace, !!dg]);
    // 通知中心的磨砂由原生表盘画：通知中心这层在的时候逐帧把它的下边缘报过去（拖动、进出场动画都跟得上），
    // 位置连续 30 帧不变就歇着，导航/拖动状态一变再重新开始。
    useEffect(function () {
      if (!notifsVisible) return;
      var alive = true, last = -1, still = 0;
      (function tick() {
        if (!alive) return;
        var el = document.querySelector('.sh-notifs-layer'), f = 0;
        if (el) f = Math.max(0, Math.min(1, el.getBoundingClientRect().bottom / (document.documentElement.clientHeight || 200)));
        f = Math.round(f * 1000) / 1000;
        if (f !== last) { last = f; still = 0; host.faceBlur(f); } else still++;
        if (still < 30 || document.documentElement.classList.contains('sh-dragging')) requestAnimationFrame(tick);
      })();
      return function () { alive = false; };
    }, [notifsVisible, s, n.anim, !!dg]);
    useEffect(function () { if (!notifsVisible) host.faceBlur(0); }, [notifsVisible]);
    useEffect(function () {
      if (faceVisibleNow) { host.faceShown(true); return; }
      var t = setTimeout(function () { host.faceShown(false); }, 350);
      return function () { clearTimeout(t); };
    }, [faceVisibleNow]);

    return h(React.Fragment, null,
      h(Layer, { className: 'sh-face-layer' + (s === 'face' ? '' : ' is-sunk'), z: 1, visible: true, current: s === 'face',
        style: faceStyle, live: dragging }, h(FaceMemo)),
      h(Layer, { className: 'sh-start-layer', z: 2, visible: startVisible, current: s === 'start', style: startStyle, live: dragging,
        anim: s === 'start' && (n.anim === 'enter-up' || n.anim === 'leave-up') ? n.anim : null }, h(StartMemo)),
      notifsVisible ? h(Layer, { className: 'sh-notifs-layer', z: 3, visible: true, current: s === 'notifs', style: notifsStyle, live: dragging,
        anim: s === 'notifs' ? n.anim : null }, h(Notifs)) : null,
      s === 'page' && n.page ? h(Layer, { className: 'sh-page-layer', z: 4, visible: true, current: true, anim: n.anim },
        n.page.kind === 'settings' ? h(Settings) : h(Edit, { id: n.page.id })) : null,
      h(ToastLayer));
  }

  // 原生网格拖拽排序后回传新顺序：写回 layout，和 WebView 版共用同一份顺序
  TS.on('gridOrder', function (ids) {
    if (Array.isArray(ids) && ids.length) {
      layout.set(Object.assign({}, layout.get(), { order: ids }));
    }
  });

  // 开始屏渲染器：默认 WebView 版；打开 host.startNative() 时开始屏交给原生网格
  var NATIVE_START = false;
  try { NATIVE_START = !!(host.startNative && host.startNative()); } catch (e) {}
  if (NATIVE_START) document.documentElement.classList.add('native-start');
  // 原生开始屏跟着导航走：进场从下面推上来（300ms 缓出）、退场推下去（240ms 加速），
  // 从 Tessera 自己的页面回来时按列翻回来；拖动中由 dragApply 逐帧摆位置，这里不插手。
  var lastNav = nav.get();
  nav.sub(function (n) {
    if (!NATIVE_START) { lastNav = n; return; }
    var H = document.documentElement.clientHeight || 200;
    try {
      if (n.screen === 'start' && n.anim === 'enter-up') { host.gridPose(H, 0, 0, false); host.gridPose(0, EASE_OUT, 0, false); }
      else if (n.screen === 'start' && n.anim === 'leave-up') host.gridPose(H, EASE_IN, 1, true);
      else if (n.screen === 'start' && n.turn === 'in' && lastNav.screen === 'page') host.gridTurnIn();
      else if (n.screen === 'start') { if (!dragFrom) host.showGrid(true); }
      else host.showGrid(false);
    } catch (e) {}
    lastNav = n;
  });
  // 原生网格里点了设置 / 长按不动松手：它自己已经按列翻完了，直接把页面翻进来
  TS.on('gridOpen', function (d) {
    var page = d && d.kind === 'settings' ? { kind: 'settings' } : { kind: 'edit', id: d && d.id };
    nav.set({ screen: 'page', anim: 'page-in', page: page, turn: 'out' });
  });

  // 双指手势兜底：挂在 document 上（不依赖表盘那层的手势区），只在表盘页生效。
  // 两指一起按下、600ms 内抬起 → 切换"可翻"模式：进入时时间渐隐，退出时时间回来且卡片全部复位。
  (function () {
    var twoAt = 0, armed = false;
    document.addEventListener('touchstart', function (e) {
      if (e.touches.length === 2) { armed = true; twoAt = Date.now(); }
    }, { passive: true, capture: true });
    document.addEventListener('touchend', function (e) {
      if (!armed || e.touches.length > 0) return;
      var quick = Date.now() - twoAt < 600;
      armed = false;
      if (!quick) return;
      if (nav.get().screen !== 'face') return;
      host.faceToggleMode();
    }, { passive: true, capture: true });
    document.addEventListener('touchcancel', function () { armed = false; }, { passive: true, capture: true });
  })();

  ReactDOM.createRoot(document.getElementById('root')).render(h(App));
  host.log('Tessera shell ready');
})();
