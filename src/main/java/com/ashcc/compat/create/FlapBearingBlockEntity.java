package com.ashcc.compat.create;

import com.ashcc.AshCCConfig;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * A mechanical bearing that works as a servo: it needs no rotational power and turns the attached contraption
 * towards a target angle at a set speed. Assembles / disassembles like a mechanical bearing (right-click or Lua).
 * On a Sable ship the contraption rides with it, so sails on it act as control surfaces.
 */
public class FlapBearingBlockEntity extends MechanicalBearingBlockEntity {
    private float targetAngle;
    /** Degrees per tick. */
    private float servoSpeed = (float) (AshCCConfig.FLAP_DEFAULT_SPEED.get() / 20.0);
    /** Disassemble once the flap has returned to 0 degrees. */
    private boolean disassembleAtZero;
    private @Nullable FlapBearingPeripheral peripheral;

    public FlapBearingBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public FlapBearingPeripheral peripheral() {
        if (peripheral == null) peripheral = new FlapBearingPeripheral(this);
        return peripheral;
    }

    @Override
    public float getGeneratedSpeed() {
        return 0;
    }

    @Override
    public float calculateStressApplied() {
        return 0;
    }

    /**
     * The base class schedules (dis)assembly on every speed change, including the one assemble() itself causes.
     * Here assembleNextTick only means "the player right-clicked", so keep it as it was.
     */
    @Override
    public void onSpeedChanged(float prevSpeed) {
        boolean requested = assembleNextTick;
        super.onSpeedChanged(prevSpeed);
        assembleNextTick = requested;
    }

    @Override
    public float getAngularSpeed() {
        float diff = AngleHelper.getShortestAngleDiff(angle, targetAngle);
        float speed = Math.abs(diff) < 1e-3f ? 0 : Math.signum(diff) * Math.min(servoSpeed, Math.abs(diff));
        if (level != null && level.isClientSide) speed += clientAngleDiff / 3f;
        return speed;
    }

    @Override
    public void tick() {
        // The base class only assembles with rotational input; handle the right-click toggle here instead
        if (level != null && !level.isClientSide && assembleNextTick) {
            assembleNextTick = false;
            if (running) requestDisassemble();
            else tryAssemble();
        }
        super.tick();
        if (level != null && !level.isClientSide && disassembleAtZero && running && isAtTarget()) {
            disassembleAtZero = false;
            disassemble();
            sendData();
        }
    }

    // ---- Controls ----

    public float setTargetAngle(double degrees) {
        double max = AshCCConfig.FLAP_MAX_ANGLE.get();
        targetAngle = (float) Mth.clamp(Mth.wrapDegrees(degrees), -max, max);
        disassembleAtZero = false;
        sendData();
        setChanged();
        return targetAngle;
    }

    /** @param degreesPerSecond clamped to 1..maxSpeed */
    public double setServoSpeed(double degreesPerSecond) {
        double s = Mth.clamp(degreesPerSecond, 1, AshCCConfig.FLAP_MAX_SPEED.get());
        servoSpeed = (float) (s / 20.0);
        sendData();
        setChanged();
        return s;
    }

    public double servoSpeedPerSecond() {
        return servoSpeed * 20.0;
    }

    public float targetAngle() {
        return targetAngle;
    }

    /** Current angle in -180..180. */
    public float currentAngle() {
        return Mth.wrapDegrees(angle);
    }

    public boolean isAtTarget() {
        return Math.abs(AngleHelper.getShortestAngleDiff(angle, targetAngle)) < 1e-3f;
    }

    /** Returning to 0 degrees to disassemble there. */
    public boolean disassembling() {
        return disassembleAtZero;
    }

    public boolean assembled() {
        return running && movedContraption != null;
    }

    /** @return null on success, otherwise the reason */
    public @Nullable String tryAssemble() {
        if (running) return null;
        assemble();
        sendData();
        AssemblyException e = getLastAssemblyException();
        if (running) return null;
        return e != null ? e.component.getString() : "Nothing to assemble";
    }

    /** Disassembles now if at 0 degrees, otherwise returns to 0 first. @return true if disassembled immediately */
    public boolean requestDisassemble() {
        if (!running) return true;
        if (Math.abs(currentAngle()) < 1e-3f) {
            disassemble();
            sendData();
            return true;
        }
        targetAngle = 0;
        disassembleAtZero = true;
        sendData();
        return false;
    }

    // ---- Save / sync ----

    @Override
    public void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putFloat("TargetAngle", targetAngle);
        tag.putFloat("ServoSpeed", servoSpeed);
        tag.putBoolean("DisassembleAtZero", disassembleAtZero);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        targetAngle = tag.getFloat("TargetAngle");
        if (tag.contains("ServoSpeed")) servoSpeed = tag.getFloat("ServoSpeed");
        disassembleAtZero = tag.getBoolean("DisassembleAtZero");
    }
}
