package com.ashcc.panel;

import com.ashcc.AshCC;
import com.ashcc.AshCCConfig;
import com.ashcc.compat.SableCompat;
import com.ashcc.decoy.DecoyEntity;
import com.ashcc.decoy.DecoyLauncherBlockEntity;
import com.ashcc.laser.LaserBlockEntity;
import com.ashcc.monitor.Canvas;
import com.ashcc.monitor.MonitorFont;
import com.ashcc.monitor.MonitorScreen;
import com.ashcc.radar.Contact;
import com.ashcc.radar.RadarFrame;
import com.ashcc.radar.RadarScanner;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * The panel's other built-in screens besides the flight instruments: a B-scope radar, a top view of the ship the
 * panel is mounted on, and a weapons status list. All three are drawn by the panel itself into its canvas, like
 * {@link PanelInstruments}; none of them needs a computer. Scans are cached per panel (radar about once a second,
 * the ship plan and weapon list about every two seconds) because they walk entities or the ship's blocks.
 */
public final class PanelPages {
    private PanelPages() {
    }

    private static final int BG = 0xFF04070A;
    private static final int GRID = 0xFF1D3A2A;
    private static final int TEXT = 0xFF69F0AE;
    private static final int DIM = 0xFF3E6B4F;
    private static final int SHIP_DOT = 0xFF4DD0E1;
    private static final int PLAYER_DOT = 0xFFFFEB3B;
    private static final int ENTITY_DOT = 0xFF69F0AE;
    private static final int OUTLINE = 0xFF2EFF6A;
    private static final int MARKER = 0xFFFFCC33;
    private static final int COM = 0xFFFF5252;
    private static final int WARN = 0xFFFF5252;
    private static final int LABEL = 0xFFD7E3EA;

    /** A weapon found by the ship scan. Laser/decoy keep their block entity for live status; a cannon mount keeps only its kind. */
    private record Weapon(@Nullable BlockEntity be, @Nullable String mountKind, BlockPos pos) {
    }

    private record ShipCache(long tick, @Nullable SableCompat.ShipPlan plan, List<Weapon> weapons, boolean onShip) {
    }

    private static final Map<ControlPanelBlockEntity, ShipCache> SHIP = new WeakHashMap<>();

    /** Refreshes whichever of the radar / ship / weapons screens the panel has (called every few ticks). */
    public static void refresh(ControlPanelBlockEntity be) {
        if (!(be.getLevel() instanceof ServerLevel server)) return;
        boolean radar = false, ship = false, weapons = false;
        for (PanelControl c : be.controls()) {
            if (c.type != PanelControl.Type.SCREEN) continue;
            if (c.mode == PanelControl.MODE_RADAR) radar = true;
            else if (c.mode == PanelControl.MODE_SHIP) ship = true;
            else if (c.mode == PanelControl.MODE_WEAPONS) weapons = true;
        }
        if (!radar && !ship && !weapons) return;
        MonitorScreen screen = be.screen();
        if (screen == null) return;
        long now = server.getGameTime();
        ByteBuf b = Unpooled.buffer(1 << 18);
        boolean drew = false;

        if (radar && now % 20 == 0) {
            RadarFrame frame = frame(be, server);
            int range = AshCCConfig.RADAR_DEFAULT_RANGE.get();
            List<Contact> contacts = new ArrayList<>();
            contacts.addAll(RadarScanner.scanEntities(server, frame, range, e -> true, 48));
            contacts.addAll(RadarScanner.scanSubLevels(server, frame, range, 48));
            for (PanelControl c : be.controls()) {
                if (c.type == PanelControl.Type.SCREEN && c.mode == PanelControl.MODE_RADAR) {
                    int[] r = be.pixels(c);
                    drawRadar(b, r[0], r[1], r[2], r[3], frame, contacts, range);
                }
            }
            drew = true;
        }

        if (ship || weapons) {
            ShipCache cache = SHIP.get(be);
            boolean rescanned = false;
            if (cache == null || now - cache.tick() >= 40) {
                cache = scanShip(be, now);
                SHIP.put(be, cache);
                rescanned = true;
            }
            for (PanelControl c : be.controls()) {
                if (c.type != PanelControl.Type.SCREEN) continue;
                int[] r = be.pixels(c);
                if (c.mode == PanelControl.MODE_WEAPONS) {
                    drawWeapons(b, r[0], r[1], r[2], r[3], cache);
                    drew = true;
                } else if (c.mode == PanelControl.MODE_SHIP && cache != null) {
                    drawShip(b, be, r[0], r[1], r[2], r[3], cache);
                    drew = true;
                }
            }
        }

        if (drew) {
            Canvas.clip(b, 0, 0, ControlPanelBlockEntity.CANVAS, ControlPanelBlockEntity.CANVAS);
            screen.drawBuiltIn(b);
        }
    }

