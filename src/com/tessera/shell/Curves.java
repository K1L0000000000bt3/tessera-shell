package com.tessera.shell;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * 所有动画曲线，一个动画一条，三次贝塞尔 (x1, y1, x2, y2)，和 CSS cubic-bezier 一个意思。
 * 默认值按 Windows Phone（用户 2026-10-25："按照wp改掉所有动画"；数取自 Windows Phone Toolkit 源码）：
 *   进来 / 滑入 / 滑出 = ExponentialEase Exponent 6 EaseOut ≈ (0.15, 0.83, 0.28, 1)；转门翻走 = EaseIn ≈ (0.7, 0, 0.85, 0.14)；
 *   倾斜回正匀速；活动磁贴翻面 CubicEase EaseOut。拟合误差都在 1% 左右。
 * 用户在调曲线网页上调好，存进网页的数据库；再推到手表上实时生效（不用重新编译）：
 *   am start -n com.tessera.shell/.MainActivity --es curves 'nc.shift=0.3,0,0.2,1.12;toast.drop=0.1,0.9,0.2,1'
 *   --es curves reset          全部恢复默认
 *   --es curves dump           只往 logcat 打印现在的值（tag Tessera/Curves）
 * 错开的一组动画（转门 / 翻页）在数后面加 ",w"（例：grid.tsIn=0.4,0,0.2,1,w）= 曲线管整段动画，见 Curve.vt；
 * 再加 ",s15" = 每块之间错开 15ms（不写就用代码里的），见 Curve.stagger。
 * 推过来的值存在 SharedPreferences 里，进程重启也还在；定稿后把数改进下面的默认值里。
 */
final class Curves {
    private Curves() {}

    static final class Curve implements Interpolator {
        final String id;
        final float[] def;
        private volatile float[] cur;                 // x1, y1, x2, y2, whole（0 / 1）, stagger（ms，-1 = 代码里的）
        private volatile PathInterpolator path;
        private volatile boolean whole;

        private volatile long stg = -1;

        Curve(String id, float[] def) { this.id = id; this.def = new float[] {def[0], def[1], def[2], def[3], 0f, -1f}; set(this.def); }

        void set(float[] v) {
            float[] c = new float[] {v[0], v[1], v[2], v[3], v.length > 4 ? v[4] : 0f, v.length > 5 ? v[5] : -1f};
            path = new PathInterpolator(c[0], c[1], c[2], c[3]);
            whole = c[4] != 0f;
            stg = Math.round(c[5]);
            cur = c;
        }

        /** 错开的一组动画里每块之间晚多少 ms：推过来的值，没推过就用代码里的 def。 */
        long stagger(long def) { return stg >= 0 ? stg : def; }

        float[] get() { return cur.clone(); }

        boolean isDefault() { return java.util.Arrays.equals(cur, def); }

        @Override public float getInterpolation(float t) { return path.getInterpolation(t); }

        /**
         * 错开的一组动画里的"虚拟时间"（ms，都不乘慢放）：elapsed = 整组开始后过了多久，span = 整组从第一块起步到最后一块走完的时长。
         * 每块的进度 = clamp((虚拟时间 − 自己的延迟) / 自己的时长)，再过 shape()。
         * 默认（每块各自）：虚拟时间就是真实时间，每块各自走一遍曲线。
         * 整段（用户 2026-10-25："曲线调整的应该是整个动画而不是单个卡片"）：曲线管整段时间轴，每块在变形后的时间里匀速走，一块拖着一块。
         */
        float vt(float elapsed, float span) {
            if (!whole || span <= 0f || elapsed <= 0f || elapsed >= span) return elapsed;
            return path.getInterpolation(elapsed / span) * span;
        }

        /** 一块自己的进度 k 0..1 → 动画量：整段模式下匀速（快慢已经在 vt 里了），否则过曲线。 */
        float shape(float k) { return whole ? k : path.getInterpolation(k); }
    }

    private static final LinkedHashMap<String, Curve> ALL = new LinkedHashMap<>();
    private static final String PREFS = "curves";

    private static Curve c(String id, float x1, float y1, float x2, float y2) {
        Curve k = new Curve(id, new float[] {x1, y1, x2, y2});
        ALL.put(id, k);
        return k;
    }

