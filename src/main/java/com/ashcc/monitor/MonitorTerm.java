package com.ashcc.monitor;

import dan200.computercraft.api.lua.LuaException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * CC's terminal on top of the pixel canvas, so the monitor works with term.redirect and existing programs.
 * Cells are 8x16 Unifont glyphs times k = textScale * 2 (scale 0.5 = 8x16 px, 1 = 16x32 px), centred on the screen.
 * Text goes into the same pixels as the drawing API. Unlike CC, palette changes only affect later drawing.
 */
final class MonitorTerm {
    static final int[] DEFAULT_PALETTE = {
        0xFFF0F0F0, 0xFFF2B233, 0xFFE57FD8, 0xFF99B2F2, 0xFFDEDE6C, 0xFF7FCC19, 0xFFF2B2CC, 0xFF4C4C4C,
        0xFF999999, 0xFF4C99B2, 0xFFB266E5, 0xFF3366CC, 0xFF7F664C, 0xFF57A64E, 0xFFCC4C4C, 0xFF111111,
    };

    private final MonitorScreen screen;
    private final int[] palette = DEFAULT_PALETTE.clone();
    private int k = 2;
    private int cursorX = 1, cursorY = 1;
    private boolean blink;
    private int fg = 0, bg = 15;
    private int sentCursor = Integer.MIN_VALUE;

    MonitorTerm(MonitorScreen screen) {
        this.screen = screen;
    }

    // ---- Layout ----

    int cellW() {
        return 8 * k;
    }

    int cellH() {
        return 16 * k;
    }

    int columns() {
        return Math.max(1, screen.pixelWidth / cellW());
    }

    int rows() {
        return Math.max(1, screen.pixelHeight / cellH());
    }

    int offsetX() {
        return (screen.pixelWidth - columns() * cellW()) / 2;
    }

    int offsetY() {
        return (screen.pixelHeight - rows() * cellH()) / 2;
    }

    /** Term cell (1-based) under a pixel, for monitor_touch. */
    int[] cellAt(int px, int py) {
        int cx = Math.floorDiv(px - offsetX(), cellW()) + 1;
        int cy = Math.floorDiv(py - offsetY(), cellH()) + 1;
        return new int[]{Math.max(1, Math.min(columns(), cx)), Math.max(1, Math.min(rows(), cy))};
    }

    double textScale() {
        return k / 2.0;
    }

    synchronized void setTextScale(double scale) throws LuaException {
        if (scale < 0.5 || scale > 5 || Math.abs(scale * 2 - Math.round(scale * 2)) > 1e-9) {
            throw new LuaException("Expected number in range 0.5-5 in steps of 0.5");
        }
        int nk = (int) Math.round(scale * 2);
        if (nk == k) return;
        k = nk;
        sendCursor();
        screen.queueEvent("monitor_resize");
    }

    // ---- Colours ----

    static int parseColour(int colour) throws LuaException {
        if (colour <= 0) throw new LuaException("Colour out of range");
        int bit = 31 - Integer.numberOfLeadingZeros(colour);
        if (bit > 15) throw new LuaException("Colour out of range");
        return bit;
    }

    synchronized int textColour() {
        return 1 << fg;
    }

    synchronized void setTextColour(int colour) throws LuaException {
        fg = parseColour(colour);
        sendCursor();
    }

    synchronized int backgroundColour() {
        return 1 << bg;
    }

    synchronized void setBackgroundColour(int colour) throws LuaException {
        bg = parseColour(colour);
    }

    synchronized double[] paletteColour(int colour) throws LuaException {
        int c = palette[parseColour(colour)];
        return new double[]{((c >> 16) & 255) / 255.0, ((c >> 8) & 255) / 255.0, (c & 255) / 255.0};
    }

    synchronized void setPaletteColour(int colour, double r, double g, double b) throws LuaException {
        int i = parseColour(colour);
        palette[i] = 0xFF000000 | (channel(r) << 16) | (channel(g) << 8) | channel(b);
        if (i == fg) sendCursor();
    }

