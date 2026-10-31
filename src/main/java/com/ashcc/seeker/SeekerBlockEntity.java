package com.ashcc.seeker;

import com.ashcc.AshCC;
import com.ashcc.radar.RadarFrame;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class SeekerBlockEntity extends BlockEntity {
    private @Nullable SeekerPeripheral peripheral;

    public SeekerBlockEntity(BlockPos pos, BlockState state) {
        super(AshCC.SEEKER_BE.get(), pos, state);
    }

    public SeekerPeripheral peripheral() {
        if (peripheral == null) peripheral = new SeekerPeripheral(this);
        return peripheral;
    }

    /** Line of sight = FACING. Local y is world up, or north when looking straight up/down. */
    public RadarFrame frame() {
        Direction facing = getBlockState().getValue(SeekerBlock.FACING);
        return RadarFrame.of(level, worldPosition, facing, RadarFrame.upFor(facing));
    }
}
