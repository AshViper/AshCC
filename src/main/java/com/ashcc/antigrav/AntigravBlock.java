package com.ashcc.antigrav;

import com.ashcc.AshCC;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.jetbrains.annotations.Nullable;

/**
 * Anti-gravity core. On a Sable sub-level it cancels gravity and moves the ship as told from Lua.
 * No facing: the ship's own frame (as assembled) is forward = north, up = up. ACTIVE = currently driving the ship.
 */
public class AntigravBlock extends BaseEntityBlock {
    public static final MapCodec<AntigravBlock> CODEC = simpleCodec(AntigravBlock::new);
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    public AntigravBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ACTIVE, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ACTIVE);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!state.getValue(ACTIVE) || random.nextInt(3) != 0) return;
        double x = pos.getX() + 0.5 + random.nextGaussian() * 0.3;
        double y = pos.getY() + 0.5 + random.nextGaussian() * 0.3;
        double z = pos.getZ() + 0.5 + random.nextGaussian() * 0.3;
        level.addParticle(ParticleTypes.REVERSE_PORTAL, x, y, z, 0, 0.02, 0);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return AshCC.ANTIGRAV_BE.get().create(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, AshCC.ANTIGRAV_BE.get(), AntigravBlockEntity::serverTick);
    }
}
