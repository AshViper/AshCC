package com.ashcc.panel;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The panel's face in block coordinates: {@code normal} points out of the panel, {@code right} and {@code up} span it as seen
 * by someone facing it (on the floor, up is the way the panel was placed facing). right x normal = -up, so (right, normal, -up)
 * is a right-handed frame for drawing. A tilted panel turns about its near (bottom) edge, raising the far edge.
 */
public record PanelFrame(Vec3 normal, Vec3 right, Vec3 up, Vec3 surfaceCentre, Direction base) {
    /** Panel plate thickness: an untilted surface is this far from the side it is attached to. */
    public static final double THICKNESS = 3 / 16.0;

    public static PanelFrame of(BlockState state) {
        return of(state, 0);
    }

    public static PanelFrame of(BlockState state, int tiltDegrees) {
        AttachFace face = state.getValue(ControlPanelBlock.FACE);
        Direction facing = state.getValue(ControlPanelBlock.FACING);
        Direction out;
        Vec3 right, up;
        switch (face) {
            case FLOOR -> {
                out = Direction.UP;
                right = v(facing.getClockWise());
                up = v(facing);
            }
            case CEILING -> {
                out = Direction.DOWN;
                right = v(facing.getCounterClockWise());
                up = v(facing);
            }
            default -> {
                out = facing;
                right = v(facing.getCounterClockWise());
                up = v(Direction.UP);
            }
        }
        Vec3 n = v(out);
        Vec3 flatCentre = new Vec3(0.5, 0.5, 0.5).add(n.scale(THICKNESS - 0.5));
        if (tiltDegrees == 0) return new PanelFrame(n, right, up, flatCentre, out.getOpposite());
        double t = Math.toRadians(tiltDegrees);
        Vec3 up2 = up.scale(Math.cos(t)).add(n.scale(Math.sin(t)));
        Vec3 n2 = n.scale(Math.cos(t)).subtract(up.scale(Math.sin(t)));
        Vec3 nearEdge = flatCentre.subtract(up.scale(0.5));
        return new PanelFrame(n2, right, up2, nearEdge.add(up2.scale(0.5)), out.getOpposite());
    }

    private static Vec3 v(Direction d) {
        return Vec3.atLowerCornerOf(d.getNormal());
    }

    /** The side of the block the panel is attached to. */
    public Direction attached() {
        return base;
    }

    /** Point on the surface at panel coordinates u (0 left .. 1 right) and w (0 bottom .. 1 top), in block coordinates. */
    public Vec3 point(double u, double w) {
        return surfaceCentre.add(right.scale(u - 0.5)).add(up.scale(w - 0.5));
    }

    /** Grid cell {col, row} (row 0 at the top) under a point on the surface (block coordinates 0..1), or null off the grid. */
    public @Nullable int[] cell(Vec3 local, int grid) {
        Vec3 d = local.subtract(surfaceCentre);
        double u = d.dot(right) + 0.5, w = d.dot(up) + 0.5;
        if (u < -0.01 || u > 1.01 || w < -0.01 || w > 1.01) return null;
        int col = Math.max(0, Math.min(grid - 1, (int) (u * grid)));
        int row = Math.max(0, Math.min(grid - 1, (int) ((1 - w) * grid)));
        return new int[]{col, row};
    }

    /** Where a ray (block coordinates) meets the surface from the front, or null. */
    public @Nullable Vec3 hit(Vec3 from, Vec3 dir) {
        double dn = dir.dot(normal);
        if (dn >= -1e-6) return null;
        double t = surfaceCentre.subtract(from).dot(normal) / dn;
        return t < 0 ? null : from.add(dir.scale(t));
    }

    /** Box around the plate (with its thickness under the surface), clipped to the block: outline and collision. */
    public AABB bounds() {
        double minX = 1, minY = 1, minZ = 1, maxX = 0, maxY = 0, maxZ = 0;
        for (int a = 0; a <= 1; a++) for (int b = 0; b <= 1; b++) for (int c = 0; c <= 1; c++) {
            Vec3 p = point(a, b).subtract(normal.scale(c * THICKNESS));
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
            maxZ = Math.max(maxZ, p.z);
        }
        // The flat plate reaches the attached side; a tilted one also has its foot there
        Vec3 b = v(base);
        if (b.x < 0) minX = 0;
        if (b.x > 0) maxX = 1;
        if (b.y < 0) minY = 0;
        if (b.y > 0) maxY = 1;
        if (b.z < 0) minZ = 0;
        if (b.z > 0) maxZ = 1;
        return new AABB(Math.max(0, minX), Math.max(0, minY), Math.max(0, minZ), Math.min(1, maxX), Math.min(1, maxY), Math.min(1, maxZ));
    }
}
