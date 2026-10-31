package com.ashcc.monitor;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * ARGB pixel buffer of an HD monitor, changed only by applying encoded draw operations. The server and every client
 * run the same operations through this class, so only the (small) operations go over the network. All maths is
 * deterministic: integer or IEEE double without fused operations, and StrictMath for trigonometry.
 * <p>
 * Coordinates: pixel (x, y) covers [x, x+1) x [y, y+1), origin top-left. Strokes and circles take pixel-centre
 * coordinates (0.5 is added), filled polygons take corner coordinates. Polygons use the non-zero winding rule.
 */
public final class Canvas {
    // Operation codes
    static final int OP_RECT = 1;
    static final int OP_PIXEL = 2;
    static final int OP_LINE = 3;
    static final int OP_ELLIPSE = 4;
    static final int OP_POLYGON = 5;
    static final int OP_TEXT = 6;
    static final int OP_IMAGE = 7;
    static final int OP_COPY = 8;
    static final int OP_TERM = 9;
    static final int OP_CLIP = 10;
    static final int OP_BLEND = 11;
    static final int OP_AA = 12;
    static final int OP_CURSOR = 13;
    static final int OP_RAW = 14;

    public static final int MAX_POLYGON_POINTS = 4096;
    public static final int MAX_TEXT = 4096;
    private static final int AA_SAMPLES = 4;

    public final int width;
    public final int height;
    public final int[] pixels;

    private int clipX0, clipY0, clipX1, clipY1;
    private boolean blend = true;
    private boolean antialias = true;

    // Text cursor of the term emulation (drawn by the client renderer, not into the pixels)
    public int cursorX, cursorY, cursorW, cursorH, cursorColor;
    public boolean cursorVisible;

    // Changed region since the client last uploaded the texture
    private int dirtyX0, dirtyY0, dirtyX1, dirtyY1;
    private boolean dirty;

    public Canvas(int width, int height) {
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
        Arrays.fill(pixels, 0xFF000000);
        resetClip();
        markDirty(0, 0, width, height);
    }

    private void resetClip() {
        clipX0 = 0;
        clipY0 = 0;
        clipX1 = width;
        clipY1 = height;
    }

    public boolean blend() {
        return blend;
    }

    public boolean antialias() {
        return antialias;
    }

    // ---- Dirty region (client texture upload) ----

    private void markDirty(int x0, int y0, int x1, int y1) {
        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        x1 = Math.min(width, x1);
        y1 = Math.min(height, y1);
        if (x0 >= x1 || y0 >= y1) return;
        if (!dirty) {
            dirtyX0 = x0;
            dirtyY0 = y0;
            dirtyX1 = x1;
            dirtyY1 = y1;
            dirty = true;
        } else {
            dirtyX0 = Math.min(dirtyX0, x0);
            dirtyY0 = Math.min(dirtyY0, y0);
            dirtyX1 = Math.max(dirtyX1, x1);
            dirtyY1 = Math.max(dirtyY1, y1);
        }
    }

    /** The changed region as {x0, y0, x1, y1} and resets it, or null if nothing changed. */
    public int[] takeDirty() {
        if (!dirty) return null;
        dirty = false;
        return new int[]{dirtyX0, dirtyY0, dirtyX1, dirtyY1};
    }

    // ---- Pixel writes ----

    private void put(int x, int y, int color) {
        if (x < clipX0 || y < clipY0 || x >= clipX1 || y >= clipY1) return;
        int i = y * width + x;
        pixels[i] = blend ? over(pixels[i], color) : color;
    }

    /** Writes with partial coverage (0..1) for anti-aliased edges. */
    private void putCoverage(int x, int y, int color, double coverage) {
        if (coverage <= 0) return;
        if (!blend) {
            if (coverage >= 0.5) put(x, y, color);
            return;
        }
        if (coverage < 1) {
            int a = (int) ((color >>> 24) * coverage + 0.5);
            if (a == 0) return;
            color = (a << 24) | (color & 0xFFFFFF);
        }
        put(x, y, color);
    }

