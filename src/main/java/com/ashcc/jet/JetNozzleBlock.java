package com.ashcc.jet;

import com.ashcc.AshCC;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import org.jetbrains.annotations.Nullable;

/**
 * The nozzle (plain or with afterburner) is the engine's controller: its block entity checks the structure, runs the engine and pushes the ship.
 * GLOW: 0 cold, 1 hot / running, 2 afterburner (also the light level).
 */
public class JetNozzleBlock extends JetPartBlock implements EntityBlock {
    public static final IntegerProperty GLOW = IntegerProperty.create("glow", 0, 2);

    public JetNozzleBlock(JetPart part, Properties properties) {
        super(part, properties);
        registerDefaultState(defaultBlockState().setValue(GLOW, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(GLOW);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide && level.getBlockEntity(pos) instanceof JetEngineBlockEntity engine) {
            engine.disassemble();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return AshCC.JET_ENGINE_BE.get().create(pos, state);
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type != AshCC.JET_ENGINE_BE.get()) return null;
        return level.isClientSide
            ? (l, p, s, be) -> ((JetEngineBlockEntity) be).clientTick()
            : (l, p, s, be) -> JetEngineBlockEntity.serverTick(l, p, s, (JetEngineBlockEntity) be);
    }
}
