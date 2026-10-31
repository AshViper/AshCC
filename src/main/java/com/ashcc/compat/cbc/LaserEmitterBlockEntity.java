package com.ashcc.compat.cbc;

import com.ashcc.AshCCConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Laser cannon emitter: the Forge Energy store, the heat and the trigger (redstone on the mount's fire side, or Lua).
 * Assembled, it lives inside {@link MountedLaserContraption}, which runs the beam.
 */
public class LaserEmitterBlockEntity extends BlockEntity {
    private int energy;
    private double heat;
    private boolean overheated;
    /** Fire-side redstone signal the mount last passed on. */
    int redstone;
    /** Trigger held from Lua. */
    boolean luaTrigger;

    private final IEnergyStorage energyStorage = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int max, boolean simulate) {
            int r = Math.max(0, Math.min(getMaxEnergyStored() - energy, Math.min(max, AshCCConfig.LASER_CANNON_MAX_RECEIVE.get())));
            if (!simulate && r > 0) {
                energy += r;
                setChanged();
            }
            return r;
        }

        @Override
        public int extractEnergy(int max, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return energy;
        }

        @Override
        public int getMaxEnergyStored() {
            return AshCCConfig.LASER_CANNON_ENERGY_CAPACITY.get();
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
    };

    public LaserEmitterBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public IEnergyStorage energy() {
        return energyStorage;
    }

    public double heat() {
        return heat;
    }

    public boolean overheated() {
        return overheated;
    }

    boolean triggered() {
        return luaTrigger || redstone > 0;
    }

    /**
     * One tick of the trigger, heat and energy. Returns whether the beam is on this tick
     * (trigger held, not overheated and enough energy, which is then used).
     */
    boolean tickFiring() {
        boolean on = triggered() && !overheated;
        int cost = AshCCConfig.LASER_CANNON_ENERGY_PER_TICK.get();
        if (on && energy < cost) on = false;
        double maxFire = AshCCConfig.LASER_CANNON_MAX_FIRE_SECONDS.get();
        if (on) {
            energy -= cost;
            if (maxFire > 0) heat += 1.0 / (maxFire * 20);
            if (heat >= 1) {
                heat = 1;
                overheated = true;
            }
        } else if (heat > 0) {
            heat = Math.max(0, heat - 1.0 / (AshCCConfig.LASER_CANNON_COOLDOWN_SECONDS.get() * 20));
            if (heat == 0) overheated = false;
        }
        return on;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Energy", energy);
        tag.putDouble("Heat", heat);
        tag.putBoolean("Overheated", overheated);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy = Math.max(0, tag.getInt("Energy"));
        heat = tag.getDouble("Heat");
        overheated = tag.getBoolean("Overheated");
    }
}