    /** Source-over compositing of non-premultiplied ARGB, integer only. */
    static int over(int dst, int src) {
        int sa = src >>> 24;
        if (sa == 255) return src;
        if (sa == 0) return dst;
        int da = dst >>> 24;
        int ia = 255 - sa;
        int dw = da * ia; // dst weight x255
        int oa255 = sa * 255 + dw;
        if (oa255 == 0) return 0;
        int oa = (oa255 + 127) / 255;
        int r = (((src >> 16) & 255) * sa * 255 + ((dst >> 16) & 255) * dw + oa255 / 2) / oa255;
        int g = (((src >> 8) & 255) * sa * 255 + ((dst >> 8) & 255) * dw + oa255 / 2) / oa255;
        int b = ((src & 255) * sa * 255 + (dst & 255) * dw + oa255 / 2) / oa255;
        return (oa << 24) | (r << 16) | (g << 8) | b;
    }

    private void fillRect(int x, int y, int w, int h, int color, boolean replace) {
        int x0 = Math.max(x, clipX0), y0 = Math.max(y, clipY0);
        int x1 = (int) Math.min((long) x + w, clipX1), y1 = (int) Math.min((long) y + h, clipY1);
        if (x0 >= x1 || y0 >= y1) return;
        if (replace || !blend || (color >>> 24) == 255) {
            for (int yy = y0; yy < y1; yy++) Arrays.fill(pixels, yy * width + x0, yy * width + x1, color);
        } else if ((color >>> 24) != 0) {
            for (int yy = y0; yy < y1; yy++) {
                int row = yy * width;
                for (int xx = x0; xx < x1; xx++) pixels[row + xx] = over(pixels[row + xx], color);
            }
        }
        markDirty(x0, y0, x1, y1);
    }

    // ---- Polygon rasterizer (non-zero winding, 4 sub-scanlines with exact horizontal coverage) ----

    /** Fills the given closed contours (each {x0, y0, x1, y1, ...}). */
    private void fillContours(double[][] contours, int color) {
        if ((color >>> 24) == 0 && blend) return;
        int edges = 0;
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (double[] c : contours) {
            edges += c.length / 2;
            for (int i = 0; i + 1 < c.length; i += 2) {
                minX = Math.min(minX, c[i]);
                maxX = Math.max(maxX, c[i]);
                minY = Math.min(minY, c[i + 1]);
                maxY = Math.max(maxY, c[i + 1]);
            }
        }
        if (edges < 3 || !(minX <= maxX)) return;
        int bx0 = (int) Math.max(clipX0, Math.floor(minX));
        int by0 = (int) Math.max(clipY0, Math.floor(minY));
        int bx1 = (int) Math.min(clipX1, Math.ceil(maxX) + 1);
        int by1 = (int) Math.min(clipY1, Math.ceil(maxY) + 1);
        if (bx0 >= bx1 || by0 >= by1) return;

        double[] ex0 = new double[edges], ey0 = new double[edges], ex1 = new double[edges], ey1 = new double[edges];
        int n = 0;
        for (double[] c : contours) {
            int pts = c.length / 2;
            for (int i = 0; i < pts; i++) {
                int j = (i + 1) % pts;
                ex0[n] = c[2 * i];
                ey0[n] = c[2 * i + 1];
                ex1[n] = c[2 * j];
                ey1[n] = c[2 * j + 1];
                n++;
            }
        }
        int samples = antialias ? AA_SAMPLES : 1;
        double weight = 1.0 / samples;
        int span = bx1 - bx0;
        double[] cov = new double[span + 1];
        double[] xs = new double[edges];
        int[] dirs = new int[edges];
        for (int y = by0; y < by1; y++) {
            Arrays.fill(cov, 0);
            boolean any = false;
            for (int s = 0; s < samples; s++) {
                double sy = y + (s + 0.5) / samples;
                int k = 0;
                for (int e = 0; e < n; e++) {
                    double ya = ey0[e], yb = ey1[e];
                    if (ya <= sy && yb > sy) {
                        xs[k] = ex0[e] + (sy - ya) * (ex1[e] - ex0[e]) / (yb - ya);
                        dirs[k++] = 1;
                    } else if (yb <= sy && ya > sy) {
                        xs[k] = ex0[e] + (sy - ya) * (ex1[e] - ex0[e]) / (yb - ya);
                        dirs[k++] = -1;
                    }
                }
                if (k < 2) continue;
                // Insertion sort by x (few crossings per scanline), keeping the directions alongside
                for (int i = 1; i < k; i++) {
                    double vx = xs[i];
                    int vd = dirs[i];
                    int j = i - 1;
                    while (j >= 0 && xs[j] > vx) {
                        xs[j + 1] = xs[j];
                        dirs[j + 1] = dirs[j];
                        j--;
                    }
                    xs[j + 1] = vx;
                    dirs[j + 1] = vd;
                }
                int winding = 0;
                double start = 0;
                for (int i = 0; i < k; i++) {
                    int before = winding;
                    winding += dirs[i];
                    if (before == 0 && winding != 0) {
                        start = xs[i];
                        continue;
                    }
                    if (before == 0 || winding != 0) continue;
                    double a = start, b = xs[i];
                    if (antialias) {
                        any |= addCoverage(cov, a - bx0, b - bx0, span, weight);
                    } else {
                        // Pixel centres inside [a, b)
                        int ia = (int) Math.max(0, Math.ceil(a - 0.5) - bx0);
                        int ib = (int) Math.min(span, Math.ceil(b - 0.5) - bx0);
                        for (int x = ia; x < ib; x++) cov[x] = 1;
                        any |= ia < ib;
                    }
                }
            }
            if (!any) continue;
            for (int x = 0; x < span; x++) {
                if (cov[x] > 0) putCoverage(bx0 + x, y, color, Math.min(1, cov[x]));
            }
        }
        markDirty(bx0, by0, bx1, by1);
    }

