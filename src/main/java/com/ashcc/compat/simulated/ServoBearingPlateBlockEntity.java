package com.ashcc.compat.simulated;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/** Links the turntable back to its bearing and drives the joint motor every physics step (the plate is always on a ship). */
public class ServoBearingPlateBlockEntity extends BlockEntity implements BlockEntitySubLevelActor {
    private @Nullable BlockPos parent;
    private @Nullable UUID parentSubLevelId;
    /** Being moved by Sable or removed by the bearing itself: do not break the bearing. */
    private boolean moving;

    public ServoBearingPlateBlockEntity(BlockPos pos, BlockState state) {
        super(SimulatedCompat.SERVO_BEARING_PLATE_BE.get(), pos, state);
    }

    void setParent(ServoBearingBlockEntity bearing) {
        SubLevel sub = Sable.HELPER.getContaining(bearing);
        parent = bearing.getBlockPos();
        parentSubLevelId = sub != null ? sub.getUniqueId() : null;
        setChanged();
    }

    private @Nullable ServoBearingBlockEntity parentBearing() {
        return parent != null && level != null && level.getBlockEntity(parent) instanceof ServoBearingBlockEntity be ? be : null;
    }

    void beforeMove() {
        moving = true;
    }

    void onBroken() {
        if (!moving && parent != null && level != null && level.getBlockState(parent).is(SimulatedCompat.SERVO_BEARING.get())) {
            level.destroyBlock(parent, false);
        }
    }

    void toggleParent() {
        ServoBearingBlockEntity be = parentBearing();
        if (be != null) be.toggleNextTick();
    }

    /** The ship carrying the plate was re-assembled or split: point the bearing at the new sub-level. */
    void fixParentLinkingWhenMoved() {
        if (level == null || level.isClientSide) return;
        ServoBearingBlockEntity be = parentBearing();
        if (be == null) return;
        be.setPlatePos(getBlockPos());
        SubLevel sub = Sable.HELPER.getContaining(this);
        be.setSubLevelID(sub != null ? sub.getUniqueId() : null);
        be.reattachConstraint(sub instanceof ServerSubLevel s ? s : null, true);
    }

    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep) {
        ServoBearingBlockEntity be = parentBearing();
        if (be != null) be.updateServoCoefficients();
    }

    @Override
    public @Nullable Iterable<SubLevel> sable$getConnectionDependencies() {
        if (parentSubLevelId == null || level == null) return null;
        SubLevel sub = SubLevelContainer.getContainer(level).getSubLevel(parentSubLevelId);
        return sub != null ? List.of(sub) : null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (parent != null) tag.put("ParentPos", NbtUtils.writeBlockPos(parent));
        if (parentSubLevelId != null) tag.putUUID("ParentSubLevelId", parentSubLevelId);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        parent = NbtUtils.readBlockPos(tag, "ParentPos").orElse(null);
        parentSubLevelId = tag.hasUUID("ParentSubLevelId") ? tag.getUUID("ParentSubLevelId") : null;
    }
}
