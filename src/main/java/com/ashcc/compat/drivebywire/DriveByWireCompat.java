package com.ashcc.compat.drivebywire;

import com.ashcc.panel.PanelControl;
import edn.stratodonut.drivebywire.wire.WireNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Drive By Wire side of the control panel (only touched when drivebywire is loaded). Channel n of the panel is the cable channel
 * "panel_n"; "world" carries the panel's redstone output like any other block.
 */
public final class DriveByWireCompat {
    public static final List<String> CHANNELS;

    static {
        List<String> list = new ArrayList<>();
        list.add(WireNetworkManager.WORLD_CHANNEL);
        for (int ch = 1; ch <= PanelControl.MAX_CHANNEL; ch++) list.add(name(ch));
        CHANNELS = List.copyOf(list);
    }

    private DriveByWireCompat() {
    }

    public static String name(int channel) {
        return "panel_" + channel;
    }

    public static void setSignal(Level level, BlockPos pos, int channel, int value) {
        WireNetworkManager.trySetSignalAt(level, pos, name(channel), value);
    }

    public static String next(String current, boolean forward) {
        int i = CHANNELS.indexOf(current);
        if (i < 0) return CHANNELS.getFirst();
        return CHANNELS.get(Math.floorMod(i + (forward ? 1 : -1), CHANNELS.size()));
    }
}
