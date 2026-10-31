package com.ashcc.compat.create;

import com.ashcc.AshCCConfig;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.IControlContraption;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Four flap bearings in one block. FACING is the body axis; each of the four faces around it assembles the
 * blocks in front of it into its own contraption and turns it about that face's axis, like a mechanical bearing.
 * One block entity controls all four contraption entities (it is their IControlContraption).
 */
public class QuadFlapBearingBlockEntity extends BlockEntity implements IControlContraption {
    /** Contraption entities that never reattach after loading are given up after this many ticks. */
    private static final int REATTACH_TIMEOUT = 100;

    static final class Flap {
        final Direction side;
        @Nullable ControlledContraptionEntity entity;
        boolean assembled;
        float angle;
        float target;
        boolean disassembleAtZero;
        int missingTicks;

        Flap(Direction side) {
            this.side = side;
        }
    }

    private final Map<Direction, Flap> flaps = new EnumMap<>(Direction.class);
    /** Degrees per tick, shared by all four. */
    private float speed = (float) (AshCCConfig.FLAP_DEFAULT_SPEED.get() / 20.0);
    private @Nullable QuadFlapBearingPeripheral peripheral;

    public QuadFlapBearingBlockEntity(BlockPos pos, BlockState state) {
        super(CreateCompat.QUAD_FLAP_BEARING_BE.get(), pos, state);
    }

    public QuadFlapBearingPeripheral peripheral() {
        if (peripheral == null) peripheral = new QuadFlapBearingPeripheral(this);
        return peripheral;
    }

    public Direction facing() {
        return getBlockState().getValue(QuadFlapBearingBlock.FACING);
    }

    /** The four flap faces, in a fixed order. */
    public List<Direction> sides() {
        List<Direction> out = new ArrayList<>(4);
        for (Direction d : Direction.values()) if (d.getAxis() != facing().getAxis()) out.add(d);
        return out;
    }

    /** null if {@code side} is not one of the four flap faces. */
    @Nullable Flap flap(Direction side) {
        if (side.getAxis() == facing().getAxis()) return null;
        return flaps.computeIfAbsent(side, Flap::new);
    }

    // ---- Assembly ----

    /** @return null on success, otherwise the reason */
    @Nullable String assemble(Direction side) {
        Flap f = flap(side);
        if (f == null || level == null || level.isClientSide) return "Not a flap side";
        if (f.assembled) return null;
        BearingContraption contraption = new BearingContraption(false, side);
        try {
            if (!contraption.assemble(level, worldPosition)) return "Nothing to assemble";
        } catch (AssemblyException e) {
            return e.component.getString();
        }
        contraption.removeBlocksFromWorld(level, BlockPos.ZERO);
        ControlledContraptionEntity entity = ControlledContraptionEntity.create(level, this, contraption);
        BlockPos anchor = worldPosition.relative(side);
        entity.setPos(anchor.getX(), anchor.getY(), anchor.getZ());
        entity.setRotationAxis(side.getAxis());
        level.addFreshEntity(entity);
        AllSoundEvents.CONTRAPTION_ASSEMBLE.playOnServer(level, worldPosition);
        f.entity = entity;
        f.assembled = true;
        f.angle = 0;
        f.target = 0;
        f.disassembleAtZero = false;
        changed();
        return null;
    }

    /** Disassembles now if at 0 degrees, otherwise returns to 0 first. @return true if done immediately */
    boolean disassemble(Direction side) {
        Flap f = flap(side);
        if (f == null || !f.assembled) return true;
        if (Math.abs(Mth.wrapDegrees(f.angle)) < 1e-3f) {
            disassembleNow(f);
            return true;
        }
        f.target = 0;
        f.disassembleAtZero = true;
        changed();
        return false;
    }

    private void disassembleNow(Flap f) {
        if (f.entity != null && f.entity.isAlive()) {
            f.entity.setAngle(0);
            f.entity.disassemble();
            if (level != null) AllSoundEvents.CONTRAPTION_DISASSEMBLE.playOnServer(level, worldPosition);
        }
        f.entity = null;
        f.assembled = false;
        f.angle = 0;
        f.target = 0;
        f.disassembleAtZero = false;
        changed();
    }

    /** Puts every flap back as blocks (block broken). */
    void disassembleAll() {
        for (Flap f : flaps.values()) if (f.assembled) disassembleNow(f);
    }

    // ---- Controls ----

