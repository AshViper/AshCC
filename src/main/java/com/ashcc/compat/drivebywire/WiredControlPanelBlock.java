package com.ashcc.compat.drivebywire;

import com.ashcc.panel.ControlPanelBlock;
import com.mojang.serialization.MapCodec;
import edn.stratodonut.drivebywire.wire.MultiChannelWireSource;
import net.minecraft.world.level.block.BaseEntityBlock;

import java.util.List;

/** Control panel registered when drivebywire is loaded: a cable source whose channels (scroll with the cable) are the panel's. */
public class WiredControlPanelBlock extends ControlPanelBlock implements MultiChannelWireSource {
    public static final MapCodec<WiredControlPanelBlock> CODEC = simpleCodec(WiredControlPanelBlock::new);

    public WiredControlPanelBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public List<String> wire$getChannels() {
        return DriveByWireCompat.CHANNELS;
    }

    @Override
    public String wire$nextChannel(String current, boolean forward) {
        return DriveByWireCompat.next(current, forward);
    }
}