    // ---------- 开始界面（TileGridView） ----------
    static final Curve GRID_FT_IN    = c("grid.ftIn",    0.15f, 0.83f, 0.28f, 1f);        // 从表盘上滑进来：磁贴从外面 50° 翻平（WP 转门进场 0.35 秒，每行晚 0.05 秒，先等 0.13 秒）
    static final Curve GRID_FT_OUT   = c("grid.ftOut",   0.7f, 0f, 0.85f, 0.14f);         // 回表盘：磁贴翻走（WP 转门出场 0.25 秒，往外翻到 50°）
    static final Curve GRID_TS_OUT   = c("grid.tsOut",   0.7f, 0f, 0.85f, 0.14f);         // 打开应用：转门翻走（WP 0.25 秒，往外翻到 50°，每行晚 0.05 秒）
    static final Curve GRID_TS_IN    = c("grid.tsIn",    0.15f, 0.83f, 0.28f, 1f);        // 从应用回来：转门翻回（WP 0.35 秒，从外面 50° 转平）
    static final Curve GRID_TURN_OUT = c("grid.turnOut", 0.7f, 0f, 0.85f, 0.14f);         // 打开设置 / 编辑页：转门翻走（同打开应用）
    static final Curve GRID_TURN_IN  = c("grid.turnIn",  0.15f, 0.83f, 0.28f, 1f);        // 从设置页回来：转门翻回（同从应用回来）
    static final Curve GRID_ENTER    = c("grid.enter",   0.15f, 0.83f, 0.28f, 1f);        // 开始界面顶上往下拉回表盘：磁贴跟着手指往下沉（跟手，横轴是拉了多远）
    static final Curve GRID_LIFT     = c("grid.lift",    0.15f, 0.83f, 0.28f, 1f);        // 长按磁贴抬起（0.2 秒）
    static final Curve GRID_SETTLE   = c("grid.settle",  0.15f, 0.83f, 0.28f, 1f);        // 磁贴放下归位（0.24 秒）
    static final Curve GRID_FLIP     = c("grid.flip",    0.33f, 1f, 0.67f, 1f);           // 活动磁贴翻面（WP HubTile：0.85 秒，三次方缓出）

    // ---------- 几个界面共用 ----------
    static final Curve TILT_IN       = c("tilt.in",      0f, 0f, 1f, 1f);                 // 按下卡片倾斜（WP TiltEffect：立刻倾）
    static final Curve TILT_OUT      = c("tilt.out",     0f, 0f, 1f, 1f);                 // 松手回正（WP：先停 0.2 秒，再 0.1 秒匀速回正）
    static final Curve CROWN         = c("crown",        0.15f, 0.83f, 0.28f, 1f);        // 表冠滚一格（0.25 秒）

    // ---------- 应用列表（AppList） ----------
    static final Curve AL_TS_IN      = c("al.tsIn",      0.15f, 0.83f, 0.28f, 1f);        // 进应用列表：一行行转回来（0.35 秒）
    static final Curve AL_TS_OUT     = c("al.tsOut",     0.7f, 0f, 0.85f, 0.14f);         // 回开始界面：从下往上往外翻走（0.25 秒）
    static final Curve AL_LAUNCH     = c("al.launch",    0.7f, 0f, 0.85f, 0.14f);         // 打开应用：翻走，点的那行最后（0.25 秒）
    static final Curve AL_FT_OUT     = c("al.ftOut",     0.7f, 0f, 0.85f, 0.14f);         // 下滑回表盘：翻走（0.25 秒）
    static final Curve AL_SEARCH     = c("al.search",    0.15f, 0.83f, 0.28f, 1f);        // 搜索框滑入 / 滑出（0.35 秒）
    static final Curve AL_LIFT       = c("al.lift",      0.15f, 0.83f, 0.28f, 1f);        // 长按把应用拿起来（0.2 秒）

