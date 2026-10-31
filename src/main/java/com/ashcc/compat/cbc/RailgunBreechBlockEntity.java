package com.ashcc.compat.cbc;

import com.ashcc.AshCCConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock;

/**
 * Railgun breech: holds up to ammoCapacity rounds of one single-block CBC big cannon projectile (AP shot, HE shell, ...)
 * and the Forge Energy for the shots. Assembled, it lives inside {@link MountedRailgunContraption} and is reached through the mount.
 */
public class RailgunBreechBlockEntity extends BlockEntity {
    private ItemStack ammo = ItemStack.EMPTY;
    private int energy;
    private int cooldown;

    private final IEnergyStorage energyStorage = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int max, boolean simulate) {
            int r = Math.max(0, Math.min(getMaxEnergyStored() - energy, Math.min(max, AshCCConfig.RAILGUN_MAX_RECEIVE.get())));
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
            return AshCCConfig.RAILGUN_ENERGY_CAPACITY.get();
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

    private final IItemHandler items = new IItemHandler() {
        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public @NotNull ItemStack getStackInSlot(int slot) {
            return ammo;
        }

        @Override
        public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
            if (stack.isEmpty() || !isItemValid(slot, stack)) return stack;
            if (!ammo.isEmpty() && !ItemStack.isSameItemSameComponents(ammo, stack)) return stack;
            int n = Math.min(stack.getCount(), AshCCConfig.RAILGUN_AMMO_CAPACITY.get() - ammo.getCount());
            if (n <= 0) return stack;
            if (!simulate) {
                if (ammo.isEmpty()) ammo = stack.copyWithCount(n);
                else ammo.grow(n);
                setChanged();
            }
            return stack.copyWithCount(stack.getCount() - n);
        }

        @Override
        public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (ammo.isEmpty() || amount <= 0) return ItemStack.EMPTY;
            int n = Math.min(amount, ammo.getCount());
            ItemStack out = ammo.copyWithCount(n);
            if (!simulate) {
                ammo.shrink(n);
                if (ammo.isEmpty()) ammo = ItemStack.EMPTY;
                setChanged();
            }
            return out;
        }

        @Override
        public int getSlotLimit(int slot) {
            return AshCCConfig.RAILGUN_AMMO_CAPACITY.get();
        }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            return isAmmo(stack);
        }
    };

    public RailgunBreechBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** Single-block CBC big cannon projectiles (shot, AP shot, HE / AP / shrapnel shells, ...). */
    public static boolean isAmmo(ItemStack stack) {
        return stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ProjectileBlock<?> pb && pb.getExpectedSize() == 1;
    }

    public IEnergyStorage energy() {
        return energyStorage;
    }

    public IItemHandler items() {
        return items;
    }

    public ItemStack ammo() {
        return ammo;
    }

    public int cooldown() {
        return cooldown;
    }

    void tickCooldown() {
        if (cooldown > 0) cooldown--;
    }

    /** Takes one round and the shot's energy; call only after checking {@link #ammo()} and the energy. */
    ItemStack takeShot(int energyCost) {
        ItemStack round = ammo.split(1);
        if (ammo.isEmpty()) ammo = ItemStack.EMPTY;
        energy -= energyCost;
        cooldown = (int) Math.round(AshCCConfig.RAILGUN_COOLDOWN_SECONDS.get() * 20.0);
        setChanged();
        return round;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!ammo.isEmpty()) tag.put("Ammo", ammo.save(registries));
        tag.putInt("Energy", energy);
        tag.putInt("Cooldown", cooldown);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        ammo = tag.contains("Ammo") ? ItemStack.parseOptional(registries, tag.getCompound("Ammo")) : ItemStack.EMPTY;
        energy = Math.max(0, tag.getInt("Energy"));
        cooldown = Math.max(0, tag.getInt("Cooldown"));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
