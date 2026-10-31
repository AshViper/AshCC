package com.ashcc.thruster;

import com.ashcc.AshCC;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import org.jetbrains.annotations.Nullable;

/**
 * Thruster. FACING is the nozzle (exhaust) direction; thrust pushes the ship the opposite way.
 * Only produces force on a Sable sub-level.
 */
public class ThrusterBlock extends BaseEntityBlock {
    public static final MapCodec<ThrusterBlock> CODEC = simpleCodec(ThrusterBlock::new);
    public static final DirectionProperty FACING = DirectionalBlock.FACING;
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    public ThrusterBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.DOWN).setValue(ACTIVE, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ACTIVE);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Nozzle points back at the player (placing under a ship from below -> pushes up); sneak to reverse
        Direction nozzle = context.getNearestLookingDirection().getOpposite();
        if (context.getPlayer() != null && context.getPlayer().isShiftKeyDown()) nozzle = nozzle.getOpposite();
        return defaultBlockState().setValue(FACING, nozzle);
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
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!state.getValue(ACTIVE)) return;
        Direction d = state.getValue(FACING);
        double x = pos.getX() + 0.5 + d.getStepX() * 0.6;
        double y = pos.getY() + 0.5 + d.getStepY() * 0.6;
        double z = pos.getZ() + 0.5 + d.getStepZ() * 0.6;
        for (int i = 0; i < 2; i++) {
            double s = 0.15 + random.nextDouble() * 0.1;
            level.addParticle(ParticleTypes.FLAME, x, y, z,
                d.getStepX() * s + random.nextGaussian() * 0.02, d.getStepY() * s + random.nextGaussian() * 0.02, d.getStepZ() * s + random.nextGaussian() * 0.02);
        }
        level.addParticle(ParticleTypes.SMOKE, x, y, z, d.getStepX() * 0.1, d.getStepY() * 0.1, d.getStepZ() * 0.1);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return AshCC.THRUSTER_BE.get().create(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, AshCC.THRUSTER_BE.get(), ThrusterBlockEntity::serverTick);
    }
}