    // ---- Shared frame ----

    /** The panel's frame in the world, aircraft-style: forward is where a pilot facing the panel is looking. */
    private static RadarFrame frame(ControlPanelBlockEntity be, Level level) {
        PanelFrame pf = PanelFrame.of(be.getBlockState(), 0);
        if (AshCC.sableLoaded) {
            BlockPos pos = be.getBlockPos();
            return new RadarFrame(SableCompat.worldCenter(level, pos),
                SableCompat.dirToWorld(level, pos, pf.right()),
                SableCompat.dirToWorld(level, pos, pf.up()),
                SableCompat.dirToWorld(level, pos, pf.normal().scale(-1)),
                SableCompat.subLevelAt(level, pos));
        }
        return new RadarFrame(Vec3.atCenterOf(be.getBlockPos()), pf.right(), pf.up(), pf.normal().scale(-1), null);
    }

    // ---- Radar (B-scope) ----

    private static void drawRadar(ByteBuf b, int x, int y, int w, int h, RadarFrame frame, List<Contact> contacts, int range) {
        Canvas.clip(b, x, y, x + w, y + h);
        Canvas.rect(b, x, y, w, h, BG, true);
        double cx = x + w / 2.0;
        double top = y + 20, bottom = y + h - 6;
        // Grid: azimuth lines every 25 degrees out to +-75, range lines every quarter
        for (int az = -50; az <= 50; az += 25) {
            double gx = cx + az / 75.0 * (w / 2.0 - 3);
            Canvas.line(b, gx, top, gx, bottom, 1, GRID);
        }
        for (int q = 1; q <= 3; q++) {
            double gy = bottom - q / 4.0 * (bottom - top);
            Canvas.line(b, x + 2, gy, x + w - 2, gy, 1, GRID);
        }
        Canvas.line(b, cx, top, cx, bottom, 1, DIM);
        Canvas.line(b, x + 2, bottom, x + w - 2, bottom, 1, DIM);

        UUID own = frame.subLevel();
        int shown = 0;
        for (Contact c : contacts) {
            Object sub = c.extra().get("subLevel");
            if (own != null && sub != null && own.toString().equals(sub)) continue;
            Vec3 local = frame.toLocal(c.position());
            double az = Math.toDegrees(Math.atan2(local.x, local.z));
            if (Math.abs(az) > 75) continue;
            double frac = Math.min(1, c.distance() / range);
            int sx = (int) (cx + az / 75.0 * (w / 2.0 - 3));
            int sy = (int) (bottom - frac * (bottom - top));
            int color = Contact.SUB_LEVEL.equals(c.kind()) ? SHIP_DOT
                : Boolean.TRUE.equals(c.extra().get("isPlayer")) ? PLAYER_DOT : ENTITY_DOT;
            int s = w >= 96 ? 3 : 2;
            Canvas.rect(b, sx - s / 2, sy - s / 2, s, s, color, true);
            shown++;
        }
        MonitorFont font = MonitorFont.get();
        Canvas.text(b, x + 3, y + 1, 1, TEXT, 0, "RAD " + range);
        String n = Integer.toString(shown);
        Canvas.text(b, x + w - 3 - font.measure(n), y + 1, 1, DIM, 0, n);
    }

