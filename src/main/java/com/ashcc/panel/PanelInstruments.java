package com.ashcc.panel;

import com.ashcc.AshCC;
import com.ashcc.compat.SableCompat;
import com.ashcc.monitor.Canvas;
import com.ashcc.monitor.MonitorFont;
import com.ashcc.monitor.MonitorScreen;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Built-in flight instruments for screens set to {@link PanelControl#MODE_INSTRUMENTS}: the panel draws an attitude
 * indicator plus heading, speed, altitude and vertical speed into its own canvas, so a cockpit shows the essentials
 * with no computer attached. The values describe the panel's own frame (the pilot sits facing the panel, so the
 * aircraft's forward is out of the panel's back); on a Sable sub-level they follow the ship, on the ground the panel
 * is simply still.
 */
public final class PanelInstruments {
    private PanelInstruments() {
    }

    private static final int SKY = 0xFF1B4F72;
    private static final int GROUND = 0xFF7A5230;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int SYMBOL = 0xFFFFCC33;
    private static final int TEXT = 0xFF69F0AE;
    private static final int STRIP = 0xFF05070A;
    private static final int BORDER = 0xFF445566;

    /** Redraws every screen of the panel that is in instruments mode (called every few ticks from the panel's tick). */
    public static void refresh(ControlPanelBlockEntity be) {
        Level level = be.getLevel();
        if (level == null || level.isClientSide) return;
        boolean any = be.controls().stream().anyMatch(c -> c.type == PanelControl.Type.SCREEN && c.mode == PanelControl.MODE_INSTRUMENTS);
        if (!any) return;
        MonitorScreen screen = be.screen();
        if (screen == null) return;

        // The panel's frame in the world: forward is where the pilot looking at the panel is facing.
        PanelFrame frame = PanelFrame.of(be.getBlockState(), 0);
        Vec3 f, u, r, vel;
        double alt;
        if (AshCC.sableLoaded) {
            f = SableCompat.dirToWorld(level, be.getBlockPos(), frame.normal().scale(-1));
            u = SableCompat.dirToWorld(level, be.getBlockPos(), frame.up());
            r = SableCompat.dirToWorld(level, be.getBlockPos(), frame.right());
            vel = SableCompat.velocityAt(level, be.getBlockPos());
            alt = SableCompat.worldCenter(level, be.getBlockPos()).y;
        } else {
            f = frame.normal().scale(-1);
            u = frame.up();
            r = frame.right();
            vel = Vec3.ZERO;
            alt = be.getBlockPos().getY() + 0.5;
        }
        double heading = (Math.toDegrees(Math.atan2(f.x, -f.z)) + 360) % 360;
        double pitch = Math.toDegrees(Math.asin(Mth.clamp(f.y, -1, 1)));
        double roll = Math.toDegrees(Math.atan2(-r.y, u.y));
        double speed = Math.hypot(vel.x, vel.z);
        double vs = vel.y;

        ByteBuf b = Unpooled.buffer(2048);
        for (PanelControl c : be.controls()) {
            if (c.type != PanelControl.Type.SCREEN || c.mode != PanelControl.MODE_INSTRUMENTS) continue;
            int[] rect = be.pixels(c);
            draw(b, rect[0], rect[1], rect[2], rect[3], heading, pitch, roll, speed, alt, vs);
        }
        Canvas.clip(b, 0, 0, ControlPanelBlockEntity.CANVAS, ControlPanelBlockEntity.CANVAS);
        screen.drawBuiltIn(b);
    }

