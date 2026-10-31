package com.ashcc.compat.cbc;

import com.ashcc.AshCC;
import com.ashcc.compat.SableClientCompat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

/**
 * Where a mounted line cannon's muzzle is drawn this frame. On a Sable ship the contraption sits in the plot grid, so
 * the point is moved out with the ship's render pose (the pose its blocks are drawn with) rather than its once-a-tick
 * logical pose, which would make the effects trail and jump against the moving ship. Client only.
 */
final class CannonFxGeometry {
    private CannonFxGeometry() {
    }

    /** {muzzle, unit direction} in world space, or null when the contraption is gone. */
    static Vec3 @Nullable [] muzzle(ClientLevel level, int entityId, float partial) {
        Entity e = level.getEntity(entityId);
        if (!(e instanceof PitchOrientedContraptionEntity poce) || !(poce.getContraption() instanceof AbstractLineCannonContraption c)) return null;
        Vec3 local = c.fxMuzzle();
        Vec3 n = Vec3.atLowerCornerOf(c.initialOrientation().getNormal());
        BlockPos root = BlockPos.containing(poce.getPosition(partial));
        Vec3 from = toWorld(level, root, poce.toGlobalVector(local, partial), partial);
        Vec3 ahead = toWorld(level, root, poce.toGlobalVector(local.add(n), partial), partial);
        return new Vec3[]{from, ahead.subtract(from).normalize()};
    }

    private static Vec3 toWorld(ClientLevel level, BlockPos root, Vec3 p, float partial) {
        return AshCC.sableLoaded ? SableClientCompat.pointToWorld(level, root, p, partial) : p;
    }
}