    // ---- Ship scan (shared by the ship and weapons pages) ----

    private static ShipCache scanShip(ControlPanelBlockEntity be, long now) {
        Level level = be.getLevel();
        List<BlockEntity> found;
        SableCompat.ShipPlan plan = null;
        boolean onShip = AshCC.sableLoaded && SableCompat.subLevelAt(level, be.getBlockPos()) != null;
        if (onShip) {
            found = SableCompat.shipBlockEntities(level, be.getBlockPos());
            plan = SableCompat.shipPlan(level, be.getBlockPos());
        } else {
            found = new ArrayList<>();
            ChunkPos cp = new ChunkPos(be.getBlockPos());
            for (int cx = cp.x - 1; cx <= cp.x + 1; cx++) {
                for (int cz = cp.z - 1; cz <= cp.z + 1; cz++) {
                    if (level.hasChunk(cx, cz)) found.addAll(level.getChunk(cx, cz).getBlockEntities().values());
                }
            }
        }
        boolean cbc = net.neoforged.fml.ModList.get().isLoaded("createbigcannons");
        List<Weapon> weapons = new ArrayList<>();
        for (BlockEntity e : found) {
            if (e instanceof LaserBlockEntity || e instanceof DecoyLauncherBlockEntity) {
                weapons.add(new Weapon(e, null, e.getBlockPos()));
            } else if (cbc) {
                String kind = com.ashcc.compat.cbc.CbcCompat.cannonStatus(e);
                if (kind != null) weapons.add(new Weapon(null, kind, e.getBlockPos()));
            }
        }
        weapons.sort((a, z) -> a.pos().compareTo(z.pos()));
        return new ShipCache(now, plan, weapons, onShip);
    }

    // ---- Ship top view ----

