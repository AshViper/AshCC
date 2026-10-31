package com.ashcc.jet;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * One section of a jet engine. FACING is the exhaust direction (the engine pushes the opposite way), the same for every
 * section of one engine. FORMED is set by the nozzle while the section is part of a complete engine.
 * Right-click with an iron ingot to repair the engine, with a wrench to set its throttle limit.
 */
public class JetPartBlock extends Block {
    public static final DirectionProperty FACING = DirectionalBlock.FACING;
    public static final BooleanProperty FORMED = BooleanProperty.create("formed");

    private final JetPart part;

    public JetPartBlock(JetPart part, Properties properties) {
        super(properties);
        this.part = part;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH).setValue(FORMED, false));
    }

    public JetPart part() {
        return part;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, FORMED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Placed against another section: line up with it. Otherwise the exhaust points away from the player; sneak to reverse.
        BlockState against = context.getLevel().getBlockState(context.getClickedPos().relative(context.getClickedFace().getOpposite()));
        Direction exhaust;
        if (against.getBlock() instanceof JetPartBlock && against.getValue(FACING).getAxis() == context.getClickedFace().getAxis()) {
            exhaust = against.getValue(FACING);
        } else {
            exhaust = context.getNearestLookingDirection();
            if (context.getPlayer() != null && context.getPlayer().isShiftKeyDown()) exhaust = exhaust.getOpposite();
        }
        return defaultBlockState().setValue(FACING, exhaust);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    private static final TagKey<Item> WRENCHES = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "tools/wrench"));
    private static final ResourceLocation CREATE_WRENCH = ResourceLocation.fromNamespaceAndPath("create", "wrench");

    /**
     * A wrench on any block of a complete engine sets its throttle limit: -10%, sneaking +10% (wrapping 10%..100%). Handled in the click event,
     * before the item, because a sneaking click with an item in hand never reaches the block.
     */
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ItemStack stack = event.getItemStack();
        if (!stack.is(WRENCHES) && !CREATE_WRENCH.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))) return;
        Level level = event.getLevel();
        if (!(level.getBlockState(event.getPos()).getBlock() instanceof JetPartBlock)) return;
        JetEngineBlockEntity engine = JetEngineBlockEntity.find(level, event.getPos());
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        if (level.isClientSide) return;
        Player player = event.getEntity();
        if (engine == null || !engine.formed()) {
            player.displayClientMessage(Component.translatable("ashcc.jet.wrench.incomplete").withStyle(ChatFormatting.RED), true);
            return;
        }
        // The limit starts at 100%, so a plain click lowers it
        double limit = engine.cycleThrottleLimit(!player.isShiftKeyDown());
        MutableComponent msg = Component.translatable("ashcc.jet.wrench.limit", Math.round(limit * 100));
        if (limit < 1 && engine.hasAfterburner()) msg.append(Component.translatable("ashcc.jet.wrench.no_ab").withStyle(ChatFormatting.GRAY));
        player.displayClientMessage(msg, true);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(Items.IRON_INGOT)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide) return ItemInteractionResult.SUCCESS;
        JetEngineBlockEntity engine = JetEngineBlockEntity.find(level, pos);
        if (engine == null || !engine.repair()) return ItemInteractionResult.FAIL;
        if (!player.getAbilities().instabuild) stack.shrink(1);
        return ItemInteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide && state.getValue(FORMED) && !part.isNozzle()) {
            // The nozzle rescans every few ticks anyway; this makes it notice at once
            JetEngineBlockEntity engine = JetEngineBlockEntity.find(level, pos.relative(state.getValue(FACING)), state.getValue(FACING));
            if (engine != null) engine.requestScan();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
