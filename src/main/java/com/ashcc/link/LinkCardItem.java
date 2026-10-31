package com.ashcc.link;

import com.ashcc.AshCC;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Link card. Use on peripheral blocks to collect them (use again to remove), then use on a link hub to register
 * them all (sneak to unregister them instead). Sneak-use in the air clears the card.
 */
public class LinkCardItem extends Item {
    public LinkCardItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    public static List<LinkTarget> targets(ItemStack stack) {
        return stack.getOrDefault(AshCC.LINK_TARGETS.get(), List.of());
    }

    /** Adds the target, or removes it if that block is already on the card. Returns true when added. */
    public static boolean toggle(ItemStack stack, LinkTarget target) {
        List<LinkTarget> list = new ArrayList<>(targets(stack));
        boolean removed = list.removeIf(t -> t.sameBlock(target));
        if (!removed) list.add(target);
        stack.set(AshCC.LINK_TARGETS.get(), List.copyOf(list));
        return !removed;
    }

    /** Runs before the block's own interaction, so chests and computers do not open their screens. */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext ctx) {
        Level level = ctx.getLevel();
        Player player = ctx.getPlayer();
        if (level.isClientSide) return InteractionResult.SUCCESS;

        if (level.getBlockEntity(ctx.getClickedPos()) instanceof LinkHubBlockEntity hub) {
            List<LinkTarget> list = targets(stack);
            boolean remove = player != null && player.isShiftKeyDown();
            LinkHubBlockEntity.Result r = remove ? hub.removeTargets(list) : hub.addTargets(list);
            if (!remove) stack.remove(AshCC.LINK_TARGETS.get());
            message(player, Component.translatable(remove ? "message.ashcc.card.unlinked" : "message.ashcc.card.linked",
                r.changed(), r.skipped(), hub.linkCount()));
            return InteractionResult.CONSUME;
        }

        IPeripheral p = level.getCapability(PeripheralCapability.get(), ctx.getClickedPos(), ctx.getClickedFace());
        if (p == null) {
            message(player, Component.translatable("message.ashcc.card.not_peripheral"));
            return InteractionResult.FAIL;
        }
        LinkTarget target = new LinkTarget(GlobalPos.of(level.dimension(), ctx.getClickedPos()), ctx.getClickedFace(), p.getType());
        boolean added = toggle(stack, target);
        message(player, Component.translatable(added ? "message.ashcc.card.added" : "message.ashcc.card.removed",
            p.getType(), ctx.getClickedPos().toShortString(), targets(stack).size()));
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown() && !targets(stack).isEmpty()) {
            if (!level.isClientSide) {
                stack.remove(AshCC.LINK_TARGETS.get());
                message(player, Component.translatable("message.ashcc.card.cleared"));
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        return InteractionResultHolder.pass(stack);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return !targets(stack).isEmpty();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        List<LinkTarget> list = targets(stack);
        tooltip.add(Component.translatable("tooltip.ashcc.card.count", list.size()).withStyle(ChatFormatting.GRAY));
        for (int i = 0; i < Math.min(list.size(), 8); i++) {
            LinkTarget t = list.get(i);
            tooltip.add(Component.literal(" " + t.type() + " @ " + t.pos().pos().toShortString()).withStyle(ChatFormatting.DARK_GRAY));
        }
        if (list.size() > 8) tooltip.add(Component.literal(" ...").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.ashcc.card.usage").withStyle(ChatFormatting.DARK_AQUA));
    }

    private static void message(Player player, Component text) {
        if (player != null) player.displayClientMessage(text, true);
    }
}
