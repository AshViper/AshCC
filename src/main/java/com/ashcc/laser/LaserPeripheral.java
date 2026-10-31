package com.ashcc.laser;

import com.ashcc.AshCCConfig;
import com.ashcc.radar.LuaUtil;
import com.ashcc.radar.RadarFrame;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/**
 * Lua API of the laser (peripheral type "laser").
 * Aim is yaw/pitch in degrees in the block's frame (the ship's on a sub-level): yaw 0 = north, positive towards east
 * (-180..180), pitch positive up (-90..90). The block has no front, so every direction can be reached.
 * Calling fire/stop switches the laser from redstone to computer control.
 * Events: laser_break(name, block, x, y, z, subLevel), laser_overheat(name), laser_ready(name).
 */
public class LaserPeripheral implements IPeripheral {
    private final LaserBlockEntity laser;

    LaserPeripheral(LaserBlockEntity laser) {
        this.laser = laser;
    }

    @Override
    public String getType() {
        return "laser";
    }

    @Override
    public void attach(IComputerAccess computer) {
        laser.computers.add(computer);
    }

    @Override
    public void detach(IComputerAccess computer) {
        laser.computers.remove(computer);
    }

    @Override
    public Object getTarget() {
        return laser;
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof LaserPeripheral o && o.laser == laser;
    }

    private void loaded() throws LuaException {
        if (laser.getLevel() == null || laser.isRemoved()) throw new LuaException("Laser is not loaded");
    }

    // ---- Firing ----

    /** fire([seconds]): fire until stop() or for the given time. Returns true, or false, "overheated". */
    @LuaFunction(mainThread = true)
    public final MethodResult fire(IArguments args) throws LuaException {
        double seconds = args.optFiniteDouble(0).orElse(-1.0);
        if (args.count() > 0 && seconds <= 0) throw new LuaException("seconds must be positive");
        int ticks = seconds < 0 ? -1 : Math.max(1, (int) Math.round(seconds * 20));
        return laser.fire(ticks) ? MethodResult.of(true) : MethodResult.of(false, "overheated");
    }

    @LuaFunction(mainThread = true)
    public final void stop() {
        laser.stopFiring();
    }

    /** Whether the beam is on right now. */
    @LuaFunction
    public final boolean isFiring() {
        return laser.firing();
    }

    /** 0 (cold) .. 1 (overheated). */
    @LuaFunction
    public final double getHeat() {
        return laser.heat();
    }

    /** True from reaching full heat until cooled down to 0; the laser cannot fire meanwhile. */
    @LuaFunction
    public final boolean isOverheated() {
        return laser.overheated();
    }

    // ---- Aim ----

    /** setAim(yaw, pitch) in degrees. Returns the yaw (wrapped to -180..180) and pitch (clamped to -90..90). */
    @LuaFunction(mainThread = true)
    public final MethodResult setAim(double yaw, double pitch) throws LuaException {
        if (!Double.isFinite(yaw) || !Double.isFinite(pitch)) throw new LuaException("yaw and pitch must be finite numbers");
        double[] a = laser.setAim(yaw, pitch);
        return MethodResult.of(a[0], a[1]);
    }

    /**
     * aimAt(x, y, z): aims at a world position (works on a ship: the angles follow its rotation when called again).
     * Returns ok, yaw, pitch; ok is false only for a point inside the laser block itself.
     */
    @LuaFunction(mainThread = true)
    public final MethodResult aimAt(double x, double y, double z) throws LuaException {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new LuaException("coordinates must be finite numbers");
        loaded();
        RadarFrame f = laser.frame();
        Vec3 target = new Vec3(x, y, z);
        // Aim from the block centre, then once more from the muzzle that aim gives
        Vec3 local = f.toLocal(target);
        double yaw = RadarFrame.azimuth(local);
        double pitch = RadarFrame.elevation(local);
        double offset = LaserBlockEntity.muzzleOffset(LaserBlockEntity.blockDirection(yaw, pitch));
        boolean ok = local.length() > offset;
        double yr = Math.toRadians(yaw);
        double pr = Math.toRadians(pitch);
        local = local.subtract(new Vec3(Math.sin(yr) * Math.cos(pr), Math.sin(pr), Math.cos(yr) * Math.cos(pr)).scale(offset));
        yaw = RadarFrame.azimuth(local);
        pitch = RadarFrame.elevation(local);
        double[] a = laser.setAim(yaw, pitch);
        return MethodResult.of(ok, a[0], a[1]);
    }

    /** Current aim (it turns towards the target at aimSpeed). */
    @LuaFunction
    public final MethodResult getAim() {
        return MethodResult.of(laser.yaw(), laser.pitch());
    }

    @LuaFunction
    public final MethodResult getTargetAim() {
        return MethodResult.of(laser.targetYaw(), laser.targetPitch());
    }

    /** Whether the aim has reached its target. */
    @LuaFunction
    public final boolean isAimed() {
        return laser.yaw() == laser.targetYaw() && laser.pitch() == laser.targetPitch();
    }

    @LuaFunction
    public final double getRange() {
        return AshCCConfig.LASER_RANGE.get();
    }

    /** World direction of the beam for the current aim, plus where it starts ({@code origin}). */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getDirection() throws LuaException {
        loaded();
        Map<String, Object> t = LuaUtil.vec(laser.worldDirection(laser.yaw(), laser.pitch()));
        t.put("origin", LuaUtil.vec(laser.muzzle()));
        return t;
    }

    // ---- Hits ----

    /** Where the beam would end for the current aim, without firing: {type = "block"/"entity"/"none", x, y, z, distance, ...}. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> trace() throws LuaException {
        loaded();
        return laser.trace().toLua();
    }

    /** What the beam hit on the last tick (nil while not firing). Includes {@code progress} (0..1) of burning through a block. */
    @LuaFunction(mainThread = true)
    public final @Nullable Map<String, Object> getHit() {
        LaserHit hit = laser.lastHit();
        if (hit == null) return null;
        Map<String, Object> t = hit.toLua();
        if (hit.blockPos() != null) t.put("progress", laser.burnFraction());
        return t;
    }

    /** ID of the sub-level the laser is mounted on, or nil. */
    @LuaFunction(mainThread = true)
    public final @Nullable String getSubLevel() {
        UUID id = laser.subLevel();
        return id == null ? null : id.toString();
    }

    // ---- Redstone ----

    @LuaFunction(mainThread = true)
    public final void setRedstoneControl(boolean enabled) {
        laser.setRedstoneControl(enabled);
    }

    @LuaFunction
    public final boolean isRedstoneControl() {
        return laser.redstoneControl();
    }

    @LuaFunction
    public final int getRedstoneInput() {
        return laser.redstoneInput();
    }
}
