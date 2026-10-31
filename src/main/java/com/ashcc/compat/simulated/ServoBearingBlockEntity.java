package com.ashcc.compat.simulated;

import com.ashcc.AshCCConfig;
import com.simibubi.create.content.contraptions.AssemblyException;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintHandle;
import dev.ryanhcode.sable.api.schematic.SubLevelSchematicSerializationContext;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.simulated_team.simulated.index.SimSoundEvents;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import dev.simulated_team.simulated.util.SimLevelUtil;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Servo version of Simulated's swivel bearing. Assembling turns the blocks in front into a Sable sub-level joined to
 * this block by a rotary joint (so blocks can still be placed on it afterwards); the joint motor is driven towards an
 * angle commanded from Lua at a set speed instead of by rotational power. Assembly and disassembly use Simulated's own
 * helpers, so glue and the other assembly rules behave exactly like the swivel bearing.
 */
public class ServoBearingBlockEntity extends BlockEntity implements BlockEntitySubLevelActor {
    /** How close (degrees) the turntable must be to 0 before a requested disassembly happens. */
    private static final double ZERO_TOLERANCE = 2.0;
    /** Disassemble anyway after this many ticks of trying to reach 0 (e.g. something blocks the rotation). */
    private static final int DISASSEMBLE_TIMEOUT = 100;
    private static final double AT_TARGET_TOLERANCE = 1.0;
    private static final double SETTLE_TOLERANCE = 0.2;

    /** Commanded angle in -180..180 degrees. */
    private double targetAngle;
    /** Joint motor goal (degrees) this tick and last tick. It follows targetAngle at servoSpeed. */
    private double setpoint;
    private double lastSetpoint;
    /** Degrees per tick. */
    private double servoSpeed = AshCCConfig.SERVO_DEFAULT_SPEED.get() / 20.0;
    /** Unlocked: the joint swings freely (with friction), like an unlocked swivel bearing. */
    private boolean locked = true;
    private boolean disassembleAtZero;
    private int disassembleTicks;
    private boolean toggleNextTick;
    private @Nullable String lastError;

    private @Nullable UUID subLevelID;
    private @Nullable BlockPos platePos;
    private @Nullable RotaryConstraintHandle handle;
    /** Being moved by Sable assembly: keep the plate. */
    private boolean moving;
    private @Nullable ServoBearingPeripheral peripheral;

    public ServoBearingBlockEntity(BlockPos pos, BlockState state) {
        super(SimulatedCompat.SERVO_BEARING_BE.get(), pos, state);
    }

    public ServoBearingPeripheral peripheral() {
        if (peripheral == null) peripheral = new ServoBearingPeripheral(this);
        return peripheral;
    }

    private Direction facing() {
        return getBlockState().getValue(ServoBearingBlock.FACING);
    }

    public boolean isAssembled() {
        return getBlockState().getValue(ServoBearingBlock.ASSEMBLED);
    }

    void toggleNextTick() {
        toggleNextTick = true;
    }

    void serverTick() {
        if (toggleNextTick) {
            toggleNextTick = false;
            if (isAssembled()) requestDisassemble();
            else tryAssemble();
        }
        if (subLevelID != null) checkPersistence();

        lastSetpoint = setpoint;
        if (!isAssembled()) return;
        SubLevelPhysicsSystem physics = SubLevelPhysicsSystem.get(level);
        if (physics == null || physics.getPaused()) return;
        double diff = Mth.wrapDegrees(targetAngle - setpoint);
        if (locked && diff != 0) {
            setpoint = Mth.wrapDegrees(setpoint + Math.signum(diff) * Math.min(servoSpeed, Math.abs(diff)));
            setChanged();
        }
        // Keep the bodies awake until the turntable has caught up, or they fall asleep part way
        if (locked && (diff != 0 || Math.abs(Mth.wrapDegrees(currentAngle() - setpoint)) > SETTLE_TOLERANCE)) wakeUp(physics.getPipeline());
        if (disassembleAtZero) {
            boolean atZero = setpoint == 0 && Math.abs(currentAngle()) < ZERO_TOLERANCE;
            if (atZero || ++disassembleTicks > DISASSEMBLE_TIMEOUT) disassemble();
        }
    }

