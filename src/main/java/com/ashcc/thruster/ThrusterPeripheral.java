package com.ashcc.thruster;

import com.ashcc.AshCC;
import com.ashcc.AshCCConfig;
import com.ashcc.compat.SableCompat;
import com.ashcc.radar.LuaUtil;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lua API of the thruster (peripheral type "thruster").
 * Throttle is 0..1 of maxThrust; gimbal x/y are -1..1 of maxGimbal degrees.
 * Calling setThrust switches the thruster from redstone to computer control.
 */
public class ThrusterPeripheral implements IPeripheral {
    private final ThrusterBlockEntity thruster;

    ThrusterPeripheral(ThrusterBlockEntity thruster) {
        this.thruster = thruster;
    }

    @Override
    public String getType() {
        return "thruster";
    }

    @Override
    public Object getTarget() {
        return thruster;
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof ThrusterPeripheral o && o.thruster == thruster;
    }

    private static double finite(double v, String name) throws LuaException {
        if (!Double.isFinite(v)) throw new LuaException(name + " must be a finite number");
        return v;
    }

    private Vec3 toWorld(Vec3 dir) {
        if (!AshCC.sableLoaded || thruster.getLevel() == null) return dir;
        return SableCompat.dirToWorld(thruster.getLevel(), thruster.getBlockPos(), dir);
    }

    // ---- Throttle ----

    /** setThrust(0..1). Also switches to computer control. */
    @LuaFunction(mainThread = true)
    public final void setThrust(double throttle) throws LuaException {
        thruster.setThrottle(finite(throttle, "thrust"));
    }

    /** Target throttle (0..1). */
    @LuaFunction(mainThread = true)
    public final double getThrust() {
        return thruster.targetThrottle();
    }

    /** Throttle currently applied (follows the target at throttleRate per tick). */
    @LuaFunction
    public final double getCurrentThrust() {
        return thruster.throttle();
    }

    /** Current force (mass x blocks/s^2). */
    @LuaFunction
    public final double getForce() {
        return thruster.force();
    }

    @LuaFunction
    public final double getMaxThrust() {
        return AshCCConfig.THRUSTER_MAX_THRUST.get();
    }

    /** Throttle 0 and gimbal centred. */
    @LuaFunction(mainThread = true)
    public final void stop() {
        thruster.setThrottle(0);
        thruster.setGimbal(0, 0);
    }

    // ---- Gimbal ----

    /** setVector(x, y), each -1..1 (= +-maxGimbal degrees). Axes are given by getAxes(). */
    @LuaFunction(mainThread = true)
    public final void setVector(double x, double y) throws LuaException {
        thruster.setGimbal(finite(x, "x"), finite(y, "y"));
    }

    @LuaFunction
    public final Map<String, Object> getVector() {
        return vec2(thruster.gimbalX(), thruster.gimbalY());
    }

    @LuaFunction
    public final Map<String, Object> getTargetVector() {
        return vec2(thruster.targetX(), thruster.targetY());
    }

    @LuaFunction
    public final double getMaxGimbal() {
        return AshCCConfig.THRUSTER_MAX_GIMBAL.get();
    }

    private static Map<String, Object> vec2(double x, double y) {
        Map<String, Object> t = new HashMap<>();
        t.put("x", x);
        t.put("y", y);
        return t;
    }

    // ---- Redstone ----

    /** true: throttle = redstone signal / 15 (the default until setThrust is called). */
    @LuaFunction(mainThread = true)
    public final void setRedstoneControl(boolean enabled) {
        thruster.setRedstoneControl(enabled);
    }

    @LuaFunction
    public final boolean isRedstoneControl() {
        return thruster.redstoneControl();
    }

    @LuaFunction
    public final int getRedstoneInput() {
        return thruster.redstoneInput();
    }

    // ---- Geometry ----

    /**
     * Directions in world space (rotating with the ship): thrust = push direction without gimbal,
     * x / y = where positive gimbal tilts the thrust. local = the same in the ship's block frame.
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getAxes() {
        Map<String, Object> local = new HashMap<>();
        local.put("thrust", LuaUtil.vec(thruster.thrustAxis()));
        local.put("x", LuaUtil.vec(thruster.gimbalAxisX()));
        local.put("y", LuaUtil.vec(thruster.gimbalAxisY()));

        Map<String, Object> t = new HashMap<>();
        t.put("thrust", LuaUtil.vec(toWorld(thruster.thrustAxis())));
        t.put("x", LuaUtil.vec(toWorld(thruster.gimbalAxisX())));
        t.put("y", LuaUtil.vec(toWorld(thruster.gimbalAxisY())));
        t.put("nozzle", thruster.nozzle().getSerializedName());
        t.put("local", local);
        return t;
    }

    /** Current force vector in world space, and in the ship frame as "local". */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getThrustVector() {
        Vec3 local = thruster.localForce();
        Map<String, Object> t = LuaUtil.vec(toWorld(local));
        t.put("local", LuaUtil.vec(local));
        return t;
    }

    /** Id of the Sable sub-level this thruster is mounted on, or nil (it produces no force off a ship). */
    @LuaFunction(mainThread = true)
    public final @Nullable String getSubLevel() {
        if (!AshCC.sableLoaded || thruster.getLevel() == null) return null;
        UUID id = SableCompat.subLevelAt(thruster.getLevel(), thruster.getBlockPos());
        return id == null ? null : id.toString();
    }
}
