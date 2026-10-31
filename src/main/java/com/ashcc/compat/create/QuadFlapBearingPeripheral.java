package com.ashcc.compat.create;

import com.ashcc.AshCC;
import com.ashcc.AshCCConfig;
import com.ashcc.compat.SableCompat;
import com.ashcc.radar.LuaUtil;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lua API of the quad flap bearing (peripheral type "quad_flap_bearing").
 * Flaps are named after the face they sit on, in block coordinates ("north", "east", "up", ...).
 * A positive angle turns a flap about the positive axis of its face (so the opposite flap with the same angle turns
 * the same way). Methods taking a side also accept "all" or nil for every flap.
 */
public class QuadFlapBearingPeripheral implements IPeripheral {
    private final QuadFlapBearingBlockEntity bearing;

    QuadFlapBearingPeripheral(QuadFlapBearingBlockEntity bearing) {
        this.bearing = bearing;
    }

    @Override
    public String getType() {
        return "quad_flap_bearing";
    }

    @Override
    public Object getTarget() {
        return bearing;
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof QuadFlapBearingPeripheral o && o.bearing == bearing;
    }

    /** One side, or every side for nil / "all". */
    private List<Direction> sides(@Nullable String name) throws LuaException {
        if (name == null || name.equals("all")) return bearing.sides();
        Direction d = Direction.byName(name.toLowerCase());
        if (d == null || !bearing.sides().contains(d)) throw new LuaException("Not a flap side: " + name + " (sides: " + names() + ")");
        return List.of(d);
    }

    private List<String> names() {
        List<String> out = new ArrayList<>();
        for (Direction d : bearing.sides()) out.add(d.getSerializedName());
        return out;
    }

    private static double finite(double v, String what) throws LuaException {
        if (!Double.isFinite(v)) throw new LuaException(what + " must be a finite number");
        return v;
    }

    private Vec3 toWorld(Vec3 dir) {
        if (!AshCC.sableLoaded || bearing.getLevel() == null) return dir;
        return SableCompat.dirToWorld(bearing.getLevel(), bearing.getBlockPos(), dir);
    }

    // ---- Layout ----

    /** The four flap sides, e.g. {"north", "south", "west", "east"} for a bearing facing up. */
    @LuaFunction
    public final List<String> getSides() {
        return names();
    }

    /** World direction of the body axis (FACING), rotating with a Sable ship. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getFacing() {
        return LuaUtil.vec(toWorld(Vec3.atLowerCornerOf(bearing.facing().getNormal())));
    }

    /** World direction of a flap's face (its rotation axis), rotating with a Sable ship. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getAxis(String side) throws LuaException {
        Direction d = sides(side).get(0);
        return LuaUtil.vec(toWorld(Vec3.atLowerCornerOf(d.getNormal())));
    }

    // ---- Assembly ----

    /**
     * assemble(side) -> true, or false and the reason.
     * assemble() -> number assembled, { side = reason } for the ones that failed.
     */
    @LuaFunction(mainThread = true)
    public final MethodResult assemble(IArguments args) throws LuaException {
        String side = args.optString(0).orElse(null);
        if (side != null && !side.equals("all")) {
            String error = bearing.assemble(sides(side).get(0));
            return error == null ? MethodResult.of(true) : MethodResult.of(false, error);
        }
        int ok = 0;
        Map<String, Object> errors = new HashMap<>();
        for (Direction d : bearing.sides()) {
            String error = bearing.assemble(d);
            if (error == null) ok++;
            else errors.put(d.getSerializedName(), error);
        }
        return MethodResult.of(ok, errors);
    }

    /** disassemble([side]) -> true when done now; false means some flaps return to 0 degrees first. */
    @LuaFunction(mainThread = true)
    public final boolean disassemble(IArguments args) throws LuaException {
        boolean all = true;
        for (Direction d : sides(args.optString(0).orElse(null))) all &= bearing.disassemble(d);
        return all;
    }

    /** isAssembled(side) -> bool; isAssembled() -> { side = bool } */
    @LuaFunction
    public final Object isAssembled(IArguments args) throws LuaException {
        String side = args.optString(0).orElse(null);
        if (side != null && !side.equals("all")) return bearing.flap(sides(side).get(0)).assembled;
        Map<String, Object> t = new HashMap<>();
        for (Direction d : bearing.sides()) t.put(d.getSerializedName(), bearing.flap(d).assembled);
        return t;
    }

    // ---- Angles ----

    /** setAngle(side | "all", degrees) -> the clamped target. */
    @LuaFunction(mainThread = true)
    public final double setAngle(String side, double degrees) throws LuaException {
        finite(degrees, "angle");
        double set = 0;
        for (Direction d : sides(side)) set = bearing.setTarget(d, degrees);
        return set;
    }

    /** setAngles({ north = 10, south = -10, ... }) -> { side = clamped target } */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> setAngles(Map<?, ?> angles) throws LuaException {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<?, ?> e : angles.entrySet()) {
            if (!(e.getKey() instanceof String side) || !(e.getValue() instanceof Number n)) throw new LuaException("Expected { side = degrees }");
            Direction d = sides(side).get(0);
            out.put(d.getSerializedName(), (double) bearing.setTarget(d, finite(n.doubleValue(), "angle")));
        }
        return out;
    }

    /** getAngle(side) -> degrees; getAngle() -> { side = degrees } */
    @LuaFunction
    public final Object getAngle(IArguments args) throws LuaException {
        return perSide(args, f -> (double) Mth.wrapDegrees(f.angle));
    }

    @LuaFunction
    public final Object getTargetAngle(IArguments args) throws LuaException {
        return perSide(args, f -> (double) f.target);
    }

    /** isAtTarget([side]) -> true when that flap (or every assembled flap) has reached its target. */
    @LuaFunction
    public final boolean isAtTarget(IArguments args) throws LuaException {
        for (Direction d : sides(args.optString(0).orElse(null))) {
            QuadFlapBearingBlockEntity.Flap f = bearing.flap(d);
            if (f.assembled && !QuadFlapBearingBlockEntity.atTarget(f)) return false;
        }
        return true;
    }

    @LuaFunction
    public final double getMaxAngle() {
        return AshCCConfig.FLAP_MAX_ANGLE.get();
    }

    // ---- Speed (shared) ----

    @LuaFunction(mainThread = true)
    public final double setSpeed(double degreesPerSecond) throws LuaException {
        return bearing.setSpeed(finite(degreesPerSecond, "speed"));
    }

    @LuaFunction
    public final double getSpeed() {
        return bearing.speedPerSecond();
    }

    @LuaFunction
    public final double getMaxSpeed() {
        return AshCCConfig.FLAP_MAX_SPEED.get();
    }

    private interface FlapValue {
        Object get(QuadFlapBearingBlockEntity.Flap f);
    }

    private Object perSide(IArguments args, FlapValue value) throws LuaException {
        String side = args.optString(0).orElse(null);
        if (side != null && !side.equals("all")) return value.get(bearing.flap(sides(side).get(0)));
        Map<String, Object> t = new HashMap<>();
        for (Direction d : bearing.sides()) t.put(d.getSerializedName(), value.get(bearing.flap(d)));
        return t;
    }
}