    private void wakeUp(PhysicsPipeline pipeline) {
        if (getContainingSubLevel() instanceof ServerSubLevel containing) pipeline.wakeUp(containing);
        if (getAttachedSubLevel() instanceof ServerSubLevel attached) pipeline.wakeUp(attached);
    }

    // ---- Controls ----

    /** @return the target actually set, wrapped to -180..180 */
    public double setTargetAngle(double degrees) {
        targetAngle = Mth.wrapDegrees(degrees);
        disassembleAtZero = false;
        setChanged();
        return targetAngle;
    }

    public double targetAngle() {
        return targetAngle;
    }

    /** @param degreesPerSecond clamped to 1..maxSpeed */
    public double setServoSpeed(double degreesPerSecond) {
        double s = Mth.clamp(degreesPerSecond, 1, AshCCConfig.SERVO_MAX_SPEED.get());
        servoSpeed = s / 20.0;
        setChanged();
        return s;
    }

    public double servoSpeedPerSecond() {
        return servoSpeed * 20.0;
    }

    public boolean locked() {
        return locked;
    }

    /** Unlocking lets the turntable swing; locking holds it where it is and then servoes on to the target. */
    public void setLocked(boolean value) {
        if (value == locked) return;
        locked = value;
        if (locked && isAssembled()) {
            setpoint = lastSetpoint = currentAngle();
        }
        setChanged();
    }

    /**
     * Measured angle of the turntable relative to the bearing, -180..180 (0 = as assembled). Same measurement the
     * swivel bearing uses to hold its current pose when it locks, so it matches the motor's goal angle.
     */
    public double currentAngle() {
        SubLevel attached = getAttachedSubLevel();
        if (!isAssembled() || attached == null || platePos == null || level == null) return 0;
        BlockState plate = level.getBlockState(platePos);
        if (!plate.is(SimulatedCompat.SERVO_BEARING_PLATE.get())) return 0;
        Quaterniond a = new Quaterniond();
        SubLevel containing = getContainingSubLevel();
        if (containing != null) a.set(containing.logicalPose().orientation());
        a.mul(new Quaterniond(facing().getRotation()));
        Quaterniond b = new Quaterniond(attached.logicalPose().orientation()).mul(new Quaterniond(plate.getValue(ServoBearingPlateBlock.FACING).getRotation()));
        Quaterniond local = a.conjugate().mul(b);
        return Mth.wrapDegrees(Math.toDegrees(2 * Math.atan2(local.y(), local.w())));
    }

    public boolean isAtTarget() {
        return isAssembled() && locked && setpoint == targetAngle && Math.abs(Mth.wrapDegrees(currentAngle() - targetAngle)) < AT_TARGET_TOLERANCE;
    }

    public @Nullable UUID subLevelID() {
        return subLevelID;
    }

    /** Turntable position on the sub-level's plot (the bearing's own position mapped into it). */
    public @Nullable BlockPos platePos() {
        return platePos;
    }

    public @Nullable String lastError() {
        return lastError;
    }

    // ---- Joint ----

