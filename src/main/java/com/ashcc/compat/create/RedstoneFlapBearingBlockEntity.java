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
 * Flap bearing whose target angle follows redstone instead of a computer, in exact 1-degree steps from 0 to 90.
 * The lateral faces are coarse (6 degrees per signal level, so 15 levels span 90) and the top/bottom faces are
 * fine (1 degree per level, up to 5), so coarse*6 + fine reaches every whole degree. Each face also has a fixed
 * sign: seen from the front, the right-hand faces turn it +, the left-hand faces and the back turn it - (with
 * the front pointing up: north is coarse +, south coarse -, east fine +, west fine -). Unpowered it holds
 * 0 degrees. Right-click assembles / disassembles as usual.
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

    /** Signed target in whole degrees: coarse faces at 6 degrees per level plus fine faces at 1 degree per level (max 5). */
    static float redstoneTarget(Level level, BlockPos pos, BlockState state) {
        Direction facing = state.getValue(DirectionalKineticBlock.FACING);
        int coarsePos = 0, coarseNeg = 0, finePos = 0, fineNeg = 0;
        for (Direction d : Direction.values()) {
            if (d == facing) continue;
            int s = level.getSignal(pos.relative(d), d);
            if (s == 0) continue;
            if (d == facing.getOpposite()) {
                coarseNeg = Math.max(coarseNeg, s);
                continue;
            }
            int side = sideSign(d, facing);
            if (side > 0) coarsePos = Math.max(coarsePos, s);
            else if (side < 0) coarseNeg = Math.max(coarseNeg, s);
            else {
                int ref = refSign(d, facing);
                if (ref > 0) finePos = Math.max(finePos, Math.min(5, s));
                else if (ref < 0) fineNeg = Math.max(fineNeg, Math.min(5, s));
            }
        }
        int degrees = (coarsePos - coarseNeg) * 6 + (finePos - fineNeg);
        double cap = AshCCConfig.FLAP_REDSTONE_ANGLE.get();
        return (float) Mth.clamp(degrees, -cap, cap);
    }

    /**
     * Sign of a lateral input face (the pair across the rotation axis, along facing x reference): +1, -1,
     * or 0 when the face is on the reference axis instead. The reference is up, or X for a vertical facing.
     */
    static int sideSign(Direction d, Direction facing) {
        net.minecraft.core.Vec3i a = facing.getNormal();
        net.minecraft.core.Vec3i r = Math.abs(a.getY()) < 1 ? new net.minecraft.core.Vec3i(0, 1, 0) : new net.minecraft.core.Vec3i(1, 0, 0);
        net.minecraft.core.Vec3i dv = d.getNormal();
        int along = dv.getX() * (a.getY() * r.getZ() - a.getZ() * r.getY())
            + dv.getY() * (a.getZ() * r.getX() - a.getX() * r.getZ())
            + dv.getZ() * (a.getX() * r.getY() - a.getY() * r.getX());
        return Integer.signum(along);
    }

    /** Sign of a reference-axis input face (top/bottom, or east/west for a vertical facing): +1, -1, or 0. */
    static int refSign(Direction d, Direction facing) {
        net.minecraft.core.Vec3i a = facing.getNormal();
        net.minecraft.core.Vec3i r = Math.abs(a.getY()) < 1 ? new net.minecraft.core.Vec3i(0, 1, 0) : new net.minecraft.core.Vec3i(1, 0, 0);
        net.minecraft.core.Vec3i dv = d.getNormal();
        return Integer.signum(dv.getX() * r.getX() + dv.getY() * r.getY() + dv.getZ() * r.getZ());
    }

    /** 0..15 from how far the flap is turned either way, full at redstoneAngle. */
    public int comparatorOutput() {
        if (!assembled()) return 0;
        double ratio = Math.abs(currentAngle()) / AshCCConfig.FLAP_REDSTONE_ANGLE.get();
        return Mth.clamp((int) Math.round(ratio * 15), 0, 15);
    }
}
