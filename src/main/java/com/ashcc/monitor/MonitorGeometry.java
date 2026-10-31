package com.ashcc.monitor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Screen axes of a monitor block in block coordinates. facing = out of the screen, up = the screen's top,
 * right = the viewer's right (right x up = facing). Columns grow to the right, rows grow downwards.
 */
public record MonitorGeometry(Direction facing, Direction up, Direction right) {
    public static final int PIXELS_PER_BLOCK = 128;
    /** Frame width of a normal (non-hologram) monitor around the whole screen, in blocks. */
    public static final double BEZEL = 1.0 / 16;

    public static MonitorGeometry of(BlockState state) {
        Direction facing = state.getValue(HdMonitorBlock.FACING);
        Direction rot = state.getValue(HdMonitorBlock.ROTATION);
        return switch (facing) {
            case UP -> new MonitorGeometry(facing, rot, rot.getClockWise());
            case DOWN -> new MonitorGeometry(facing, rot, rot.getCounterClockWise());
            default -> new MonitorGeometry(facing, Direction.UP, facing.getCounterClockWise());
        };
    }

    private static int dot(BlockPos pos, Direction d) {
        return pos.getX() * d.getStepX() + pos.getY() * d.getStepY() + pos.getZ() * d.getStepZ();
    }

    public int column(BlockPos pos) {
        return dot(pos, right);
    }

    public int row(BlockPos pos) {
        return -dot(pos, up);
    }

    /** The block dx columns right and dy rows down of pos. */
    public BlockPos offset(BlockPos pos, int dx, int dy) {
        return pos.relative(right, dx).relative(up, -dy);
    }

    /** Top-left corner of the front face of the given block, in block-local coordinates (0..1 cube). */
    public Vec3 cornerLocal() {
        Vec3 c = new Vec3(0.5, 0.5, 0.5);
        return c.add(vec(facing).scale(0.5)).add(vec(right).scale(-0.5)).add(vec(up).scale(0.5));
    }

    public static Vec3 vec(Direction d) {
        return new Vec3(d.getStepX(), d.getStepY(), d.getStepZ());
    }

    /**
     * Pixel under a point on the front face. local = hit point minus the origin block's position (block coordinates).
     * Returns {px, py} (may be outside the screen) for a screen of w x h blocks.
     */
    public double[] toPixel(Vec3 local, int w, int h, boolean hologram) {
        Vec3 v = local.subtract(cornerLocal());
        double u = v.dot(vec(right));
        double d = -v.dot(vec(up));
        double m = hologram ? 0 : BEZEL;
        double px = (u - m) / (w - 2 * m) * w * PIXELS_PER_BLOCK;
        double py = (d - m) / (h - 2 * m) * h * PIXELS_PER_BLOCK;
        return new double[]{px, py};
    }
}
