package com.ashcc.decoy;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** Flare / chaff launcher. FACING is the launch direction; load it by using flares or chaff on it (or a hopper). */
public class DecoyLauncherBlock extends BaseEntityBlock {
    public static final MapCodec<DecoyLauncherBlock> CODEC = simpleCodec(DecoyLauncherBlock::new);
    public static final DirectionProperty FACING = DirectionalBlock.FACING;

    public DecoyLauncherBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
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
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
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
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DecoyLauncherBlockEntity(pos, state);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
                                              BlockHitResult hit) {
        if (DecoyLauncherBlockEntity.kindOf(stack) < 0) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof DecoyLauncherBlockEntity be) {
            ItemStack rest = be.insert(stack.copy(), false);
            if (!player.getAbilities().instabuild) stack.setCount(rest.getCount());
            showAmmo(player, be);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof DecoyLauncherBlockEntity be) showAmmo(player, be);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static void showAmmo(Player player, DecoyLauncherBlockEntity be) {
        player.displayClientMessage(Component.translatable("message.ashcc.decoy_launcher.ammo",
            be.ammo(DecoyEntity.FLARE), be.ammo(DecoyEntity.CHAFF), be.capacity()), true);
    }

    /** Drops the loaded ammo only when a player breaks it (Sable moving the block into a ship must not duplicate it). */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && !player.getAbilities().instabuild && level.getBlockEntity(pos) instanceof DecoyLauncherBlockEntity be) {
            for (ItemStack s : be.ammoStacks()) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), s);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
