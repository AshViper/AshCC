package com.ashcc.compat.simulated;

import com.ashcc.AshCCConfig;
import com.ashcc.compat.SableCompat;
import com.ashcc.radar.LuaUtil;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/** Lua API of the servo bearing (peripheral type "servo_bearing"). Angles in degrees, speed in degrees/second. */
public class ServoBearingPeripheral implements IPeripheral {
    private final ServoBearingBlockEntity bearing;

    ServoBearingPeripheral(ServoBearingBlockEntity bearing) {
        this.bearing = bearing;
    }

    @Override
    public String getType() {
        return "servo_bearing";
    }

    @Override
    public Object getTarget() {
        return bearing;
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof ServoBearingPeripheral o && o.bearing == bearing;
    }

    private static double finite(double v, String name) throws LuaException {
        if (!Double.isFinite(v)) throw new LuaException(name + " must be a finite number");
        return v;
    }

    /** setAngle(degrees) -> the target wrapped to -180..180. The turntable turns there the short way at the servo speed. */
    @LuaFunction(mainThread = true)
    public final double setAngle(double degrees) throws LuaException {
        return bearing.setTargetAngle(finite(degrees, "angle"));
    }

    /** Measured angle of the turntable, -180..180 (0 = as assembled). */
    @LuaFunction(mainThread = true)
    public final double getAngle() {
        return bearing.currentAngle();
    }

    @LuaFunction
    public final double getTargetAngle() {
        return bearing.targetAngle();
    }

    /** True once the servo has finished moving and the turntable is within 1 degree of the target. */
    @LuaFunction(mainThread = true)
    public final boolean isAtTarget() {
        return bearing.isAtTarget();
    }

    /** setSpeed(degreesPerSecond) -> the clamped speed (1..maxSpeed). */
    @LuaFunction(mainThread = true)
    public final double setSpeed(double degreesPerSecond) throws LuaException {
        return bearing.setServoSpeed(finite(degreesPerSecond, "speed"));
    }

    @LuaFunction
    public final double getSpeed() {
        return bearing.servoSpeedPerSecond();
    }

    @LuaFunction
    public final double getMaxSpeed() {
        return AshCCConfig.SERVO_MAX_SPEED.get();
    }

    /** setLocked(false) lets the turntable swing freely; setLocked(true) holds it and servoes to the target again. */
    @LuaFunction(mainThread = true)
    public final void setLocked(boolean locked) {
        bearing.setLocked(locked);
    }

    @LuaFunction
    public final boolean isLocked() {
        return bearing.locked();
    }

    /** assemble() -> true, or false and the reason. */
    @LuaFunction(mainThread = true)
    public final MethodResult assemble() {
        String error = bearing.tryAssemble();
        return error == null ? MethodResult.of(true) : MethodResult.of(false, error);
    }

    /** disassemble() -> true when done now; false means it is returning to 0 degrees and will disassemble there. */
    @LuaFunction(mainThread = true)
    public final boolean disassemble() {
        return bearing.requestDisassemble();
    }

    @LuaFunction(mainThread = true)
    public final boolean isAssembled() {
        return bearing.isAssembled();
    }

    /** UUID of the turntable's sub-level (the same id CC: Sable and the radar use), or nil. */
    @LuaFunction(mainThread = true)
    public final @Nullable String getSubLevel() {
        UUID id = bearing.isAssembled() ? bearing.subLevelID() : null;
        return id != null ? id.toString() : null;
    }

    /** World-space rotation axis (the direction the turntable faces), rotating with a Sable ship. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getAxis() {
        Vec3 dir = Vec3.atLowerCornerOf(bearing.getBlockState().getValue(ServoBearingBlock.FACING).getNormal());
        if (bearing.getLevel() != null) dir = SableCompat.dirToWorld(bearing.getLevel(), bearing.getBlockPos(), dir);
        return LuaUtil.vec(dir);
    }
}
