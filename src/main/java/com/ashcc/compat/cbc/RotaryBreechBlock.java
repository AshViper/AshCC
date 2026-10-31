package com.ashcc.compat.cbc;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity;
import rbasamoyai.createbigcannons.cannons.autocannon.breech.AutocannonBreechBlock;
import rbasamoyai.createbigcannons.cannons.autocannon.material.AutocannonMaterial;

/**
 * Breech of the rotary autocannon. It is a CBC autocannon breech (same loading, magazines, handle and mounting) whose
 * block entity spins up while the trigger is held and then fires much faster than CBC's own breech.
 */
public class RotaryBreechBlock extends AutocannonBreechBlock {
    private final MapCodec<RotaryBreechBlock> codec;

    public RotaryBreechBlock(Properties properties, AutocannonMaterial material) {
        super(properties, material);
        this.codec = simpleCodec(p -> new RotaryBreechBlock(p, material));
    }

    @Override
    protected MapCodec<? extends DirectionalBlock> codec() {
        return codec;
    }

    @Override
    public BlockEntityType<? extends AbstractAutocannonBreechBlockEntity> getBlockEntityType() {
        return CbcCompat.ROTARY_BREECH_BE.get();
    }
}
