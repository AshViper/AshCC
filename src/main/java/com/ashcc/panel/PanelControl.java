package com.ashcc.panel;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.item.DyeColor;

import java.util.Locale;

/**
 * One control on a panel: what it is, the cells it covers (col/row of its top-left cell, width/height in cells of the panel's
 * grid), its first output channel (1..64) and colour, and its live state.
 */
public final class PanelControl {
    public enum Type {
        /** Momentary: 15 while pressed. */
        BUTTON(1, false),
        /** Flips between 0 and 15 on each click. */
        TOGGLE(1, false),
        /** Grabbed, W/S turn it in 16 steps (0..15). Stays where it is left. */
        KNOB(1, true),
        /** Grabbed, W/S slide it smoothly (0..15). Stays where it is left. */
        SLIDER(1, true),
        /**
         * Side stick. Grabbed: W/S/A/D tilt it, Space trigger, arrow keys the hat, X the second button. Springs back when let go.
         * Channels: forward, back, left, right, trigger, hat up, hat down, hat left, hat right, button 2.
         */
        STICK(10, true),
        /** Throttle lever: moves like a slider, plus two thumb buttons. Channels: throttle, button 1, button 2. */
        THROTTLE(3, true),
        /** Shows its part of the panel's canvas (drawn from CC): an MFD. No output. */
        SCREEN(0, false),
        /** A see-through glass standing up from the panel, showing its part of the canvas: a HUD. No output. */
        HUD(0, false);

        public final int channels;
        public final boolean grabbed;

        Type(int channels, boolean grabbed) {
            this.channels = channels;
            this.grabbed = grabbed;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Type byId(String id) {
            for (Type t : values()) if (t.id().equals(id)) return t;
            return BUTTON;
        }

        /** Shows the canvas instead of being operated. */
        public boolean display() {
            return this == SCREEN || this == HUD;
        }
    }

    /** Bits of a grabbed control's buttons (see {@link ControlPanelBlockEntity#input}). */
    public static final int TRIGGER = 1, BUTTON2 = 2, HAT_UP = 4, HAT_DOWN = 8, HAT_LEFT = 16, HAT_RIGHT = 32, THUMB1 = 64, THUMB2 = 128;

    public static final int MAX_CHANNEL = 64;

    public final Type type;
    public final int col, row, width, height;
    public final int channel;
    public final DyeColor color;

    // State
    /** Button pressed / toggle on. */
    public boolean on;
    /** Knob, slider and throttle 0..1; stick x (right +) and y (forward +) -1..1. */
    public double value, x, y;
    /** Grip buttons held (bits above). */
    public int buttons;
    /** Ticks a button stays pressed without another hold message. */
    public int pressTicks;

    public PanelControl(Type type, int col, int row, int width, int height, int channel, DyeColor color, int grid) {
        this.type = type;
        this.width = Mth.clamp(width, 1, grid);
        this.height = Mth.clamp(height, 1, grid);
        this.col = Mth.clamp(col, 0, grid - this.width);
        this.row = Mth.clamp(row, 0, grid - this.height);
        this.channel = Mth.clamp(channel, 1, MAX_CHANNEL - Math.max(1, type.channels) + 1);
        this.color = color;
    }

    /** The same control on a grid scaled by {@code factor} (2 = 4x4 to 8x8, 0.5 = back). Null if it no longer fits. */
    public PanelControl rescaled(double factor, int grid) {
        int c = (int) Math.floor(col * factor), r = (int) Math.floor(row * factor);
        int w = Math.max(1, (int) Math.round(width * factor)), h = Math.max(1, (int) Math.round(height * factor));
        PanelControl p = new PanelControl(type, c, r, w, h, channel, color, grid);
        p.on = on;
        p.value = value;
        return p;
    }

    public boolean covers(int c, int r) {
        return c >= col && c < col + width && r >= row && r < row + height;
    }

    public boolean overlaps(PanelControl o) {
        return col < o.col + o.width && o.col < col + width && row < o.row + o.height && o.row < row + height;
    }

    /** Output on each of this control's channels (0..15), in channel order. */
    public int[] outputs() {
        return switch (type) {
            case BUTTON, TOGGLE -> new int[]{on ? 15 : 0};
            case KNOB, SLIDER -> new int[]{(int) Math.round(value * 15)};
            case STICK -> new int[]{level(y), level(-y), level(-x), level(x), bit(TRIGGER),
                bit(HAT_UP), bit(HAT_DOWN), bit(HAT_LEFT), bit(HAT_RIGHT), bit(BUTTON2)};
            case THROTTLE -> new int[]{(int) Math.round(value * 15), bit(THUMB1), bit(THUMB2)};
            case SCREEN, HUD -> new int[0];
        };
    }

    private int bit(int b) {
        return (buttons & b) != 0 ? 15 : 0;
    }

    private static int level(double v) {
        return v <= 0 ? 0 : (int) Math.round(Math.min(1, v) * 15);
    }

    public CompoundTag save(boolean withState) {
        CompoundTag t = new CompoundTag();
        t.putString("Type", type.id());
        t.putByte("Col", (byte) col);
        t.putByte("Row", (byte) row);
        t.putByte("W", (byte) width);
        t.putByte("H", (byte) height);
        t.putByte("Channel", (byte) channel);
        t.putByte("Color", (byte) color.getId());
        if (withState) {
            t.putBoolean("On", on);
            t.putDouble("Value", value);
            t.putDouble("X", x);
            t.putDouble("Y", y);
            t.putInt("Buttons", buttons);
        }
        return t;
    }

    public static PanelControl load(CompoundTag t, int grid) {
        PanelControl c = new PanelControl(Type.byId(t.getString("Type")), t.getByte("Col"), t.getByte("Row"), t.getByte("W"), t.getByte("H"),
            t.getByte("Channel"), DyeColor.byId(t.getByte("Color")), grid);
        c.on = t.getBoolean("On");
        c.value = Mth.clamp(t.getDouble("Value"), 0, 1);
        c.x = Mth.clamp(t.getDouble("X"), -1, 1);
        c.y = Mth.clamp(t.getDouble("Y"), -1, 1);
        c.buttons = t.getInt("Buttons");
        return c;
    }
}
