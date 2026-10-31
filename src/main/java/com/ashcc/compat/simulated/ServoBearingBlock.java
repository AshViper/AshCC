package com.ashcc.compat.simulated;

import com.mojang.serialization.MapCodec;
import dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Body of the servo bearing. FACING = the side the turntable faces. Once assembled the turntable (and everything on it)
 * is a separate Sable sub-level held by a rotary joint, so blocks can still be placed on it, like Simulated's swivel bearing.
 */
public class ServoBearingBlock extends DirectionalBlock implements EntityBlock, BlockSubLevelAssemblyListener {
    public static final MapCodec<ServoBearingBlock> CODEC = simpleCodec(ServoBearingBlock::new);
    public static final BooleanProperty ASSEMBLED = BooleanProperty.create("assembled");
    /** Height of the body along FACING; the turntable plate takes the remaining 6 px. */
    static final double BODY = 10;

    public ServoBearingBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP).setValue(ASSEMBLED, false));
    }

    @Override
    protected MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ASSEMBLED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /** A slab of the block between {@code from} and {@code to} pixels, measured from the back towards FACING. */
    static VoxelShape slab(Direction facing, double from, double to) {
        return switch (facing) {
            case UP -> Block.box(0, from, 0, 16, to, 16);
            case DOWN -> Block.box(0, 16 - to, 0, 16, 16 - from, 16);
            case SOUTH -> Block.box(0, 0, from, 16, 16, to);
            case NORTH -> Block.box(0, 0, 16 - to, 16, 16, 16 - from);
            case EAST -> Block.box(from, 0, 0, to, 16, 16);
            case WEST -> Block.box(16 - to, 0, 0, 16 - from, 16, 16);
        };
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(ASSEMBLED) ? slab(state.getValue(FACING), 0, BODY) : Shapes.block();
    }

    /** Empty hand: assemble, or disassemble when assembled (like the swivel bearing). */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
                                              BlockHitResult hit) {
        if (!stack.isEmpty() || player.isShiftKeyDown() || !player.mayBuild()) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof ServoBearingBlockEntity be) be.toggleNextTick();
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide && level.getBlockEntity(pos) instanceof ServoBearingBlockEntity be) be.onBroken();
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ServoBearingBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != SimulatedCompat.SERVO_BEARING_BE.get()) return null;
        return (l, p, s, be) -> ((ServoBearingBlockEntity) be).serverTick();
    }

    // ---- Sable moves this block when the structure it is on gets assembled into a ship ----

    @Override
    public void beforeMove(ServerLevel originLevel, ServerLevel resultingLevel, BlockState newState, BlockPos oldPos, BlockPos newPos) {
        if (originLevel.getBlockEntity(oldPos) instanceof ServoBearingBlockEntity be) be.beforeMove();
    }

    @Override
    public void afterMove(ServerLevel originLevel, ServerLevel resultingLevel, BlockState newState, BlockPos oldPos, BlockPos newPos) {
        if (resultingLevel.getBlockEntity(newPos) instanceof ServoBearingBlockEntity be) be.associatePlateWithParent();
    }
}
