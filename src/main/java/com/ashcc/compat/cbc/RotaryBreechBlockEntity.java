package com.ashcc.compat.cbc;

import com.ashcc.AshCCConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannons.autocannon.breech.AutocannonBreechBlockEntity;

/**
 * Rotary autocannon breech. While the fire rate (redstone or Lua) is above 0 the barrels spin up over spinUpSeconds;
 * from minSpin on it fires spin x fireRate/15 x maxRpm rounds per minute, up to two rounds per tick.
 * CBC's contraption fires once per tick through {@link #canFire()}; a second round in the same tick is fired from here.
 */
public class RotaryBreechBlockEntity extends AutocannonBreechBlockEntity {
    private static final int MAX_PER_TICK = 2;

    private float spin;
    /** Rounds owed by the fire rate so far; a shot is allowed while it is at least 1. */
    private double owed;

    public RotaryBreechBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** Belt-fed: holds more loose rounds than CBC's breech (5). */
    @Override
    public int getQueueLimit() {
        return 32;
    }

    public float spin() {
        return spin;
    }

    private double roundsPerTick() {
        if (spin < AshCCConfig.ROTARY_MIN_SPIN.get() || getFireRate() <= 0) return 0;
        return spin * (getFireRate() / 15.0) * AshCCConfig.ROTARY_MAX_RPM.get() / 1200.0;
    }

    @Override
    public int getActualFireRate() {
        return (int) Math.round(roundsPerTick() * 1200.0);
    }

    @Override
    public boolean canFire() {
        return getFireRate() > 0 && owed >= 1.0;
    }

    @Override
    public void handleFiring() {
        super.handleFiring();
        owed -= 1.0;
    }

    @Override
    public void tickFromContraption(Level level, PitchOrientedContraptionEntity poce, BlockPos localPos) {
        super.tickFromContraption(level, poce, localPos);
        if (level.isClientSide) return;
        boolean trigger = getFireRate() > 0;
        float up = (float) (1.0 / (AshCCConfig.ROTARY_SPIN_UP_SECONDS.get() * 20.0));
        float down = (float) (1.0 / (AshCCConfig.ROTARY_SPIN_DOWN_SECONDS.get() * 20.0));
        spin = Mth.clamp(spin + (trigger ? up : -down), 0.0f, 1.0f);
        double rate = roundsPerTick();
        owed = rate > 0 ? Math.min(owed + rate, MAX_PER_TICK + 1) : 0;
        // The contraption had its one shot this tick and takes the next one at the start of the next tick, so only
        // what goes beyond that round is fired here
        if (level instanceof ServerLevel slevel && poce.getContraption() instanceof MountedAutocannonContraption cannon) {
            for (int i = 1; i < MAX_PER_TICK && owed >= 2.0; i++) {
                double before = owed;
                cannon.fireShot(slevel, poce);
                if (owed == before) break;
            }
        }
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putFloat("Spin", spin);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        spin = tag.getFloat("Spin");
    }
}
