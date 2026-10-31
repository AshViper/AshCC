package com.ashcc.compat;

import com.ashcc.AshCC;
import com.ashcc.connector.ConnectorEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.constraint.FixedConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.FixedConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Sable side of the offset connector: the child sub-level that carries the blocks placed on the plate, and the fixed
 * joint that welds it to the plate's ship (or the world) at the anchor. Sable does not save joints, so the plate makes it
 * again whenever it is missing, and also when the child's centre of mass moves (the joint's frames go stale).
 */
public final class SableConnector {
    private SableConnector() {
    }

    /** Joint state kept on the plate entity. */
    private static final class Joint {
        @Nullable FixedConstraintHandle handle;
        /** Welds to sibling children that touch this one (contacts off, so overlapping blocks do not push each other apart). */
        final java.util.List<FixedConstraintHandle> siblings = new java.util.ArrayList<>();
        /** Siblings were looked for (a new child has no bounding box until its first tick, so this waits for one). */
        boolean siblingsChecked;
        final Vector3d com = new Vector3d(Double.NaN, 0, 0);
    }

    public static Vec3 toBlockSpace(Level level, BlockPos pos, Vec3 hit) {
        SubLevel sub = Sable.HELPER.getContaining(level, pos);
        return sub == null ? hit : sub.logicalPose().transformPositionInverse(hit);
    }