    /** Called by the plate every physics step. */
    void updateServoCoefficients() {
        if (handle != null && !handle.isValid()) handle = null;
        if (!isAssembled() || handle == null || level == null) return;
        handle.setContactsEnabled(false);
        if (!locked) {
            handle.setMotor(RotaryConstraintHandle.DEFAULT_AXIS, 0, 0, AshCCConfig.SERVO_FRICTION.get(), false, 0);
            return;
        }
        // Gains scale with the larger inertia about the axis, as in the swivel bearing
        Vector3d axis = JOMLConversion.toJOML(net.minecraft.world.phys.Vec3.atLowerCornerOf(facing().getNormal()));
        SubLevel a = getContainingSubLevel();
        SubLevel b = getAttachedSubLevel();
        double inertiaA = a instanceof ServerSubLevel s ? s.getMassTracker().getInertiaTensor().transform(axis, new Vector3d()).dot(axis) : Double.MAX_VALUE;
        double inertiaB = b instanceof ServerSubLevel s ? s.getMassTracker().getInertiaTensor().transform(axis, new Vector3d()).dot(axis) : Double.MAX_VALUE;
        double inertia = Math.max(10.0, a != null && b != null ? Math.max(inertiaA, inertiaB) : Math.min(inertiaA, inertiaB));
        SubLevelPhysicsSystem physics = SubLevelContainer.getContainer((ServerLevel) level).physicsSystem();
        float goal = AngleHelper.rad(AngleHelper.angleLerp(physics.getPartialPhysicsTick(), lastSetpoint, setpoint));
        handle.setMotor(RotaryConstraintHandle.DEFAULT_AXIS, goal, AshCCConfig.SERVO_STIFFNESS.get() * inertia, AshCCConfig.SERVO_DAMPING.get() * inertia, false, 0);
    }

    private void checkPersistence() {
        if (platePos != null && SimLevelUtil.isAreaActuallyLoaded(level, platePos, 1) && !level.getBlockState(platePos).is(SimulatedCompat.SERVO_BEARING_PLATE.get())) return;
        if (handle != null && !handle.isValid()) handle = null;
        if (handle == null && getAttachedSubLevel() instanceof ServerSubLevel attached) reattachConstraint(attached, true);
    }

    void reattachConstraint(@Nullable ServerSubLevel plateSubLevel, boolean updatePlate) {
        if (platePos == null) return;
        removeHandle();
        if (updatePlate) associatePlateWithParent();
        BlockState plate = level.getBlockState(platePos);
        if (!plate.is(SimulatedCompat.SERVO_BEARING_PLATE.get())) return;
        attachConstraint(plateSubLevel, JOMLConversion.toJOML(platePos.relative(plate.getValue(ServoBearingPlateBlock.FACING)).getCenter()));
    }

    void associatePlateWithParent() {
        if (platePos != null && level != null && level.getBlockEntity(platePos) instanceof ServoBearingPlateBlockEntity plate) plate.setParent(this);
    }

    private void attachConstraint(@Nullable ServerSubLevel plateSubLevel, Vector3d attachPos) {
        if (platePos == null) return;
        BlockState plate = level.getBlockState(platePos);
        if (!plate.is(SimulatedCompat.SERVO_BEARING_PLATE.get())) return;
        Vector3d anchor = JOMLConversion.toJOML(getBlockPos().relative(facing()).getCenter());
        Vector3d normal = JOMLConversion.toJOML(net.minecraft.world.phys.Vec3.atLowerCornerOf(facing().getNormal()));
        Vector3d plateNormal = JOMLConversion.toJOML(net.minecraft.world.phys.Vec3.atLowerCornerOf(plate.getValue(ServoBearingPlateBlock.FACING).getNormal()));
        RotaryConstraintConfiguration config = new RotaryConstraintConfiguration(anchor, attachPos.sub(plateNormal.mul(0.001, new Vector3d())), normal, plateNormal);
        ServerSubLevel containing = (ServerSubLevel) Sable.HELPER.getContaining(this);
        PhysicsPipeline pipeline = SubLevelContainer.getContainer((ServerLevel) level).physicsSystem().getPipeline();
        if (containing != plateSubLevel) handle = pipeline.addConstraint(containing, plateSubLevel, config);
    }

    private void removeHandle() {
        if (handle != null) {
            handle.remove();
            handle = null;
        }
    }

    // ---- Assembly ----

    /** @return null on success, otherwise the reason */
    public @Nullable String tryAssemble() {
        if (isAssembled()) return null;
        assemble();
        return isAssembled() ? null : Objects.requireNonNullElse(lastError, "Could not assemble");
    }

