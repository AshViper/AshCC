package com.ashcc.decoy;

import com.ashcc.AshCC;
import com.ashcc.AshCCConfig;
import com.ashcc.compat.SableCompat;
import com.ashcc.radar.Contact;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DecoyLauncherBlockEntity extends BlockEntity {
    private @Nullable DecoyLauncherPeripheral peripheral;
    private final int[] ammo = new int[2];
    /** Game time when the launcher may fire again. */
    private long readyAt;

    private final IItemHandler handler = new IItemHandler() {
        @Override
        public int getSlots() {
            return 2;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return ammo[slot] == 0 ? ItemStack.EMPTY : new ItemStack(item((byte) slot), Math.min(ammo[slot], 64));
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return kindOf(stack) == slot ? insert(stack, simulate) : stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY; // ammo only goes in
        }

        @Override
        public int getSlotLimit(int slot) {
            return capacity();
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return kindOf(stack) == slot;
        }
    };

    public DecoyLauncherBlockEntity(BlockPos pos, BlockState state) {
        super(AshCC.DECOY_LAUNCHER_BE.get(), pos, state);
    }

    public DecoyLauncherPeripheral peripheral() {
        if (peripheral == null) peripheral = new DecoyLauncherPeripheral(this);
        return peripheral;
    }

    public IItemHandler itemHandler() {
        return handler;
    }

    static Item item(byte kind) {
        return kind == DecoyEntity.FLARE ? AshCC.FLARE.get() : AshCC.CHAFF.get();
    }

    /** FLARE / CHAFF for ammo items, -1 otherwise. */
    public static int kindOf(ItemStack stack) {
        if (stack.is(AshCC.FLARE.get())) return DecoyEntity.FLARE;
        if (stack.is(AshCC.CHAFF.get())) return DecoyEntity.CHAFF;
        return -1;
    }

    public int capacity() {
        return AshCCConfig.DECOY_CAPACITY.get();
    }

    public int ammo(byte kind) {
        return ammo[kind];
    }

    /** Loads as much of the stack as fits; returns what is left. */
    public ItemStack insert(ItemStack stack, boolean simulate) {
        int kind = kindOf(stack);
        if (kind < 0 || stack.isEmpty()) return stack;
        int n = Math.min(stack.getCount(), capacity() - ammo[kind]);
        if (n <= 0) return stack;
        if (!simulate) {
            ammo[kind] += n;
            setChanged();
        }
        return stack.copyWithCount(stack.getCount() - n);
    }

    public List<ItemStack> ammoStacks() {
        List<ItemStack> out = new ArrayList<>();
        for (byte kind = 0; kind < 2; kind++) {
            for (int left = ammo[kind]; left > 0; left -= 64) out.add(new ItemStack(item(kind), Math.min(64, left)));
        }
        return out;
    }

    /** Seconds until the launcher can fire again (0 = ready). */
    public double cooldown() {
        return level == null ? 0 : Math.max(0, readyAt - level.getGameTime()) / 20.0;
    }

    public Direction facing() {
        return getBlockState().getValue(DecoyLauncherBlock.FACING);
    }

    /** Fires up to {@code count} decoys at once. Returns how many left, or -1 while cooling down. */
    public int fire(byte kind, int count) {
        if (!(level instanceof ServerLevel server) || isRemoved()) return 0;
        if (level.getGameTime() < readyAt) return -1;
        boolean needAmmo = AshCCConfig.DECOY_REQUIRE_AMMO.get();
        int n = Math.min(count, AshCCConfig.DECOY_MAX_SALVO.get());
        if (needAmmo) n = Math.min(n, ammo[kind]);
        if (n <= 0) return 0;

        Vec3 center = Vec3.atCenterOf(worldPosition);
        Vec3 dir = Vec3.atLowerCornerOf(facing().getNormal());
        Vec3 origin = center;
        Vec3 shipVelocity = Vec3.ZERO;
        Map<String, Object> echo = new HashMap<>();
        if (AshCC.sableLoaded) {
            origin = SableCompat.worldCenter(level, worldPosition);
            dir = SableCompat.dirToWorld(level, worldPosition, dir).normalize();
            Contact self = SableCompat.self(level, worldPosition, origin);
            if (self != null) {
                shipVelocity = self.velocity();
                // The echo mimics the launching ship
                for (String k : new String[]{"size", "mass", "orientation"}) {
                    if (self.extra().containsKey(k)) echo.put(k, self.extra().get(k));
                }
            }
        }

        boolean flare = kind == DecoyEntity.FLARE;
        double speed = (flare ? AshCCConfig.DECOY_FLARE_SPEED.get() : AshCCConfig.DECOY_CHAFF_SPEED.get()) / 20.0;
        int life = (int) Math.round((flare ? AshCCConfig.DECOY_FLARE_SECONDS.get() : AshCCConfig.DECOY_CHAFF_SECONDS.get()) * 20);
        double spread = Math.toRadians(AshCCConfig.DECOY_SPREAD.get());
        RandomSource rnd = server.random;
        Vec3 a = (Math.abs(dir.y) < 0.9 ? dir.cross(new Vec3(0, 1, 0)) : dir.cross(new Vec3(1, 0, 0))).normalize();
        Vec3 b = dir.cross(a).normalize();
        Vec3 spawn = origin.add(dir.scale(0.8));
        for (int i = 0; i < n; i++) {
            // Uniform over the cone's cap
            double cosT = 1 - rnd.nextDouble() * (1 - Math.cos(spread));
            double sinT = Math.sqrt(Math.max(0, 1 - cosT * cosT));
            double phi = rnd.nextDouble() * Math.PI * 2;
            Vec3 d = dir.scale(cosT).add(a.scale(sinT * Math.cos(phi))).add(b.scale(sinT * Math.sin(phi)));
            DecoyEntity e = new DecoyEntity(AshCC.DECOY.get(), level);
            e.setup(kind, life, echo);
            e.setPos(spawn);
            e.setDeltaMovement(d.scale(speed * (0.85 + 0.3 * rnd.nextDouble())).add(shipVelocity.scale(1 / 20.0)));
            server.addFreshEntity(e);
        }
        if (needAmmo) ammo[kind] -= n;
        readyAt = level.getGameTime() + Math.max(1, Math.round(AshCCConfig.DECOY_COOLDOWN_SECONDS.get() * 20));
        setChanged();
        server.playSound(null, spawn.x, spawn.y, spawn.z, flare ? SoundEvents.FIREWORK_ROCKET_LAUNCH : SoundEvents.DISPENSER_LAUNCH,
            SoundSource.BLOCKS, 1.0f, flare ? 1.2f : 0.8f);
        return n;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Flares", ammo[DecoyEntity.FLARE]);
        tag.putInt("Chaff", ammo[DecoyEntity.CHAFF]);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        ammo[DecoyEntity.FLARE] = Math.max(0, tag.getInt("Flares"));
        ammo[DecoyEntity.CHAFF] = Math.max(0, tag.getInt("Chaff"));
    }
}
