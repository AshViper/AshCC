package com.ashcc.tracker;

import com.ashcc.AshCC;
import com.ashcc.link.LinkHubBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Head tracker. Use in the air to bind it to yourself (sneak: unbind), then use it on a link hub to register your head
 * direction as a "head_tracker" peripheral (sneak: unregister). The item does not need to be carried afterwards.
 */
public class HeadTrackerItem extends Item {
    public HeadTrackerItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    public static @Nullable TrackedPlayer player(ItemStack stack) {
        return stack.get(AshCC.TRACKED_PLAYER.get());
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (!(level.getBlockEntity(ctx.getClickedPos()) instanceof LinkHubBlockEntity hub)) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        Player user = ctx.getPlayer();
        TrackedPlayer tracked = player(stack);
        if (tracked == null) {
            message(user, Component.translatable("message.ashcc.tracker.unbound"));
            return InteractionResult.FAIL;
        }
        boolean remove = user != null && user.isShiftKeyDown();
        boolean changed = remove ? hub.removePlayer(tracked) : hub.addPlayer(tracked);
        String key = remove ? (changed ? "message.ashcc.tracker.unlinked" : "message.ashcc.tracker.not_linked")
            : (changed ? "message.ashcc.tracker.linked" : "message.ashcc.tracker.skipped");
        message(user, Component.translatable(key, tracked.name(), hub.linkCount()));
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) {
            if (player.isShiftKeyDown()) {
                stack.remove(AshCC.TRACKED_PLAYER.get());
                message(player, Component.translatable("message.ashcc.tracker.cleared"));
            } else {
                stack.set(AshCC.TRACKED_PLAYER.get(), new TrackedPlayer(player.getUUID(), player.getGameProfile().getName()));
                message(player, Component.translatable("message.ashcc.tracker.bound", player.getGameProfile().getName()));
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return player(stack) != null;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        TrackedPlayer tracked = player(stack);
        tooltip.add(tracked == null
            ? Component.translatable("tooltip.ashcc.tracker.unbound").withStyle(ChatFormatting.GRAY)
            : Component.translatable("tooltip.ashcc.tracker.bound", tracked.name()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.ashcc.tracker.usage").withStyle(ChatFormatting.DARK_AQUA));
    }

    private static void message(@Nullable Player player, Component text) {
        if (player != null) player.displayClientMessage(text, true);
    }
}