    /** Same steps as the swivel bearing. With nothing in front it makes a sub-level holding just the turntable. */
    private void assemble() {
        BlockPos pos = getBlockPos();
        BlockPos front = pos.relative(facing());
        SimAssemblyHelper.AssemblyResult result;
        try {
            result = SimAssemblyHelper.assembleFromSingleBlock(level, pos, front, false, false);
            lastError = null;
        } catch (AssemblyException e) {
            lastError = e.component.getString();
            return;
        }

        BlockState plateState = SimulatedCompat.SERVO_BEARING_PLATE.get().defaultBlockState().setValue(ServoBearingPlateBlock.FACING, facing());
        ServerSubLevel sub;
        BlockPos offset;
        if (result != null) {
            sub = (ServerSubLevel) result.subLevel();
            offset = result.offset();
        } else {
            ServerSubLevelContainer container = SubLevelContainer.getContainer((ServerLevel) level);
            Pose3d pose = new Pose3d();
            pose.position().set(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            sub = (ServerSubLevel) container.allocateNewSubLevel(pose);
            LevelPlot plot = sub.getPlot();
            ChunkPos center = plot.getCenterChunk();
            plot.newEmptyChunk(center);
            plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, plateState, 3);
            BlockPos plotAnchor = plot.getCenterBlock();
            Vector3dc com = sub.getMassTracker().getCenterOfMass();
            Vector3d subCenter = JOMLConversion.atLowerCornerOf(pos);
            if (com != null) {
                subCenter.add(com.x() - plotAnchor.getX(), com.y() - plotAnchor.getY(), com.z() - plotAnchor.getZ());
            } else {
                sub.logicalPose().rotationPoint().set(plotAnchor.getX() + 0.5, plotAnchor.getY() + 0.5, plotAnchor.getZ() + 0.5);
            }
            sub.logicalPose().position().set(subCenter.x, subCenter.y, subCenter.z);
            offset = plotAnchor.subtract(pos);
            SubLevelPhysicsSystem physics = container.physicsSystem();
            PhysicsPipeline pipeline = physics.getPipeline();
            SubLevel containing = getContainingSubLevel();
            if (containing != null) {
                SubLevelAssemblyHelper.kickFromContainingSubLevel((ServerLevel) level, physics, pipeline, sub, containing);
                sub.logicalPose().orientation().set(containing.logicalPose().orientation());
            }
            pipeline.teleport(sub, sub.logicalPose().position(), sub.logicalPose().orientation());
            sub.updateLastPose();
            level.playSound(null, pos, SimSoundEvents.SIMULATED_CONTRAPTION_MOVES.event(), SoundSource.BLOCKS, 1, 1);
        }

        level.setBlockAndUpdate(pos, getBlockState().setValue(ServoBearingBlock.ASSEMBLED, true));
        BlockPos plotPos = pos.offset(offset);
        if (result != null) level.setBlockAndUpdate(plotPos, plateState);
        if (level.getBlockEntity(plotPos) instanceof ServoBearingPlateBlockEntity plate) {
            plate.setParent(this);
            platePos = plotPos;
        }
        subLevelID = sub.getUniqueId();
        attachConstraint(sub, JOMLConversion.toJOML(front.offset(offset).getCenter()));
        setpoint = lastSetpoint = 0;
        disassembleAtZero = false;
        setChanged();
    }

    /** Disassembles now if at 0 degrees (or unlocked), otherwise returns to 0 first. @return true if disassembled immediately */
    public boolean requestDisassemble() {
        if (!isAssembled()) return true;
        if (!locked || Math.abs(currentAngle()) < ZERO_TOLERANCE) {
            disassemble();
            return true;
        }
        targetAngle = 0;
        disassembleAtZero = true;
        disassembleTicks = 0;
        setChanged();
        return false;
    }

