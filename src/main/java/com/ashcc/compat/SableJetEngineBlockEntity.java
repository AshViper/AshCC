package com.ashcc.compat;

import com.ashcc.AshCC;
import com.ashcc.jet.JetEngineBlockEntity;
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

/** Jet engine nozzle that pushes the Sable sub-level it is mounted on, at the nozzle, against the exhaust. */
public class SableJetEngineBlockEntity extends JetEngineBlockEntity implements BlockEntitySubLevelActor {
    public SableJetEngineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public static JetEngineBlockEntity create(BlockPos pos, BlockState state) {
        return new SableJetEngineBlockEntity(AshCC.JET_ENGINE_BE.get(), pos, state);
    }

    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep) {
        if (isRemoved()) return;
        Vec3 f = localForce();
        if (f.lengthSqr() < 1e-12) return;
        Vector3d point = JOMLConversion.atCenterOf(getBlockPos());
        Vector3d impulse = new Vector3d(f.x * timeStep, f.y * timeStep, f.z * timeStep);
        subLevel.getOrCreateQueuedForceGroup(ForceGroups.PROPULSION.get()).applyAndRecordPointForce(point, impulse);
    }
}
