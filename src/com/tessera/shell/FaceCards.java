package com.tessera.shell;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 数据表盘的卡片区（设计稿"C 数据 · 拖入卡片与即时活动"FaceComplications.dc.html 的 order / lift / drop / 即时活动）：
 * 管卡片顺序、拿起来跟着手指的那张、虚线落点、即时活动叠在上面、B ↔ C 两种布局之间的变形，每一项都按设计稿的
 * transition 时长和曲线走；每帧交给 FaceStyles.drawData 一个 DataFrame。坐标都是 200×200 设计像素。
 */
final class FaceCards {
    interface Host {
        /** 卡片区要重画（动画中每帧都会叫）。 */
        void invalidate();
    }

    private static final RectF REST = new RectF(0f, 102f, 200f, 200f);       // 不在表盘上的卡停在这里（设计稿的默认 box）
    private static final int DRAG = 0, DROP = 1, CANCEL = 2;

    /** 一张卡在表盘上的位置（left / top / width / height 300ms、底色 300ms ease）。 */
    private static final class Slot {
        FaceStyles.Card card;
        final Tween x = new Tween(REST.left), y = new Tween(REST.top), w = new Tween(REST.width()), h = new Tween(REST.height());
        final Tween soft = new Tween(0);             // 0 = tile-base，1 = tile-soft（第二张）
        boolean shown, wide, left;
    }

    /** 拿起来的那张。 */
    private static final class Drag {
        FaceStyles.Card card;
        int phase;
        float x, y, ox, w, h, offX, offY;
        boolean hover;
        float hoverX;
        RectF target;
        int slot;
        List<FaceStyles.Card> result;
        boolean wide, left;
        boolean fromApps;              // 从开始屏拿过来的磁贴：落位前一直是磁贴的样子
        Bitmap tileLook;
        String tileSize = "s";
    }

    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<FaceStyles.Card> comps = new ArrayList<>();
    private List<FaceStyles.Card> pending;                     // 拖动期间数据刷新进来的卡片内容
    private final HashMap<String, Slot> slots = new HashMap<>();
    private final Tween phX = new Tween(REST.left), phY = new Tween(REST.top), phW = new Tween(REST.width()), phH = new Tween(REST.height()), phO = new Tween(0);
    private final Tween stackT = new Tween(0), stackO = new Tween(0);
    private FaceStyles.Live live;
    private boolean liveLeaving, liveDragging;
    private float liveDragDx;
    private final Tween liveX = new Tween(0), liveA = new Tween(0);
    // B ↔ C：数字三块的位置 / 缩放 / 字距 380ms，分钟颜色 300ms ease，B 独有元素 200ms ease，
    // C 的日期行和冒号（去 C：延迟 180ms 再 200ms 淡入；回 B：120ms 淡出），三块数字和真实一行在 380ms 时交接
    private final Tween morph = new Tween(1), ink = new Tween(1), bA = new Tween(0), cA = new Tween(1), swap = new Tween(1);
    private boolean isC = true, inited;
    private Drag drag;
    private final Tween gX = new Tween(0), gY = new Tween(0), gW = new Tween(0), gH = new Tween(0);
    private final Tween gR = new Tween(0), gS = new Tween(1), gO = new Tween(0);
    private final Tween gBg = new Tween(1), gTile = new Tween(1);     // gBg：底色从 gBgFrom 过渡到 gBgTo；gTile：0 = 磁贴样子，1 = 卡片
    private int gBgFrom, gBgTo;
    private boolean gBgSlot;                                          // true = 目标底色是格子的（tile-base / tile-soft，按主题取）
    private int gSlot;
    private final Runnable commit = this::commit, clearDrag = this::clearDrag, liveGone = this::liveGone;

    FaceCards(Host host) { this.host = host; }

    // ---------- 数据 ----------

    /** 表盘上该有的卡片（按顺序，最多两张）。拖动中只更新内容，松手落定后再换。 */
    void setCards(List<FaceStyles.Card> list) {
        if (drag != null) { pending = new ArrayList<>(list); refreshContent(list); host.invalidate(); return; }
        comps.clear();
        comps.addAll(list);
        refreshContent(list);
        sync(!inited);
        inited = true;
    }

