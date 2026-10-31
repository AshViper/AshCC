package com.ashcc.compat.cbc;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.DirectionalBlock;

/** A rail section of a railgun; each one after the first adds speedPerRail to the muzzle speed. */
public class RailgunRailBlock extends RailgunBlock {
    private static final MapCodec<RailgunRailBlock> CODEC = simpleCodec(RailgunRailBlock::new);

    public RailgunRailBlock(Properties properties) {
        super(properties, 2);
    }

    @Override
    protected MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }
}
