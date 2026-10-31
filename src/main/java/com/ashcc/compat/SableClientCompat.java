package com.ashcc.compat;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side Sable helpers for things drawn outside the ship's own renderer. They use the ship's render pose at the
 * frame's partial tick (the same pose its blocks are drawn with); the logical pose only moves once a tick, so geometry
 * attached with it trails and jumps against the smoothly drawn ship. Only call when {@code AshCC.sableLoaded}.
 */
public final class SableClientCompat {
    private SableClientCompat() {
    }

    private static Pose3dc pose(Level level, BlockPos pos, float partialTick) {
        SubLevel sub = Sable.HELPER.getContaining(level, pos);
        if (sub == null) return null;
        return sub instanceof ClientSubLevel c ? c.renderPose(partialTick) : sub.logicalPose();
    }

    /** A point in block coordinates to world space, where the ship is drawn this frame (identity off a ship). */
    public static Vec3 pointToWorld(Level level, BlockPos pos, Vec3 point, float partialTick) {
        Pose3dc pose = pose(level, pos, partialTick);
        return pose == null ? point : pose.transformPosition(point);
    }

    /** A direction in block coordinates to world space, as the ship is drawn this frame. */
    public static Vec3 dirToWorld(Level level, BlockPos pos, Vec3 dir, float partialTick) {
        Pose3dc pose = pose(level, pos, partialTick);
        return pose == null ? dir : pose.transformNormal(dir);
    }
}