    // ---------- 通知中心（NativeShell + ActionCenter） ----------
    static final Curve NC_OPEN       = c("nc.open",      0.15f, 0.83f, 0.28f, 1f);        // 通知中心落下来（WP 滑入 0.35 秒）
    static final Curve NC_CLOSE      = c("nc.close",     0.15f, 0.83f, 0.28f, 1f);        // 返回键收起（WP 滑出 0.25 秒；WP 的滑出也是缓出）
    static final Curve NC_SETTLE     = c("nc.settle",    0.15f, 0.83f, 0.28f, 1f);        // 下拉松手：落到底或收回去（0.25 秒）
    static final Curve NC_CARD_LEAVE = c("nc.cardLeave", 0.15f, 0.83f, 0.28f, 1f);        // 通知右滑划走（WP 滑出 0.25 秒）
    static final Curve NC_CARD_BACK  = c("nc.cardBack",  0.15f, 0.83f, 0.28f, 1f);        // 没划够松手弹回（0.35 秒）
    static final Curve NC_SHIFT      = c("nc.shift",     0.15f, 0.83f, 0.28f, 1f);        // 下面的通知补上来（0.35 秒，每张晚 0.05 秒）
    static final Curve NC_PIVOT      = c("nc.pivot",     0.15f, 0.83f, 0.28f, 1f);        // 通知 / 控制中心左右切页（0.35 秒）
    static final Curve NC_TS_OUT     = c("nc.tsOut",     0.7f, 0f, 0.85f, 0.14f);         // 点通知打开应用：翻走（0.25 秒）
    static final Curve NC_TS_IN      = c("nc.tsIn",      0.15f, 0.83f, 0.28f, 1f);        // 从应用回来：翻回（0.35 秒）

    // ---------- 横幅（ToastView） ----------
    static final Curve TOAST_DROP    = c("toast.drop",   0.15f, 0.83f, 0.28f, 1f);        // 横幅落下（WP 滑入 0.35 秒）
    static final Curve TOAST_LIFT    = c("toast.lift",   0.15f, 0.83f, 0.28f, 1f);        // 横幅收起（WP 滑出 0.25 秒）

    // ---------- 表盘（NativeShell + FaceView） ----------
    static final Curve FACE_UP       = c("face.up",      0.15f, 0.83f, 0.28f, 1f);        // 上滑进开始界面：表盘被推出顶部（滑出 0.25 秒）
    static final Curve FACE_DOWN     = c("face.down",    0.15f, 0.83f, 0.28f, 1f);        // 回表盘：表盘落下来（滑入 0.35 秒）
    static final Curve FACE_SINK     = c("face.sink",    0.15f, 0.83f, 0.28f, 1f);        // 表盘被盖住时下沉缩小（0.35 秒，跟着盖上来的那层）
    static final Curve FACE_SINK_FADE= c("face.sinkFade",0.15f, 0.83f, 0.28f, 1f);        // 下沉时变暗
    static final Curve FACE_ACT_IN   = c("face.actIn",   0.15f, 0.83f, 0.28f, 1f);        // 即时活动推进来（0.35 秒，晚 0.12 秒起步）
    static final Curve FACE_ACT_OUT  = c("face.actOut",  0.15f, 0.83f, 0.28f, 1f);        // 即时活动推回去（0.25 秒）
    static final Curve FACE_CLOCK    = c("face.clock",   0.15f, 0.83f, 0.28f, 1f);        // 时间变矮 / 拉长（0.35 秒）

    // ---------- 表盘卡片区（FaceCards） ----------
    static final Curve FC_ORDER      = c("fc.order",     0.15f, 0.83f, 0.28f, 1f);        // 卡片换位置 / 大小（0.3 秒）
    static final Curve FC_SLOT       = c("fc.slot",      0.15f, 0.83f, 0.28f, 1f);        // 虚线落点框移动
    static final Curve FC_MORPH      = c("fc.morph",     0.15f, 0.83f, 0.28f, 1f);        // 两种布局之间变形（0.38 秒）
    static final Curve FC_STACK      = c("fc.stack",     0.15f, 0.83f, 0.28f, 1f);        // 即时活动叠上去（0.32 秒）
    static final Curve FC_LIVE       = c("fc.live",      0.15f, 0.83f, 0.28f, 1f);        // 即时活动滑入 / 滑走（0.3 秒）
    static final Curve FC_LIFT       = c("fc.lift",      0.15f, 0.83f, 0.28f, 1f);        // 拿起卡片：放大、变圆角（0.2 秒）
    static final Curve FC_DROP       = c("fc.drop",      0.15f, 0.83f, 0.28f, 1f);        // 放到落点（0.3 秒，缩回 0.24 秒）
    static final Curve FC_TOSS       = c("fc.toss",      0.15f, 0.83f, 0.28f, 1f);        // 拖出去删掉：缩小消失（0.2 秒）
    static final Curve FC_FADE       = c("fc.fade",      0.15f, 0.83f, 0.28f, 1f);        // 卡片区里各种淡入淡出

