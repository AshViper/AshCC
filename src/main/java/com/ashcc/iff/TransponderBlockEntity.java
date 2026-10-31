package com.ashcc.iff;

import com.ashcc.AshCC;
import com.ashcc.compat.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class TransponderBlockEntity extends BlockEntity {
    public static final int MAX_LENGTH = 32;

    private @Nullable TransponderPeripheral peripheral;
    private String code = "";
    private String callsign = "";
    private boolean enabled = true;

    public TransponderBlockEntity(BlockPos pos, BlockState state) {
        super(AshCC.TRANSPONDER_BE.get(), pos, state);
    }

    public TransponderPeripheral peripheral() {
        if (peripheral == null) peripheral = new TransponderPeripheral(this);
        return peripheral;
    }

    public String code() {
        return code;
    }

    public String callsign() {
        return callsign;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setCode(String code) {
        this.code = clip(code);
        setChanged();
    }

    public void setCallsign(String callsign) {
        this.callsign = clip(callsign);
        setChanged();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        setChanged();
    }

    private static String clip(String s) {
        s = s.strip();
        return s.length() > MAX_LENGTH ? s.substring(0, MAX_LENGTH) : s;
    }

    public boolean silenced() {
        return level != null && level.hasNeighborSignal(worldPosition);
    }

    /** The sub-level this transponder identifies, or null off a ship (or without Sable). */
    public @Nullable UUID subLevel() {
        return AshCC.sableLoaded && level != null ? SableCompat.subLevelAt(level, worldPosition) : null;
    }

    /** Enabled, has a code, no redstone signal and mounted on a ship. */
    public boolean transmitting() {
        return enabled && !code.isEmpty() && !silenced() && subLevel() != null;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) Transponders.add(this);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        Transponders.remove(this);
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        Transponders.remove(this);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("Code", code);
        tag.putString("Callsign", callsign);
        tag.putBoolean("Enabled", enabled);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        code = clip(tag.getString("Code"));
        callsign = clip(tag.getString("Callsign"));
        enabled = !tag.contains("Enabled") || tag.getBoolean("Enabled");
    }
}
