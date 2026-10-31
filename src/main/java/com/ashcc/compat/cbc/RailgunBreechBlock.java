package com.ashcc.compat.cbc;

import com.ashcc.AshCCConfig;
import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannons.InteractableCannonBlock;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBlock;

/**
 * Back end of a railgun. Load it by hand (also while assembled), with a hopper into the breech or the mount, or from a
 * Create funnel; sneak-use with an empty hand takes a round back. Using it with an empty hand shows ammo and charge.
 */
public class RailgunBreechBlock extends RailgunBlock implements EntityBlock, InteractableCannonBlock {
    private static final MapCodec<RailgunBreechBlock> CODEC = simpleCodec(RailgunBreechBlock::new);

    public RailgunBreechBlock(Properties properties) {
        super(properties, 0);
    }

    @Override
    protected MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RailgunBreechBlockEntity(CbcCompat.RAILGUN_BREECH_BE.get(), pos, state);
    }

    /** Puts the held round in or takes one out; returns whether anything changed. */
    private static boolean interact(Player player, InteractionHand hand, RailgunBreechBlockEntity breech) {
        ItemStack held = player.getItemInHand(hand);
        if (!held.isEmpty()) {
            ItemStack left = breech.items().insertItem(0, held, false);
            if (left.getCount() == held.getCount()) return false;
            if (!player.isCreative()) player.setItemInHand(hand, left);
            return true;
        }
        if (player.isShiftKeyDown()) {
            ItemStack out = breech.items().extractItem(0, 1, false);
            if (out.isEmpty()) return false;
            if (!player.addItem(out)) player.drop(out, false);
            return true;
        }
        status(player, breech);
        return false;
    }

    private static void status(Player player, RailgunBreechBlockEntity breech) {
        ItemStack ammo = breech.ammo();
        Component rounds = ammo.isEmpty() ? Component.translatable("block.ashcc.railgun_breech.empty")
            : Component.literal(ammo.getCount() + " x ").append(ammo.getHoverName());
        player.displayClientMessage(Component.translatable("block.ashcc.railgun_breech.status", rounds,
            breech.energy().getEnergyStored(), breech.energy().getMaxEnergyStored(), AshCCConfig.RAILGUN_ENERGY_PER_SHOT.get()), true);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!RailgunBreechBlockEntity.isAmmo(stack)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!(level.getBlockEntity(pos) instanceof RailgunBreechBlockEntity breech)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!level.isClientSide && interact(player, hand, breech)) {
            level.playSound(null, pos, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 1.0f, 1.2f);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof RailgunBreechBlockEntity breech)) return InteractionResult.PASS;
        if (!level.isClientSide) interact(player, InteractionHand.MAIN_HAND, breech);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public boolean onInteractWhileAssembled(Player player, BlockPos localPos, Direction side, InteractionHand hand, Level level,
                                            Contraption contraption, BlockEntity be, StructureBlockInfo info, PitchOrientedContraptionEntity entity) {
        if (!(be instanceof RailgunBreechBlockEntity breech) || hand != InteractionHand.MAIN_HAND) return false;
        ItemStack held = player.getItemInHand(hand);
        if (!held.isEmpty() && !RailgunBreechBlockEntity.isAmmo(held)) return false;
        if (level.isClientSide) return true;
        if (interact(player, hand, breech)) {
            BigCannonBlock.writeAndSyncSingleBlockData(be, info, entity, contraption);
            level.playSound(null, entity.blockPosition(), SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 1.0f, 1.2f);
        }
        return true;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        // moving: taken into a contraption on assembly, which keeps the rounds
        if (!moving && !state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof RailgunBreechBlockEntity breech && !breech.ammo().isEmpty()) {
            Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, breech.ammo().copy());
        }
        super.onRemove(state, level, pos, newState, moving);
    }
}
