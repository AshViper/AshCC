package com.ashcc.compat.cbc;

import org.jetbrains.annotations.NotNull;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;

/** A block of a railgun: one breech at the back and rails in front. Assembles into {@link MountedRailgunContraption}. */
public abstract class RailgunBlock extends LineCannonBlock {
    protected RailgunBlock(Properties properties, double inset) {
        super(properties, inset);
    }

    @Override
    public @NotNull AbstractMountedCannonContraption getCannonContraption() {
        return new MountedRailgunContraption();
    }
}