    /** Puts the held block on a new sub-level at the plate's offset and tilt. @return false if nothing was placed */
    public static boolean placeFirst(ConnectorEntity plate, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(plate.level() instanceof ServerLevel level)) return false;
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof BlockItem item)) return false;
        SubLevel containing = Sable.HELPER.getContaining(level, plate.parentPos());
        if (containing != null && !(containing instanceof ServerSubLevel)) return false;
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return false;
        BlockState state = item.getBlock().getStateForPlacement(new BlockPlaceContext(player, hand, stack, hit));
        if (state == null) state = item.getBlock().defaultBlockState();

        Pose3d pose = new Pose3d();
        Vec3 start = containing != null ? containing.logicalPose().transformPosition(plate.anchor()) : plate.anchor();
        pose.position().set(start.x, start.y, start.z);
        ServerSubLevel sub = (ServerSubLevel) container.allocateNewSubLevel(pose);
        LevelPlot plot = sub.getPlot();
        ChunkPos center = plot.getCenterChunk();
        plot.newEmptyChunk(center);
        plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, state, 3);
        BlockPos plotPos = plot.getCenterBlock();
        if (sub.getMassTracker().getCenterOfMass() == null) sub.logicalPose().rotationPoint().set(plotPos.getX() + 0.5, plotPos.getY() + 0.5, plotPos.getZ() + 0.5);
        if (containing != null) {
            SubLevelAssemblyHelper.kickFromContainingSubLevel(level, container.physicsSystem(), container.physicsSystem().getPipeline(), sub, containing);
        }
        Vec3 attach = Vec3.atCenterOf(plotPos).subtract(Vec3.atLowerCornerOf(plate.face().getNormal()).scale(0.5));
        plate.setChild(sub.getUniqueId(), attach);
        align(plate);

        SoundType sound = state.getSoundType(level, plotPos, player);
        level.playSound(null, plate.blockPosition(), sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1) / 2, sound.getPitch() * 0.8f);
        if (!player.getAbilities().instabuild) stack.shrink(1);
        return true;
    }

    private static @Nullable ServerSubLevel parent(ConnectorEntity plate) {
        return Sable.HELPER.getContaining(plate.level(), plate.parentPos()) instanceof ServerSubLevel s && !s.isRemoved() ? s : null;
    }

    private static @Nullable ServerSubLevel child(ConnectorEntity plate) {
        if (plate.child() == null) return null;
        return SubLevelContainer.getContainer(plate.level()).getSubLevel(plate.child()) instanceof ServerSubLevel s && !s.isRemoved() ? s : null;
    }

    private static Quaterniond relative(ConnectorEntity plate) {
        return new Quaterniond().rotateYXZ(Math.toRadians(-plate.yawDeg()), Math.toRadians(plate.pitchDeg()), Math.toRadians(plate.rollDeg()));
    }

    /** Moves the child so its attach point sits on the anchor with the set tilt, then welds it again. */
    public static void align(ConnectorEntity plate) {
        ServerSubLevel child = child(plate);
        if (child == null) return;
        ServerSubLevel parent = parent(plate);
        Quaterniond q = relative(plate);
        Quaterniond orientation = parent != null ? new Quaterniond(parent.logicalPose().orientation()).mul(q) : new Quaterniond(q);
        Vec3 anchorWorld = parent != null ? parent.logicalPose().transformPosition(plate.anchor()) : plate.anchor();
        Pose3d pose = child.logicalPose();
        pose.orientation().set(orientation);
        pose.position().set(0, 0, 0);
        Vec3 attach = pose.transformPosition(plate.childAttach());
        pose.position().set(anchorWorld.x - attach.x, anchorWorld.y - attach.y, anchorWorld.z - attach.z);
        PhysicsPipeline pipeline = SubLevelContainer.getContainer((ServerLevel) plate.level()).physicsSystem().getPipeline();
        pipeline.teleport(child, pose.position(), pose.orientation());
        child.updateLastPose();
        join(plate, child, parent, pipeline);
    }

    private static void join(ConnectorEntity plate, ServerSubLevel child, @Nullable ServerSubLevel parent, PhysicsPipeline pipeline) {
        release(plate);
        Vector3d a = JOMLConversion.toJOML(plate.anchor());
        Vector3d b = JOMLConversion.toJOML(plate.childAttach());
        // A plate whose ship is not loaded (or was taken apart) still points into the plot grid: welding that to the
        // world fails Sable's coordinate check, so wait instead
        ServerSubLevelContainer container = SubLevelContainer.getContainer((ServerLevel) plate.level());
        if (parent == null && container.inBounds(a)) return;
        Joint joint = new Joint();
        joint.com.set(com(child));
        plate.joint = joint;
        try {
            joint.handle = pipeline.addConstraint(parent, child, new FixedConstraintConfiguration(a, b, relative(plate)));
        } catch (IllegalArgumentException e) {
            // Kept as a failed joint: tried again when the child's mass changes or the tilt is set, not every tick
            AshCC.LOGGER.warn("Offset connector at {} could not weld {} to {}: {}", plate.blockPosition(), child.getUniqueId(), parent == null ? "the world" : parent.getUniqueId(), e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return;
        }
        if (joint.handle != null) joint.handle.setContactsEnabled(false);
        weldSiblings(plate, joint, child, parent, pipeline);
        if (parent != null) pipeline.wakeUp(parent);
        pipeline.wakeUp(child);
    }

    /**
     * Other plates on the same ship (or on the ground) whose blocks touch or overlap this plate's blocks: weld the two children
     * together with contacts off. Both are already fixed to the parent, so this only stops them colliding with each other.
     */
    private static void weldSiblings(ConnectorEntity plate, Joint joint, ServerSubLevel child, @Nullable ServerSubLevel parent, PhysicsPipeline pipeline) {
        if (child.boundingBox().width() <= 0) return;
        joint.siblingsChecked = true;
        for (ConnectorEntity other : ConnectorEntity.serverPlatesWithChild()) {
            if (other == plate || other.isRemoved() || other.level() != plate.level()) continue;
            ServerSubLevel sibling = child(other);
            if (sibling == null || parent(other) != parent || !touching(child, sibling)) continue;
            // Same world point in both plots, and the sibling's orientation seen from this child
            Vector3d a = JOMLConversion.toJOML(plate.childAttach());
            Vector3d world = child.logicalPose().transformPosition(new Vector3d(a));
            Vector3d b = sibling.logicalPose().transformPositionInverse(new Vector3d(world));
            Quaterniond rel = new Quaterniond(child.logicalPose().orientation()).conjugate().mul(sibling.logicalPose().orientation());
            try {
                FixedConstraintHandle h = pipeline.addConstraint(child, sibling, new FixedConstraintConfiguration(a, b, rel));
                if (h != null) {
                    h.setContactsEnabled(false);
                    joint.siblings.add(h);
                }
            } catch (IllegalArgumentException e) {
                AshCC.LOGGER.warn("Offset connector at {} could not weld its blocks to the neighbouring plate's: {}", plate.blockPosition(), e.getMessage());
            }
        }
    }

    private static boolean touching(ServerSubLevel a, ServerSubLevel b) {
        var x = a.boundingBox();
        var y = b.boundingBox();
        double m = 0.1;
        return x.minX() - m <= y.maxX() && y.minX() - m <= x.maxX() && x.minY() - m <= y.maxY() && y.minY() - m <= x.maxY()
            && x.minZ() - m <= y.maxZ() && y.minZ() - m <= x.maxZ();
    }

    public static void tick(ConnectorEntity plate) {
        Joint joint = plate.joint instanceof Joint j ? j : null;
        if (joint != null && joint.handle != null && !joint.handle.isValid()) joint = null;
        ServerSubLevel child = child(plate);
        if (child == null) return;
        if (joint != null && joint.com.equals(com(child), 1e-6)) {
            if (!joint.siblingsChecked && joint.handle != null) {
                weldSiblings(plate, joint, child, parent(plate), SubLevelContainer.getContainer((ServerLevel) plate.level()).physicsSystem().getPipeline());
            }
            return;
        }
        ServerSubLevel parent = parent(plate);
        // A plate on a ship waits for that ship to load
        if (parent == null && Sable.HELPER.getContaining(plate.level(), plate.parentPos()) != null) return;
        join(plate, child, parent, SubLevelContainer.getContainer((ServerLevel) plate.level()).physicsSystem().getPipeline());
    }

    /** The child sub-level is not there (removed, or not loaded yet). */
    public static boolean childMissing(ConnectorEntity plate) {
        return child(plate) == null;
    }

    public static void release(ConnectorEntity plate) {
        if (plate.joint instanceof Joint j) {
            if (j.handle != null) j.handle.remove();
            for (FixedConstraintHandle h : j.siblings) if (h.isValid()) h.remove();
        }
        plate.joint = null;
    }

    private static Vector3dc com(ServerSubLevel sub) {
        Vector3dc c = sub.getMassTracker().getCenterOfMass();
        return c != null ? c : new Vector3d();
    }
}