    private static void draw(ByteBuf b, int x, int y, int w, int h,
                             double heading, double pitch, double roll, double speed, double alt, double vs) {
        Canvas.clip(b, x, y, x + w, y + h);
        Canvas.rect(b, x, y, w, h, SKY, true);

        double cx = x + w / 2.0, cy = y + h / 2.0;
        // With readout strips top and bottom (the large tier below), keep the attitude ball clear of them
        double rad = Math.min(w, h >= 96 && w >= 128 ? h - 36 : h) / 2.0 - 2;
        if (rad < 8) return;
        double pxPerDeg = rad / 30.0;
        double phi = Math.toRadians(roll);
        // Horizon direction on screen and the way "down to the ground" points on screen
        double hx = Math.cos(phi), hy = -Math.sin(phi);
        double gx = Math.sin(phi), gy = Math.cos(phi);
        double diag = w + h;
        // Horizon centre: sinks as the nose rises
        double ox = cx + gx * pitch * pxPerDeg, oy = cy + gy * pitch * pxPerDeg;

        Canvas.polygon(b, new double[]{
            ox - hx * diag, oy - hy * diag,
            ox + hx * diag, oy + hy * diag,
            ox + hx * diag + gx * diag, oy + hy * diag + gy * diag,
            ox - hx * diag + gx * diag, oy - hy * diag + gy * diag}, 0, GROUND);
        Canvas.line(b, ox - hx * diag, oy - hy * diag, ox + hx * diag, oy + hy * diag, 2, WHITE);

        // Pitch ladder at +-10 and +-20 degrees
        for (int mark : new int[]{-20, -10, 10, 20}) {
            double off = (pitch - mark) * pxPerDeg;
            if (Math.abs(off) > rad) continue;
            double lx = cx + gx * off, ly = cy + gy * off;
            double len = rad * (Math.abs(mark) == 10 ? 0.45 : 0.3);
            Canvas.line(b, lx - hx * len, ly - hy * len, lx + hx * len, ly + hy * len, 1, WHITE);
        }

        // Roll pointer against a fixed tick at the top
        Canvas.line(b, cx, cy - rad, cx, cy - rad + 6, 2, WHITE);
        double px = Math.sin(phi), py = -Math.cos(phi);
        double tipX = cx + px * (rad - 1), tipY = cy + py * (rad - 1);
        double baseX = cx + px * (rad - 10), baseY = cy + py * (rad - 10);
        double qx = Math.cos(phi), qy = Math.sin(phi);
        Canvas.polygon(b, new double[]{tipX, tipY, baseX + qx * 4, baseY + qy * 4, baseX - qx * 4, baseY - qy * 4}, 0, SYMBOL);

        // Fixed aircraft symbol
        double arm = rad * 0.55, inner = rad * 0.18;
        Canvas.line(b, cx - arm, cy, cx - inner, cy, 2.5, SYMBOL);
        Canvas.line(b, cx - inner, cy, cx - inner, cy + 4, 2.5, SYMBOL);
        Canvas.line(b, cx + inner, cy, cx + arm, cy, 2.5, SYMBOL);
        Canvas.line(b, cx + inner, cy, cx + inner, cy + 4, 2.5, SYMBOL);
        Canvas.rect(b, (int) cx - 1, (int) cy - 1, 3, 3, SYMBOL, true);

        // Border
        Canvas.line(b, x + 0.5, y + 0.5, x + w - 0.5, y + 0.5, 1, BORDER);
        Canvas.line(b, x + 0.5, y + h - 0.5, x + w - 0.5, y + h - 0.5, 1, BORDER);
        Canvas.line(b, x + 0.5, y + 0.5, x + 0.5, y + h - 0.5, 1, BORDER);
        Canvas.line(b, x + w - 0.5, y + 0.5, x + w - 0.5, y + h - 0.5, 1, BORDER);

        // Readouts
        MonitorFont font = MonitorFont.get();
        if (h >= 96 && w >= 128) {
            Canvas.rect(b, x, y, w, 18, STRIP, true);
            Canvas.rect(b, x, y + h - 18, w, 18, STRIP, true);
            text(b, font, x + 3, y + 1, String.format(Locale.ROOT, "HDG %03d", Math.round(heading) % 360));
            rightText(b, font, x + w - 3, y + 1, String.format(Locale.ROOT, "ALT %.0f", alt));
            text(b, font, x + 3, y + h - 17, String.format(Locale.ROOT, "SPD %.1f", speed));
            rightText(b, font, x + w - 3, y + h - 17, String.format(Locale.ROOT, "VS %+.1f", vs));
        } else if (h >= 56 && w >= 96) {
            Canvas.rect(b, x, y + h - 18, w, 18, STRIP, true);
            text(b, font, x + 3, y + h - 17, String.format(Locale.ROOT, "H%03d S%.0f", Math.round(heading) % 360, speed));
            rightText(b, font, x + w - 3, y + h - 17, String.format(Locale.ROOT, "A%.0f V%+.0f", alt, vs));
        } else if (w >= 64) {
            String s = String.format(Locale.ROOT, "%03d", Math.round(heading) % 360);
            text(b, font, (int) (cx - font.measure(s) / 2.0), y + 1, s);
        }
    }

    private static void text(ByteBuf b, MonitorFont font, int x, int y, String s) {
        Canvas.text(b, x, y, 1, TEXT, 0, s);
    }

    private static void rightText(ByteBuf b, MonitorFont font, int right, int y, String s) {
        Canvas.text(b, right - font.measure(s), y, 1, TEXT, 0, s);
    }
}