    private static void drawShip(ByteBuf b, ControlPanelBlockEntity be, int x, int y, int w, int h, ShipCache cache) {
        Canvas.clip(b, x, y, x + w, y + h);
        Canvas.rect(b, x, y, w, h, BG, true);
        MonitorFont font = MonitorFont.get();
        if (cache.plan() == null || cache.plan().columns().isEmpty()) {
            centerText(b, font, x, y + h / 2 - 8, w, cache.onShip() ? "NO BLOCKS" : "NO SHIP");
            return;
        }
        // Aircraft axes in plot space (horizontal): right from the panel frame, forward the way the pilot faces
        PanelFrame pf = PanelFrame.of(be.getBlockState(), 0);
        double[] right = horizontal(pf.right().x, pf.right().z, 1, 0);
        Vec3 fwdSrc = Math.abs(pf.normal().y) > 0.5 ? pf.up() : pf.normal().scale(-1);
        double[] fwd = horizontal(fwdSrc.x, fwdSrc.z, 0, -1);
        double px = be.getBlockPos().getX() + 0.5, pz = be.getBlockPos().getZ() + 0.5;

        // Occupied columns in panel-relative index space (iu along right, iv along forward)
        java.util.Set<Long> cells = new java.util.HashSet<>();
        int minIu = Integer.MAX_VALUE, maxIu = Integer.MIN_VALUE, minIv = Integer.MAX_VALUE, maxIv = Integer.MIN_VALUE;
        for (long key : cache.plan().columns()) {
            double[] uv = project(SableCompat.columnX(key) + 0.5 - px, SableCompat.columnZ(key) + 0.5 - pz, right, fwd);
            int iu = (int) Math.round(uv[0]), iv = (int) Math.round(uv[1]);
            cells.add(((long) iu << 32) | (iv & 0xFFFFFFFFL));
            minIu = Math.min(minIu, iu);
            maxIu = Math.max(maxIu, iu);
            minIv = Math.min(minIv, iv);
            maxIv = Math.max(maxIv, iv);
        }
        double[] comUv = null;
        if (cache.plan().centerOfMass() != null) {
            Vec3 com = cache.plan().centerOfMass();
            comUv = project(com.x - px, com.z - pz, right, fwd);
            minIu = Math.min(minIu, (int) Math.floor(comUv[0]));
            maxIu = Math.max(maxIu, (int) Math.ceil(comUv[0]));
            minIv = Math.min(minIv, (int) Math.floor(comUv[1]));
            maxIv = Math.max(maxIv, (int) Math.ceil(comUv[1]));
        }
        minIu = Math.min(minIu, 0);
        maxIu = Math.max(maxIu, 0);
        minIv = Math.min(minIv, 0);
        maxIv = Math.max(maxIv, 0);
        // Fit the whole plan into the rect: cell (iu, iv) spans [i-0.5, i+0.5] on each axis
        double minU = minIu - 0.5, minV = minIv - 0.5;
        double spanU = Math.max(1, maxIu - minIu + 1), spanV = Math.max(1, maxIv - minIv + 1);
        double scale = Math.min((w - 10) / spanU, (h - 10) / spanV);
        // Outline in green: an edge wherever a column's neighbour is empty, merged into runs
        java.util.Map<Integer, List<Integer>> vEdges = new java.util.HashMap<>(), hEdges = new java.util.HashMap<>();
        for (long key : cells) {
            int iu = (int) (key >> 32), iv = (int) key;
            if (!cells.contains(((long) (iu + 1) << 32) | (iv & 0xFFFFFFFFL))) vEdges.computeIfAbsent(iu, k -> new ArrayList<>()).add(iv);
            if (!cells.contains(((long) (iu - 1) << 32) | (iv & 0xFFFFFFFFL))) vEdges.computeIfAbsent(iu - 1, k -> new ArrayList<>()).add(iv);
            if (!cells.contains(((long) iu << 32) | ((iv + 1) & 0xFFFFFFFFL))) hEdges.computeIfAbsent(iv, k -> new ArrayList<>()).add(iu);
            if (!cells.contains(((long) iu << 32) | ((iv - 1) & 0xFFFFFFFFL))) hEdges.computeIfAbsent(iv - 1, k -> new ArrayList<>()).add(iu);
        }
        for (var e : vEdges.entrySet()) {
            double sx = x + 5 + (e.getKey() + 0.5 - minU) * scale;
            for (int[] run : runs(e.getValue())) {
                Canvas.line(b, sx, y + h - 5 - (run[0] - 0.5 - minV) * scale, sx, y + h - 5 - (run[1] + 0.5 - minV) * scale, 1, OUTLINE);
            }
        }
        for (var e : hEdges.entrySet()) {
            double sy = y + h - 5 - (e.getKey() + 0.5 - minV) * scale;
            for (int[] run : runs(e.getValue())) {
                Canvas.line(b, x + 5 + (run[0] - 0.5 - minU) * scale, sy, x + 5 + (run[1] + 0.5 - minU) * scale, sy, 1, OUTLINE);
            }
        }
        // Weapons on the plan
        for (Weapon wp : cache.weapons()) {
            double[] uv = project(wp.pos().getX() + 0.5 - px, wp.pos().getZ() + 0.5 - pz, right, fwd);
            int sx = (int) (x + 5 + (uv[0] - minU) * scale), sy = (int) (y + h - 5 - (uv[1] - minV) * scale);
            Canvas.rect(b, sx - 1, sy - 1, 3, 3, WARN, true);
        }
        // Centre of mass
        if (comUv != null) {
            int sx = (int) (x + 5 + (comUv[0] - minU) * scale), sy = (int) (y + h - 5 - (comUv[1] - minV) * scale);
            Canvas.line(b, sx - 4, sy, sx + 4, sy, 1, COM);
            Canvas.line(b, sx, sy - 4, sx, sy + 4, 1, COM);
        }
        // The panel itself: a triangle pointing forward (up on screen)
        int sx = (int) (x + 5 + (0 - minU) * scale), sy = (int) (y + h - 5 - (0 - minV) * scale);
        Canvas.polygon(b, new double[]{sx, sy - 5, sx + 4, sy + 4, sx - 4, sy + 4}, 0, MARKER);
        Canvas.text(b, x + 3, y + 1, 1, TEXT, 0, "SHIP");
    }

