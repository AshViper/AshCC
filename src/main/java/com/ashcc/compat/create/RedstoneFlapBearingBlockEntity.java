package com.ashcc.compat.create;

import com.ashcc.AshCCConfig;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Flap bearing whose target angle follows redstone instead of a computer:
 * angle = (strongest side signal - back signal) / 15 * redstoneAngle. Unpowered it holds 0 degrees.
 * Right-click assembles / disassembles as usual.
 */
public class RedstoneFlapBearingBlockEntity extends FlapBearingBlockEntity {
    private int lastComparator;

    public RedstoneFlapBearingBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide) return;
        if (running && !disassembling()) {
            float target = redstoneTarget(level, worldPosition, getBlockState());
            if (Math.abs(target - targetAngle()) > 1e-3f) setTargetAngle(target);
        }
        int out = comparatorOutput();
        if (out != lastComparator) {
            lastComparator = out;
            level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
        }
    }

    /** Signed target from the signals around the bearing. */
    static float redstoneTarget(Level level, BlockPos pos, BlockState state) {
        Direction facing = state.getValue(DirectionalKineticBlock.FACING);
        Direction back = facing.getOpposite();
        int side = 0;
        for (Direction d : Direction.values()) {
            if (d.getAxis() == facing.getAxis()) continue;
            side = Math.max(side, level.getSignal(pos.relative(d), d));
        }
        int rear = level.getSignal(pos.relative(back), back);
        return (float) ((side - rear) / 15.0 * AshCCConfig.FLAP_REDSTONE_ANGLE.get());
    }

    /** 0..15 from how far the flap is turned either way, full at redstoneAngle. */
    public int comparatorOutput() {
        if (!assembled()) return 0;
        double ratio = Math.abs(currentAngle()) / AshCCConfig.FLAP_REDSTONE_ANGLE.get();
        return Mth.clamp((int) Math.round(ratio * 15), 0, 15);
    }
}
