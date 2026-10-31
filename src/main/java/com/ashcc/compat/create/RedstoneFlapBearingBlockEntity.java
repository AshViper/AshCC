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
 * Flap bearing whose target angle follows redstone instead of a computer. Each input face has a fixed sign:
 * seen from the front, the right-hand and top faces turn it +, the left-hand and bottom faces and the back turn
 * it - (with the front pointing up: north and east are +, south and west are -). The angle is
 * (strongest + signal - strongest - signal) / 15 * redstoneAngle, so the flap swings both ways depending on
 * which side the signal comes from. Unpowered it holds 0 degrees. Right-click assembles / disassembles as usual.
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

    /** Signed target from the signals around the bearing: the strongest + face against the strongest - face. */
    static float redstoneTarget(Level level, BlockPos pos, BlockState state) {
        Direction facing = state.getValue(DirectionalKineticBlock.FACING);
        int pos15 = 0, neg15 = 0;
        for (Direction d : Direction.values()) {
            if (d == facing) continue;
            int s = level.getSignal(pos.relative(d), d);
            if (s == 0) continue;
            int sign = d == facing.getOpposite() ? -1 : signOf(d, facing);
            if (sign > 0) pos15 = Math.max(pos15, s);
            else neg15 = Math.max(neg15, s);
        }
        return (float) ((pos15 - neg15) / 15.0 * AshCCConfig.FLAP_REDSTONE_ANGLE.get());
    }

    /**
     * The sign of an input face: + for the faces on one side of the rotation axis (the lateral face along
     * facing x up, and the up face itself), - for the opposite pair. With a vertical facing the reference
     * axis is X instead of up, which makes north/east + and south/west -.
     */
    static int signOf(Direction d, Direction facing) {
        net.minecraft.core.Vec3i a = facing.getNormal();
        net.minecraft.core.Vec3i r = Math.abs(a.getY()) < 1 ? new net.minecraft.core.Vec3i(0, 1, 0) : new net.minecraft.core.Vec3i(1, 0, 0);
        net.minecraft.core.Vec3i dv = d.getNormal();
        int alongSide = dv.getX() * (a.getY() * r.getZ() - a.getZ() * r.getY())
            + dv.getY() * (a.getZ() * r.getX() - a.getX() * r.getZ())
            + dv.getZ() * (a.getX() * r.getY() - a.getY() * r.getX());
        if (alongSide != 0) return Integer.signum(alongSide);
        int alongRef = dv.getX() * r.getX() + dv.getY() * r.getY() + dv.getZ() * r.getZ();
        return Integer.signum(alongRef);
    }

    /** 0..15 from how far the flap is turned either way, full at redstoneAngle. */
    public int comparatorOutput() {
        if (!assembled()) return 0;
        double ratio = Math.abs(currentAngle()) / AshCCConfig.FLAP_REDSTONE_ANGLE.get();
        return Mth.clamp((int) Math.round(ratio * 15), 0, 15);
    }
}