    /** 即时活动：新出现的淡入；没了的淡出（被划走的已经在走了）。 */
    void setLive(FaceStyles.Live l) {
        long now = SystemClock.uptimeMillis();
        if (l == null) {
            if (live != null && !liveLeaving) { liveLeaving = true; handler.removeCallbacks(liveGone); handler.postDelayed(liveGone, 300); }
        } else if (live == null || liveLeaving || !l.key.equals(live.key)) {
            handler.removeCallbacks(liveGone);
            live = l; liveLeaving = false; liveDragging = false;
            liveX.jump(0f);
            if (!inited) liveA.jump(1f);
            else liveA.jump(0f);
        } else {
            live = l;
        }
        sync(!inited);
    }

    private void liveGone() { if (liveLeaving) { live = null; liveLeaving = false; sync(false); } }

    private void refreshContent(List<FaceStyles.Card> list) {
        for (FaceStyles.Card c : list) {
            Slot s = slots.get(c.id);
            if (s != null) s.card = c;
            if (drag != null && drag.card.id.equals(c.id)) drag.card = c;
        }
    }

    // ---------- 设计稿 order() / slots() ----------

    private List<FaceStyles.Card> order() {
        if (drag == null) return comps;
        if (drag.phase == DROP) return drag.result;
        List<FaceStyles.Card> base = without(comps, drag.card.id);
        if (drag.hover) return insertAt(base, drag.card, drag.hoverX);
        return base;                                       // 拿起最后一张：先预览成 B
    }

    private static List<FaceStyles.Card> without(List<FaceStyles.Card> l, String id) {
        List<FaceStyles.Card> out = new ArrayList<>();
        for (FaceStyles.Card c : l) if (!c.id.equals(id)) out.add(c);
        return out;
    }

    private static List<FaceStyles.Card> insertAt(List<FaceStyles.Card> base, FaceStyles.Card card, float x) {
        List<FaceStyles.Card> a = new ArrayList<>(base);
        if (a.size() >= 2) { a.set(x < 100f ? 0 : 1, card); return a; }
        if (x < 100f) a.add(0, card); else a.add(card);
        return a;
    }

    private static int indexOf(List<FaceStyles.Card> l, String id) {
        for (int i = 0; i < l.size(); i++) if (l.get(i).id.equals(id)) return i;
        return -1;
    }

    /** 状态变了：把每一样东西的目标值重新算一遍（变了的才会起过渡）。 */
    private void sync(boolean instant) {
        long now = SystemClock.uptimeMillis();
        List<FaceStyles.Card> order = order();
        RectF[] box = FaceStyles.slots(order.size());
        for (FaceStyles.Card c : order) if (!slots.containsKey(c.id)) { Slot s = new Slot(); s.card = c; slots.put(c.id, s); }
        for (FaceStyles.Card c : comps) if (!slots.containsKey(c.id)) { Slot s = new Slot(); s.card = c; slots.put(c.id, s); }
        for (java.util.Map.Entry<String, Slot> e : slots.entrySet()) {
            Slot s = e.getValue();
            int i = indexOf(order, e.getKey());
            RectF b = i >= 0 ? box[i] : REST;
            boolean dragged = drag != null && drag.card.id.equals(e.getKey()) && drag.phase != CANCEL;
            if (drag != null && drag.phase == DROP && drag.card.id.equals(e.getKey())) b = drag.target;
            boolean shown = i >= 0 && !dragged;
            if (shown && !instant) {
                s.x.set(b.left, now, 300, Curves.FC_ORDER); s.y.set(b.top, now, 300, Curves.FC_ORDER);
                s.w.set(b.width(), now, 300, Curves.FC_ORDER); s.h.set(b.height(), now, 300, Curves.FC_ORDER);
                s.soft.set(i == 1 ? 1f : 0f, now, 300, Curves.FC_FADE);
            } else {                                       // 没显示的卡：opacity 0ms，位置直接到位（和拿起的那张同一帧交接）
                s.x.jump(b.left); s.y.jump(b.top); s.w.jump(b.width()); s.h.jump(b.height());
                s.soft.jump(i == 1 ? 1f : 0f);
            }
            s.shown = shown;
            // 内容排版按落定后的格子算（设计稿 wide = box.w > 150），不在动画中途跳
            s.wide = b.width() > 150f;
            s.left = !s.wide && b.left < 100f;
        }
        // 虚线落点
        RectF ph = REST;
        float phA = 0f;
        if (drag != null && drag.phase == DRAG) { int i = indexOf(order, drag.card.id); if (i >= 0) { ph = box[i]; phA = 1f; } }
        long dp = instant ? 0 : 300, dpo = instant ? 0 : 160;
        phX.set(ph.left, now, dp, Curves.FC_SLOT); phY.set(ph.top, now, dp, Curves.FC_SLOT); phW.set(ph.width(), now, dp, Curves.FC_SLOT); phH.set(ph.height(), now, dp, Curves.FC_SLOT);
        phO.set(phA, now, dpo, Curves.FC_FADE);
        // B / C
        boolean c = !order.isEmpty() || (live != null && !liveLeaving);
        if (instant) {
            isC = c;
            morph.jump(c ? 1f : 0f); ink.jump(c ? 1f : 0f); bA.jump(c ? 0f : 1f); cA.jump(c ? 1f : 0f); swap.jump(c ? 1f : 0f);
        } else if (c != isC) {
            isC = c;
            morph.set(c ? 1f : 0f, now, 380, Curves.FC_MORPH);
            ink.set(c ? 1f : 0f, now, 300, Curves.FC_FADE);
            bA.set(c ? 0f : 1f, now, 200, Curves.FC_FADE);
            if (c) { cA.set(1f, now, 200, Curves.FC_FADE, 180); swap.set(1f, now, 0, null, 380); }
            else { cA.set(0f, now, 120, Curves.FC_FADE); swap.jump(0f); }
        }
        // 即时活动压在卡片上：卡片往后退（translateY(-6) scale(.94)，露出 6px），320ms
        boolean st = live != null && !liveLeaving && !order.isEmpty();
        stackT.set(st ? 1f : 0f, now, instant ? 0 : 320, Curves.FC_STACK);
        stackO.set(st ? 1f : 0f, now, instant ? 0 : 320, Curves.FC_FADE);
        if (live != null) {
            liveA.set(liveLeaving ? 0f : 1f, now, instant ? 0 : 300, Curves.FC_FADE);
            if (liveDragging) liveX.jump(liveDragDx);
            else liveX.set(liveLeaving ? liveX.target() : 0f, now, instant ? 0 : 300, Curves.FC_LIVE);
        }
        host.invalidate();
    }

