package com.ashcc.decoy;

import com.ashcc.AshCCConfig;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IPeripheral;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Lua API of the flare / chaff launcher (peripheral type "decoy_launcher").
 * Flares lure seekers, chaff shows up on radars as a ship; neither is seen by the other sensor.
 */
public class DecoyLauncherPeripheral implements IPeripheral {
    private final DecoyLauncherBlockEntity launcher;

    DecoyLauncherPeripheral(DecoyLauncherBlockEntity launcher) {
        this.launcher = launcher;
    }

    @Override
    public String getType() {
        return "decoy_launcher";
    }

    @Override
    public Object getTarget() {
        return launcher;
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof DecoyLauncherPeripheral o && o.launcher == launcher;
    }

    private static byte kind(String name) throws LuaException {
        return switch (name) {
            case "flare" -> DecoyEntity.FLARE;
            case "chaff" -> DecoyEntity.CHAFF;
            default -> throw new LuaException("kind must be \"flare\" or \"chaff\"");
        };
    }

    /** fire(kind, [count]) -> number launched, or 0 plus "cooldown" / "empty". */
    @LuaFunction(mainThread = true)
    public final MethodResult fire(IArguments args) throws LuaException {
        byte kind = kind(args.getString(0));
        int count = args.optInt(1, 1);
        if (count < 1) throw new LuaException("count must be at least 1");
        int n = launcher.fire(kind, count);
        if (n < 0) return MethodResult.of(0, "cooldown");
        if (n == 0) return MethodResult.of(0, "empty");
        return MethodResult.of(n);
    }

    /** { flare = n, chaff = n } */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getAmmo() {
        Map<String, Object> t = new HashMap<>();
        t.put("flare", launcher.ammo(DecoyEntity.FLARE));
        t.put("chaff", launcher.ammo(DecoyEntity.CHAFF));
        return t;
    }

    @LuaFunction(mainThread = true)
    public final int getCapacity() {
        return launcher.capacity();
    }

    @LuaFunction(mainThread = true)
    public final int getMaxSalvo() {
        return AshCCConfig.DECOY_MAX_SALVO.get();
    }

    /** Seconds until it can fire again. */
    @LuaFunction(mainThread = true)
    public final double getCooldown() {
        return launcher.cooldown();
    }

    @LuaFunction(mainThread = true)
    public final boolean isReady() {
        return launcher.cooldown() <= 0;
    }

    @LuaFunction(mainThread = true)
    public final String getFacing() {
        return launcher.facing().getSerializedName();
    }
}
