package com.ashcc.radar;

import com.ashcc.AshCC;
import com.ashcc.AshCCConfig;
import com.ashcc.compat.SableCompat;
import com.ashcc.iff.Transponders;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lua API of the radar (peripheral type "radar").
 * Positions are world coordinates (sub-level blocks are projected out of the plot grid), velocities are blocks/second,
 * "local" vectors are in the radar's frame: x = right, y = up, z = forward.
 */
public class RadarPeripheral implements IPeripheral {
    private final RadarBlockEntity radar;

    RadarPeripheral(RadarBlockEntity radar) {
        this.radar = radar;
    }

    @Override
    public String getType() {
        return "radar";
    }

    @Override
    public void attach(IComputerAccess computer) {
        radar.computers.add(computer);
    }

    @Override
    public void detach(IComputerAccess computer) {
        radar.computers.remove(computer);
    }

    @Override
    public Object getTarget() {
        return radar;
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof RadarPeripheral o && o.radar == radar;
    }

    private ServerLevel level() throws LuaException {
        if (!(radar.getLevel() instanceof ServerLevel level) || radar.isRemoved()) throw new LuaException("Radar is not loaded");
        return level;
    }

    private static int limit() {
        return AshCCConfig.RADAR_MAX_RESULTS.get();
    }

    private static List<Map<String, Object>> toLua(List<Contact> contacts, RadarFrame frame) {
        List<Map<String, Object>> out = new ArrayList<>(contacts.size());
        for (Contact c : contacts) out.add(c.toLua(frame));
        return out;
    }