    // ---------- 触摸（NativeShell 的表盘手势转过来，设计坐标） ----------

    /** 手指下的那张卡（有即时活动时卡片接不到点击）。 */
    String cardAt(float x, float y) {
        if (drag != null || (live != null && !liveLeaving)) return null;
        RectF[] box = FaceStyles.slots(comps.size());
        for (int i = 0; i < comps.size(); i++) if (box[i].contains(x, y)) return comps.get(i).id;
        return null;
    }

    boolean liveAt(float x, float y) { return live != null && !liveLeaving && y >= 102f; }

    String liveKey() { return live != null && !liveLeaving ? live.key : null; }

    boolean dragging() { return drag != null && drag.phase == DRAG; }

    /** 长按 400ms：拿起这张，变圆角、绕手指放大一点。 */
    void lift(String id, float px, float py) {
        int i = indexOf(comps, id);
        if (i < 0 || drag != null) return;
        long now = SystemClock.uptimeMillis();
        RectF b = FaceStyles.slots(comps.size())[i];
        Drag d = new Drag();
        d.card = comps.get(i); d.phase = DRAG;
        d.x = b.left; d.y = b.top; d.ox = b.left; d.w = b.width(); d.h = b.height();
        d.offX = px - b.left; d.offY = py - b.top;
        d.wide = d.w > 150f; d.left = !d.wide && d.ox < 100f;
        drag = d;
        gX.jump(d.x); gY.jump(d.y); gW.jump(d.w); gH.jump(d.h);
        gR.jump(0f); gR.set(22f, now, 200, Curves.FC_LIFT);
        gS.jump(1f); gS.set(1.06f, now, 200, Curves.FC_LIFT);
        gO.jump(1f);
        gBgSlot = true; gSlot = i; gBg.jump(1f); gTile.jump(1f);
        handler.removeCallbacks(commit); handler.removeCallbacks(clearDrag);
        sync(false);
    }

