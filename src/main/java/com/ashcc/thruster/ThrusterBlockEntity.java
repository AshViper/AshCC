package com.ashcc.thruster;

import com.ashcc.AshCC;
import com.ashcc.AshCCConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * Throttle and gimbal state of a thruster. With Sable installed the registered type creates
 * {@code SableThrusterBlockEntity}, which applies {@link #localForce()} to the ship every physics step.
 * All vectors here are in block coordinates (the sub-level's own frame when on a ship).
 */
public class ThrusterBlockEntity extends BlockEntity {
    private @Nullable ThrusterPeripheral peripheral;

    // Targets set by Lua / redstone
    private double targetThrottle;
    private double targetX;
    private double targetY;
    private boolean redstoneControl = true;
    private int redstoneInput;

    // Current values, read by the physics step
    private volatile double throttle;
    private volatile double gimbalX;
    private volatile double gimbalY;

    public ThrusterBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public ThrusterBlockEntity(BlockPos pos, BlockState state) {
        this(AshCC.THRUSTER_BE.get(), pos, state);
    }

    public ThrusterPeripheral peripheral() {
        if (peripheral == null) peripheral = new ThrusterPeripheral(this);
        return peripheral;
    }

    // ---- Controls ----

    public void setThrottle(double t) {
        targetThrottle = Mth.clamp(t, 0, 1);
        redstoneControl = false;
        setChanged();
    }

    public void setGimbal(double x, double y) {
        targetX = Mth.clamp(x, -1, 1);
        targetY = Mth.clamp(y, -1, 1);
        setChanged();
    }

    public void setRedstoneControl(boolean enabled) {
        redstoneControl = enabled;
        setChanged();
    }

    public double targetThrottle() {
        return redstoneControl ? redstoneInput / 15.0 : targetThrottle;
    }

    public double throttle() {
        return throttle;
    }

    public double gimbalX() {
        return gimbalX;
    }

    public double gimbalY() {
        return gimbalY;
    }

    public double targetX() {
        return targetX;
    }

    public double targetY() {
        return targetY;
    }

    public boolean redstoneControl() {
        return redstoneControl;
    }

    public int redstoneInput() {
        return redstoneInput;
    }

    // ---- Geometry (block coordinates) ----

    public Direction nozzle() {
        return getBlockState().getValue(ThrusterBlock.FACING);
    }

    /** Unit vector the ship is pushed along with no gimbal. */
    public Vec3 thrustAxis() {
        return Vec3.atLowerCornerOf(nozzle().getOpposite().getNormal());
    }

    /** Directions that gimbal x / y tilt the thrust towards. Vertical: x = +X, y = +Z. Horizontal: x = right, y = up. */
    public Vec3 gimbalAxisX() {
        Vec3 d = thrustAxis();
        return nozzle().getAxis() == Direction.Axis.Y ? new Vec3(1, 0, 0) : d.cross(new Vec3(0, 1, 0));
    }

    public Vec3 gimbalAxisY() {
        return nozzle().getAxis() == Direction.Axis.Y ? new Vec3(0, 0, 1) : new Vec3(0, 1, 0);
    }

    /** Direction of the current thrust including gimbal (unit vector). */
    public Vec3 thrustDirection() {
        double max = Math.toRadians(AshCCConfig.THRUSTER_MAX_GIMBAL.get());
        return thrustAxis()
            .add(gimbalAxisX().scale(Math.tan(gimbalX * max)))
            .add(gimbalAxisY().scale(Math.tan(gimbalY * max)))
            .normalize();
    }

    public double force() {
        return throttle * AshCCConfig.THRUSTER_MAX_THRUST.get();
    }

    /** Current force vector in block coordinates. */
    public Vec3 localForce() {
        return thrustDirection().scale(force());
    }

    // ---- Tick ----

    public static void serverTick(Level level, BlockPos pos, BlockState state, ThrusterBlockEntity be) {
        if (be.redstoneControl) be.redstoneInput = level.getBestNeighborSignal(pos);
        double rate = AshCCConfig.THRUSTER_THROTTLE_RATE.get();
        double gRate = AshCCConfig.THRUSTER_GIMBAL_RATE.get();
        be.throttle = approach(be.throttle, be.targetThrottle(), rate);
        be.gimbalX = approach(be.gimbalX, be.targetX, gRate);
        be.gimbalY = approach(be.gimbalY, be.targetY, gRate);

        boolean active = be.throttle > 1e-3;
        if (state.getValue(ThrusterBlock.ACTIVE) != active) {
            level.setBlock(pos, state.setValue(ThrusterBlock.ACTIVE, active), Block.UPDATE_CLIENTS);
        }
    }

    private static double approach(double current, double target, double rate) {
        double next = current + (target - current) * rate;
        return Math.abs(target - next) < 1e-4 ? target : next;
    }

    // ---- Save ----

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putDouble("Throttle", targetThrottle);
        tag.putDouble("GimbalX", targetX);
        tag.putDouble("GimbalY", targetY);
        tag.putBoolean("RedstoneControl", redstoneControl);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        targetThrottle = tag.getDouble("Throttle");
        targetX = tag.getDouble("GimbalX");
        targetY = tag.getDouble("GimbalY");
        redstoneControl = !tag.contains("RedstoneControl") || tag.getBoolean("RedstoneControl");
    }
}