    /** Sorted values merged into [first, last] runs of consecutive integers. */
    private static List<int[]> runs(List<Integer> values) {
        values.sort(Integer::compareTo);
        List<int[]> out = new ArrayList<>();
        int start = values.get(0), prev = values.get(0);
        for (int i = 1; i < values.size(); i++) {
            int v = values.get(i);
            if (v == prev + 1) {
                prev = v;
            } else if (v != prev) {
                out.add(new int[]{start, prev});
                start = prev = v;
            }
        }
        out.add(new int[]{start, prev});
        return out;
    }

    private static double[] horizontal(double vx, double vz, double fallbackX, double fallbackZ) {
        double len = Math.hypot(vx, vz);
        return len < 1e-6 ? new double[]{fallbackX, fallbackZ} : new double[]{vx / len, vz / len};
    }

    private static double[] project(double dx, double dz, double[] right, double[] fwd) {
        return new double[]{dx * right[0] + dz * right[1], dx * fwd[0] + dz * fwd[1]};
    }

    // ---- Weapons status ----

    private static void drawWeapons(ByteBuf b, int x, int y, int w, int h, ShipCache cache) {
        Canvas.clip(b, x, y, x + w, y + h);
        Canvas.rect(b, x, y, w, h, BG, true);
        MonitorFont font = MonitorFont.get();
        Canvas.text(b, x + 3, y + 1, 1, TEXT, 0, "WEAPONS " + cache.weapons().size());
        if (cache.weapons().isEmpty()) {
            centerText(b, font, x, y + h / 2 - 8, w, "NONE");
            return;
        }
        int rowH = 20;
        int maxRows = Math.max(0, (h - 20) / rowH);
        for (int i = 0; i < Math.min(maxRows, cache.weapons().size()); i++) {
            Weapon wp = cache.weapons().get(i);
            int ry = y + 20 + i * rowH;
            String label, status;
            int statusColor = TEXT;
            if (wp.be() instanceof LaserBlockEntity laser) {
                label = "LASER";
                if (laser.overheated()) {
                    status = "OVERHEAT";
                    statusColor = WARN;
                } else if (laser.firing()) {
                    status = "FIRING";
                    statusColor = PLAYER_DOT;
                } else {
                    status = String.format(Locale.ROOT, "HEAT %.0f%%", laser.heat() * 100);
                }
            } else if (wp.be() instanceof DecoyLauncherBlockEntity decoy) {
                label = "DECOY";
                double cd = decoy.cooldown();
                status = String.format(Locale.ROOT, "F%d C%d %s", decoy.ammo(DecoyEntity.FLARE), decoy.ammo(DecoyEntity.CHAFF),
                    cd > 0 ? String.format(Locale.ROOT, "CD %.0f", cd) : "RDY");
            } else {
                label = "CANNON";
                status = wp.mountKind() == null || wp.mountKind().equals("none") ? "NO CANNON" : wp.mountKind().toUpperCase(Locale.ROOT);
                if (status.equals("NO CANNON")) statusColor = DIM;
            }
            Canvas.text(b, x + 3, ry + 2, 1, LABEL, 0, label);
            Canvas.text(b, x + w - 3 - font.measure(status), ry + 2, 1, statusColor, 0, status);
        }
    }

    private static void centerText(ByteBuf b, MonitorFont font, int x, int y, int w, String s) {
        Canvas.text(b, x + (w - font.measure(s)) / 2, y, 1, TEXT, 0, s);
    }
}