    private static UUID parseId(String id) throws LuaException {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new LuaException("Invalid id: " + id);
        }
    }

    // ---- Settings ----

    @LuaFunction(mainThread = true)
    public final double getRange() {
        return radar.range();
    }

    @LuaFunction(mainThread = true)
    public final double setRange(double range) throws LuaException {
        if (!Double.isFinite(range)) throw new LuaException("range must be a finite number");
        return radar.setRange(range);
    }

    @LuaFunction
    public final boolean hasSable() {
        return AshCC.sableLoaded;
    }

    // ---- Own position and orientation ----

    /** World position of the radar, plus the id of the sub-level it is mounted on (if any). */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getPosition() throws LuaException {
        level();
        RadarFrame f = radar.frame();
        Map<String, Object> t = LuaUtil.vec(f.origin());
        if (f.subLevel() != null) t.put("subLevel", f.subLevel().toString());
        return t;
    }

    /** World-space unit vectors of the radar's local axes. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getOrientation() throws LuaException {
        level();
        RadarFrame f = radar.frame();
        Map<String, Object> t = new HashMap<>();
        t.put("right", LuaUtil.vec(f.right()));
        t.put("up", LuaUtil.vec(f.up()));
        t.put("forward", LuaUtil.vec(f.forward()));
        return t;
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> toLocal(double x, double y, double z) throws LuaException {
        level();
        return LuaUtil.vec(radar.frame().toLocal(new Vec3(x, y, z)));
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> toWorld(double x, double y, double z) throws LuaException {
        level();
        return LuaUtil.vec(radar.frame().toWorld(new Vec3(x, y, z)));
    }

    /** The sub-level the radar is mounted on, as a contact table, or nil. */
    @LuaFunction(mainThread = true)
    public final @Nullable Map<String, Object> getSelf() throws LuaException {
        ServerLevel level = level();
        if (!AshCC.sableLoaded) return null;
        RadarFrame f = radar.frame();
        Contact c = SableCompat.self(level, radar.getBlockPos(), f.origin());
        if (c == null) return null;
        Transponders.annotate(level, List.of(c));
        return c.toLua(f);
    }

    // ---- Scanning ----

    /** scan([range]) -> { entities = {...}, subLevels = {...} } */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> scan(IArguments args) throws LuaException {
        ServerLevel level = level();
        double r = radar.effectiveRange(args.optFiniteDouble(0).orElse(null));
        RadarFrame f = radar.frame();
        Map<String, Object> t = new HashMap<>();
        t.put("entities", toLua(RadarScanner.scanEntities(level, f, r, e -> true, limit()), f));
        t.put("subLevels", toLua(RadarScanner.scanSubLevels(level, f, r, limit()), f));
        return t;
    }

    /** scanEntities([range], [filter]); filter = { type = "id" | {ids}, player = bool, living = bool, name = "substring" } */
    @LuaFunction(mainThread = true)
    public final List<Map<String, Object>> scanEntities(IArguments args) throws LuaException {
        ServerLevel level = level();
        double r = radar.effectiveRange(args.optFiniteDouble(0).orElse(null));
        Map<?, ?> filter = args.optTable(1).orElse(null);
        RadarFrame f = radar.frame();
        return toLua(RadarScanner.scanEntities(level, f, r, RadarScanner.filter(filter), limit()), f);
    }

    @LuaFunction(mainThread = true)
    public final List<Map<String, Object>> scanPlayers(IArguments args) throws LuaException {
        ServerLevel level = level();
        double r = radar.effectiveRange(args.optFiniteDouble(0).orElse(null));
        RadarFrame f = radar.frame();
        return toLua(RadarScanner.scanEntities(level, f, r, RadarScanner.filter(Map.of("player", true)), limit()), f);
    }

    /** Sable sub-levels (ships) and chaff echoes. The radar's own sub-level is excluded. */
    @LuaFunction(mainThread = true)
    public final List<Map<String, Object>> scanSubLevels(IArguments args) throws LuaException {
        ServerLevel level = level();
        double r = radar.effectiveRange(args.optFiniteDouble(0).orElse(null));
        RadarFrame f = radar.frame();
        return toLua(RadarScanner.scanSubLevels(level, f, r, limit()), f);
    }

    /**
     * scanTerrain([range], [size], [northUp]) -> surface height map around the radar (see {@link RadarTerrain}).
     * size = cells per side (default 32); the grid follows the radar's heading unless northUp.
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> scanTerrain(IArguments args) throws LuaException {
        ServerLevel level = level();
        double r = Math.max(1, radar.effectiveRange(args.optFiniteDouble(0).orElse(null)));
        int max = AshCCConfig.RADAR_TERRAIN_MAX_SIZE.get();
        int size = args.optInt(1).orElse(Math.min(32, max));
        if (size < 1 || size > max) throw new LuaException("size must be between 1 and " + max);
        boolean northUp = args.optBoolean(2).orElse(false);
        return RadarTerrain.scan(level, radar.frame(), r, size, northUp);
    }

    /** Re-reads one contact by id (for tracking). nil when gone or out of range. */
    @LuaFunction(mainThread = true)
    public final @Nullable Map<String, Object> getContact(String id) throws LuaException {
        ServerLevel level = level();
        RadarFrame f = radar.frame();
        Contact c = RadarScanner.find(level, f, radar.range(), parseId(id));
        return c == null ? null : c.toLua(f);
    }

    /**
     * intercept(id, projectileSpeed, [gravity]) -> aim solution for a projectile fired from the radar,
     * assuming the target keeps its current velocity. gravity is in blocks/s^2 (downwards). nil when unreachable.
     */
    @LuaFunction(mainThread = true)
    public final @Nullable Map<String, Object> intercept(String id, double speed, java.util.Optional<Double> gravity) throws LuaException {
        if (!(speed > 0)) throw new LuaException("projectileSpeed must be positive");
        ServerLevel level = level();
        RadarFrame f = radar.frame();
        Contact c = RadarScanner.find(level, f, radar.range(), parseId(id));
        if (c == null) return null;
        Intercept.Solution s = Intercept.solve(c.position().subtract(f.origin()), c.velocity(), speed, gravity.orElse(0.0));
        if (s == null) return null;

        Vec3 aim = f.origin().add(s.aim());
        Vec3 dir = s.aim().normalize();
        Vec3 local = f.dirToLocal(s.aim());
        Map<String, Object> t = LuaUtil.vec(aim);
        t.put("time", s.time());
        t.put("direction", LuaUtil.vec(dir));
        t.put("local", LuaUtil.vec(local));
        t.put("azimuth", RadarFrame.azimuth(local));
        t.put("elevation", RadarFrame.elevation(local));
        // Minecraft rotation convention (yaw 0 = south, pitch positive = down)
        t.put("yaw", Math.toDegrees(Math.atan2(-dir.x, dir.z)));
        t.put("pitch", -Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, dir.y)))));
        return t;
    }

    // ---- Watch mode (events) ----

    /**
     * setWatch(enabled, [options]); options = { interval = ticks, entities = bool, subLevels = bool, filter = {...} }.
     * Queues "radar_contact"(side, contact) when something enters range and "radar_lost"(side, id, kind) when it leaves.
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> setWatch(IArguments args) throws LuaException {
        boolean enabled = args.getBoolean(0);
        Map<?, ?> opts = args.optTable(1).orElse(Map.of());
        int interval = opts.get("interval") instanceof Number n ? n.intValue() : 10;
        boolean entities = !(opts.get("entities") instanceof Boolean b) || b;
        boolean subLevels = !(opts.get("subLevels") instanceof Boolean b) || b;
        Map<String, Object> filter = normalizeFilter(opts.get("filter") instanceof Map<?, ?> m ? m : Map.of());
        radar.setWatch(enabled, interval, entities, subLevels, filter);
        return radar.watchInfo();
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getWatch() {
        return radar.watchInfo();
    }

    private static Map<String, Object> normalizeFilter(Map<?, ?> in) throws LuaException {
        Map<String, Object> out = new HashMap<>();
        Object type = in.get("type");
        if (type instanceof String s) out.put("type", List.of(s));
        else if (type instanceof Map<?, ?> m) {
            List<String> ids = new ArrayList<>();
            for (Object v : m.values()) ids.add(v.toString());
            out.put("type", ids);
        } else if (type != null) throw new LuaException("filter.type must be a string or a list of strings");
        if (in.get("player") instanceof Boolean b) out.put("player", b);
        if (in.get("living") instanceof Boolean b) out.put("living", b);
        if (in.get("name") instanceof String s) out.put("name", s);
        return out;
    }
}
