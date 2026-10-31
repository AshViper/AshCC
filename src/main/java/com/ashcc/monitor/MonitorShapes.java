package com.ashcc.monitor;

import com.ashcc.AshCC;
import com.ashcc.AshCCConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Joins neighbouring monitors into rectangles. All monitors connected in the screen plane with the same orientation
 * form a component; it is cut into rectangles greedily from the top-left (widest first, then as tall as possible),
 * so the result depends only on which blocks exist and every change can simply redo the whole component.
 */
public final class MonitorShapes {
    /** Largest component that is regrouped at once; bigger walls of monitors stay as they were. */
    private static final int MAX_COMPONENT = 4096;

    private MonitorShapes() {
    }

    public static void rebuild(Level level, BlockPos start) {
        BlockState startState = level.getBlockState(start);
        if (!startState.is(AshCC.HD_MONITOR.get())) return;
        MonitorGeometry g = MonitorGeometry.of(startState);

        // Component in screen coordinates (column, row) -> position
        Map<Long, BlockPos> cells = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);
        Direction[] plane = {g.right(), g.right().getOpposite(), g.up(), g.up().getOpposite()};
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            cells.put(key(g.column(p), g.row(p)), p);
            if (cells.size() >= MAX_COMPONENT) return;
            for (Direction d : plane) {
                BlockPos n = p.relative(d);
                if (seen.contains(n)) continue;
                BlockState s = level.getBlockState(n);
                if (s.is(AshCC.HD_MONITOR.get()) && MonitorGeometry.of(s).equals(g)) {
                    seen.add(n);
                    queue.add(n);
                }
            }
        }

        int maxW = AshCCConfig.MONITOR_MAX_WIDTH.get();
        int maxH = AshCCConfig.MONITOR_MAX_HEIGHT.get();
        List<long[]> sorted = new ArrayList<>();
        for (BlockPos p : cells.values()) sorted.add(new long[]{g.row(p), g.column(p)});
        sorted.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        Set<Long> assigned = new HashSet<>();
        for (long[] rc : sorted) {
            int row = (int) rc[0], col = (int) rc[1];
            if (assigned.contains(key(col, row))) continue;
            int w = 1;
            while (w < maxW && free(cells, assigned, col + w, row)) w++;
            int h = 1;
            grow:
            while (h < maxH) {
                for (int x = 0; x < w; x++) if (!free(cells, assigned, col + x, row + h)) break grow;
                h++;
            }
            BlockPos origin = cells.get(key(col, row));
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    assigned.add(key(col + x, row + y));
                    BlockPos p = cells.get(key(col + x, row + y));
                    if (level.getBlockEntity(p) instanceof HdMonitorBlockEntity be) be.setPart(x, y, w, h, origin);
                }
            }
        }
    }

    private static boolean free(Map<Long, BlockPos> cells, Set<Long> assigned, int col, int row) {
        long k = key(col, row);
        return cells.containsKey(k) && !assigned.contains(k);
    }

    private static long key(int col, int row) {
        return ((long) col << 32) | (row & 0xFFFFFFFFL);
    }
}
