package com.ashcc.radar;

import com.ashcc.AshCC;
import com.ashcc.compat.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * World-space position and axes of a radar. On a Sable sub-level the axes rotate with the ship.
 * Local coordinates: x = right, y = up, z = forward (the block's FACING).
 */
public record RadarFrame(Vec3 origin, Vec3 right, Vec3 up, Vec3 forward, @Nullable UUID subLevel) {

    public static RadarFrame of(Level level, BlockPos pos, Direction facing) {
        return of(level, pos, facing, Direction.UP);
    }

    /**
     * Frame for a block facing any of the 6 directions. {@code up} must be perpendicular to {@code facing};
     * see {@link #upFor(Direction)} for the convention used by directional blocks.
     */
    public static RadarFrame of(Level level, BlockPos pos, Direction facing, Direction up) {
        if (AshCC.sableLoaded) {
            RadarFrame frame = SableCompat.frameOnSubLevel(level, pos, facing, up);
            if (frame != null) return frame;
        }
        Vec3 forward = Vec3.atLowerCornerOf(facing.getNormal());
        Vec3 u = Vec3.atLowerCornerOf(up.getNormal());
        return new RadarFrame(Vec3.atCenterOf(pos), forward.cross(u), u, forward, null);
    }

    /** Local "up" for a block facing {@code facing}: world up, or north when it faces straight up or down. */
    public static Direction upFor(Direction facing) {
        return facing.getAxis() == Direction.Axis.Y ? Direction.NORTH : Direction.UP;
    }

    public Vec3 toLocal(Vec3 world) {
        return dirToLocal(world.subtract(origin));
    }

    public Vec3 dirToLocal(Vec3 d) {
        return new Vec3(d.dot(right), d.dot(up), d.dot(forward));
    }

    public Vec3 toWorld(Vec3 local) {
        return origin.add(dirToWorld(local));
    }

    public Vec3 dirToWorld(Vec3 l) {
        return right.scale(l.x).add(up.scale(l.y)).add(forward.scale(l.z));
    }

    /** Degrees, positive to the right of forward. */
    public static double azimuth(Vec3 local) {
        return Math.toDegrees(Math.atan2(local.x, local.z));
    }

    /** Degrees, positive above the radar's horizontal plane. */
    public static double elevation(Vec3 local) {
        return Math.toDegrees(Math.atan2(local.y, Math.sqrt(local.x * local.x + local.z * local.z)));
    }
}
