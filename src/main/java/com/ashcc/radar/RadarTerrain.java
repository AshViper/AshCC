package com.ashcc.radar;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Terrain map around a radar: a square grid of surface columns, row 1 = far edge ahead (top of a scope),
 * column 1 = left edge. The grid turns with the radar's horizontal heading unless north-up.
 * Unloaded chunks are never loaded; their cells are nil with kind "unloaded".
 */
public final class RadarTerrain {
    private RadarTerrain() {
    }

    public static Map<String, Object> scan(ServerLevel level, RadarFrame frame, double range, int size, boolean northUp) {
        Vec3 forward = northUp ? new Vec3(0, 0, -1) : horizontal(frame.forward(), frame.right().cross(new Vec3(0, 1, 0)).scale(-1));
        // Right of forward on the horizontal plane (north -> east)
        Vec3 right = forward.cross(new Vec3(0, 1, 0));
        Vec3 o = frame.origin();
        double cell = 2 * range / size;

        List<List<Object>> height = new ArrayList<>(size);
        List<List<Object>> floor = new ArrayList<>(size);
        List<List<Object>> color = new ArrayList<>(size);
        List<List<Object>> kind = new ArrayList<>(size);
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        int bottom = level.getMinBuildHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int row = 0; row < size; row++) {
            List<Object> hRow = new ArrayList<>(size), fRow = new ArrayList<>(size), cRow = new ArrayList<>(size), kRow = new ArrayList<>(size);
            double lz = range - (row + 0.5) * cell;
            for (int col = 0; col < size; col++) {
                double lx = -range + (col + 0.5) * cell;
                int x = (int) Math.floor(o.x + right.x * lx + forward.x * lz);
                int z = (int) Math.floor(o.z + right.z * lx + forward.z * lz);
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    hRow.add(null);
                    fRow.add(null);
                    cRow.add(null);
                    kRow.add("unloaded");
                    continue;
                }
                // Heightmaps give the first free block above the column
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                if (top < bottom) {
                    hRow.add(null);
                    fRow.add(null);
                    cRow.add(0);
                    kRow.add("void");
                    continue;
                }
                int ground = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
                BlockState state = level.getBlockState(pos.set(x, top, z));
                hRow.add((double) top);
                fRow.add((double) Math.min(top, ground));
                cRow.add(state.getMapColor(level, pos).col);
                kRow.add(kind(state));
                min = Math.min(min, top);
                max = Math.max(max, top);
            }
            height.add(hRow);
            floor.add(fRow);
            color.add(cRow);
            kind.add(kRow);
        }

        Map<String, Object> t = new HashMap<>();
        t.put("size", size);
        t.put("range", range);
        t.put("cell", cell);
        t.put("northUp", northUp);
        t.put("originX", o.x);
        t.put("originY", o.y);
        t.put("originZ", o.z);
        t.put("forward", LuaUtil.vec(forward));
        t.put("right", LuaUtil.vec(right));
        t.put("height", height);
        t.put("floor", floor);
        t.put("color", color);
        t.put("kind", kind);
        if (min <= max) {
            t.put("min", min);
            t.put("max", max);
        }
        return t;
    }

    /** {@code v} flattened onto the horizontal plane, or {@code fallback} (also flattened) when v is vertical. */
    private static Vec3 horizontal(Vec3 v, Vec3 fallback) {
        Vec3 h = new Vec3(v.x, 0, v.z);
        if (h.lengthSqr() < 1e-6) h = new Vec3(fallback.x, 0, fallback.z);
        return h.lengthSqr() < 1e-6 ? new Vec3(0, 0, -1) : h.normalize();
    }

    private static String kind(BlockState state) {
        if (state.getFluidState().is(FluidTags.WATER)) return "water";
        if (state.getFluidState().is(FluidTags.LAVA)) return "lava";
        if (state.is(BlockTags.ICE)) return "ice";
        if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.POWDER_SNOW)) return "snow";
        if (state.is(BlockTags.LEAVES)) return "leaves";
        return "ground";
    }
}
