package com.ashcc.compat.cbc;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import rbasamoyai.createbigcannons.cannons.CannonContraptionProviderBlock;
import rbasamoyai.createbigcannons.crafting.casting.CannonCastShape;

/**
 * A block of a cannon built as one back block (railgun breech, laser emitter) with parts in a straight line in front of
 * it, all along the same axis ({@link AbstractLineCannonContraption}). FACING points towards the muzzle.
 */
public abstract class LineCannonBlock extends DirectionalBlock implements CannonContraptionProviderBlock {
    private final VoxelShape[] shapes;

    protected LineCannonBlock(Properties properties, double inset) {
        super(properties.pushReaction(PushReaction.BLOCK));
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
        double a = inset;
        double b = 16 - inset;
        shapes = new VoxelShape[]{
            Block.box(0, a, a, 16, b, b),
            Block.box(a, 0, a, b, 16, b),
            Block.box(a, a, 0, b, b, 16)};
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getNearestLookingDirection());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes[state.getValue(FACING).getAxis().ordinal()];
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }

    @Override
    public Direction getFacing(BlockState state) {
        return state.getValue(FACING);
    }

    @Override
    public CannonCastShape getCannonShape() {
        return CannonCastShape.VERY_LARGE;
    }

    @Override
    public boolean isComplete(BlockState state) {
        return true;
    }
}