    /** Puts the blocks back in the world, unrotated (like the swivel bearing). */
    private void disassemble() {
        if (isRemoved()) return;
        removeHandle();
        SubLevel sub = getAttachedSubLevel();
        BlockPos plate = platePos;
        if (plate != null) {
            destroyPlate();
            if (sub != null && Objects.equals(sub, getContainingSubLevel())) {
                lastError = "The turntable is part of the bearing's own sub-level";
                level.playSound(null, plate, SimSoundEvents.ASSEMBLER_FAIL.event(), SoundSource.BLOCKS, 1, 1);
            } else if (sub != null) {
                if (!sub.isRemoved()) SimAssemblyHelper.disassembleSubLevel(level, sub, plate, getBlockPos(), Rotation.NONE, true);
                else level.playSound(null, plate, SimSoundEvents.SIMULATED_CONTRAPTION_STOPS.event(), SoundSource.BLOCKS, 1, 1);
            }
        }
        level.setBlockAndUpdate(getBlockPos(), getBlockState().setValue(ServoBearingBlock.ASSEMBLED, false));
        subLevelID = null;
        platePos = null;
        targetAngle = setpoint = lastSetpoint = 0;
        disassembleAtZero = false;
        setChanged();
    }

    private void destroyPlate() {
        if (platePos == null || level == null) return;
        if (subLevelID != null && getAttachedSubLevel() == null) return;
        if (level.getBlockEntity(platePos) instanceof ServoBearingPlateBlockEntity plate) plate.beforeMove();
        if (level.getBlockState(platePos).is(SimulatedCompat.SERVO_BEARING_PLATE.get())) level.setBlock(platePos, Blocks.AIR.defaultBlockState(), 2);
    }

    void beforeMove() {
        moving = true;
    }

    /** The bearing block was broken: take the turntable with it. */
    void onBroken() {
        if (!moving) destroyPlate();
        removeHandle();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        removeHandle();
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        removeHandle();
    }

    void setPlatePos(@Nullable BlockPos pos) {
        platePos = pos;
        setChanged();
    }

    void setSubLevelID(@Nullable UUID id) {
        subLevelID = id;
        setChanged();
    }

    private @Nullable SubLevel getAttachedSubLevel() {
        return subLevelID == null || level == null ? null : SubLevelContainer.getContainer(level).getSubLevel(subLevelID);
    }

    private @Nullable SubLevel getContainingSubLevel() {
        return Sable.HELPER.getContaining(this);
    }

    @Override
    public @Nullable Iterable<SubLevel> sable$getConnectionDependencies() {
        SubLevel attached = getAttachedSubLevel();
        return attached == null ? null : List.of(attached);
    }

    // ---- Save ----

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putDouble("TargetAngle", targetAngle);
        tag.putDouble("Setpoint", setpoint);
        tag.putDouble("ServoSpeed", servoSpeed);
        tag.putBoolean("Locked", locked);
        tag.putBoolean("DisassembleAtZero", disassembleAtZero);
        UUID id = subLevelID;
        BlockPos plate = platePos;
        // Simulated's schematics remap sub-level ids and plot positions
        SubLevelSchematicSerializationContext schematic = SubLevelSchematicSerializationContext.getCurrentContext();
        if (id != null && schematic != null) {
            SubLevelSchematicSerializationContext.SchematicMapping mapping = schematic.getMapping(id);
            id = mapping != null ? mapping.newUUID() : null;
            plate = mapping != null && plate != null ? mapping.transform().apply(plate) : null;
        }
        if (id != null) tag.putUUID("SubLevelID", id);
        if (plate != null) tag.put("ServoPlate", NbtUtils.writeBlockPos(plate));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        targetAngle = tag.getDouble("TargetAngle");
        setpoint = lastSetpoint = tag.getDouble("Setpoint");
        if (tag.contains("ServoSpeed")) servoSpeed = tag.getDouble("ServoSpeed");
        locked = !tag.contains("Locked") || tag.getBoolean("Locked");
        disassembleAtZero = tag.getBoolean("DisassembleAtZero");
        subLevelID = null;
        if (tag.hasUUID("SubLevelID")) {
            UUID id = tag.getUUID("SubLevelID");
            SubLevelSchematicSerializationContext schematic = SubLevelSchematicSerializationContext.getCurrentContext();
            SubLevelSchematicSerializationContext.SchematicMapping mapping = schematic != null ? schematic.getMapping(id) : null;
            subLevelID = mapping != null ? mapping.newUUID() : id;
        }
        platePos = NbtUtils.readBlockPos(tag, "ServoPlate").orElse(null);
    }
}
