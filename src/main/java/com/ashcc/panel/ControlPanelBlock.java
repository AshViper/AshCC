package com.ashcc.panel;

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
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.fml.loading.FMLEnvironment;
import org.jetbrains.annotations.Nullable;

/**
 * Control panel: a plate on a floor, wall or ceiling with a 4x4 grid of controls set up by the player (buttons, toggles,
 * knobs, sliders, sticks, throttles, screens, HUDs), on a 4x4 or 8x8 grid, tilted up to 60 degrees. Sneak-right-click a cell with an empty hand to set it up; right-click to use. Each control drives
 * numbered channels (0..15): drivebywire cables, CC (peripheral "control_panel") and a redstone signal of the highest channel.
 */
public class ControlPanelBlock extends BaseEntityBlock {
    public static final MapCodec<ControlPanelBlock> CODEC = simpleCodec(ControlPanelBlock::new);
    public static final EnumProperty<AttachFace> FACE = BlockStateProperties.ATTACH_FACE;
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    private static final java.util.Map<String, VoxelShape> SHAPES = new java.util.concurrent.ConcurrentHashMap<>();

    public ControlPanelBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACE, AttachFace.FLOOR).setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACE, FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction clicked = ctx.getClickedFace();
        if (clicked == Direction.UP) return defaultBlockState().setValue(FACE, AttachFace.FLOOR).setValue(FACING, ctx.getHorizontalDirection());
        if (clicked == Direction.DOWN) return defaultBlockState().setValue(FACE, AttachFace.CEILING).setValue(FACING, ctx.getHorizontalDirection());
        return defaultBlockState().setValue(FACE, AttachFace.WALL).setValue(FACING, clicked);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int tilt = level.getBlockEntity(pos) instanceof ControlPanelBlockEntity be ? be.tilt() : 0;
        return SHAPES.computeIfAbsent(state + "/" + tilt, k -> {
            net.minecraft.world.phys.AABB b = PanelFrame.of(state, tilt).bounds();
            return net.minecraft.world.phys.shapes.Shapes.box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
        });
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof ControlPanelBlockEntity be)) return InteractionResult.PASS;
        // The client finds the cell along its line of sight (the surface may be tilted) and tells the server
        if (level.isClientSide) {
            // Only resolved on the client, so the client classes never load on a dedicated server
            if (FMLEnvironment.dist.isClient()) com.ashcc.panel.client.PanelClient.click(be, player.isShiftKeyDown());
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return level.getBlockEntity(pos) instanceof ControlPanelBlockEntity be ? be.redstone() : 0;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof ControlPanelBlockEntity be) be.clearOutputs();
        super.onRemove(state, level, pos, newState, moved);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return AshCC.CONTROL_PANEL_BE.get().create(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, AshCC.CONTROL_PANEL_BE.get(), ControlPanelBlockEntity::serverTick);
    }
}