    /**
     * 从开始屏拿过来的磁贴（贴着上沿停了一下交过来的）：接着跟手指，样子还是那块磁贴；
     * 进卡片区让位，松手落进格子时 200ms 换成卡片、底色 300ms 变成格子色；放在别处就缩小淡出，表盘不变。
     */
    void liftFromApps(FaceStyles.Card card, Bitmap look, String size, int tileBg, float x, float y, float w, float h,
                      float fx, float fy, float radius, float scale) {
        if (drag != null) return;
        long now = SystemClock.uptimeMillis();
        Drag d = new Drag();
        d.card = card; d.phase = DRAG; d.fromApps = true; d.tileLook = look; d.tileSize = size;
        d.x = x; d.y = y; d.ox = x; d.w = w; d.h = h;
        d.offX = fx - x; d.offY = fy - y;
        d.wide = w > 150f; d.left = !d.wide && x < 100f;
        drag = d;
        gX.jump(x); gY.jump(y); gW.jump(w); gH.jump(h);
        gR.jump(radius);
        gS.jump(scale); gS.set(1.06f, now, 200, Curves.FC_LIFT);
        gO.jump(1f);
        gBgSlot = false; gBgFrom = tileBg; gBgTo = tileBg; gBg.jump(1f);
        gTile.jump(0f);
        handler.removeCallbacks(commit); handler.removeCallbacks(clearDrag);
        sync(false);
    }

    /** 跟着手指；手指进到卡片区（y > 92）时给它让出位置。 */
    void dragTo(float px, float py) {
        Drag d = drag;
        if (d == null || d.phase != DRAG) return;
        d.x = px - d.offX; d.y = py - d.offY;
        gX.jump(d.x); gY.jump(d.y);
        boolean over = py > 92f;
        if (over != d.hover || (over && (px < 100f) != (d.hoverX < 100f))) { d.hover = over; d.hoverX = px; sync(false); }
        else { d.hoverX = px; host.invalidate(); }
    }

    /**
     * 松手：在卡片区上就落进让出来的格子（300ms 移过去、变回方角、换成卡片），否则放掉（缩小淡出，从表盘上拿掉）。
     * 返回新的卡片 id 顺序（存起来用）。
     */
    List<String> drop() {
        Drag d = drag;
        if (d == null || d.phase != DRAG) return null;
        long now = SystemClock.uptimeMillis();
        List<String> ids = new ArrayList<>();
        if (d.hover) {
            List<FaceStyles.Card> result = insertAt(without(comps, d.card.id), d.card, d.hoverX);
            int slot = indexOf(result, d.card.id);
            RectF t = FaceStyles.slots(result.size())[slot];
            d.phase = DROP; d.result = result; d.target = t; d.slot = slot; d.hover = false;
            d.wide = t.width() > 150f; d.left = !d.wide && t.left < 100f;
            gX.set(t.left, now, 300, Curves.FC_DROP); gY.set(t.top, now, 300, Curves.FC_DROP); gW.set(t.width(), now, 300, Curves.FC_DROP); gH.set(t.height(), now, 300, Curves.FC_DROP);
            gR.set(0f, now, 240, Curves.FC_DROP); gS.set(1f, now, 240, Curves.FC_DROP);
            gBgFrom = lastGhostBg; gBgSlot = true; gSlot = slot; gBg.jump(0f); gBg.set(1f, now, 300, Curves.FC_FADE);
            gTile.set(1f, now, 200, Curves.FC_FADE);
            for (FaceStyles.Card c : result) ids.add(c.id);
            handler.postDelayed(commit, 320);
        } else {
            d.phase = CANCEL;
            gS.set(0.6f, now, 200, Curves.FC_TOSS); gO.set(0f, now, 200, Curves.FC_TOSS);
            handler.postDelayed(clearDrag, 220);
            if (d.fromApps) { sync(false); return null; }             // 从开始屏拿来的放在别处：表盘不变
            List<FaceStyles.Card> rest = without(comps, d.card.id);
            comps.clear(); comps.addAll(rest);
            for (FaceStyles.Card c : rest) ids.add(c.id);
        }
        sync(false);
        return ids;
    }

    private void commit() {
        Drag d = drag;
        if (d == null || d.phase != DROP) return;
        comps.clear();
        comps.addAll(d.result);
        drag = null;
        if (pending != null) { refreshContent(pending); pending = null; }
        sync(false);
    }

    private void clearDrag() {
        if (drag == null || drag.phase != CANCEL) return;
        drag = null;
        if (pending != null) { refreshContent(pending); pending = null; }
        sync(false);
    }

    /** 即时活动跟着手指横着走。 */
    void liveMove(float dx) {
        if (live == null || liveLeaving) return;
        liveDragging = true; liveDragDx = dx;
        sync(false);
    }