    float setTarget(Direction side, double degrees) {
        Flap f = flap(side);
        if (f == null) return 0;
        double max = AshCCConfig.FLAP_MAX_ANGLE.get();
        f.target = (float) Mth.clamp(Mth.wrapDegrees(degrees), -max, max);
        f.disassembleAtZero = false;
        changed();
        return f.target;
    }

    double setSpeed(double degreesPerSecond) {
        double s = Mth.clamp(degreesPerSecond, 1, AshCCConfig.FLAP_MAX_SPEED.get());
        speed = (float) (s / 20.0);
        changed();
        return s;
    }

    double speedPerSecond() {
        return speed * 20.0;
    }

    /** The contraption entity of a flap, if assembled and attached (for tests and debugging). */
    public @Nullable ControlledContraptionEntity contraption(Direction side) {
        Flap f = flaps.get(side);
        return f == null ? null : f.entity;
    }

    static boolean atTarget(Flap f) {
        return Math.abs(AngleHelper.getShortestAngleDiff(f.angle, f.target)) < 1e-3f;
    }

    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    // ---- Tick (both sides, so the client turns the contraptions smoothly) ----

    public static void tick(Level level, BlockPos pos, BlockState state, QuadFlapBearingBlockEntity be) {
        for (Flap f : be.flaps.values()) {
            if (!f.assembled) continue;
            if (f.entity != null && !f.entity.isAlive()) {
                // Removed from outside (e.g. Simulated disassembled it while building a ship)
                f.entity = null;
                if (!level.isClientSide) {
                    f.assembled = false;
                    be.changed();
                }
                continue;
            }
            if (f.entity == null) {
                if (!level.isClientSide && ++f.missingTicks > REATTACH_TIMEOUT) {
                    f.assembled = false;
                    be.changed();
                }
                continue;
            }
            f.missingTicks = 0;
            float diff = AngleHelper.getShortestAngleDiff(f.angle, f.target);
            if (Math.abs(diff) >= 1e-3f) f.angle = Mth.wrapDegrees(f.angle + Math.signum(diff) * Math.min(be.speed, Math.abs(diff)));
            else f.angle = f.target;
            f.entity.setRotationAxis(f.side.getAxis());
            f.entity.setAngle(f.angle);
            if (!level.isClientSide && f.disassembleAtZero && atTarget(f)) be.disassembleNow(f);
        }
    }

    // ---- IControlContraption ----

    @Override
    public boolean isAttachedTo(AbstractContraptionEntity entity) {
        for (Flap f : flaps.values()) if (f.entity == entity) return true;
        return false;
    }

    /** Called by a contraption entity that is not attached yet (after loading); matched by its facing. */
    @Override
    public void attach(ControlledContraptionEntity entity) {
        if (!(entity.getContraption() instanceof BearingContraption bc)) return;
        Flap f = flap(bc.getFacing());
        if (f == null) return;
        f.entity = entity;
        f.assembled = true;
        f.missingTicks = 0;
        entity.setAngle(f.angle);
    }

    @Override
    public void onStall() {
    }

    @Override
    public boolean isValid() {
        return !isRemoved();
    }

    @Override
    public BlockPos getBlockPosition() {
        return worldPosition;
    }

    // ---- Save / sync ----

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putFloat("Speed", speed);
        CompoundTag all = new CompoundTag();
        for (Flap f : flaps.values()) {
            CompoundTag c = new CompoundTag();
            c.putBoolean("Assembled", f.assembled);
            c.putFloat("Angle", f.angle);
            c.putFloat("Target", f.target);
            c.putBoolean("DisassembleAtZero", f.disassembleAtZero);
            all.put(f.side.getSerializedName(), c);
        }
        tag.put("Flaps", all);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Speed")) speed = tag.getFloat("Speed");
        CompoundTag all = tag.getCompound("Flaps");
        for (String key : all.getAllKeys()) {
            Direction side = Direction.byName(key);
            if (side == null) continue;
            CompoundTag c = all.getCompound(key);
            Flap f = flaps.computeIfAbsent(side, Flap::new);
            boolean wasAssembled = f.assembled;
            f.assembled = c.getBoolean("Assembled");
            f.target = c.getFloat("Target");
            f.disassembleAtZero = c.getBoolean("DisassembleAtZero");
            // A client keeps its own angle while the flap is moving, so updates do not make it jump
            if (level == null || !level.isClientSide || !wasAssembled) f.angle = c.getFloat("Angle");
            if (!f.assembled) f.entity = null;
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
