package com.ashcc.iff;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * IFF transponder. On a Sable sub-level it tags that ship's radar contacts with {@code iff = {code, callsign}}.
 * Use a named name tag on it to set the code; a redstone signal silences it.
 */
public class TransponderBlock extends BaseEntityBlock {
    public static final MapCodec<TransponderBlock> CODEC = simpleCodec(TransponderBlock::new);

    public TransponderBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TransponderBlockEntity(pos, state);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
                                              BlockHitResult hit) {
        if (!stack.is(Items.NAME_TAG) || !stack.has(DataComponents.CUSTOM_NAME)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof TransponderBlockEntity be) {
            be.setCode(stack.getHoverName().getString());
            player.displayClientMessage(Component.translatable("message.ashcc.transponder.code", be.code()), true);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof TransponderBlockEntity be) {
            String code = be.code().isEmpty() ? "-" : be.code();
            String key = be.transmitting() ? "message.ashcc.transponder.on" : "message.ashcc.transponder.off";
            player.displayClientMessage(Component.translatable(key, code, be.callsign().isEmpty() ? "-" : be.callsign()), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