    private static boolean addCoverage(double[] cov, double a, double b, int span, double weight) {
        a = Math.max(0, a);
        b = Math.min(span, b);
        if (b <= a) return false;
        int ia = (int) Math.floor(a), ib = (int) Math.floor(b);
        if (ia == ib) {
            cov[ia] += (b - a) * weight;
        } else {
            cov[ia] += (ia + 1 - a) * weight;
            for (int i = ia + 1; i < ib; i++) cov[i] += weight;
            if (ib < span) cov[ib] += (b - ib) * weight;
        }
        return true;
    }

    // ---- Shapes as contours ----

    private static int segments(double r) {
        return (int) Math.max(24, Math.min(1024, Math.ceil(2 * Math.PI * r / 2)));
    }

    /** Closed ellipse contour; counter-clockwise on screen when ccw (y down), else clockwise. */
    static double[] ellipse(double cx, double cy, double rx, double ry, boolean ccw) {
        int n = segments(Math.max(rx, ry));
        double[] c = new double[n * 2];
        for (int i = 0; i < n; i++) {
            double t = 2 * Math.PI * i / n;
            if (!ccw) t = -t;
            c[2 * i] = cx + rx * StrictMath.cos(t);
            c[2 * i + 1] = cy + ry * StrictMath.sin(t);
        }
        return c;
    }

    /** Capsule (round caps) or box (square caps) around a segment, always the same winding as {@link #ellipse} ccw. */
    static double[] stroke(double x1, double y1, double x2, double y2, double half, boolean round) {
        double dx = x2 - x1, dy = y2 - y1;
        double len = Math.sqrt(dx * dx + dy * dy);
        double ux, uy;
        if (len < 1e-9) {
            ux = 1;
            uy = 0;
        } else {
            ux = dx / len;
            uy = dy / len;
        }
        double nx = -uy * half, ny = ux * half;
        if (!round) {
            double ex = ux * half, ey = uy * half;
            return orient(new double[]{
                x1 - ex + nx, y1 - ey + ny, x2 + ex + nx, y2 + ey + ny,
                x2 + ex - nx, y2 + ey - ny, x1 - ex - nx, y1 - ey - ny});
        }
        int capSeg = Math.max(4, segments(half) / 2);
        double[] c = new double[(capSeg + 1) * 4];
        double base = StrictMath.atan2(ny, nx);
        int k = 0;
        // Cap around p2 from +n to -n through +u, then around p1 from -n to +n through -u
        for (int i = 0; i <= capSeg; i++) {
            double t = base - Math.PI * i / capSeg;
            c[k++] = x2 + half * StrictMath.cos(t);
            c[k++] = y2 + half * StrictMath.sin(t);
        }
        for (int i = 0; i <= capSeg; i++) {
            double t = base + Math.PI - Math.PI * i / capSeg;
            c[k++] = x1 + half * StrictMath.cos(t);
            c[k++] = y1 + half * StrictMath.sin(t);
        }
        return orient(c);
    }

