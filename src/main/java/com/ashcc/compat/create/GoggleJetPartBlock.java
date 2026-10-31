package com.ashcc.compat.create;

import com.ashcc.jet.JetEngineBlockEntity;
import com.ashcc.jet.JetPart;
import com.ashcc.jet.JetPartBlock;
import com.simibubi.create.api.equipment.goggles.IProxyHoveringInformation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Intake / core that show their engine's goggle readout (the nozzle holds it). */
public class GoggleJetPartBlock extends JetPartBlock implements IProxyHoveringInformation {
    public GoggleJetPartBlock(JetPart part, Properties properties) {
        super(part, properties);
    }

    @Override
    public BlockPos getInformationSource(Level level, BlockPos pos, BlockState state) {
        JetEngineBlockEntity e = JetEngineBlockEntity.find(level, pos);
        return e == null ? pos : e.getBlockPos();
    }
}
