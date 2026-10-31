package com.ashcc.seat;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * An invisible seat anchored to a block (a seat block, or a control panel / cockpit). The entity stays in the block's own
 * coordinates — on a Sable ship that is the plot grid, so the ship carries the seat (and its rider) with it. It removes
 * itself when the block is gone or nobody has sat on it for a moment.
 */
public class SeatEntity extends Entity {
    private @Nullable BlockPos block;

    public SeatEntity(EntityType<?> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    public void setBlock(BlockPos pos) {
        block = pos;
    }

    public @Nullable BlockPos blockPos() {
        return block;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide || block == null) return;
        Vec3 anchor = Seats.anchor(level(), block);
        if (anchor == null) {
            ejectPassengers();
            discard();
            return;
        }
        setPos(anchor.x, anchor.y, anchor.z);
        if (getPassengers().isEmpty() && tickCount > 20) discard();
    }

    @Override
    protected void positionRider(Entity passenger, MoveFunction callback) {
        callback.accept(passenger, getX(), getY(), getZ());
    }

    /** Lets riders use the sitting pose (present on Entity in 1.21; kept override-free so it is harmless either way). */
    public boolean shouldRiderSit() {
        return true;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().isEmpty();
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        if (block != null) {
            Vec3 spot = Seats.dismount(level(), block);
            if (spot != null) return spot;
        }
        return super.getDismountLocationForPassenger(passenger);
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(net.minecraft.world.damagesource.DamageSource source) {
        return true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Block")) {
            int[] a = tag.getIntArray("Block");
            if (a.length == 3) block = new BlockPos(a[0], a[1], a[2]);
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (block != null) tag.putIntArray("Block", new int[]{block.getX(), block.getY(), block.getZ()});
    }
}
