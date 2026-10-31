package com.ashcc.link;

import com.ashcc.AshCC;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/** Place next to a computer. Link peripherals to it with a link card; they appear as if on a wired network. */
public class LinkHubBlock extends BaseEntityBlock {
    public static final MapCodec<LinkHubBlock> CODEC = simpleCodec(LinkHubBlock::new);

    public LinkHubBlock(Properties properties) {
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

    /** Empty hand: list the links in chat. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof LinkHubBlockEntity hub)) return InteractionResult.PASS;
        List<Map<String, Object>> links = hub.peripheral().getLinks();
        player.sendSystemMessage(Component.translatable("message.ashcc.hub.links", links.size()));
        for (Map<String, Object> l : links) {
            boolean on = Boolean.TRUE.equals(l.get("connected"));
            if (l.containsKey("player")) {
                player.sendSystemMessage(Component.translatable(on ? "message.ashcc.hub.player_on" : "message.ashcc.hub.player_off",
                    l.get("name"), l.get("player")));
            } else {
                player.sendSystemMessage(Component.translatable(on ? "message.ashcc.hub.link_on" : "message.ashcc.hub.link_off",
                    l.get("name"), l.get("x"), l.get("y"), l.get("z")));
            }
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LinkHubBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, AshCC.LINK_HUB_BE.get(), LinkHubBlockEntity::serverTick);
    }
}
