package com.ashcc.compat.createarms;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/** Shared part of the createarms peripherals: the block they wrap. */
abstract class ArmsPeripheral<T extends BlockEntity> implements IPeripheral {
    protected final T block;

    ArmsPeripheral(T block) {
        this.block = block;
    }

    @Override
    public Object getTarget() {
        return block;
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other != null && other.getClass() == getClass() && ((ArmsPeripheral<?>) other).block == block;
    }

    protected ServerLevel level() throws LuaException {
        if (!(block.getLevel() instanceof ServerLevel level) || block.isRemoved()) throw new LuaException("Block is not loaded");
        return level;
    }
}
