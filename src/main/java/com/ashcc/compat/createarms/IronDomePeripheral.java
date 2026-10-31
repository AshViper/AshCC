package com.ashcc.compat.createarms;

import com.defpwoq.createarms.block.IronDomeRadarBlockEntity;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;

import java.util.HashMap;
import java.util.Map;

/** Lua API of the createarms Iron Dome radar (peripheral type "iron_dome"): the settings of its screen. */
public class IronDomePeripheral extends ArmsPeripheral<IronDomeRadarBlockEntity> {
    IronDomePeripheral(IronDomeRadarBlockEntity dome) {
        super(dome);
    }

    @Override
    public String getType() {
        return "iron_dome";
    }

    /** { autoEngage, scanRadius, targetMissiles, targetPlayers, targetShips, iff, ammo } */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getSettings() {
        Map<String, Object> t = new HashMap<>();
        t.put("autoEngage", block.isAutoEngage());
        t.put("scanRadius", block.getScanRadius());
        t.put("targetMissiles", block.isTargetMissiles());
        t.put("targetPlayers", block.isTargetPlayers());
        t.put("targetShips", block.isTargetShips());
        t.put("iff", block.getIffCode());
        t.put("ammo", block.getAmmoCount());
        return t;
    }

    @LuaFunction(mainThread = true)
    public final int getAmmo() {
        return block.getAmmoCount();
    }

    @LuaFunction(mainThread = true)
    public final void setAutoEngage(boolean on) {
        block.setAutoEngage(on);
    }

    @LuaFunction(mainThread = true)
    public final void setScanRadius(int radius) throws LuaException {
        if (radius < 1) throw new LuaException("radius must be positive");
        block.setScanRadius(radius);
    }

    /** setTargets(missiles, players, ships) */
    @LuaFunction(mainThread = true)
    public final void setTargets(boolean missiles, boolean players, boolean ships) {
        block.setTargetMissiles(missiles);
        block.setTargetPlayers(players);
        block.setTargetShips(ships);
    }

    @LuaFunction(mainThread = true)
    public final void setIffCode(int code) {
        block.setIffCode(code);
    }
}
