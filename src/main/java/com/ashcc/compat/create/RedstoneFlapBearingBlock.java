package com.ashcc.compat.create;

import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Flap bearing steered by redstone alone: signal into a side turns it one way, signal into the back the other way.
 * Comparators read how far it is deflected.
 */
public class RedstoneFlapBearingBlock extends FlapBearingBlock {
    public RedstoneFlapBearingBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntityType<? extends MechanicalBearingBlockEntity> getBlockEntityType() {
        return CreateCompat.REDSTONE_FLAP_BEARING_BE.get();
    }

    /** Dust connects to every face but the front, where the contraption is. */
    @Override
    public boolean canConnectRedstone(BlockState state, BlockGetter level, BlockPos pos, @Nullable Direction direction) {
        return direction != null && direction != state.getValue(DirectionalKineticBlock.FACING).getOpposite();
    }

    @Override
    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof RedstoneFlapBearingBlockEntity be ? be.comparatorOutput() : 0;
    }
}
