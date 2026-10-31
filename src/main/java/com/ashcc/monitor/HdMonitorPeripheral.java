package com.ashcc.monitor;

import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lua API of the HD monitor (peripheral type "hd_monitor", also "monitor").
 * <p>
 * Pixel API: 128 px per block, 0-based pixels from the top-left, full 32-bit colour with alpha blending and
 * anti-aliasing. Colours: number 0xRRGGBB (opaque) or 0xAARRGGBB (when above 0xFFFFFF), string "#RGB"/"#RRGGBB"/
 * "#RRGGBBAA"/"transparent", or a table {r, g, b, [a]} of 0..255. Lines and circles take pixel-centre coordinates.
 * <p>
 * Term API: the same functions as a CC monitor (write, blit, setCursorPos, setTextScale, ...), so the monitor can be
 * passed to term.redirect / window.create. Text is drawn into the same pixels.
 * <p>
 * Events: monitor_touch(side, col, row), hd_touch / hd_drag / hd_release(side, x, y, button, player),
 * hd_scroll(side, x, y, direction, player), monitor_resize(side).
 * <p>
 * None of the drawing functions wait for the server tick; they are applied at once and sent to players every tick.
 * setAutoPresent(false) collects drawing until present(), so a frame never shows half-drawn.
 */
public class HdMonitorPeripheral implements IPeripheral {
    protected final MonitorScreen screen;

    protected HdMonitorPeripheral(MonitorScreen screen) {
        this.screen = screen;
    }

    @Override
    public String getType() {
        return "hd_monitor";
    }

    @Override
    public Set<String> getAdditionalTypes() {
        return Set.of("monitor");
    }

    @Override
    public void attach(IComputerAccess computer) {
        screen.computers.add(computer);
    }

    @Override
    public void detach(IComputerAccess computer) {
        screen.computers.remove(computer);
    }

    @Override
    public Object getTarget() {
        return screen.origin;
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof HdMonitorPeripheral o && o.screen == screen;
    }

    private void submit(ByteBuf b) throws LuaException {
        screen.submit(b);
    }

    private static ByteBuf buf() {
        return Unpooled.buffer(64);
    }

    // ---- Colours ----

