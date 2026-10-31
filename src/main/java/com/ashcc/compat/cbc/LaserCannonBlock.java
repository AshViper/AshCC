package com.ashcc.compat.cbc;

import org.jetbrains.annotations.NotNull;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;

/** A block of a laser cannon: one emitter at the back and lenses in front. Assembles into {@link MountedLaserContraption}. */
public abstract class LaserCannonBlock extends LineCannonBlock {
    protected LaserCannonBlock(Properties properties, double inset) {
        super(properties, inset);
    }

    @Override
    public @NotNull AbstractMountedCannonContraption getCannonContraption() {
        return new MountedLaserContraption();
    }
}
