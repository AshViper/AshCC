package com.ashcc.compat;

import com.ashcc.AshCC;
import com.ashcc.thruster.ThrusterBlockEntity;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/** Thruster that pushes the Sable sub-level it is mounted on. Sable calls the physics tick only for blocks on a ship. */
public class SableThrusterBlockEntity extends ThrusterBlockEntity implements BlockEntitySubLevelActor {
    public SableThrusterBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public static ThrusterBlockEntity create(BlockPos pos, BlockState state) {
        return new SableThrusterBlockEntity(AshCC.THRUSTER_BE.get(), pos, state);
    }

    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep) {
        if (isRemoved()) return;
        Vec3 f = localForce();
        if (f.lengthSqr() < 1e-12) return;
        // Same as Sable's own propellers: impulse = force * dt at the block centre, both in the ship's frame
        Vector3d point = JOMLConversion.atCenterOf(getBlockPos());
        Vector3d impulse = new Vector3d(f.x * timeStep, f.y * timeStep, f.z * timeStep);
        subLevel.getOrCreateQueuedForceGroup(ForceGroups.PROPULSION.get()).applyAndRecordPointForce(point, impulse);
    }
}
