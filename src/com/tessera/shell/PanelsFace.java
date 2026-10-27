package com.tessera.shell;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextPaint;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

/**
 * 图片表盘：把用户给的拼板图解析成"卡片"（矩形 + 原色），
 * 每张卡片能单独翻转换色（换的颜色只取自这张图里的颜色），双指按住时中间的时间淡出，
 * 双指点击把所有卡片翻回原色（已经是原色的不翻）。
 */
final class PanelsFace {
    static final class Panel {
        float x, y, w, h;
        int color, base;
        int target;
        boolean anim;
        long t0;
        float progress;      // 0..1
    }

    private final List<Panel> panels = new ArrayList<>();
    private Bitmap source;            // 原图（绘制时直接贴，保证和壁纸一模一样）
    private final int[] palette;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelsPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final TextPaint timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Camera camera = new Camera();
    private final Matrix matrix = new Matrix();
    private final Random rnd = new Random();

    private float timeAlpha = 1f, timeTarget = 1f;
    private long alphaT0;
    private float alphaFrom;
    private boolean animating;
    private int srcW = 1, srcH = 1;

    PanelsFace(Context ctx) {
        Bitmap bmp = null;
        try (java.io.InputStream in = ctx.getAssets().open("face/panels.png")) {
            bmp = BitmapFactory.decodeStream(in);
        } catch (Throwable ignored) { }
        if (bmp == null) { palette = new int[0]; return; }
        srcW = bmp.getWidth();
        srcH = bmp.getHeight();
        source = bmp;                 // 保留原图，绘制时直接贴它
        build(bmp);
        android.util.Log.i("Tessera", "panels parsed=" + panels.size());
        // 调色板只收"卡片本身的颜色"：黑缝（很暗）一律不要，颜色太接近的也去重，
        // 否则翻出来可能是缝的颜色、或和当前色几乎一样看不出变化。
        java.util.ArrayList<Integer> list = new java.util.ArrayList<>();
        for (Panel p : panels) {
            if (lum(p.base) < 32) continue;                       // 缝隙（亮度 14~23）不算卡片色；最暗的卡片 #23282f 是 39，保留
            boolean dup = false;
            for (int c : list) if (dist(c, p.base) < 12) { dup = true; break; }
            if (!dup) list.add(p.base);
        }
        palette = new int[list.size()];
        for (int i = 0; i < list.size(); i++) palette[i] = list.get(i);
        StringBuilder sb = new StringBuilder();
        for (int c : palette) sb.append(String.format("#%06X ", c & 0xFFFFFF));
        android.util.Log.i("Tessera", "palette(" + palette.length + ") = " + sb);
    }

    boolean ready() { return !panels.isEmpty(); }

    // ---------- 解析：整体由黑缝分出列/行，再按"相邻两格之间有没有黑缝"合并成大卡片 ----------
    private void build(Bitmap bmp) {
        buildFlood(bmp);   // 按"连通的非黑区域"切卡片：黑缝怎么错落都能切开
    }

    private void buildGrid(Bitmap bmp) {
        int W = bmp.getWidth(), H = bmp.getHeight();
        int[] px = new int[W * H];
        bmp.getPixels(px, 0, W, 0, 0, W, H);
        boolean[] colGap = new boolean[W], rowGap = new boolean[H];
        for (int x = 0; x < W; x++) {
            int dark = 0;
            for (int y = 0; y < H; y++) if (lum(px[y * W + x]) < 34) dark++;
            colGap[x] = dark > H * 0.55;
        }
        for (int y = 0; y < H; y++) {
            int dark = 0;
            for (int x = 0; x < W; x++) if (lum(px[y * W + x]) < 34) dark++;
            rowGap[y] = dark > W * 0.55;
        }
        List<int[]> cols = ranges(colGap, W), rows = ranges(rowGap, H);
        int cw = cols.size(), rh = rows.size();
        if (cw == 0 || rh == 0) return;
        int[][] color = new int[rh][cw];
        for (int r = 0; r < rh; r++)
            for (int c = 0; c < cw; c++)
                color[r][c] = centerColor(px, W, cols.get(c), rows.get(r));
        boolean[][] used = new boolean[rh][cw];
        for (int r = 0; r < rh; r++) {
            for (int c = 0; c < cw; c++) {
                if (used[r][c] || color[r][c] == 0) { used[r][c] = true; continue; }
                List<int[]> cells = new ArrayList<>();
                java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
                q.add(new int[]{r, c}); used[r][c] = true;
                while (!q.isEmpty()) {
                    int[] cur = q.poll();
                    cells.add(cur);
                    int[][] nb = {{cur[0]-1,cur[1]},{cur[0]+1,cur[1]},{cur[0],cur[1]-1},{cur[0],cur[1]+1}};
                    for (int[] n : nb) {
                        if (n[0] < 0 || n[1] < 0 || n[0] >= rh || n[1] >= cw) continue;
                        if (used[n[0]][n[1]]) continue;
                        if (gapBetween(px, W, H, cols, rows, cur, n)) continue;   // 中间有黑缝就分开
                        used[n[0]][n[1]] = true;
                        q.add(n);
                    }
                }
                int minC = Integer.MAX_VALUE, maxC = -1, minR = Integer.MAX_VALUE, maxR = -1;
                for (int[] cell : cells) {
                    minC = Math.min(minC, cell[1]); maxC = Math.max(maxC, cell[1]);
                    minR = Math.min(minR, cell[0]); maxR = Math.max(maxR, cell[0]);
                }
                Panel p = new Panel();
                p.x = cols.get(minC)[0] / (float) W;
                p.y = rows.get(minR)[0] / (float) H;
                p.w = (cols.get(maxC)[1] - cols.get(minC)[0]) / (float) W;
                p.h = (rows.get(maxR)[1] - rows.get(minR)[0]) / (float) H;
                p.color = p.base = p.target = color[r][c];
                panels.add(p);
            }
        }
    }

