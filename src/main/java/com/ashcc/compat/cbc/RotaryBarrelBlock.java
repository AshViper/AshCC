package com.ashcc.compat.cbc;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import rbasamoyai.createbigcannons.cannons.autocannon.AutocannonBarrelBlock;
import rbasamoyai.createbigcannons.cannons.autocannon.AutocannonBlockEntity;
import rbasamoyai.createbigcannons.cannons.autocannon.material.AutocannonMaterial;

/** Barrel cluster of the rotary autocannon: a CBC autocannon barrel of the rotary material. */
public class RotaryBarrelBlock extends AutocannonBarrelBlock {
    private final MapCodec<RotaryBarrelBlock> codec;

    public RotaryBarrelBlock(Properties properties, AutocannonMaterial material) {
        super(properties, material);
        this.codec = simpleCodec(p -> new RotaryBarrelBlock(p, material));
    }

    @Override
    protected MapCodec<? extends DirectionalBlock> codec() {
        return codec;
    }

    @Override
    public BlockEntityType<? extends AutocannonBlockEntity> getBlockEntityType() {
        return CbcCompat.ROTARY_BARREL_BE.get();
    }
}