    /** 松手：横移超过 50 就划走（300ms 滑出 ±240 并淡出），否则弹回。返回是否划走了。 */
    boolean liveUp() {
        if (live == null || !liveDragging) return false;
        liveDragging = false;
        long now = SystemClock.uptimeMillis();
        if (Math.abs(liveDragDx) > 50f) {
            liveLeaving = true;
            liveX.set(liveDragDx > 0 ? 240f : -240f, now, 300, Curves.FC_LIVE);
            handler.removeCallbacks(liveGone); handler.postDelayed(liveGone, 300);
            sync(false);
            return true;
        }
        sync(false);
        return false;
    }

    // ---------- 每帧 ----------

    boolean running(long now) {
        if (drag != null || liveDragging) return true;
        if (gBg.running(now) || gTile.running(now)) return true;
        for (Slot s : slots.values()) if (s.x.running(now) || s.y.running(now) || s.w.running(now) || s.h.running(now) || s.soft.running(now)) return true;
        return phO.running(now) || phX.running(now) || phY.running(now) || phW.running(now) || phH.running(now)
                || stackT.running(now) || stackO.running(now) || liveX.running(now) || liveA.running(now)
                || morph.running(now) || ink.running(now) || bA.running(now) || cA.running(now) || swap.running(now);
    }

    FaceStyles.DataFrame frame(long now, Theme th) {
        FaceStyles.DataFrame f = new FaceStyles.DataFrame();
        f.morph = morph.get(now);
        f.minuteInk = ink.get(now);
        f.bAlpha = bA.get(now);
        f.cAlpha = cA.get(now);
        f.pieces = swap.get(now) < 0.5f;
        for (FaceStyles.Card c : comps) add(f, c.id, now, th);
        if (drag != null && drag.phase == DROP) for (FaceStyles.Card c : drag.result) if (indexOf(comps, c.id) < 0) add(f, c.id, now, th);
        f.placeholder.set(phX.get(now), phY.get(now), phX.get(now) + phW.get(now), phY.get(now) + phH.get(now));
        f.phAlpha = phO.get(now);
        float st = stackT.get(now);
        f.stackDy = -6f * st;
        f.stackScale = 1f - 0.06f * st;
        f.stackAlpha = 1f - 0.28f * stackO.get(now);
        if (live != null) { f.live = live; f.liveDx = liveX.get(now); f.liveAlpha = liveA.get(now); }
        return f;
    }

    private int lastGhostBg;

    /** 拿着的那张（没有就 null）：单独画在最上面一层（从开始屏拿来的时候表盘本身还在往下落，不能跟着表盘走）。 */
    FaceStyles.Ghost ghost(long now, Theme th) {
        Drag d = drag;
        if (d == null) return null;
        FaceStyles.Ghost g = new FaceStyles.Ghost();
        g.card = d.card;
        g.x = gX.get(now); g.y = gY.get(now); g.w = gW.get(now); g.h = gH.get(now);
        g.radius = gR.get(now); g.scale = gS.get(now); g.alpha = gO.get(now);
        if (d.phase == DRAG) { g.ox = d.offX; g.oy = d.offY; } else { g.ox = g.w / 2f; g.oy = g.h / 2f; }   // transform-origin
        int to = gBgSlot ? (gSlot == 1 ? th.tileSoft : th.tileBase) : gBgTo;
        g.bg = FaceStyles.lerpColor(gBgFrom == 0 ? to : gBgFrom, to, gBg.get(now));
        lastGhostBg = g.bg;
        g.fg = th.onTile;
        g.tileMix = gTile.get(now);
        g.asTile = d.tileLook != null;
        g.tileLook = d.tileLook;
        g.tileSize = d.tileSize;
        g.wide = d.wide; g.left = d.left;
        return g;
    }

    private void add(FaceStyles.DataFrame f, String id, long now, Theme th) {
        Slot s = slots.get(id);
        if (s == null || !s.shown) return;
        FaceStyles.CardDraw d = new FaceStyles.CardDraw();
        d.card = s.card;
        d.x = s.x.get(now); d.y = s.y.get(now); d.w = s.w.get(now); d.h = s.h.get(now);
        d.bg = FaceStyles.lerpColor(th.tileBase, th.tileSoft, s.soft.get(now));
        d.wide = s.wide; d.left = s.left;
        f.cards.add(d);
    }
}
