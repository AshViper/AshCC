package com.ashcc.monitor;

import org.jetbrains.annotations.Nullable;

/** A block entity other than an HD monitor that owns a screen (the client asks it for snapshots by block position). */
public interface ScreenOwner {
    @Nullable MonitorScreen screen();
}
