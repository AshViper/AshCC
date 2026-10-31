package com.ashcc.antigrav;

import com.ashcc.AshCC;
import com.ashcc.AshCCConfig;
import dan200.computercraft.api.peripheral.AttachedComputerSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Settings of an anti-gravity core. With Sable installed the registered type creates
 * {@code SableAntigravBlockEntity}, which reads {@link #command()} every physics step and pushes the ship.
 * Only one core per ship drives it (the first enabled one Sable lists); the others stand by.
 */
public class AntigravBlockEntity extends BlockEntity {
    public enum LinearMode { FREE, VELOCITY, LOCAL_VELOCITY, POSITION }

    public enum AngularMode { FREE, RATE, ROTATION }

    /**
     * Everything the physics step needs, swapped as a whole.
     * linear: world velocity (VELOCITY), right/up/forward velocity (LOCAL_VELOCITY) or world target of this block (POSITION).
     * angular: yawRate/pitchRate/rollRate in deg/s (RATE) or yaw/pitch/roll in degrees (ROTATION).
     */
    public record Command(boolean enabled, double gravity, LinearMode linearMode, Vec3 linear, AngularMode angularMode, Vec3 angular,
                          double maxSpeed, double maxAcceleration, double maxAngularSpeed, double maxAngularAcceleration) {
    }

    private @Nullable AntigravPeripheral peripheral;
    final AttachedComputerSet computers = new AttachedComputerSet();

    private boolean enabled = true;
    private double gravity;
    private LinearMode linearMode = LinearMode.VELOCITY;
    private Vec3 linear = Vec3.ZERO;
    private AngularMode angularMode = AngularMode.RATE;
    private Vec3 angular = Vec3.ZERO;
    private double maxSpeed = AshCCConfig.ANTIGRAV_MAX_SPEED.get();
    private double maxAcceleration = AshCCConfig.ANTIGRAV_MAX_ACCELERATION.get();
    private double maxAngularSpeed = AshCCConfig.ANTIGRAV_MAX_ANGULAR_SPEED.get();
    private double maxAngularAcceleration = AshCCConfig.ANTIGRAV_MAX_ANGULAR_ACCELERATION.get();
    private boolean powered;

    private volatile Command command;

    // Written by the physics step
    protected volatile boolean driving;
    protected volatile boolean positionReached;
    protected volatile boolean rotationReached;
    private boolean positionEventSent;
    private boolean rotationEventSent;

    public AntigravBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        rebuild();
    }

    public AntigravBlockEntity(BlockPos pos, BlockState state) {
        this(AshCC.ANTIGRAV_BE.get(), pos, state);
    }

    public AntigravPeripheral peripheral() {
        if (peripheral == null) peripheral = new AntigravPeripheral(this);
        return peripheral;
    }

    public Command command() {
        return command;
    }

    private void rebuild() {
        command = new Command(enabled && !powered, gravity, linearMode, linear, angularMode, angular,
            maxSpeed, maxAcceleration, maxAngularSpeed, maxAngularAcceleration);
    }

    private void changed() {
        rebuild();
        setChanged();
        onCommandChanged();
    }

    // ---- Controls ----

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        changed();
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean powered() {
        return powered;
    }

    /** 0 = weightless, 1 = normal gravity; anything in between (or beyond) scales it. */
    public void setGravity(double scale) {
        gravity = scale;
        changed();
    }

    public double gravity() {
        return gravity;
    }

    public void setLinear(LinearMode mode, Vec3 value) {
        linearMode = mode;
        linear = value;
        positionReached = false;
        positionEventSent = false;
        changed();
    }

    public void setAngular(AngularMode mode, Vec3 value) {
        angularMode = mode;
        angular = mode == AngularMode.ROTATION ? new Vec3(Mth.wrapDegrees(value.x), Mth.clamp(value.y, -90, 90), Mth.wrapDegrees(value.z)) : value;
        rotationReached = false;
        rotationEventSent = false;
        changed();
    }

    public LinearMode linearMode() {
        return linearMode;
    }

    public Vec3 linear() {
        return linear;
    }

    public AngularMode angularMode() {
        return angularMode;
    }

    public Vec3 angular() {
        return angular;
    }

    public void setLimits(double speed, double acceleration, double angularSpeed, double angularAcceleration) {
        maxSpeed = Mth.clamp(speed, 0, AshCCConfig.ANTIGRAV_SPEED_CAP.get());
        maxAcceleration = Mth.clamp(acceleration, 0, AshCCConfig.ANTIGRAV_ACCELERATION_CAP.get());
        maxAngularSpeed = Mth.clamp(angularSpeed, 0, AshCCConfig.ANTIGRAV_ANGULAR_SPEED_CAP.get());
        maxAngularAcceleration = Mth.clamp(angularAcceleration, 0, AshCCConfig.ANTIGRAV_ANGULAR_ACCELERATION_CAP.get());
        changed();
    }

    public boolean driving() {
        return driving;
    }

    public boolean positionReached() {
        return positionReached;
    }

    public boolean rotationReached() {
        return rotationReached;
    }

    // ---- Sable hooks (no-ops without Sable) ----

    /** The ship this core sits on, or null when it is not on one (or Sable is missing). */
    public @Nullable ShipState shipState() {
        return null;
    }

    /** Called every server tick on the server. */
    protected void sableTick() {
    }

    /** Called when a setting changes, so a sleeping ship can be woken. */
    protected void onCommandChanged() {
    }

    // ---- Tick ----

    public static void serverTick(Level level, BlockPos pos, BlockState state, AntigravBlockEntity be) {
        boolean powered = level.hasNeighborSignal(pos);
        if (powered != be.powered) {
            be.powered = powered;
            be.changed();
        }
        be.sableTick();

        if (be.driving && be.linearMode == LinearMode.POSITION && be.positionReached && !be.positionEventSent) {
            be.positionEventSent = true;
            Vec3 t = be.linear;
            be.computers.forEach(c -> c.queueEvent("antigravity_arrived", c.getAttachmentName(), "position", t.x, t.y, t.z));
        }
        if (be.driving && be.angularMode == AngularMode.ROTATION && be.rotationReached && !be.rotationEventSent) {
            be.rotationEventSent = true;
            Vec3 r = be.angular;
            be.computers.forEach(c -> c.queueEvent("antigravity_arrived", c.getAttachmentName(), "rotation", r.x, r.y, r.z));
        }

        boolean active = be.driving;
        if (state.getValue(AntigravBlock.ACTIVE) != active) {
            level.setBlock(pos, state.setValue(AntigravBlock.ACTIVE, active), Block.UPDATE_CLIENTS);
        }
    }

    // ---- Save ----

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("Enabled", enabled);
        tag.putDouble("Gravity", gravity);
        tag.putString("LinearMode", linearMode.name());
        putVec(tag, "Linear", linear);
        tag.putString("AngularMode", angularMode.name());
        putVec(tag, "Angular", angular);
        tag.putDouble("MaxSpeed", maxSpeed);
        tag.putDouble("MaxAcceleration", maxAcceleration);
        tag.putDouble("MaxAngularSpeed", maxAngularSpeed);
        tag.putDouble("MaxAngularAcceleration", maxAngularAcceleration);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Enabled")) {
            enabled = tag.getBoolean("Enabled");
            gravity = tag.getDouble("Gravity");
            linearMode = parse(LinearMode.class, tag.getString("LinearMode"), LinearMode.VELOCITY);
            linear = getVec(tag, "Linear");
            angularMode = parse(AngularMode.class, tag.getString("AngularMode"), AngularMode.RATE);
            angular = getVec(tag, "Angular");
            maxSpeed = tag.getDouble("MaxSpeed");
            maxAcceleration = tag.getDouble("MaxAcceleration");
            maxAngularSpeed = tag.getDouble("MaxAngularSpeed");
            maxAngularAcceleration = tag.getDouble("MaxAngularAcceleration");
        }
        rebuild();
    }

    private static void putVec(CompoundTag tag, String key, Vec3 v) {
        tag.putDouble(key + "X", v.x);
        tag.putDouble(key + "Y", v.y);
        tag.putDouble(key + "Z", v.z);
    }

    private static Vec3 getVec(CompoundTag tag, String key) {
        return new Vec3(tag.getDouble(key + "X"), tag.getDouble(key + "Y"), tag.getDouble(key + "Z"));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
