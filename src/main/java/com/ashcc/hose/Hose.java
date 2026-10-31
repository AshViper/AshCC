package com.ashcc.hose;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * A fluid line from one block face to another: a flexible hose, or a rigid pipe that runs along the block axes.
 * Fluid flows from {@code from} to {@code to}. Not a block: it only stores its two ends, so it can run through blocks
 * and blocks can be placed where it runs. Positions are block coordinates (a ship's plot grid when on a Sable ship).
 */
public record Hose(int id, BlockPos from, Direction fromFace, BlockPos to, Direction toFace, boolean pipe) {
    public static final StreamCodec<RegistryFriendlyByteBuf, Hose> STREAM_CODEC = StreamCodec.of((buf, h) -> {
        buf.writeVarInt(h.id);
        buf.writeBlockPos(h.from);
        buf.writeEnum(h.fromFace);
        buf.writeBlockPos(h.to);
        buf.writeEnum(h.toFace);
        buf.writeBoolean(h.pipe);
    }, buf -> new Hose(buf.readVarInt(), buf.readBlockPos(), buf.readEnum(Direction.class), buf.readBlockPos(), buf.readEnum(Direction.class),
        buf.readBoolean()));

    /** Where the line leaves a block face, in block coordinates. */
    public static Vec3 attach(BlockPos pos, Direction face) {
        return Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
    }

    public boolean touches(BlockPos pos) {
        return from.equals(pos) || to.equals(pos);
    }

    public List<Vec3> route() {
        return route(from, fromFace, to, toFace);
    }

    /**
     * A pipe's path in block coordinates: straight out of the first face to the centre of the block in front of it,
     * then along the axes (at most one run per axis) to the centre of the block in front of the second face, and in.
     * Of the possible axis orders it takes the one that does not double back into either end block, with the fewest bends.
     */
    public static List<Vec3> route(BlockPos from, Direction fromFace, BlockPos to, Direction toFace) {
        Vec3 a = attach(from, fromFace);
        Vec3 b = attach(to, toFace);
        Vec3 p1 = Vec3.atCenterOf(from.relative(fromFace));
        Vec3 p3 = Vec3.atCenterOf(to.relative(toFace));
        Direction.Axis[][] orders = {
            {Direction.Axis.X, Direction.Axis.Y, Direction.Axis.Z}, {Direction.Axis.X, Direction.Axis.Z, Direction.Axis.Y},
            {Direction.Axis.Y, Direction.Axis.X, Direction.Axis.Z}, {Direction.Axis.Y, Direction.Axis.Z, Direction.Axis.X},
            {Direction.Axis.Z, Direction.Axis.X, Direction.Axis.Y}, {Direction.Axis.Z, Direction.Axis.Y, Direction.Axis.X}};
        List<Vec3> best = null;
        double bestScore = Double.MAX_VALUE;
        for (Direction.Axis[] order : orders) {
            List<Vec3> pts = new ArrayList<>();
            pts.add(a);
            pts.add(p1);
            Vec3 cur = p1;
            for (Direction.Axis ax : order) {
                double target = p3.get(ax);
                if (Math.abs(target - cur.get(ax)) < 1e-6) continue;
                cur = with(cur, ax, target);
                pts.add(cur);
            }
            pts.add(b);
            double score = pts.size();
            AABB fromBox = new AABB(from).deflate(0.01);
            AABB toBox = new AABB(to).deflate(0.01);
            // Runs that pass through the end blocks themselves (or come back over the start) are what looks wrong
            for (int i = 2; i < pts.size() - 1; i++) {
                if (crosses(fromBox, pts.get(i - 1), pts.get(i))) score += 100;
                if (i < pts.size() - 2 && crosses(toBox, pts.get(i - 1), pts.get(i))) score += 100;
            }
            // Prefer the first run to continue straight out of the face, and the last to go straight in
            if (pts.size() > 3 && !sameDir(pts.get(1).subtract(pts.get(0)), pts.get(2).subtract(pts.get(1)))) score += 0.5;
            int n = pts.size();
            if (n > 3 && !sameDir(pts.get(n - 2).subtract(pts.get(n - 3)), pts.get(n - 1).subtract(pts.get(n - 2)))) score += 0.5;
            if (score < bestScore) {
                bestScore = score;
                best = pts;
            }
        }
        return best;
    }

    /** Total length of a path. */
    public static double length(List<Vec3> pts) {
        double l = 0;
        for (int i = 1; i < pts.size(); i++) l += pts.get(i).distanceTo(pts.get(i - 1));
        return l;
    }

    private static Vec3 with(Vec3 v, Direction.Axis ax, double value) {
        return switch (ax) {
            case X -> new Vec3(value, v.y, v.z);
            case Y -> new Vec3(v.x, value, v.z);
            case Z -> new Vec3(v.x, v.y, value);
        };
    }

    private static boolean sameDir(Vec3 a, Vec3 b) {
        return a.lengthSqr() > 1e-9 && b.lengthSqr() > 1e-9 && a.normalize().dot(b.normalize()) > 0.99;
    }

    private static boolean crosses(AABB box, Vec3 a, Vec3 b) {
        return box.contains(a) || box.contains(b) || box.clip(a, b).isPresent();
    }

    CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("Id", id);
        t.putLong("From", from.asLong());
        t.putInt("FromFace", fromFace.get3DDataValue());
        t.putLong("To", to.asLong());
        t.putInt("ToFace", toFace.get3DDataValue());
        t.putBoolean("Pipe", pipe);
        return t;
    }

    static Hose load(CompoundTag t) {
        return new Hose(t.getInt("Id"), BlockPos.of(t.getLong("From")), Direction.from3DDataValue(t.getInt("FromFace")),
            BlockPos.of(t.getLong("To")), Direction.from3DDataValue(t.getInt("ToFace")), t.getBoolean("Pipe"));
    }
}
