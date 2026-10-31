package com.ashcc.monitor;

import com.ashcc.AshCC;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * HD monitor block. FACING = the side the screen shows on (any of the six), ROTATION = the screen's top for floor and
 * ceiling monitors. Neighbouring monitors with the same orientation join into rectangles (see {@link MonitorShapes}).
 * HOLOGRAM hides the casing so transparent pixels show the world behind.
 * Touch input comes from the client (see the client tick handler), so right-clicking only swallows the click here.
 */
public class HdMonitorBlock extends BaseEntityBlock {
    public static final MapCodec<HdMonitorBlock> CODEC = simpleCodec(HdMonitorBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final DirectionProperty ROTATION = DirectionProperty.create("rotation", Direction.Plane.HORIZONTAL);
    public static final BooleanProperty HOLOGRAM = BooleanProperty.create("hologram");

    public HdMonitorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(ROTATION, Direction.NORTH).setValue(HOLOGRAM, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ROTATION, HOLOGRAM);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // The screen faces the player; on floors and ceilings its top points away from them
        Direction facing = context.getNearestLookingDirection().getOpposite();
        Direction rot = facing.getAxis().isVertical() ? context.getHorizontalDirection() : Direction.NORTH;
        return defaultBlockState().setValue(FACING, facing).setValue(ROTATION, rot);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING))).setValue(ROTATION, rotation.rotate(state.getValue(ROTATION)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return state.getValue(HOLOGRAM);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        // Touches are sent by the client with exact pixels; only the screen side reacts
        return hit.getDirection() == state.getValue(FACING) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level.isClientSide) return;
        if (oldState.is(this) && oldState.getValue(FACING) == state.getValue(FACING) && oldState.getValue(ROTATION) == state.getValue(ROTATION)) return;
        MonitorShapes.rebuild(level, pos);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        boolean sameShape = newState.is(this) && newState.getValue(FACING) == state.getValue(FACING) && newState.getValue(ROTATION) == state.getValue(ROTATION);
        if (!sameShape && level.getBlockEntity(pos) instanceof HdMonitorBlockEntity be) be.disposeScreen();
        super.onRemove(state, level, pos, newState, movedByPiston);
        if (level.isClientSide || sameShape) return;
        MonitorGeometry g = MonitorGeometry.of(state);
        for (Direction d : new Direction[]{g.right(), g.right().getOpposite(), g.up(), g.up().getOpposite()}) {
            MonitorShapes.rebuild(level, pos.relative(d));
        }
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HdMonitorBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, AshCC.HD_MONITOR_BE.get(), HdMonitorBlockEntity::serverTick);
    }
}