    private static int channel(double v) {
        return (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
    }

    // ---- Cursor ----

    synchronized int[] cursor() {
        return new int[]{cursorX, cursorY};
    }

    synchronized void setCursor(int x, int y) throws LuaException {
        cursorX = x;
        cursorY = y;
        sendCursor();
    }

    synchronized boolean blink() {
        return blink;
    }

    synchronized void setBlink(boolean on) throws LuaException {
        blink = on;
        sendCursor();
    }

    /** Tells clients where to draw the blinking cursor (only when something changed). */
    private void sendCursor() throws LuaException {
        boolean visible = blink && cursorX >= 1 && cursorY >= 1 && cursorX <= columns() && cursorY <= rows();
        int key = visible ? (cursorX * 31 + cursorY) * 31 * 31 + k * 31 + fg + palette[fg] * 7 : -1;
        if (key == sentCursor) return;
        sentCursor = key;
        ByteBuf b = Unpooled.buffer(32);
        int x = offsetX() + (cursorX - 1) * cellW(), y = offsetY() + (cursorY - 1) * cellH();
        Canvas.cursor(b, x, y + cellH() - Math.max(2, k * 2), cellW(), Math.max(2, k * 2), palette[fg], visible);
        screen.submit(b);
    }

    // ---- Text ----

    /** write() with CC bytes in the current colours. */
    synchronized void write(byte[] text) throws LuaException {
        byte[] colours = new byte[text.length];
        java.util.Arrays.fill(colours, (byte) ((fg << 4) | bg));
        put(text, colours);
    }

    synchronized void blit(byte[] text, byte[] fgHex, byte[] bgHex) throws LuaException {
        if (fgHex.length != text.length || bgHex.length != text.length) throw new LuaException("Arguments must be the same length");
        byte[] colours = new byte[text.length];
        for (int i = 0; i < text.length; i++) colours[i] = (byte) ((hex(fgHex[i], fg) << 4) | hex(bgHex[i], bg));
        put(text, colours);
    }

    private static int hex(byte c, int fallback) {
        int v = Character.digit((char) (c & 255), 16);
        return v < 0 ? fallback : v;
    }

    private void put(byte[] text, byte[] colours) throws LuaException {
        int cols = columns();
        if (cursorY >= 1 && cursorY <= rows()) {
            int start = Math.max(0, 1 - cursorX);
            int end = Math.min(text.length, cols - cursorX + 1);
            if (end > start) {
                byte[] chars = java.util.Arrays.copyOfRange(text, start, end);
                byte[] cs = java.util.Arrays.copyOfRange(colours, start, end);
                ByteBuf b = Unpooled.buffer(32 + chars.length * 2 + 64);
                Canvas.term(b, offsetX() + (cursorX - 1 + start) * cellW(), offsetY() + (cursorY - 1) * cellH(), k, chars, cs, palette);
                screen.submit(b);
            }
        }
        cursorX += text.length;
        sendCursor();
    }

    synchronized void clear() throws LuaException {
        ByteBuf b = Unpooled.buffer(32);
        Canvas.rect(b, 0, 0, screen.pixelWidth, screen.pixelHeight, palette[bg], true);
        screen.submit(b);
    }

    synchronized void clearLine() throws LuaException {
        if (cursorY < 1 || cursorY > rows()) return;
        ByteBuf b = Unpooled.buffer(32);
        Canvas.rect(b, 0, offsetY() + (cursorY - 1) * cellH(), screen.pixelWidth, cellH(), palette[bg], true);
        screen.submit(b);
    }

    synchronized void scroll(int n) throws LuaException {
        if (n == 0) return;
        int top = offsetY(), h = rows() * cellH();
        int dy = -n * cellH();
        ByteBuf b = Unpooled.buffer(64);
        if (Math.abs(dy) < h) Canvas.copy(b, 0, top, screen.pixelWidth, h, 0, top + dy);
        int clearY = n > 0 ? top + Math.max(0, h + dy) : top;
        int clearH = Math.min(h, Math.abs(dy));
        Canvas.rect(b, 0, clearY, screen.pixelWidth, clearH, palette[bg], true);
        screen.submit(b);
    }
}
