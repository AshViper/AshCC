package com.ashcc.compat.cbc;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.DirectionalBlock;

/** A focusing lens section of a laser cannon; each one after the first adds range, block power and damage. */
public class LaserLensBlock extends LaserCannonBlock {
    private static final MapCodec<LaserLensBlock> CODEC = simpleCodec(LaserLensBlock::new);

    public LaserLensBlock(Properties properties) {
        super(properties, 3);
    }

    @Override
    protected MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }
}