    /** Parses a colour argument (see the class comment) into ARGB. */
    static int colour(Object v) throws LuaException {
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (!(d >= 0 && d <= 0xFFFFFFFFL) || d != Math.floor(d)) throw new LuaException("Bad colour " + v);
            long l = (long) d;
            return l <= 0xFFFFFFL ? (int) (0xFF000000L | l) : (int) l;
        }
        if (v instanceof String s) {
            String t = s.trim().toLowerCase();
            if (t.equals("transparent") || t.equals("none")) return 0;
            if (t.startsWith("#")) t = t.substring(1);
            try {
                switch (t.length()) {
                    case 3 -> {
                        int r = Integer.parseInt(t.substring(0, 1), 16), g = Integer.parseInt(t.substring(1, 2), 16), b = Integer.parseInt(t.substring(2, 3), 16);
                        return 0xFF000000 | (r * 17 << 16) | (g * 17 << 8) | b * 17;
                    }
                    case 6 -> {
                        return 0xFF000000 | Integer.parseInt(t, 16);
                    }
                    case 8 -> {
                        long rgba = Long.parseLong(t, 16);
                        return (int) (((rgba & 0xFF) << 24) | (rgba >>> 8));
                    }
                    default -> {
                    }
                }
            } catch (NumberFormatException ignored) {
                // falls through to the error
            }
            throw new LuaException("Bad colour \"" + s + "\"");
        }
        if (v instanceof Map<?, ?> m) {
            Object r = first(m, "r", 1.0), g = first(m, "g", 2.0), b = first(m, "b", 3.0), a = first(m, "a", 4.0);
            return (channel(a, 255) << 24) | (channel(r, 0) << 16) | (channel(g, 0) << 8) | channel(b, 0);
        }
        throw new LuaException("Bad colour (expected number, string or table)");
    }

    private static Object first(Map<?, ?> m, String name, double index) {
        Object v = m.get(name);
        return v != null ? v : m.get(index);
    }

    private static int channel(@Nullable Object v, int fallback) throws LuaException {
        if (v == null) return fallback;
        if (!(v instanceof Number n)) throw new LuaException("Colour channels must be numbers 0-255");
        return (int) Math.max(0, Math.min(255, Math.round(n.doubleValue())));
    }

    /** Lua view of a colour: 0xRRGGBB when opaque, else 0xAARRGGBB. */
    static double colourValue(int argb) {
        return (argb >>> 24) == 255 ? argb & 0xFFFFFF : argb & 0xFFFFFFFFL;
    }

    private static int colourArg(IArguments args, int i) throws LuaException {
        return colour(args.get(i));
    }

    private static int intArg(IArguments args, int i) throws LuaException {
        return (int) Math.floor(clampCoord(args.getFiniteDouble(i)));
    }

    private static double clampCoord(double v) {
        return Math.max(-1.0e6, Math.min(1.0e6, v));
    }

    /** rgba(r, g, b, [a]): a colour string for the other functions (0..255 each). */
    @LuaFunction
    public final String rgba(IArguments args) throws LuaException {
        int r = channel(args.getFiniteDouble(0), 0), g = channel(args.getFiniteDouble(1), 0), b = channel(args.getFiniteDouble(2), 0);
        int a = channel(args.optFiniteDouble(3).orElse(255.0), 255);
        return String.format("#%02x%02x%02x%02x", r, g, b, a);
    }

    // ---- Screen info ----

    /** Size of the screen in pixels: width, height. */
    @LuaFunction
    public final Object[] getResolution() {
        return new Object[]{screen.pixelWidth, screen.pixelHeight};
    }

    /** Size of the monitor in blocks: width, height. */
    @LuaFunction
    public final Object[] getBlockSize() {
        return new Object[]{screen.blocksWide, screen.blocksHigh};
    }

    @LuaFunction
    public final int getPixelsPerBlock() {
        return screen.pixelWidth / Math.max(1, screen.blocksWide);
    }

    // ---- Presentation ----

    /** setAutoPresent(false): drawing is collected until present() (no tearing). true sends the pending frame. */
    @LuaFunction
    public final void setAutoPresent(boolean on) throws LuaException {
        screen.setAutoPresent(on);
    }

    @LuaFunction
    public final boolean getAutoPresent() {
        return screen.autoPresent();
    }

    /** Shows everything drawn since the last present() (only needed with auto-present off). */
    @LuaFunction
    public final void present() throws LuaException {
        screen.present();
    }

    /** Hides the casing so transparent pixels show the world behind; the monitor then has no frame. */
    @LuaFunction(mainThread = true)
    public final void setHologram(boolean on) throws LuaException {
        screen.checkValid();
        screen.origin.setHologram(on);
    }

    @LuaFunction(mainThread = true)
    public final boolean isHologram() {
        return screen.origin.hologram();
    }

    /** Full brightness (default) or lit like the world. */
    @LuaFunction(mainThread = true)
    public final void setGlow(boolean on) throws LuaException {
        screen.checkValid();
        screen.origin.setGlow(on);
    }

    @LuaFunction(mainThread = true)
    public final boolean isGlowing() {
        return screen.origin.glow();
    }

    // ---- Drawing state ----

    /** Restricts drawing to a rectangle (x, y, width, height). */
    @LuaFunction
    public final void setClip(IArguments args) throws LuaException {
        int x = intArg(args, 0), y = intArg(args, 1), w = intArg(args, 2), h = intArg(args, 3);
        ByteBuf b = buf();
        Canvas.clip(b, x, y, x + Math.max(0, w), y + Math.max(0, h));
        submit(b);
    }

    @LuaFunction
    public final void resetClip() throws LuaException {
        ByteBuf b = buf();
        Canvas.clip(b, 0, 0, screen.pixelWidth, screen.pixelHeight);
        submit(b);
    }

    /** true (default): translucent colours mix with what is below. false: pixels are replaced, alpha included. */
    @LuaFunction
    public final void setBlend(boolean on) throws LuaException {
        ByteBuf b = buf();
        Canvas.blend(b, on);
        submit(b);
    }

    @LuaFunction
    public final boolean getBlend() {
        return screen.blend();
    }

    /** Smooth edges for lines, circles and polygons (default on). */
    @LuaFunction
    public final void setAntialias(boolean on) throws LuaException {
        ByteBuf b = buf();
        Canvas.antialias(b, on);
        submit(b);
    }

    @LuaFunction
    public final boolean getAntialias() {
        return screen.antialias();
    }

    // ---- Shapes ----

    /** fill(colour): fills the whole screen (inside the clip), replacing what was there. */
    @LuaFunction
    public final void fill(IArguments args) throws LuaException {
        ByteBuf b = buf();
        Canvas.rect(b, 0, 0, screen.pixelWidth, screen.pixelHeight, colourArg(args, 0), true);
        submit(b);
    }

    @LuaFunction
    public final void setPixel(IArguments args) throws LuaException {
        ByteBuf b = buf();
        Canvas.pixel(b, intArg(args, 0), intArg(args, 1), colourArg(args, 2));
        submit(b);
    }

    /** getPixel(x, y): colour of a pixel as shown (0xRRGGBB, or 0xAARRGGBB if translucent). */
    @LuaFunction
    public final double getPixel(int x, int y) {
        return colourValue(screen.getPixel(x, y));
    }

    @LuaFunction
    public final void fillRect(IArguments args) throws LuaException {
        ByteBuf b = buf();
        Canvas.rect(b, intArg(args, 0), intArg(args, 1), intArg(args, 2), intArg(args, 3), colourArg(args, 4), false);
        submit(b);
    }

    /** drawRect(x, y, width, height, colour, [thickness]): outline inside the rectangle. */
    @LuaFunction
    public final void drawRect(IArguments args) throws LuaException {
        int x = intArg(args, 0), y = intArg(args, 1), w = intArg(args, 2), h = intArg(args, 3);
        int c = colourArg(args, 4);
        int t = Math.max(1, (int) Math.round(args.optFiniteDouble(5).orElse(1.0)));
        if (w <= 0 || h <= 0) return;
        ByteBuf b = buf();
        if (t * 2 >= w || t * 2 >= h) {
            Canvas.rect(b, x, y, w, h, c, false);
        } else {
            Canvas.rect(b, x, y, w, t, c, false);
            Canvas.rect(b, x, y + h - t, w, t, c, false);
            Canvas.rect(b, x, y + t, t, h - 2 * t, c, false);
            Canvas.rect(b, x + w - t, y + t, t, h - 2 * t, c, false);
        }
        submit(b);
    }

    /** drawLine(x1, y1, x2, y2, colour, [thickness]). */
    @LuaFunction
    public final void drawLine(IArguments args) throws LuaException {
        ByteBuf b = buf();
        Canvas.line(b, coord(args, 0), coord(args, 1), coord(args, 2), coord(args, 3), thickness(args, 5), colourArg(args, 4));
        submit(b);
    }

    private static double coord(IArguments args, int i) throws LuaException {
        return clampCoord(args.getFiniteDouble(i));
    }

    private static double thickness(IArguments args, int i) throws LuaException {
        double t = args.optFiniteDouble(i).orElse(1.0);
        if (t <= 0) throw new LuaException("Thickness must be positive");
        return Math.min(t, 4096);
    }

    private static double radius(IArguments args, int i) throws LuaException {
        double r = args.getFiniteDouble(i);
        if (r < 0) throw new LuaException("Radius must not be negative");
        return Math.min(r, 1.0e5);
    }

    /** drawCircle(cx, cy, r, colour, [thickness]). */
    @LuaFunction
    public final void drawCircle(IArguments args) throws LuaException {
        double r = radius(args, 2);
        ByteBuf b = buf();
        Canvas.ellipse(b, coord(args, 0), coord(args, 1), r, r, thickness(args, 4), colourArg(args, 3));
        submit(b);
    }

    @LuaFunction
    public final void fillCircle(IArguments args) throws LuaException {
        double r = radius(args, 2);
        ByteBuf b = buf();
        Canvas.ellipse(b, coord(args, 0), coord(args, 1), r, r, 0, colourArg(args, 3));
        submit(b);
    }

    /** drawEllipse(cx, cy, rx, ry, colour, [thickness]). */
    @LuaFunction
    public final void drawEllipse(IArguments args) throws LuaException {
        ByteBuf b = buf();
        Canvas.ellipse(b, coord(args, 0), coord(args, 1), radius(args, 2), radius(args, 3), thickness(args, 5), colourArg(args, 4));
        submit(b);
    }

    @LuaFunction
    public final void fillEllipse(IArguments args) throws LuaException {
        ByteBuf b = buf();
        Canvas.ellipse(b, coord(args, 0), coord(args, 1), radius(args, 2), radius(args, 3), 0, colourArg(args, 4));
        submit(b);
    }

    /** Points as {x1, y1, x2, y2, ...} or {{x1, y1}, {x2, y2}, ...}. */
    private static double[] points(Map<?, ?> table) throws LuaException {
        List<Double> out = new ArrayList<>();
        for (int i = 1; ; i++) {
            Object v = table.get((double) i);
            if (v == null) break;
            if (v instanceof Number n) {
                out.add(n.doubleValue());
            } else if (v instanceof Map<?, ?> p) {
                Object x = first(p, "x", 1.0), y = first(p, "y", 2.0);
                if (!(x instanceof Number nx) || !(y instanceof Number ny)) throw new LuaException("Bad point " + i);
                out.add(nx.doubleValue());
                out.add(ny.doubleValue());
            } else {
                throw new LuaException("Bad point " + i);
            }
            if (out.size() > Canvas.MAX_POLYGON_POINTS * 2) throw new LuaException("Too many points (max " + Canvas.MAX_POLYGON_POINTS + ")");
        }
        if (out.size() % 2 != 0) throw new LuaException("Points need both x and y");
        double[] pts = new double[out.size()];
        for (int i = 0; i < pts.length; i++) {
            double d = out.get(i);
            if (!Double.isFinite(d)) throw new LuaException("Points must be finite");
            pts[i] = clampCoord(d);
        }
        return pts;
    }

    /** drawPolygon(points, colour, [thickness]): closed outline. */
    @LuaFunction
    public final void drawPolygon(IArguments args) throws LuaException {
        double[] pts = points(args.getTable(0));
        if (pts.length < 4) return;
        ByteBuf b = buf();
        Canvas.polygon(b, pts, thickness(args, 2), colourArg(args, 1));
        submit(b);
    }

    /** fillPolygon(points, colour): points are pixel corners (non-zero winding). */
    @LuaFunction
    public final void fillPolygon(IArguments args) throws LuaException {
        double[] pts = points(args.getTable(0));
        if (pts.length < 6) return;
        ByteBuf b = buf();
        Canvas.polygon(b, pts, 0, colourArg(args, 1));
        submit(b);
    }

    /** copyRect(x, y, width, height, toX, toY): moves pixels (e.g. scrolling). */
    @LuaFunction
    public final void copyRect(IArguments args) throws LuaException {
        ByteBuf b = buf();
        Canvas.copy(b, intArg(args, 0), intArg(args, 1), intArg(args, 2), intArg(args, 3), intArg(args, 4), intArg(args, 5));
        submit(b);
    }

    // ---- Text ----

    private static String utf8(IArguments args, int i) throws LuaException {
        ByteBuffer bytes = args.getBytesCoerced(i);
        byte[] arr = new byte[bytes.remaining()];
        bytes.get(arr);
        String s = new String(arr, StandardCharsets.UTF_8);
        if (s.codePointCount(0, s.length()) > Canvas.MAX_TEXT) throw new LuaException("Text too long (max " + Canvas.MAX_TEXT + " characters)");
        return s;
    }

    private static int scaleOpt(Map<?, ?> opts) throws LuaException {
        Object s = opts.get("scale");
        if (s == null) return 1;
        if (!(s instanceof Number n) || n.doubleValue() < 1 || n.doubleValue() > 64) throw new LuaException("scale must be 1-64");
        return (int) Math.round(n.doubleValue());
    }

    /**
     * drawText(x, y, text, colour, [opts]): UTF-8 text (Japanese and most of Unicode) with a 16 px high font.
     * opts = {scale = 1.., bg = colour, align = "left"|"center"|"right"}. x is the left, centre or right edge.
     * Returns the width and height drawn.
     */
    @LuaFunction
    public final Object[] drawText(IArguments args) throws LuaException {
        int x = intArg(args, 0), y = intArg(args, 1);
        String text = utf8(args, 2);
        int c = colourArg(args, 3);
        Map<?, ?> opts = args.optTable(4).orElse(Map.of());
        int scale = scaleOpt(opts);
        int bg = opts.get("bg") == null ? 0 : colour(opts.get("bg"));
        String align = opts.get("align") instanceof String a ? a : "left";
        MonitorFont font = MonitorFont.get();
        String[] lines = text.split("\n", -1);
        ByteBuf b = buf();
        int lineH = MonitorFont.HEIGHT * scale;
        int maxW = 0;
        for (int i = 0; i < lines.length; i++) {
            int w = font.measure(lines[i]) * scale;
            maxW = Math.max(maxW, w);
            int lx = switch (align) {
                case "center", "centre" -> x - w / 2;
                case "right" -> x - w;
                case "left" -> x;
                default -> throw new LuaException("align must be left, center or right");
            };
            Canvas.text(b, lx, y + i * lineH, scale, c, bg, lines[i]);
        }
        submit(b);
        return new Object[]{maxW, lines.length * lineH};
    }

    /** measureText(text, [scale]): width and height in pixels. */
    @LuaFunction
    public final Object[] measureText(IArguments args) throws LuaException {
        String text = utf8(args, 0);
        int scale = scaleOpt(Map.of("scale", args.optFiniteDouble(1).orElse(1.0)));
        int lines = text.split("\n", -1).length;
        return new Object[]{MonitorFont.get().measure(text) * scale, lines * MonitorFont.HEIGHT * scale};
    }

    // ---- Images ----

    /**
     * drawImage(x, y, width, height, data, [opts]): data is a table of colours (row by row) or a string of bytes.
     * opts = {format = "rgba"|"rgb"|"argb"|"index", palette = {colour, ...} (for "index", 0-based indices;
     * indices past the palette are transparent), scaleWidth, scaleHeight (drawn size, nearest neighbour)}.
     */
    @LuaFunction
    public final void drawImage(IArguments args) throws LuaException {
        int x = intArg(args, 0), y = intArg(args, 1);
        int w = args.getInt(2), h = args.getInt(3);
        if (w <= 0 || h <= 0 || w > 4096 || h > 4096) throw new LuaException("Image size must be 1-4096");
        Map<?, ?> opts = args.optTable(5).orElse(Map.of());
        int dw = optSize(opts, "scaleWidth", w), dh = optSize(opts, "scaleHeight", h);
        int n = w * h;
        ByteBuf b = Unpooled.buffer(32 + n * 4);
        Object data = args.get(4);
        if (data instanceof Map<?, ?> table) {
            int[] argb = new int[n];
            for (int i = 0; i < n; i++) {
                Object v = table.get((double) (i + 1));
                argb[i] = v == null ? 0 : colour(v);
            }
            Canvas.image(b, x, y, w, h, dw, dh, argb);
        } else {
            ByteBuffer bytes = args.getBytes(4);
            String format = opts.get("format") instanceof String f ? f : "rgba";
            if (format.equals("index")) {
                if (!(opts.get("palette") instanceof Map<?, ?> pal)) throw new LuaException("format \"index\" needs a palette");
                List<Integer> colours = new ArrayList<>();
                for (int i = 1; i <= 256 && pal.get((double) i) != null; i++) colours.add(colour(pal.get((double) i)));
                if (bytes.remaining() < n) throw new LuaException("Expected " + n + " bytes of image data");
                byte[] idx = new byte[n];
                bytes.get(idx);
                int[] palette = colours.stream().mapToInt(Integer::intValue).toArray();
                Canvas.indexedImage(b, x, y, w, h, dw, dh, palette, idx);
            } else {
                int stride = switch (format) {
                    case "rgba", "argb" -> 4;
                    case "rgb" -> 3;
                    default -> throw new LuaException("format must be rgba, argb, rgb or index");
                };
                if (bytes.remaining() < n * stride) throw new LuaException("Expected " + n * stride + " bytes of image data");
                int[] argb = new int[n];
                for (int i = 0; i < n; i++) {
                    int c0 = bytes.get() & 255, c1 = bytes.get() & 255, c2 = bytes.get() & 255;
                    argb[i] = switch (format) {
                        case "rgb" -> 0xFF000000 | (c0 << 16) | (c1 << 8) | c2;
                        case "argb" -> (c0 << 24) | (c1 << 16) | (c2 << 8) | (bytes.get() & 255);
                        default -> ((bytes.get() & 255) << 24) | (c0 << 16) | (c1 << 8) | c2;
                    };
                }
                Canvas.image(b, x, y, w, h, dw, dh, argb);
            }
        }
        submit(b);
    }

    private static int optSize(Map<?, ?> opts, String key, int fallback) throws LuaException {
        Object v = opts.get(key);
        if (v == null) return fallback;
        if (!(v instanceof Number n) || n.doubleValue() < 1 || n.doubleValue() > 16384) throw new LuaException(key + " must be 1-16384");
        return (int) Math.round(n.doubleValue());
    }

    // ---- Term (CC monitor compatible) ----

    private static byte[] ccBytes(IArguments args, int i) throws LuaException {
        ByteBuffer bytes = args.getBytesCoerced(i);
        byte[] arr = new byte[bytes.remaining()];
        bytes.get(arr);
        return arr;
    }

    @LuaFunction
    public final void write(IArguments args) throws LuaException {
        screen.term.write(ccBytes(args, 0));
    }

    @LuaFunction
    public final void blit(IArguments args) throws LuaException {
        screen.term.blit(ccBytes(args, 0), ccBytes(args, 1), ccBytes(args, 2));
    }

    @LuaFunction
    public final void scroll(int n) throws LuaException {
        screen.term.scroll(n);
    }

    @LuaFunction
    public final Object[] getCursorPos() {
        int[] c = screen.term.cursor();
        return new Object[]{c[0], c[1]};
    }

    @LuaFunction
    public final void setCursorPos(int x, int y) throws LuaException {
        screen.term.setCursor(x, y);
    }

    @LuaFunction
    public final boolean getCursorBlink() {
        return screen.term.blink();
    }

    @LuaFunction
    public final void setCursorBlink(boolean on) throws LuaException {
        screen.term.setBlink(on);
    }

    /** Term size in characters (columns, rows) at the current text scale. */
    @LuaFunction
    public final Object[] getSize() {
        return new Object[]{screen.term.columns(), screen.term.rows()};
    }

    @LuaFunction
    public final void clear() throws LuaException {
        screen.term.clear();
    }

    @LuaFunction
    public final void clearLine() throws LuaException {
        screen.term.clearLine();
    }

    @LuaFunction({"getTextColour", "getTextColor"})
    public final int getTextColour() {
        return screen.term.textColour();
    }

    @LuaFunction({"setTextColour", "setTextColor"})
    public final void setTextColour(int colour) throws LuaException {
        screen.term.setTextColour(colour);
    }

    @LuaFunction({"getBackgroundColour", "getBackgroundColor"})
    public final int getBackgroundColour() {
        return screen.term.backgroundColour();
    }

    @LuaFunction({"setBackgroundColour", "setBackgroundColor"})
    public final void setBackgroundColour(int colour) throws LuaException {
        screen.term.setBackgroundColour(colour);
    }

    @LuaFunction({"isColour", "isColor"})
    public final boolean isColour() {
        return true;
    }

    @LuaFunction({"getPaletteColour", "getPaletteColor"})
    public final Object[] getPaletteColour(int colour) throws LuaException {
        double[] c = screen.term.paletteColour(colour);
        return new Object[]{c[0], c[1], c[2]};
    }

    /** setPaletteColour(colour, r, g, b) with 0..1 channels, or (colour, 0xRRGGBB). */
    @LuaFunction({"setPaletteColour", "setPaletteColor"})
    public final void setPaletteColour(IArguments args) throws LuaException {
        int colour = args.getInt(0);
        if (args.count() == 2) {
            int rgb = args.getInt(1);
            screen.term.setPaletteColour(colour, ((rgb >> 16) & 255) / 255.0, ((rgb >> 8) & 255) / 255.0, (rgb & 255) / 255.0);
        } else {
            screen.term.setPaletteColour(colour, args.getFiniteDouble(1), args.getFiniteDouble(2), args.getFiniteDouble(3));
        }
    }

    /** setTextScale(0.5 .. 5): 0.5 = 8x16 px characters, 1 = 16x32 px. */
    @LuaFunction
    public final void setTextScale(double scale) throws LuaException {
        screen.term.setTextScale(scale);
    }

    @LuaFunction
    public final double getTextScale() {
        return screen.term.textScale();
    }

    /** Everything at once: resolution, blocks, text size, settings. */
    @LuaFunction(mainThread = true)
    public final MethodResult getInfo() {
        return MethodResult.of(Map.of(
            "width", screen.pixelWidth, "height", screen.pixelHeight,
            "blocksWide", screen.blocksWide, "blocksHigh", screen.blocksHigh,
            "columns", screen.term.columns(), "rows", screen.term.rows(), "textScale", screen.term.textScale(),
            "hologram", isHologram(), "glow", isGlowing(), "autoPresent", screen.autoPresent()));
    }
}
