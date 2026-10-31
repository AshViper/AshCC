package com.ashcc.hose;

import com.ashcc.AshCC;
import com.ashcc.AshCCConfig;
import com.ashcc.compat.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * All hoses of one dimension. Every tick each hose pumps up to {@code hose.rate} mB from its start to its end.
 * A hose whose end block is gone, or that is stretched too far (ships flying apart), comes off and drops as an item.
 */
public class HoseData extends SavedData {
    private static final String NAME = "ashcc_hoses";
    private final List<Hose> hoses = new ArrayList<>();
    private int nextId = 1;

    public static HoseData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(HoseData::new, HoseData::load), NAME);
    }

    public List<Hose> hoses() {
        return Collections.unmodifiableList(hoses);
    }

    public Hose add(ServerLevel level, BlockPos from, Direction fromFace, BlockPos to, Direction toFace) {
        return add(level, from, fromFace, to, toFace, false);
    }

    public Hose add(ServerLevel level, BlockPos from, Direction fromFace, BlockPos to, Direction toFace, boolean pipe) {
        Hose h = new Hose(nextId++, from, fromFace, to, toFace, pipe);
        hoses.add(h);
        changed(level);
        return h;
    }

    /** Removes every hose and pipe with an end at {@code pos}; returns them. */
    public List<Hose> removeAt(ServerLevel level, BlockPos pos) {
        List<Hose> out = new ArrayList<>();
        for (Iterator<Hose> it = hoses.iterator(); it.hasNext(); ) {
            Hose h = it.next();
            if (h.touches(pos)) {
                it.remove();
                out.add(h);
            }
        }
        if (!out.isEmpty()) changed(level);
        return out;
    }

    /** The item a line comes back as. */
    public static ItemStack item(Hose h) {
        return (h.pipe() ? AshCC.PIPE.get() : AshCC.HOSE.get()).getDefaultInstance();
    }

    private void changed(ServerLevel level) {
        setDirty();
        HoseNetwork.sendTo(level);
    }

    /** World position of a hose end (moved out of the plot grid when it is on a ship). */
    public static Vec3 worldEnd(Level level, BlockPos pos, Direction face) {
        Vec3 p = Hose.attach(pos, face);
        return AshCC.sableLoaded ? SableCompat.pointToWorld(level, pos, p) : p;
    }

    public static @Nullable IFluidHandler handler(Level level, BlockPos pos, Direction face) {
        return level.getCapability(Capabilities.FluidHandler.BLOCK, pos, face);
    }

    void tick(ServerLevel level) {
        if (hoses.isEmpty()) return;
        int hoseRate = AshCCConfig.HOSE_RATE.get();
        int pipeRate = AshCCConfig.PIPE_RATE.get();
        double maxStretch = AshCCConfig.HOSE_MAX_LENGTH.get() * 1.5;
        boolean removed = false;
        for (Iterator<Hose> it = hoses.iterator(); it.hasNext(); ) {
            Hose h = it.next();
            if (!level.isLoaded(h.from()) || !level.isLoaded(h.to())) continue;
            boolean fromGone = level.getBlockState(h.from()).isAir();
            boolean toGone = level.getBlockState(h.to()).isAir();
            // A pipe is rigid and both its ends are in one frame, so only a hose can be pulled apart
            boolean torn = !h.pipe()
                && worldEnd(level, h.from(), h.fromFace()).distanceTo(worldEnd(level, h.to(), h.toFace())) > maxStretch;
            if (fromGone || toGone || torn) {
                it.remove();
                removed = true;
                // Drop the hose where an end is still attached (or where the start was)
                BlockPos at = fromGone ? h.to() : h.from();
                Vec3 p = worldEnd(level, at, fromGone ? h.toFace() : h.fromFace());
                Containers.dropItemStack(level, p.x, p.y, p.z, item(h));
                continue;
            }
            IFluidHandler src = handler(level, h.from(), h.fromFace());
            IFluidHandler dst = handler(level, h.to(), h.toFace());
            if (src == null || dst == null) continue;
            transfer(src, dst, h.pipe() ? pipeRate : hoseRate);
        }
        if (removed) changed(level);
    }

    /** Moves up to {@code max} mB; returns how much moved. */
    static int transfer(IFluidHandler src, IFluidHandler dst, int max) {
        FluidStack offer = src.drain(max, IFluidHandler.FluidAction.SIMULATE);
        if (offer.isEmpty() && max < FluidType.BUCKET_VOLUME) {
            // Bucket-only sources (cauldrons, some tanks) give nothing less than a bucket: move one when it all fits
            FluidStack bucket = src.drain(FluidType.BUCKET_VOLUME, IFluidHandler.FluidAction.SIMULATE);
            if (bucket.isEmpty() || dst.fill(bucket, IFluidHandler.FluidAction.SIMULATE) < bucket.getAmount()) return 0;
            offer = bucket;
        }
        if (offer.isEmpty()) return 0;
        int accepted = dst.fill(offer, IFluidHandler.FluidAction.SIMULATE);
        if (accepted <= 0) return 0;
        FluidStack moved = src.drain(offer.copyWithAmount(accepted), IFluidHandler.FluidAction.EXECUTE);
        if (moved.isEmpty()) return 0;
        return dst.fill(moved, IFluidHandler.FluidAction.EXECUTE);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Hose h : hoses) list.add(h.save());
        tag.put("Hoses", list);
        tag.putInt("NextId", nextId);
        return tag;
    }

    private static HoseData load(CompoundTag tag, HolderLookup.Provider registries) {
        HoseData d = new HoseData();
        for (Tag t : tag.getList("Hoses", Tag.TAG_COMPOUND)) d.hoses.add(Hose.load((CompoundTag) t));
        d.nextId = Math.max(1, tag.getInt("NextId"));
        return d;
    }
}
