package com.ashcc.jet;

import com.ashcc.AshCC;
import com.ashcc.AshCCConfig;
import com.ashcc.compat.SableCompat;
import com.ashcc.radar.LuaUtil;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Lua API of a jet engine (peripheral type "jet_engine"), reachable from any of its three blocks.
 * Events: jet_engine_state(name, state, reason), jet_engine_light_off(name), jet_engine_flameout(name, reason),
 * jet_engine_warning(name, warning).
 */
public class JetEnginePeripheral implements IPeripheral {
    private final JetEngineBlockEntity engine;

    JetEnginePeripheral(JetEngineBlockEntity engine) {
        this.engine = engine;
    }

    @Override
    public String getType() {
        return "jet_engine";
    }

    @Override
    public void attach(IComputerAccess computer) {
        engine.computers.add(computer);
    }

    @Override
    public void detach(IComputerAccess computer) {
        engine.computers.remove(computer);
    }

    @Override
    public Object getTarget() {
        return engine;
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof JetEnginePeripheral o && o.engine == engine;
    }

    private static double finite(double v, String name) throws LuaException {
        if (!Double.isFinite(v)) throw new LuaException(name + " must be a finite number");
        return v;
    }

    // ---- Start / stop ----

    /** Starts the engine (starter, igniters, fuel). Returns false if it cannot (incomplete or seized). */
    @LuaFunction(mainThread = true)
    public final boolean start() {
        return engine.start();
    }

    /** Fuel cut-off: the flame goes out and the engine spools down. */
    @LuaFunction(mainThread = true)
    public final void shutdown() {
        engine.shutdown();
    }

    // ---- Throttle ----

    /** setThrottle(0..1): idle .. military (full dry) power. Switches to computer control. */
    @LuaFunction(mainThread = true)
    public final void setThrottle(double t) throws LuaException {
        engine.setThrottle(finite(t, "throttle"));
    }

    @LuaFunction(mainThread = true)
    public final double getThrottle() {
        return engine.throttle();
    }

    /** Limit set with a wrench (0.1..1): the engine runs at throttle x limit; the afterburner needs 1. */
    @LuaFunction(mainThread = true)
    public final double getThrottleLimit() {
        return engine.throttleLimit();
    }

    @LuaFunction(mainThread = true)
    public final void setThrottleLimit(double limit) throws LuaException {
        engine.setThrottleLimit(finite(limit, "limit"));
    }

    /** setAfterburner(0..1): reheat; only burns at full throttle and N2 above 93%. Switches to computer control. */
    @LuaFunction(mainThread = true)
    public final void setAfterburner(double a) throws LuaException {
        engine.setAfterburner(finite(a, "afterburner"));
    }

    /** Commanded afterburner (0..1); getStatus().afterburner is how much is actually burning. */
    @LuaFunction(mainThread = true)
    public final double getAfterburner() {
        return engine.afterburnerCommand();
    }

    /** true: redstone runs the engine (0 off, 1..13 idle..military, 14 half and 15 full afterburner). */
    @LuaFunction(mainThread = true)
    public final void setRedstoneControl(boolean enabled) {
        engine.setRedstoneControl(enabled);
    }

    @LuaFunction(mainThread = true)
    public final boolean isRedstoneControl() {
        return engine.redstoneControl();
    }

    // ---- Readouts ----

