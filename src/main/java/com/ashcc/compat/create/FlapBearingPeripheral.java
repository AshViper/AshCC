package com.ashcc.compat.create;

import com.ashcc.AshCC;
import com.ashcc.AshCCConfig;
import com.ashcc.compat.SableCompat;
import com.ashcc.radar.LuaUtil;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/** Lua API of the flap bearing (peripheral type "flap_bearing"). Angles in degrees, speed in degrees/second. */
public class FlapBearingPeripheral implements IPeripheral {
    private final FlapBearingBlockEntity bearing;

    FlapBearingPeripheral(FlapBearingBlockEntity bearing) {
        this.bearing = bearing;
    }

    @Override
    public String getType() {
        return "flap_bearing";
    }

    @Override
    public Object getTarget() {
        return bearing;
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof FlapBearingPeripheral o && o.bearing == bearing;
    }

    private static double finite(double v, String name) throws LuaException {
        if (!Double.isFinite(v)) throw new LuaException(name + " must be a finite number");
        return v;
    }

    /** setAngle(degrees) -> the clamped target (+-maxAngle). The bearing turns there at the servo speed. */
    @LuaFunction(mainThread = true)
    public final double setAngle(double degrees) throws LuaException {
        return bearing.setTargetAngle(finite(degrees, "angle"));
    }

    @LuaFunction
    public final double getAngle() {
        return bearing.currentAngle();
    }

    @LuaFunction
    public final double getTargetAngle() {
        return bearing.targetAngle();
    }

    @LuaFunction
    public final boolean isAtTarget() {
        return bearing.isAtTarget();
    }

    @LuaFunction
    public final double getMaxAngle() {
        return AshCCConfig.FLAP_MAX_ANGLE.get();
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
        return AshCCConfig.FLAP_MAX_SPEED.get();
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

    @LuaFunction
    public final boolean isAssembled() {
        return bearing.assembled();
    }

    /** World-space rotation axis (the direction the bearing faces), rotating with a Sable ship. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getAxis() {
        Vec3 dir = Vec3.atLowerCornerOf(bearing.getBlockState().getValue(DirectionalKineticBlock.FACING).getNormal());
        if (AshCC.sableLoaded && bearing.getLevel() != null) dir = SableCompat.dirToWorld(bearing.getLevel(), bearing.getBlockPos(), dir);
        return LuaUtil.vec(dir);
    }
}
