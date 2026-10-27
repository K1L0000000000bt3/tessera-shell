package com.tessera.shell;

import android.graphics.Path;

/**
 * Tessera 数字：逐行移植自 bundle.js 的 numeral(ch, W, H)。
 * watchOS 式的细体，画成中心线路径：端点平切、转角尖、碗形是偏方的椭圆（C = 0.6 的手柄）。
 * 拉高时只有直线变长，弧线不变形。W 为数字宽（中心线），H 为高。
 */
final class Numerals {
    private static final float C = 0.6f;

    private Numerals() {}

    static Path path(char ch, float W, float H) {
        float R = W / 2f, E = Math.min(0.78f * W, H / 4f);
        P p = new P();
        float m, e2;
        switch (ch) {
            case '0':
                p.M(0, E); p.top(0, W, 0, E); p.V(H - E); p.bottom(W, 0, H, E); p.Z();
                break;
            case '1': {
                float x1 = 0.56f * W;                         // 短旗，重心在竖笔上
                p.M(x1 - 0.42f * W, 0.3f * W); p.L(x1, 0); p.V(H);
                break;
            }
            case '2': {
                float L2 = H - E;
                p.M(0, E); p.top(0, W, 0, E);
                p.C(W, E + 0.36f * L2, 0, H - 0.34f * L2, 0, H); p.H(W);
                break;
            }
            case '3':
                m = 0.46f * H; e2 = Math.min(E, Math.min(m - E, (H - m) / 2f));
                p.M(0, E); p.top(0, W, 0, E); p.V(m - e2); p.q(W, m - e2, R, m, true); p.H(0.22f * W);
                p.M(0.22f * W, m); p.H(R); p.q(R, m, W, m + e2, false); p.V(H - E); p.bottom(W, 0, H, E);
                break;
            case '4':
                p.M(0.72f * W, H); p.V(0); p.L(0, 0.68f * H); p.H(W);
                break;
            case '5':
                m = 0.43f * H; e2 = Math.min(E, (H - m) / 2f);
                p.M(W, 0); p.H(0); p.V(m); p.H(R); p.q(R, m, W, m + e2, false); p.V(H - E); p.bottom(W, 0, H, E);
                break;
            case '6':
                m = 0.42f * H; e2 = Math.min(E, (H - m) / 2f);
                p.M(0, H - E); p.V(m + e2); p.top(0, W, m, e2); p.V(H - E); p.bottom(W, 0, H, E); p.Z();
                p.M(0, m + e2); p.V(E); p.top(0, W, 0, E);
                break;
            case '7':
                p.M(0, 0); p.H(W); p.L(0.3f * W, H);
                break;
            case '8': {
                m = 0.45f * H;
                float a = 0.06f * W, e1 = Math.min(E, m / 2f);
                e2 = Math.min(E, (H - m) / 2f);
                p.M(a, e1); p.top(a, W - a, 0, e1); p.V(m - e1); p.bottom(W - a, a, m, e1); p.Z();
                p.M(0, m + e2); p.top(0, W, m, e2); p.V(H - E); p.bottom(W, 0, H, E); p.Z();
                break;
            }
            case '9':
                m = 0.58f * H; e2 = Math.min(E, m / 2f);
                p.M(0, e2); p.top(0, W, 0, e2); p.V(m - e2); p.bottom(W, 0, m, e2); p.Z();
                p.M(W, e2); p.V(H - E); p.bottom(W, 0, H, E);
                break;
            default:
                break;
        }
        return p.path;
    }

    /** 跟 SVG 命令一一对应的小包装，记住当前点（H/V 需要）。 */
    private static final class P {
        final Path path = new Path();
        float x, y;

        void M(float nx, float ny) { path.moveTo(nx, ny); x = nx; y = ny; }

        void L(float nx, float ny) { path.lineTo(nx, ny); x = nx; y = ny; }

        void H(float nx) { L(nx, y); }

        void V(float ny) { L(x, ny); }

        void C(float x1, float y1, float x2, float y2, float nx, float ny) { path.cubicTo(x1, y1, x2, y2, nx, ny); x = nx; y = ny; }

        void Z() { path.close(); }

        /** 四分之一弧，从 (x0,y0) 到 (x1,y1)；v = 竖着出发（否则横着出发）。 */
        void q(float x0, float y0, float x1, float y1, boolean v) {
            if (v) C(x0, y0 + C * (y1 - y0), x1 - C * (x1 - x0), y1, x1, y1);
            else C(x0 + C * (x1 - x0), y0, x1, y1 - C * (y1 - y0), x1, y1);
        }

        /** 左边 → 越过顶部 → 右边。 */
        void top(float x0, float x1, float yy, float e) {
            float c = (x0 + x1) / 2f;
            q(x0, yy + e, c, yy, true);
            q(c, yy, x1, yy + e, false);
        }

        /** 右边 → 绕过底部 → 左边。 */
        void bottom(float x1, float x0, float yy, float e) {
            float c = (x0 + x1) / 2f;
            q(x1, yy - e, c, yy, true);
            q(c, yy, x0, yy - e, false);
        }
    }
}
