package com.ashcc.compat.create;

import com.ashcc.AshCC;
import com.ashcc.jet.JetEngineBlockEntity;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** Jet engine readable with the engineer's goggles (Create installed, Sable not). */
public class GoggleJetEngineBlockEntity extends JetEngineBlockEntity implements IHaveGoggleInformation {
    public GoggleJetEngineBlockEntity(BlockPos pos, BlockState state) {
        super(AshCC.JET_ENGINE_BE.get(), pos, state);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        return JetGoggles.addTooltip(this, tooltip);
    }
}
