package com.ashcc.hose;

import com.ashcc.AshCC;
import com.ashcc.AshCCConfig;
import com.ashcc.compat.SableCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Objects;

/**
 * Fluid hose. Right-click the block to take fluid from, then the block to deliver to: one hose runs between the two
 * faces you clicked. Sneak-right-click a block to take off its hoses; sneak-right-click the air to cancel a start.
 */
public class HoseItem extends Item {
    private final boolean pipe;

    public HoseItem(Properties properties) {
        this(properties, false);
    }

    /** {@code pipe}: a rigid pipe along the block axes instead of a flexible hose. */
    public HoseItem(Properties properties, boolean pipe) {
        super(properties);
        this.pipe = pipe;
    }

    public boolean isPipe() {
        return pipe;
    }

    private String key(String what) {
        return "item.ashcc." + (pipe ? "pipe" : "hose") + "." + what;
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        Player player = ctx.getPlayer();
        ItemStack stack = ctx.getItemInHand();
        BlockPos pos = ctx.getClickedPos();
        Direction face = ctx.getClickedFace();
        if (level.isClientSide) return InteractionResult.SUCCESS;
        ServerLevel server = (ServerLevel) level;

        if (player != null && player.isShiftKeyDown()) {
            List<Hose> removed = HoseData.get(server).removeAt(server, pos);
            if (!removed.isEmpty()) {
                for (Hose h : removed) player.getInventory().placeItemBackInInventory(HoseData.item(h));
                say(player, Component.translatable("item.ashcc.hose.removed", removed.size()));
                level.playSound(null, pos, pipe ? SoundEvents.NETHERITE_BLOCK_BREAK : SoundEvents.LEASH_KNOT_BREAK, SoundSource.BLOCKS, 0.8f, 1.2f);
            }
            stack.remove(AshCC.HOSE_START.get());
            return InteractionResult.CONSUME;
        }

        if (HoseData.handler(level, pos, face) == null) {
            say(player, Component.translatable("item.ashcc.hose.no_fluid").withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }

        HoseStart start = stack.get(AshCC.HOSE_START.get());
        if (start == null || !start.dimension().equals(level.dimension().location())) {
            stack.set(AshCC.HOSE_START.get(), new HoseStart(pos, face, level.dimension().location()));
            say(player, Component.translatable(key("start")));
            level.playSound(null, pos, place(), SoundSource.BLOCKS, 0.8f, 1.0f);
            return InteractionResult.CONSUME;
        }
        if (start.pos().equals(pos)) {
            stack.remove(AshCC.HOSE_START.get());
            say(player, Component.translatable("item.ashcc.hose.cancelled"));
            return InteractionResult.CONSUME;
        }
        double length;
        int max;
        if (pipe) {
            // Rigid: both ends in one frame (the ground, or the same ship)
            if (AshCC.sableLoaded && !Objects.equals(SableCompat.subLevelAt(level, start.pos()), SableCompat.subLevelAt(level, pos))) {
                say(player, Component.translatable("item.ashcc.pipe.different_frame").withStyle(ChatFormatting.RED));
                return InteractionResult.FAIL;
            }
            length = Hose.length(Hose.route(start.pos(), start.face(), pos, face));
            max = AshCCConfig.PIPE_MAX_LENGTH.get();
        } else {
            length = HoseData.worldEnd(level, start.pos(), start.face()).distanceTo(HoseData.worldEnd(level, pos, face));
            max = AshCCConfig.HOSE_MAX_LENGTH.get();
        }
        if (length > max) {
            say(player, Component.translatable("item.ashcc.hose.too_long", (int) Math.ceil(length), max).withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }
        HoseData.get(server).add(server, start.pos(), start.face(), pos, face, pipe);
        stack.remove(AshCC.HOSE_START.get());
        if (player == null || !player.getAbilities().instabuild) stack.shrink(1);
        say(player, Component.translatable(key("connected"), String.format("%.1f", length)));
        level.playSound(null, pos, place(), SoundSource.BLOCKS, 0.8f, 0.8f);
        return InteractionResult.CONSUME;
    }

    private net.minecraft.sounds.SoundEvent place() {
        return pipe ? SoundEvents.NETHERITE_BLOCK_PLACE : SoundEvents.LEASH_KNOT_PLACE;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown() && stack.has(AshCC.HOSE_START.get())) {
            if (!level.isClientSide) {
                stack.remove(AshCC.HOSE_START.get());
                say(player, Component.translatable("item.ashcc.hose.cancelled"));
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        return InteractionResultHolder.pass(stack);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return stack.has(AshCC.HOSE_START.get());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(key("tooltip")).withStyle(ChatFormatting.GRAY));
        HoseStart start = stack.get(AshCC.HOSE_START.get());
        if (start != null) {
            tooltip.add(Component.translatable("item.ashcc.hose.tooltip_start", start.pos().getX(), start.pos().getY(), start.pos().getZ())
                .withStyle(ChatFormatting.YELLOW));
        }
    }

    private static void say(Player player, Component message) {
        if (player != null) player.displayClientMessage(message, true);
    }
}