    /** 两格之间那条缝上有没有黑像素：有就说明是两张卡片。 */
    private static boolean gapBetween(int[] px, int W, int H, List<int[]> cols, List<int[]> rows, int[] a, int[] b) {
        if (a[0] == b[0]) {                       // 同一行，左右相邻
            int cL = Math.min(a[1], b[1]);
            int x = cols.get(cL)[1];
            int x2 = cols.get(cL + 1)[0];
            int dark = 0, n = 0;
            for (int xx = x; xx < x2; xx++)
                for (int y = rows.get(a[0])[0]; y < rows.get(a[0])[1]; y++) { n++; if (lum(px[y * W + xx]) < 34) dark++; }
            return n > 0 && dark > n * 0.5;
        } else {                                  // 同一列，上下相邻
            int rT = Math.min(a[0], b[0]);
            int y = rows.get(rT)[1];
            int y2 = rows.get(rT + 1)[0];
            int dark = 0, n = 0;
            for (int yy = y; yy < y2; yy++)
                for (int x = cols.get(a[1])[0]; x < cols.get(a[1])[1]; x++) { n++; if (lum(px[yy * W + x]) < 34) dark++; }
            return n > 0 && dark > n * 0.5;
        }
    }

    private static int centerColor(int[] px, int W, int[] col, int[] row) {
        int cx = (col[0] + col[1]) / 2, cy = (row[0] + row[1]) / 2;
        int x0 = Math.max(col[0] + 3, cx - 4), x1 = Math.min(col[1] - 3, cx + 4);
        int y0 = Math.max(row[0] + 3, cy - 4), y1 = Math.min(row[1] - 3, cy + 4);
        long r = 0, g = 0, b = 0; int n = 0;
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
            int c = px[y * W + x];
            r += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF; n++;
        }
        if (n == 0) return 0;
        return Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
    }

    private void buildFlood(Bitmap bmp) {
        int W = bmp.getWidth(), H = bmp.getHeight();
        int[] px = new int[W * H];
        bmp.getPixels(px, 0, W, 0, 0, W, H);
        boolean[] done = new boolean[W * H];
        int[] stack = new int[W * H];
        int minArea = (int) (W * H * 0.008f);      // 太小的碎块丢掉
        for (int start = 0; start < px.length; start++) {
            if (done[start]) continue;
            int c0 = px[start];
            if (lum(c0) < 28) { done[start] = true; continue; }   // 缝是 #0b0f12~#13181c（亮度 14~23）
            int sp = 0;
            stack[sp++] = start;
            done[start] = true;
            int minX = W, maxX = -1, minY = H, maxY = -1, n = 0;
            java.util.HashMap<Integer, Integer> hist = new java.util.HashMap<>();   // 这张卡片里出现最多的"原色"
            while (sp > 0) {
                int idx = stack[--sp];
                int x = idx % W, y = idx / W;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                n++;
                int pc = px[idx];
                hist.merge(pc, 1, Integer::sum);
                if (x > 0) sp = push(px, done, stack, sp, idx - 1, c0);
                if (x < W - 1) sp = push(px, done, stack, sp, idx + 1, c0);
                if (y > 0) sp = push(px, done, stack, sp, idx - W, c0);
                if (y < H - 1) sp = push(px, done, stack, sp, idx + W, c0);
            }
            if (n < minArea) continue;
            Panel p = new Panel();
            p.x = minX / (float) W;
            p.y = minY / (float) H;
            p.w = (maxX - minX + 1) / (float) W;
            p.h = (maxY - minY + 1) / (float) H;
            int best = c0, bestN = -1;
            for (java.util.Map.Entry<Integer, Integer> e : hist.entrySet()) {
                if (e.getValue() > bestN) { bestN = e.getValue(); best = e.getKey(); }
            }
            p.color = p.base = p.target = best;   // 取这张卡片里出现最多的那个像素色，保证是原图里的确切颜色
            panels.add(p);
        }
    }

    private static int push(int[] px, boolean[] done, int[] stack, int sp, int idx, int ref) {
        if (done[idx]) return sp;
        int c = px[idx];
        if (lum(c) < 28) { done[idx] = true; return sp; }   // 黑缝当边界；只要不是缝就连通（不比较颜色）
        done[idx] = true;
        stack[sp++] = idx;
        return sp;
    }

    private static int dist(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        int db = (a & 0xFF) - (b & 0xFF);
        return Math.abs(dr) + Math.abs(dg) + Math.abs(db);
    }

    private void buildOld(Bitmap bmp) {
        int W = bmp.getWidth(), H = bmp.getHeight();
        boolean[] colGap = new boolean[W];
        boolean[] rowGap = new boolean[H];
        int[] px = new int[W * H];
        bmp.getPixels(px, 0, W, 0, 0, W, H);
        for (int x = 0; x < W; x++) {
            int dark = 0;
            for (int y = 0; y < H; y++) if (lum(px[y * W + x]) < 26) dark++;
            colGap[x] = dark > H * 0.7;
        }
        for (int y = 0; y < H; y++) {
            int dark = 0;
            for (int x = 0; x < W; x++) if (lum(px[y * W + x]) < 26) dark++;
            rowGap[y] = dark > W * 0.7;
        }
        List<int[]> cols = ranges(colGap, W);
        List<int[]> rows = ranges(rowGap, H);
        int cw = cols.size(), rh = rows.size();
        if (cw == 0 || rh == 0) return;
        int[][] grid = new int[rh][cw];
        for (int r = 0; r < rh; r++) {
            for (int c = 0; c < cw; c++) {
                grid[r][c] = sampleColor(px, W, cols.get(c), rows.get(r));
            }
        }
        boolean[][] used = new boolean[rh][cw];
        for (int r = 0; r < rh; r++) {
            for (int c = 0; c < cw; c++) {
                if (used[r][c] || grid[r][c] == 0) continue;
                int color = grid[r][c];
                // 洪水填充同色相邻格
                List<int[]> cells = new ArrayList<>();
                java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
                q.add(new int[]{r, c});
                used[r][c] = true;
                while (!q.isEmpty()) {
                    int[] cur = q.poll();
                    cells.add(cur);
                    int[][] nb = {{cur[0]-1,cur[1]},{cur[0]+1,cur[1]},{cur[0],cur[1]-1},{cur[0],cur[1]+1}};
                    for (int[] n : nb) {
                        if (n[0] < 0 || n[1] < 0 || n[0] >= rh || n[1] >= cw) continue;
                        if (used[n[0]][n[1]] || grid[n[0]][n[1]] != color) continue;
                        used[n[0]][n[1]] = true;
                        q.add(n);
                    }
                }
                int minC = Integer.MAX_VALUE, maxC = -1, minR = Integer.MAX_VALUE, maxR = -1;
                for (int[] cell : cells) {
                    minC = Math.min(minC, cell[1]); maxC = Math.max(maxC, cell[1]);
                    minR = Math.min(minR, cell[0]); maxR = Math.max(maxR, cell[0]);
                }
                Panel p = new Panel();
                p.x = cols.get(minC)[0] / (float) W;
                p.y = rows.get(minR)[0] / (float) H;
                p.w = (cols.get(maxC)[1] - cols.get(minC)[0]) / (float) W;
                p.h = (rows.get(maxR)[1] - rows.get(minR)[0]) / (float) H;
                p.color = p.base = color;
                p.target = color;
                panels.add(p);
            }
        }
    }

    private static List<int[]> ranges(boolean[] gap, int n) {
        List<int[]> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < n; i++) {
            if (!gap[i] && start < 0) start = i;
            if (gap[i] && start >= 0) { if (i - start > 4) out.add(new int[]{start, i}); start = -1; }
        }
        if (start >= 0 && n - start > 4) out.add(new int[]{start, n});
        return out;
    }

    private static int sampleColor(int[] px, int W, int[] col, int[] row) {
        int x0 = col[0] + (col[1] - col[0]) / 4, x1 = col[1] - (col[1] - col[0]) / 4;
        int y0 = row[0] + (row[1] - row[0]) / 4, y1 = row[1] - (row[1] - row[0]) / 4;
        long r = 0, g = 0, b = 0; int n = 0;
        for (int y = y0; y < y1; y += 2) {
            for (int x = x0; x < x1; x += 2) {
                int c = px[y * W + x];
                r += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF; n++;
            }
        }
        if (n == 0) return 0;
        int rr = (int) (r / n), gg = (int) (g / n), bb = (int) (b / n);
        if (rr + gg + bb < 40) return 0;      // 黑色缝隙
        return Color.rgb(rr, gg, bb);
    }

    private static int lum(int c) {
        return (int) (0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF) + 0.114 * (c & 0xFF));
    }

    // ---------- 交互 ----------
    /** 点一下某张卡片：翻一下换个颜色（只从这张图的调色板里挑，且不等于当前色）。 */
    /** 只判断命中，不改状态（给 JS 同步用）。 */
    boolean hitTest(float nx, float ny, float size) {
        if (!interactive) return false;
        for (Panel p : panels) {
            if (nx >= p.x * size && nx <= (p.x + p.w) * size && ny >= p.y * size && ny <= (p.y + p.h) * size) return true;
        }
        return false;
    }

    /** 是否处于"可翻"状态（双指点击后进入；进入时时间隐藏）。 */
    boolean interactive = false;

    boolean tap(float nx, float ny, float size) {
        if (!interactive) return false;
        for (Panel p : panels) {
            if (nx < p.x * size || nx > (p.x + p.w) * size) continue;
            if (ny < p.y * size || ny > (p.y + p.h) * size) continue;
            int next = pickColor(p.color);
            android.util.Log.i("Tessera", "flip panel " + p.x + "," + p.y + " " + Integer.toHexString(p.color) + " -> " + Integer.toHexString(next));
            if (next == p.color) return true;
            p.target = next;
            startFlip(p);
            return true;
        }
        return false;
    }

    /** 只从调色板里挑，而且要和当前色拉开距离（否则翻一下看不出变化）。 */
    private int pickColor(int except) {
        if (palette.length == 0) return except;
        int pick = except;
        for (int i = 0; i < 24; i++) {
            int c = palette[rnd.nextInt(palette.length)];
            if (dist(c, except) >= 24) { pick = c; break; }
        }
        return pick;
    }

    private void startFlip(Panel p) {
        p.anim = true;
        p.t0 = SystemClock.uptimeMillis();
        p.progress = 0f;
        animating = true;
    }

    /** 双指点击：把所有改过色的卡片翻回原色（本来就是原色的不动）。 */
    void resetAll() {
        boolean any = false;
        for (Panel p : panels) {
            if (p.color == p.base) continue;
            p.target = p.base;
            startFlip(p);
            any = true;
        }
        if (any) animating = true;
    }

    void setTimeHidden(boolean hidden, float size) {
        alphaFrom = timeAlpha;
        timeTarget = hidden ? 0f : 1f;
        alphaT0 = SystemClock.uptimeMillis();
        animating = true;
    }

    boolean busy() { return animating; }

    /** 第一行卡片的下沿（0..1）：贴着顶边的那几张里最低的下沿，也就是第一条横贯全屏的黑缝。没解析出卡片时为 0。 */
    float firstRowBottom() {
        float top = 1f, bottom = 0f;
        for (Panel p : panels) top = Math.min(top, p.y);
        for (Panel p : panels) if (p.y <= top + 0.01f) bottom = Math.max(bottom, p.y + p.h);
        return bottom;
    }

    // ---------- 绘制 ----------
    void draw(Canvas c, float size, Calendar now) {
        long t = SystemClock.uptimeMillis();
        if (animating) {
            boolean still = false;
            float a = Math.min(1f, (t - alphaT0) / 400f);
            timeAlpha = alphaFrom + (timeTarget - alphaFrom) * a;
            if (a < 1f) still = true;
            for (Panel p : panels) {
                if (!p.anim) continue;
                float k = (t - p.t0) / 300f;
                if (k >= 1f) {
                    p.progress = 0f; p.anim = false; p.color = p.target;
                } else {
                    p.progress = k;
                    still = true;
                    if (k >= 0.5f) p.color = p.target;   // 翻到侧立时换色
                }
            }
            animating = still;
        }
        // 先原样贴整张图（保证和壁纸一模一样）；翻转/改色时才覆盖那一块
        if (source != null) {
            c.drawBitmap(source, null, new android.graphics.RectF(0, 0, size, size), panelsPaint);
        }
        for (Panel p : panels) {
            if (p.progress <= 0f && p.color == p.base) continue;
            int save = c.save();
            // 翻转中先把这块底色抹成黑缝的颜色，否则卡片转侧的时候会漏出下面原图的原色
            if (p.progress > 0f) {
                fill.setColor(0xFF000000);
                c.drawRect(p.x * size, p.y * size, (p.x + p.w) * size, (p.y + p.h) * size, fill);
            }
            float turn = 0f;                       // 0=正面 1=完全侧立
            if (p.progress > 0f) {
                float ang = p.progress <= 0.5f ? -90f * (p.progress * 2f) : -90f * (2f - p.progress * 2f);
                turn = (float) Math.abs(Math.sin(Math.toRadians(ang)));
                float cx = (p.x + p.w / 2f) * size, cy = (p.y + p.h / 2f) * size;
                matrix.reset();
                camera.save();
                camera.setLocation(0, 0, -3.2f);          // 更近的视点：远边明显收小，才不像缩放
                camera.rotateY(ang);
                camera.getMatrix(matrix);
                camera.restore();
                matrix.preTranslate(-cx, -cy);
                matrix.postTranslate(cx, cy);
                c.concat(matrix);
            }
            // 翻回去的过程中贴原图那一块，翻完/改色就是纯色
            if (source != null && p.color == p.base && p.progress <= 0.5f) {
                android.graphics.Rect srcR = new android.graphics.Rect(
                        Math.round(p.x * srcW), Math.round(p.y * srcH),
                        Math.round((p.x + p.w) * srcW), Math.round((p.y + p.h) * srcH));
                android.graphics.RectF dstR = new android.graphics.RectF(
                        p.x * size, p.y * size, (p.x + p.w) * size, (p.y + p.h) * size);
                c.drawBitmap(source, srcR, dstR, panelsPaint);
            } else {
                fill.setColor(p.color);
                c.drawRect(p.x * size, p.y * size, (p.x + p.w) * size, (p.y + p.h) * size, fill);
            }
            // 转向暗：越接近侧立越暗，模拟离开光源；同时往转向那一侧压一道投影
            if (turn > 0.01f) {
                fill.setColor(0xFF000000);
                fill.setAlpha((int) (255 * 0.55f * turn));
                c.drawRect(p.x * size, p.y * size, (p.x + p.w) * size, (p.y + p.h) * size, fill);
                fill.setAlpha(255);
            }
            c.restoreToCount(save);
            if (turn > 0.01f) {
                // 卡片外侧的投影：越侧越明显，落在卡片下方一点
                android.graphics.LinearGradient g = new android.graphics.LinearGradient(
                        0, (p.y + p.h) * size, 0, (p.y + p.h) * size + size * 0.04f,
                        ((int) (0x66 * turn) << 24), 0x00000000, android.graphics.Shader.TileMode.CLAMP);
                fill.setShader(g);
                c.drawRect(p.x * size, (p.y + p.h) * size, (p.x + p.w) * size, (p.y + p.h) * size + size * 0.04f, fill);
                fill.setShader(null);
            }
        }
        // 时间由 FaceView 里的 TextView 画（自己画在硬件画布上不显示，改用原生文字控件）
    }

    private void drawTime(Canvas c, float size, Calendar now) {
        if (timeAlpha <= 0.01f) return;
        String time = now.get(Calendar.HOUR_OF_DAY) + ":" + (now.get(Calendar.MINUTE) < 10 ? "0" : "") + now.get(Calendar.MINUTE);
        timePaint.setTextSize(size * 0.26f);
        timePaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        timePaint.setColor(0xFFF4F7FA);
        timePaint.setTextAlign(Paint.Align.CENTER);
        timePaint.setLetterSpacing(-0.02f);
        timePaint.setShadowLayer(size * 0.035f, 0f, size * 0.012f, 0xCC000000);
        timePaint.setAlpha((int) (255 * timeAlpha));
        Paint.FontMetrics fm = timePaint.getFontMetrics();
        c.drawText(time, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, timePaint);
    }
}
