/* 其他应用上方的一层：右上角的状态时间（与 Watch 组件 ts-status-time 同位置同字号），以及通知横幅。
   平时不接收触摸；横幅出现时窗口缩到横幅高度并接收触摸，收起后恢复穿透。 */
(function () {
  'use strict';
  var T = window.Tessera, h = React.createElement, host = TS.host;
  var useState = React.useState, useEffect = React.useEffect, useRef = React.useRef;

  function Status() {
    var s = useState(TS.timeText());
    useEffect(function () {
      var stop = TS.everyMinute(function () { s[1](TS.timeText()); });
      var off = TS.on('time', function () { s[1](TS.timeText()); });
      return function () { stop(); off(); };
    }, []);
    return h('div', { className: 'ov-status' }, h('span', { className: 'ts-status-time' }, s[0]));
  }

  function Banner() {
    var st = useState(null), t = st[0], set = st[1];
    var timer = useRef(null), boxRef = useRef(null), swipe = useRef(null);
    function leave() { clearTimeout(timer.current); set(function (cur) { return cur ? Object.assign({}, cur, { leaving: true }) : cur; }); }
    useEffect(function () {
      var off1 = TS.on('notify', function (n) {
        set(function (cur) { return { n: n, count: cur && !cur.leaving ? cur.count + 1 : 1, leaving: false, id: Date.now() }; });
        clearTimeout(timer.current);
        timer.current = setTimeout(leave, 6000);
        host.vibrate(30);
      });
      var off2 = TS.on('shell', function (d) { if (d && d.foreground) { clearTimeout(timer.current); set(null); } });
      return function () { off1(); off2(); };
    }, []);
    // 横幅出现后量高度：窗口缩到横幅（加下沿阴影）并接收触摸
    useEffect(function () {
      if (t && !t.leaving && boxRef.current) {
        var hgt = Math.ceil(boxRef.current.getBoundingClientRect().height) + 8;
        host.overlayTouch(true, hgt);
      }
    }, [t && t.id, t && t.leaving]);
    if (!t) return null;
    var n = t.n, multi = t.count > 1;
    var actions = multi ? [] : (n.actions || []).slice(0, 2).map(function (a, i) {
      return { label: a.label, onClick: function (e) { if (e) e.stopPropagation(); host.notifAction(n.key, i); leave(); } };
    });
    return h('div', { className: 'ov-toast', ref: boxRef,
      onPointerDown: function (e) { swipe.current = e.clientY; },
      onPointerUp: function (e) { var y0 = swipe.current; swipe.current = null; if (y0 != null && e.clientY - y0 < -16) leave(); },
      onClick: function (e) { if (e.target.closest('button')) return; host.notifOpen(n.key); leave(); } },
      h(T.Toast, {
        key: t.id,
        app: n.app, icon: TS.notifIcon(n), time: TS.ago(n.when),
        title: multi ? t.count + ' 条新消息' : (n.title || n.app),
        body: multi ? (n.title ? n.title + '：' + n.text : n.text) : n.text,
        actions: actions, leaving: t.leaving,
        onLeft: function () { set(null); host.overlayTouch(false, 0); }
      }));
  }

  function App() {
    return h(React.Fragment, null, h(Status), h(Banner));
  }

  ReactDOM.createRoot(document.getElementById('root')).render(h(App));
})();