    /**
     * Engine instruments: state (off/starting/running/failed), lit, n1/n2 (fan/core speed, 0..1), egt (deg C),
     * egtLimit, thrust (force), maxThrust, afterburner (0..1 burning), fuelFlow (mB/s), fuel/fuelCapacity (mB),
     * fuelName, health (0..1), density (air, 1 at sea level), airspeed, intakeClear, warnings, reason (of the last change).
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getStatus() {
        Map<String, Object> t = new HashMap<>();
        t.put("state", engine.state().name().toLowerCase());
        t.put("lit", engine.lit());
        t.put("n1", engine.n1());
        t.put("n2", engine.n2());
        t.put("egt", engine.egt());
        t.put("egtLimit", AshCCConfig.JET_EGT_LIMIT.get());
        t.put("thrust", engine.thrust());
        t.put("maxThrust", engine.maxWetThrust());
        t.put("throttle", engine.effectiveThrottle());
        t.put("throttleLimit", engine.throttleLimit());
        t.put("afterburner", engine.afterburnerLevel());
        t.put("fuelFlow", engine.fuelFlow());
        FluidStack fuel = engine.fuel();
        t.put("fuel", fuel.getAmount());
        t.put("fuelCapacity", engine.fuelCapacity());
        if (!fuel.isEmpty()) t.put("fuelName", BuiltInRegistries.FLUID.getKey(fuel.getFluid()).toString());
        t.put("health", engine.health());
        t.put("density", engine.density());
        t.put("airspeed", engine.airspeed());
        t.put("intakeClear", engine.intakeClear());
        t.put("warnings", engine.warnings());
        t.put("reason", engine.lastReason());
        return t;
    }

    /** Thrust (force units: mass x blocks/s^2). */
    @LuaFunction(mainThread = true)
    public final double getThrust() {
        return engine.thrust();
    }

    /** Fuel: amount, capacity, name (fluid id) and value (energy per mB, kerosene = 1). */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getFuel() {
        FluidStack fuel = engine.fuel();
        Map<String, Object> t = new HashMap<>();
        t.put("amount", fuel.getAmount());
        t.put("capacity", engine.fuelCapacity());
        if (!fuel.isEmpty()) t.put("name", BuiltInRegistries.FLUID.getKey(fuel.getFluid()).toString());
        t.put("value", JetFuels.value(fuel));
        t.put("required", AshCCConfig.JET_REQUIRE_FUEL.get());
        return t;
    }

    /**
     * Build: valid, error (no_core / no_intake), afterburner (the nozzle has one), length,
     * pressureRatio, maxDryThrust and maxWetThrust (with afterburner) at sea level.
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getStructure() {
        Map<String, Object> t = new HashMap<>();
        t.put("valid", engine.formed());
        if (!engine.formed()) t.put("error", engine.structureError());
        t.put("afterburner", engine.hasAfterburner());
        t.put("vectoring", engine.hasVectoring());
        t.put("length", engine.length());
        t.put("pressureRatio", engine.pressureRatio());
        t.put("maxDryThrust", engine.maxDryThrust());
        t.put("maxWetThrust", engine.maxWetThrust());
        return t;
    }

    /**
     * setNozzleVector(pitch, yaw): deflects a vectoring nozzle in degrees (pitch + points the exhaust up,
     * yaw + points it right), clamped to the configured maximum. Returns false when the nozzle cannot vector.
     */
    @LuaFunction(mainThread = true)
    public final boolean setNozzleVector(double pitch, double yaw) throws LuaException {
        return engine.setNozzleVector(finite(pitch, "pitch"), finite(yaw, "yaw"));
    }

    /** Nozzle vectoring: vectoring (the nozzle can vector), pitch/yaw (current deflection, degrees), maxAngle. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getNozzleVector() {
        Map<String, Object> t = new HashMap<>();
        t.put("vectoring", engine.hasVectoring());
        t.put("pitch", engine.vectorPitch());
        t.put("yaw", engine.vectorYaw());
        t.put("pitchCommand", engine.vectorPitchCommand());
        t.put("yawCommand", engine.vectorYawCommand());
        t.put("maxAngle", AshCCConfig.JET_VECTOR_MAX_ANGLE.get());
        return t;
    }

    /** Current thrust vector in world space (rotating with the ship), and in the ship frame as "local". */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getThrustVector() {
        Vec3 local = engine.localForce();
        Vec3 world = AshCC.sableLoaded && engine.getLevel() != null
            ? SableCompat.dirToWorld(engine.getLevel(), engine.getBlockPos(), local) : local;
        Map<String, Object> t = LuaUtil.vec(world);
        t.put("local", LuaUtil.vec(local));
        return t;
    }
}
