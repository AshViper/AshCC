package com.ashcc.compat.createarms;

import com.defpwoq.createarms.block.CountermeasureDispenserBlockEntity;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;

/**
 * Lua API of the createarms countermeasure dispenser (peripheral type "countermeasure_dispenser").
 * Modes: "flare" (lures IR missiles), "chaff" (breaks radar locks), "both". It can fire every half second.
 */
public class CountermeasurePeripheral extends ArmsPeripheral<CountermeasureDispenserBlockEntity> {
    private static final String[] MODES = {"flare", "chaff", "both"};

    CountermeasurePeripheral(CountermeasureDispenserBlockEntity dispenser) {
        super(dispenser);
    }

    @Override
    public String getType() {
        return "countermeasure_dispenser";
    }

    private static int mode(String name) throws LuaException {
        for (int i = 0; i < MODES.length; i++) {
            if (MODES[i].equals(name)) return i;
        }
        throw new LuaException("mode must be \"flare\", \"chaff\" or \"both\"");
    }

    /** deploy([mode]) -> true if something was released (false while reloading or when out of that kind). */
    @LuaFunction(mainThread = true)
    public final boolean deploy(IArguments args) throws LuaException {
        level();
        int before = block.getCountermeasureCount();
        if (args.get(0) == null) block.deploy();
        else block.deployOverride(mode(args.getString(0)));
        return block.getCountermeasureCount() < before;
    }

    @LuaFunction(mainThread = true)
    public final String getMode() {
        int type = block.getDispenserType();
        return type >= 0 && type < MODES.length ? MODES[type] : "both";
    }

    @LuaFunction(mainThread = true)
    public final void setMode(String mode) throws LuaException {
        block.setDispenserType(mode(mode));
    }

    /** Flares and chaff loaded. */
    @LuaFunction(mainThread = true)
    public final int getCount() {
        return block.getCountermeasureCount();
    }

    @LuaFunction(mainThread = true)
    public final int getCapacity() {
        return block.getMaxCapacity();
    }
}
