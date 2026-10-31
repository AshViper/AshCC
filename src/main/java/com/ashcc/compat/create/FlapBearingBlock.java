package com.ashcc.compat.create;

import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlock;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Mechanical bearing body with its own block entity. No shaft: it is driven by the computer, not by rotation. */
public class FlapBearingBlock extends MechanicalBearingBlock {
    public FlapBearingBlock(Properties properties) {
        super(properties);
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return false;
    }

    @Override
    public Class<MechanicalBearingBlockEntity> getBlockEntityClass() {
        return MechanicalBearingBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends MechanicalBearingBlockEntity> getBlockEntityType() {
        return CreateCompat.FLAP_BEARING_BE.get();
    }
}
