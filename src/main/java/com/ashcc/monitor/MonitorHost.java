package com.ashcc.monitor;

import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** What owns a {@link MonitorScreen}: an HD monitor's origin block, or a control panel whose screens show parts of it. */
public interface MonitorHost {
    @Nullable Level getLevel();

    void setHologram(boolean on);

    boolean hologram();

    void setGlow(boolean on);

    boolean glow();
}
