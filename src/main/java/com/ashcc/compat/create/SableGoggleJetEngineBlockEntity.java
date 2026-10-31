package com.ashcc.compat.create;

import com.ashcc.AshCC;
import com.ashcc.compat.SableJetEngineBlockEntity;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** Jet engine that pushes a Sable ship and is readable with the engineer's goggles. */
public class SableGoggleJetEngineBlockEntity extends SableJetEngineBlockEntity implements IHaveGoggleInformation {
    public SableGoggleJetEngineBlockEntity(BlockPos pos, BlockState state) {
        super(AshCC.JET_ENGINE_BE.get(), pos, state);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        return JetGoggles.addTooltip(this, tooltip);
    }
}
