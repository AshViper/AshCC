package com.ashcc.monitor;

import com.ashcc.AshCC;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

/**
 * GNU Unifont (OFL 1.1, see UNIFONT_LICENSE.txt) for the HD monitor: every glyph is 16 px high and 8 or 16 px wide.
 * Loaded from the mod jar on both sides (not through the resource manager) so the server can rasterize text exactly
 * like the client. File made by tools/gen_font.py.
 */
public final class MonitorFont {
    public static final int HEIGHT = 16;
    private static final int REPLACEMENT = 0xFFFD;

    private static volatile MonitorFont instance;

    private final int[] codePoints;
    private final int[] offsets;
    private final byte[] widths;
    private final byte[] data;

    private MonitorFont(int[] codePoints, int[] offsets, byte[] widths, byte[] data) {
        this.codePoints = codePoints;
        this.offsets = offsets;
        this.widths = widths;
        this.data = data;
    }

    public static MonitorFont get() {
        MonitorFont font = instance;
        if (font == null) {
            synchronized (MonitorFont.class) {
                if (instance == null) instance = load();
                font = instance;
            }
        }
        return font;
    }

    private static MonitorFont load() {
        try (InputStream raw = MonitorFont.class.getResourceAsStream("/com/ashcc/monitor/unifont.bin")) {
            if (raw == null) throw new IOException("unifont.bin missing from the jar");
            DataInputStream in = new DataInputStream(new GZIPInputStream(raw, 1 << 16));
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (!Arrays.equals(magic, new byte[]{'A', 'U', 'F', '1'})) throw new IOException("bad font header");
            int count = in.readInt();
            int[] cps = new int[count];
            int[] offs = new int[count];
            byte[] ws = new byte[count];
            byte[] buf = new byte[count * 32];
            int pos = 0;
            for (int i = 0; i < count; i++) {
                cps[i] = in.readInt();
                int w = in.readUnsignedByte();
                ws[i] = (byte) w;
                offs[i] = pos;
                int len = w / 8 * HEIGHT;
                in.readFully(buf, pos, len);
                pos += len;
            }
            return new MonitorFont(cps, offs, ws, Arrays.copyOf(buf, pos));
        } catch (IOException e) {
            AshCC.LOGGER.error("AshCC: could not load the monitor font", e);
            return new MonitorFont(new int[0], new int[0], new byte[0], new byte[0]);
        }
    }

    private int index(int cp) {
        int i = Arrays.binarySearch(codePoints, cp);
        if (i < 0) i = Arrays.binarySearch(codePoints, REPLACEMENT);
        return i;
    }

    /** Advance width in font pixels (8 or 16). */
    public int width(int cp) {
        if (cp == ' ') return 8;
        int i = index(cp);
        return i < 0 ? 8 : widths[i];
    }

    /** Whether the font pixel (x, y) of the glyph is set. */
    public boolean bit(int cp, int x, int y) {
        int i = index(cp);
        if (i < 0) return false;
        int bytesPerRow = widths[i] / 8;
        if (x < 0 || y < 0 || x >= widths[i] || y >= HEIGHT) return false;
        return (data[offsets[i] + y * bytesPerRow + x / 8] & (0x80 >> (x & 7))) != 0;
    }

    /** Width in font pixels of the widest line of the text (lines split at '\n'). */
    public int measure(String text) {
        int best = 0;
        int line = 0;
        for (int k = 0; k < text.length(); ) {
            int cp = text.codePointAt(k);
            k += Character.charCount(cp);
            if (cp == '\n') {
                best = Math.max(best, line);
                line = 0;
            } else {
                line += width(cp);
            }
        }
        return Math.max(best, line);
    }
}