    // ---------- 换表盘（FaceGallery） ----------
    static final Curve GAL_MOVE      = c("gal.move",     0.15f, 0.83f, 0.28f, 1f);        // 表盘卡片移动 / 缩放（0.38 秒）
    static final Curve GAL_FADE      = c("gal.fade",     0.15f, 0.83f, 0.28f, 1f);        // 名字、背景、按钮淡入淡出

    // ---------- 设置等页面（NativeShell + PageView） ----------
    static final Curve PAGE_OPEN     = c("page.open",    0.15f, 0.83f, 0.28f, 1f);        // 页面翻进来（WP ForwardIn：从里面 80° 转平，0.35 秒，一下出现）
    static final Curve PAGE_CLOSE    = c("page.close",   0.7f, 0f, 0.85f, 0.14f);         // 页面翻出去（WP BackwardOut：往里转到 80°，0.25 秒，最后才消失）
    static final Curve PAGE_TOGGLE   = c("page.toggle",  0.15f, 0.83f, 0.28f, 1f);        // 开关拨动（0.2 秒）
    static final Curve PAGE_SWIPE    = c("page.swipe",   0.15f, 0.83f, 0.28f, 1f);        // 列表项划走（WP 滑出 0.25 秒）

    /** 开机时读回上次推过来的值。 */
    static void load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (Curve k : ALL.values()) {
            float[] v = parse(sp.getString(k.id, null));
            if (v != null) k.set(v);
        }
    }

    /** 处理 --es curves：'id=x1,y1,x2,y2;…' / reset / dump。结果打到 logcat（Tessera/Curves）。 */
    static void apply(Context ctx, String spec) {
        if (spec == null) return;
        spec = spec.trim();
        SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        int n = 0;
        if (spec.equals("reset")) {
            ed.clear();
            for (Curve k : ALL.values()) k.set(k.def);
            Log.i("Tessera/Curves", "reset all to defaults");
        } else if (!spec.equals("dump")) {
            for (String part : spec.split(";")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                String id = part.substring(0, eq).trim();
                Curve k = ALL.get(id);
                float[] v = parse(part.substring(eq + 1));
                if (k == null || v == null) { Log.w("Tessera/Curves", "skip " + part); continue; }
                k.set(v);
                if (k.isDefault()) ed.remove(id); else ed.putString(id, fmt(v));
                n++;
            }
            Log.i("Tessera/Curves", "applied " + n);
        }
        ed.apply();
        StringBuilder sb = new StringBuilder();
        for (Curve k : ALL.values()) if (!k.isDefault()) sb.append(k.id).append('=').append(fmt(k.get())).append(';');
        Log.i("Tessera/Curves", "changed: " + (sb.length() == 0 ? "(none)" : sb));
    }

    /** "x1,y1,x2,y2[,w][,s15]" → 数组（第 5 个 = 整段模式 1 / 0，第 6 个 = 错开 ms / -1）；x 必须在 0..1（PathInterpolator 要求 x 单调），y 随意（可以过冲）。 */
    private static float[] parse(String s) {
        if (s == null) return null;
        String[] p = s.trim().split(",");
        if (p.length < 4 || p.length > 6) return null;
        try {
            float[] v = new float[] {0f, 0f, 0f, 0f, 0f, -1f};
            for (int i = 0; i < 4; i++) v[i] = Float.parseFloat(p[i].trim());
            for (int i = 4; i < p.length; i++) {
                String q = p[i].trim();
                if (q.equals("w")) v[4] = 1f;
                else if (q.startsWith("s")) v[5] = Math.max(0, Math.min(1000, Integer.parseInt(q.substring(1))));
                else return null;
            }
            if (v[0] < 0f || v[0] > 1f || v[2] < 0f || v[2] > 1f) return null;
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String fmt(float[] v) {
        return String.format(Locale.US, "%s,%s,%s,%s", num(v[0]), num(v[1]), num(v[2]), num(v[3]))
                + (v.length > 4 && v[4] != 0f ? ",w" : "") + (v.length > 5 && v[5] >= 0f ? ",s" + Math.round(v[5]) : "");
    }

    private static String num(float f) {
        String s = String.format(Locale.US, "%.3f", f);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