    /** Signed area > 0 means counter-clockwise in a y-up frame; flips contours to one fixed winding. */
    static double[] orient(double[] c) {
        double area = 0;
        int n = c.length / 2;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            area += c[2 * i] * c[2 * j + 1] - c[2 * j] * c[2 * i + 1];
        }
        if (area >= 0) return c;
        double[] r = new double[c.length];
        for (int i = 0; i < n; i++) {
            r[2 * i] = c[2 * (n - 1 - i)];
            r[2 * i + 1] = c[2 * (n - 1 - i) + 1];
        }
        return r;
    }

    // ---- Text ----

    private void drawText(int x, int y, int scale, int color, int background, String text) {
        MonitorFont font = MonitorFont.get();
        int lineH = MonitorFont.HEIGHT * scale;
        if ((background >>> 24) != 0) {
            int cy = y;
            for (String line : text.split("\n", -1)) {
                fillRect(x, cy, font.measure(line) * scale, lineH, background, false);
                cy += lineH;
            }
        }
        int cx = x, cy = y;
        for (int k = 0; k < text.length(); ) {
            int cp = text.codePointAt(k);
            k += Character.charCount(cp);
            if (cp == '\n') {
                cx = x;
                cy += lineH;
                continue;
            }
            int w = font.width(cp);
            if (cx < clipX1 && cx + w * scale > clipX0 && cy < clipY1 && cy + lineH > clipY0) drawGlyph(font, cp, cx, cy, scale, color);
            cx += w * scale;
        }
        markDirty(x, y, cx + 16 * scale, cy + lineH);
    }

    private void drawGlyph(MonitorFont font, int cp, int x, int y, int scale, int color) {
        int w = font.width(cp);
        for (int gy = 0; gy < MonitorFont.HEIGHT; gy++) {
            for (int gx = 0; gx < w; gx++) {
                if (!font.bit(cp, gx, gy)) continue;
                if (scale == 1) put(x + gx, y + gy, color);
                else fillRectNoDirty(x + gx * scale, y + gy * scale, scale, scale, color);
            }
        }
    }

    private void fillRectNoDirty(int x, int y, int w, int h, int color) {
        for (int yy = y; yy < y + h; yy++) for (int xx = x; xx < x + w; xx++) put(xx, yy, color);
    }

    // ---- Term cells (CC character set) ----

    /** CC's 0..255 character set as Unicode; 128..159 are the 2x3 "teletext" blocks drawn separately. */
    private static final int[] CC_CHARS = new int[256];

    static {
        String low = "\u0000☺☻♥♦♣♠•◘○◙♂♀♪♫☼"
            + "►◄↕‼¶§▬↨↑↓→←∟↔▲▼";
        for (int i = 0; i < 256; i++) CC_CHARS[i] = i;
        for (int i = 0; i < 32; i++) CC_CHARS[i] = low.charAt(i);
        CC_CHARS[0] = ' ';
        CC_CHARS[127] = 0x2592;
    }

    private void drawTermCell(MonitorFont font, int x, int y, int k, int ch, int fg, int bg) {
        int cw = 8 * k, chh = 16 * k;
        fillRect(x, y, cw, chh, bg, true);
        if (ch >= 128 && ch < 160) {
            // Sub-pixels: bit0 top-left, bit1 top-right, bit2 mid-left, bit3 mid-right, bit4 bottom-left
            int bits = ch - 128;
            int[] rows = {0, 5 * k, 11 * k, 16 * k};
            for (int i = 0; i < 5; i++) {
                if ((bits & (1 << i)) == 0) continue;
                int col = i & 1, row = i >> 1;
                fillRect(x + col * 4 * k, y + rows[row], 4 * k, rows[row + 1] - rows[row], fg, true);
            }
            return;
        }
        int cp = CC_CHARS[ch & 255];
        if (cp == ' ') return;
        boolean saved = blend;
        blend = false;
        drawGlyph(font, cp, x, y, k, fg);
        blend = saved;
    }

    // ---- Applying encoded operations ----

    /** Applies every operation in the buffer. Malformed data stops at the first bad operation. */
    public void apply(ByteBuf buf) {
        try {
            while (buf.isReadable()) applyOne(buf);
        } catch (RuntimeException e) {
            buf.skipBytes(buf.readableBytes());
        }
    }

    private void applyOne(ByteBuf b) {
        int op = b.readUnsignedByte();
        switch (op) {
            case OP_RECT -> fillRect(b.readInt(), b.readInt(), b.readInt(), b.readInt(), b.readInt(), b.readBoolean());
            case OP_PIXEL -> {
                int x = b.readInt(), y = b.readInt(), c = b.readInt();
                put(x, y, c);
                markDirty(x, y, x + 1, y + 1);
            }
            case OP_LINE -> {
                double x1 = b.readFloat() + 0.5, y1 = b.readFloat() + 0.5, x2 = b.readFloat() + 0.5, y2 = b.readFloat() + 0.5;
                double t = b.readFloat();
                int c = b.readInt();
                fillContours(new double[][]{stroke(x1, y1, x2, y2, Math.max(0.5, t / 2), t >= 2)}, c);
            }
            case OP_ELLIPSE -> {
                double cx = b.readFloat() + 0.5, cy = b.readFloat() + 0.5, rx = b.readFloat(), ry = b.readFloat();
                double t = b.readFloat();
                int c = b.readInt();
                if (t <= 0) {
                    fillContours(new double[][]{ellipse(cx, cy, rx, ry, true)}, c);
                } else {
                    double h = t / 2;
                    double ix = rx - h, iy = ry - h;
                    fillContours(ix > 0 && iy > 0
                        ? new double[][]{ellipse(cx, cy, rx + h, ry + h, true), ellipse(cx, cy, ix, iy, false)}
                        : new double[][]{ellipse(cx, cy, rx + h, ry + h, true)}, c);
                }
            }
            case OP_POLYGON -> {
                int n = b.readUnsignedShort();
                double t = b.readFloat();
                int c = b.readInt();
                double[] pts = new double[n * 2];
                for (int i = 0; i < n * 2; i++) pts[i] = b.readFloat();
                if (t <= 0) {
                    fillContours(new double[][]{pts}, c);
                } else {
                    // Outline: a stroke per edge, merged by the non-zero rule
                    double[][] parts = new double[n][];
                    for (int i = 0; i < n; i++) {
                        int j = (i + 1) % n;
                        parts[i] = stroke(pts[2 * i] + 0.5, pts[2 * i + 1] + 0.5, pts[2 * j] + 0.5, pts[2 * j + 1] + 0.5, Math.max(0.5, t / 2), t >= 2);
                    }
                    fillContours(parts, c);
                }
            }
            case OP_TEXT -> {
                int x = b.readInt(), y = b.readInt(), scale = b.readUnsignedByte(), c = b.readInt(), bg = b.readInt();
                byte[] bytes = new byte[b.readUnsignedShort()];
                b.readBytes(bytes);
                drawText(x, y, Math.max(1, scale), c, bg, new String(bytes, StandardCharsets.UTF_8));
            }
            case OP_IMAGE -> applyImage(b);
            case OP_COPY -> copy(b.readInt(), b.readInt(), b.readInt(), b.readInt(), b.readInt(), b.readInt());
            case OP_TERM -> {
                int x = b.readInt(), y = b.readInt(), k = b.readUnsignedByte();
                int n = b.readUnsignedShort();
                byte[] chars = new byte[n];
                byte[] colors = new byte[n];
                b.readBytes(chars);
                b.readBytes(colors);
                int[] palette = new int[16];
                for (int i = 0; i < 16; i++) palette[i] = b.readInt();
                MonitorFont font = MonitorFont.get();
                for (int i = 0; i < n; i++) {
                    drawTermCell(font, x + i * 8 * k, y, k, chars[i] & 255, palette[(colors[i] >> 4) & 15], palette[colors[i] & 15]);
                }
            }
            case OP_CLIP -> {
                int x0 = b.readInt(), y0 = b.readInt(), x1 = b.readInt(), y1 = b.readInt();
                clipX0 = Math.max(0, Math.min(width, x0));
                clipY0 = Math.max(0, Math.min(height, y0));
                clipX1 = Math.max(clipX0, Math.min(width, x1));
                clipY1 = Math.max(clipY0, Math.min(height, y1));
            }
            case OP_BLEND -> blend = b.readBoolean();
            case OP_AA -> antialias = b.readBoolean();
            case OP_CURSOR -> {
                cursorX = b.readInt();
                cursorY = b.readInt();
                cursorW = b.readInt();
                cursorH = b.readInt();
                cursorColor = b.readInt();
                cursorVisible = b.readBoolean();
            }
            case OP_RAW -> {
                int x = b.readInt(), y = b.readInt(), w = b.readInt(), h = b.readInt();
                for (int yy = y; yy < y + h; yy++) {
                    for (int xx = x; xx < x + w; xx++) {
                        int c = b.readInt();
                        if (xx >= 0 && yy >= 0 && xx < width && yy < height) pixels[yy * width + xx] = c;
                    }
                }
                markDirty(x, y, x + w, y + h);
            }
            default -> throw new IllegalStateException("unknown monitor op " + op);
        }
    }

    private void applyImage(ByteBuf b) {
        int x = b.readInt(), y = b.readInt(), w = b.readUnsignedShort(), h = b.readUnsignedShort();
        int dw = b.readUnsignedShort(), dh = b.readUnsignedShort();
        int format = b.readUnsignedByte();
        int[] src = new int[w * h];
        if (format == 0) {
            for (int i = 0; i < src.length; i++) src[i] = b.readInt();
        } else {
            int[] palette = new int[b.readUnsignedShort()];
            for (int i = 0; i < palette.length; i++) palette[i] = b.readInt();
            for (int i = 0; i < src.length; i++) {
                int idx = b.readUnsignedByte();
                src[i] = idx < palette.length ? palette[idx] : 0;
            }
        }
        if (w == 0 || h == 0 || dw == 0 || dh == 0) return;
        int x0 = Math.max(x, clipX0), y0 = Math.max(y, clipY0);
        int x1 = Math.min(x + dw, clipX1), y1 = Math.min(y + dh, clipY1);
        for (int yy = y0; yy < y1; yy++) {
            int sy = (int) ((long) (yy - y) * h / dh);
            for (int xx = x0; xx < x1; xx++) {
                int sx = (int) ((long) (xx - x) * w / dw);
                put(xx, yy, src[sy * w + sx]);
            }
        }
        markDirty(x0, y0, x1, y1);
    }

    private void copy(int sx, int sy, int w, int h, int dx, int dy) {
        // Clamp the source to the canvas, then copy through a temporary so overlapping moves work
        int x0 = Math.max(0, sx), y0 = Math.max(0, sy);
        int x1 = Math.min(width, sx + w), y1 = Math.min(height, sy + h);
        if (x0 >= x1 || y0 >= y1) return;
        int cw = x1 - x0, ch = y1 - y0;
        int[] tmp = new int[cw * ch];
        for (int yy = 0; yy < ch; yy++) System.arraycopy(pixels, (y0 + yy) * width + x0, tmp, yy * cw, cw);
        int ox = dx + (x0 - sx), oy = dy + (y0 - sy);
        for (int yy = 0; yy < ch; yy++) {
            int ty = oy + yy;
            if (ty < clipY0 || ty >= clipY1) continue;
            for (int xx = 0; xx < cw; xx++) {
                int tx = ox + xx;
                if (tx >= clipX0 && tx < clipX1) pixels[ty * width + tx] = tmp[yy * cw + xx];
            }
        }
        markDirty(ox, oy, ox + cw, oy + ch);
    }

    // ---- Encoders (the server builds operations with these, then applies the same bytes) ----

    public static void rect(ByteBuf b, int x, int y, int w, int h, int color, boolean replace) {
        b.writeByte(OP_RECT).writeInt(x).writeInt(y).writeInt(w).writeInt(h).writeInt(color).writeBoolean(replace);
    }

    public static void pixel(ByteBuf b, int x, int y, int color) {
        b.writeByte(OP_PIXEL).writeInt(x).writeInt(y).writeInt(color);
    }

    public static void line(ByteBuf b, double x1, double y1, double x2, double y2, double thickness, int color) {
        b.writeByte(OP_LINE).writeFloat((float) x1).writeFloat((float) y1).writeFloat((float) x2).writeFloat((float) y2)
            .writeFloat((float) thickness).writeInt(color);
    }

    /** thickness <= 0 fills. */
    public static void ellipse(ByteBuf b, double cx, double cy, double rx, double ry, double thickness, int color) {
        b.writeByte(OP_ELLIPSE).writeFloat((float) cx).writeFloat((float) cy).writeFloat((float) rx).writeFloat((float) ry)
            .writeFloat((float) thickness).writeInt(color);
    }

    /** thickness <= 0 fills. points = {x0, y0, x1, y1, ...}. */
    public static void polygon(ByteBuf b, double[] points, double thickness, int color) {
        b.writeByte(OP_POLYGON).writeShort(points.length / 2).writeFloat((float) thickness).writeInt(color);
        for (double p : points) b.writeFloat((float) p);
    }

    public static void text(ByteBuf b, int x, int y, int scale, int color, int background, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        b.writeByte(OP_TEXT).writeInt(x).writeInt(y).writeByte(scale).writeInt(color).writeInt(background)
            .writeShort(bytes.length).writeBytes(bytes);
    }

    public static void image(ByteBuf b, int x, int y, int w, int h, int dw, int dh, int[] argb) {
        b.writeByte(OP_IMAGE).writeInt(x).writeInt(y).writeShort(w).writeShort(h).writeShort(dw).writeShort(dh).writeByte(0);
        for (int c : argb) b.writeInt(c);
    }

    public static void indexedImage(ByteBuf b, int x, int y, int w, int h, int dw, int dh, int[] palette, byte[] indices) {
        b.writeByte(OP_IMAGE).writeInt(x).writeInt(y).writeShort(w).writeShort(h).writeShort(dw).writeShort(dh).writeByte(1);
        b.writeShort(palette.length);
        for (int c : palette) b.writeInt(c);
        b.writeBytes(indices);
    }

    public static void copy(ByteBuf b, int sx, int sy, int w, int h, int dx, int dy) {
        b.writeByte(OP_COPY).writeInt(sx).writeInt(sy).writeInt(w).writeInt(h).writeInt(dx).writeInt(dy);
    }

    public static void term(ByteBuf b, int x, int y, int k, byte[] chars, byte[] colors, int[] palette) {
        b.writeByte(OP_TERM).writeInt(x).writeInt(y).writeByte(k).writeShort(chars.length).writeBytes(chars).writeBytes(colors);
        for (int c : palette) b.writeInt(c);
    }

    public static void clip(ByteBuf b, int x0, int y0, int x1, int y1) {
        b.writeByte(OP_CLIP).writeInt(x0).writeInt(y0).writeInt(x1).writeInt(y1);
    }

    public static void blend(ByteBuf b, boolean on) {
        b.writeByte(OP_BLEND).writeBoolean(on);
    }

    public static void antialias(ByteBuf b, boolean on) {
        b.writeByte(OP_AA).writeBoolean(on);
    }

    public static void cursor(ByteBuf b, int x, int y, int w, int h, int color, boolean visible) {
        b.writeByte(OP_CURSOR).writeInt(x).writeInt(y).writeInt(w).writeInt(h).writeInt(color).writeBoolean(visible);
    }

    /** Writes the whole state as operations, split into raw bands of at most maxBytes each (snapshot for a new viewer). */
    public void snapshot(java.util.function.Consumer<ByteBuf> sink, int maxBytes) {
        int rowsPerBand = Math.max(1, maxBytes / Math.max(1, width * 4));
        for (int y = 0; y < height; y += rowsPerBand) {
            int h = Math.min(rowsPerBand, height - y);
            ByteBuf b = io.netty.buffer.Unpooled.buffer(17 + width * h * 4);
            b.writeByte(OP_RAW).writeInt(0).writeInt(y).writeInt(width).writeInt(h);
            for (int i = y * width; i < (y + h) * width; i++) b.writeInt(pixels[i]);
            sink.accept(b);
        }
        ByteBuf s = io.netty.buffer.Unpooled.buffer(64);
        clip(s, clipX0, clipY0, clipX1, clipY1);
        blend(s, blend);
        antialias(s, antialias);
        cursor(s, cursorX, cursorY, cursorW, cursorH, cursorColor, cursorVisible);
        sink.accept(s);
    }
}
