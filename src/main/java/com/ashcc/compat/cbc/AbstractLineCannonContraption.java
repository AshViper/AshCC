package com.ashcc.compat.cbc;

import com.simibubi.create.content.contraptions.AssemblyException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * A cannon made of one back block (railgun breech, laser emitter) and 1..max parts in a straight line in front of it.
 * The back block faces the parts; {@code startPos} is the back block and {@code initialOrientation} points to the muzzle.
 * Assembly errors use the translation keys {@code exception.ashcc.<key>.{too_long, two_backs, no_back, back_direction, no_parts}}.
 */
public abstract class AbstractLineCannonContraption extends AbstractMountedCannonContraption {
    /** Blocks that belong to this kind of cannon. */
    protected abstract boolean isPart(Block block);

    /** The back block (exactly one, at the rear end). */
    protected abstract boolean isBack(Block block);

    protected abstract int maxParts();

    protected abstract String errorKey();

    /** Energy store of the back block, for {@link #energyOf}; null when it has none. */
    public abstract @Nullable IEnergyStorage energyStorage();

    private AssemblyException error(String what, Object... args) {
        return new AssemblyException(Component.translatable("exception.ashcc." + errorKey() + "." + what, args));
    }

    @Override
    public boolean assemble(Level level, BlockPos pos) throws AssemblyException {
        BlockState startState = level.getBlockState(pos);
        if (!(startState.getBlock() instanceof LineCannonBlock start) || !isPart(startState.getBlock())) return false;
        Direction.Axis axis = start.getFacing(startState).getAxis();
        Direction positive = Direction.get(Direction.AxisDirection.POSITIVE, axis);
        Direction negative = positive.getOpposite();

        List<BlockPos> line = new ArrayList<>();
        line.add(pos);
        int max = maxParts() + 1;
        for (Direction d : new Direction[]{positive, negative}) {
            BlockPos p = pos.relative(d);
            while (level.getBlockState(p).getBlock() instanceof LineCannonBlock b && isPart(b) && b.getFacing(level.getBlockState(p)).getAxis() == axis) {
                if (d == positive) {
                    line.add(p);
                    frontExtensionLength++;
                } else {
                    line.addFirst(p);
                    backExtensionLength++;
                }
                if (line.size() > max) throw error("too_long", max - 1);
                p = p.relative(d);
            }
        }

        // Exactly one back block, at one end, facing along the line towards the parts
        BlockPos back = null;
        for (BlockPos p : line) {
            if (isBack(level.getBlockState(p).getBlock())) {
                if (back != null) throw error("two_backs");
                back = p;
            }
        }
        if (back == null) throw error("no_back");
        Direction facing = level.getBlockState(back).getValue(LineCannonBlock.FACING);
        BlockPos end = facing == positive ? line.getFirst() : facing == negative ? line.getLast() : null;
        if (!back.equals(end)) throw error("back_direction", back.getX(), back.getY(), back.getZ());
        if (line.size() < 2) throw error("no_parts");

        initialOrientation = facing;
        anchor = pos;
        startPos = back.subtract(pos);
        for (BlockPos p : line) {
            BlockPos local = p.subtract(pos);
            StructureBlockInfo info = new StructureBlockInfo(local, level.getBlockState(p), getBlockEntityNBT(level, p));
            blocks.put(local, info);
            if (info.nbt() != null) {
                BlockEntity be = BlockEntity.loadStatic(local, info.state(), info.nbt(), level.registryAccess());
                if (be != null) presentBlockEntities.put(local, be);
            }
        }
        bounds = createBoundsFromExtensionLengths();
        return true;
    }

    /** Parts in front of the back block. */
    public int parts() {
        return Math.max(0, blocks.size() - 1);
    }

    /** Local position of the block just past the muzzle. */
    protected BlockPos muzzlePos() {
        return startPos.relative(initialOrientation, parts() + 1);
    }

    /** Local point the client's effects start from (railgun: where the shell spawns). */
    public Vec3 fxMuzzle() {
        return Vec3.atCenterOf(muzzlePos());
    }

    /** Distance along the barrel from the turning point to {@code pos}'s centre. */
    protected double along(BlockPos pos) {
        return Vec3.atLowerCornerOf(pos).dot(Vec3.atLowerCornerOf(initialOrientation.getNormal()));
    }

    @Override
    public float getWeightForStress() {
        return blocks.size() * 2.0f;
    }

    @Override
    public Vec3 getInteractionVec(PitchOrientedContraptionEntity poce) {
        return poce.toGlobalVector(Vec3.atCenterOf(startPos), 0.0F);
    }
}
