package com.ashcc.compat.createarms;

import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A createarms launcher: steers the munitions it fired (getMissiles / getMissileInfo / retarget / detonate).
 * Only munitions launched from this block through CC (or, for the CIC terminal, from its linked cells) can be steered.
 */
abstract class ArmsLauncherPeripheral<T extends BlockEntity> extends ArmsPeripheral<T> {
    ArmsLauncherPeripheral(T block) {
        super(block);
    }

    protected String ownerKey() {
        return ArmsMissiles.ownerKey(block);
    }

    /** Whether this peripheral may steer the munition. */
    protected boolean controls(ArmsMissiles.Tracked missile) {
        return ownerKey().equals(ArmsMissiles.owner(missile));
    }

    private ArmsMissiles.Tracked controlled(String id) throws LuaException {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new LuaException("not an id: " + id);
        }
        ArmsMissiles.Tracked missile = ArmsMissiles.find(level(), uuid);
        return missile != null && controls(missile) ? missile : null;
    }

    /** Munitions in flight that this launcher can steer. */
    @LuaFunction(mainThread = true)
    public final List<Map<String, Object>> getMissiles() throws LuaException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ArmsMissiles.Tracked missile : ArmsMissiles.all(level())) {
            if (controls(missile)) out.add(ArmsMissiles.info(missile));
        }
        return out;
    }

    /** getMissileInfo(id) -> table, or nil once it is gone (or is not ours). */
    @LuaFunction(mainThread = true)
    public final @Nullable Map<String, Object> getMissileInfo(String id) throws LuaException {
        ArmsMissiles.Tracked missile = controlled(id);
        return missile == null ? null : ArmsMissiles.info(missile);
    }

    /** retarget(id, target) -> true if the munition took the new target. */
    @LuaFunction(mainThread = true)
    public final boolean retarget(IArguments args) throws LuaException {
        ArmsMissiles.Tracked missile = controlled(args.getString(0));
        return missile != null && ArmsMissiles.retarget(missile, ArmsTarget.read(level(), args, 1));
    }

    /** detonate(id) -> true if it blew up (torpedoes cannot be detonated). */
    @LuaFunction(mainThread = true)
    public final boolean detonate(String id) throws LuaException {
        ArmsMissiles.Tracked missile = controlled(id);
        return missile != null && ArmsMissiles.detonate(missile);
    }
}
