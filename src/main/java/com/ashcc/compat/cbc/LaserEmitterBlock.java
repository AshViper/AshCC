package com.ashcc.compat.cbc;

import com.ashcc.AshCCConfig;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** Back end of a laser cannon; stores the Forge Energy the beam uses. Using it with an empty hand shows the charge. */
public class LaserEmitterBlock extends LaserCannonBlock implements EntityBlock {
    private static final MapCodec<LaserEmitterBlock> CODEC = simpleCodec(LaserEmitterBlock::new);

    public LaserEmitterBlock(Properties properties) {
        super(properties, 0);
    }

    @Override
    protected MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LaserEmitterBlockEntity(CbcCompat.LASER_EMITTER_BE.get(), pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof LaserEmitterBlockEntity emitter)) return InteractionResult.PASS;
        if (!level.isClientSide) {
            player.displayClientMessage(Component.translatable("block.ashcc.laser_cannon_emitter.status", emitter.energy().getEnergyStored(),
                emitter.energy().getMaxEnergyStored(), AshCCConfig.LASER_CANNON_ENERGY_PER_TICK.get()), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
