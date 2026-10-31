package com.ashcc.iff;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Lua API of the IFF transponder (peripheral type "transponder").
 * While transmitting, radars and seekers report the ship it is mounted on with {@code iff = {code, callsign}}.
 */
public class TransponderPeripheral implements IPeripheral {
    private final TransponderBlockEntity transponder;

    TransponderPeripheral(TransponderBlockEntity transponder) {
        this.transponder = transponder;
    }

    @Override
    public String getType() {
        return "transponder";
    }

    @Override
    public Object getTarget() {
        return transponder;
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof TransponderPeripheral o && o.transponder == transponder;
    }

    private static String check(String s) throws LuaException {
        if (s.strip().length() > TransponderBlockEntity.MAX_LENGTH) {
            throw new LuaException("at most " + TransponderBlockEntity.MAX_LENGTH + " characters");
        }
        return s;
    }

    @LuaFunction(mainThread = true)
    public final String getCode() {
        return transponder.code();
    }

    /** setCode(code): "" stops transmitting. */
    @LuaFunction(mainThread = true)
    public final String setCode(String code) throws LuaException {
        transponder.setCode(check(code));
        return transponder.code();
    }

    @LuaFunction(mainThread = true)
    public final String getCallsign() {
        return transponder.callsign();
    }

    @LuaFunction(mainThread = true)
    public final String setCallsign(String callsign) throws LuaException {
        transponder.setCallsign(check(callsign));
        return transponder.callsign();
    }

    @LuaFunction(mainThread = true)
    public final boolean isEnabled() {
        return transponder.enabled();
    }

    @LuaFunction(mainThread = true)
    public final boolean setEnabled(boolean enabled) {
        transponder.setEnabled(enabled);
        return transponder.enabled();
    }

    /** Whether radars see the code right now: enabled, code set, no redstone signal, mounted on a ship. */
    @LuaFunction(mainThread = true)
    public final boolean isTransmitting() {
        return transponder.transmitting();
    }

    @LuaFunction(mainThread = true)
    public final boolean isSilenced() {
        return transponder.silenced();
    }

    /** Id of the ship it identifies, or nil when not on a ship. */
    @LuaFunction(mainThread = true)
    public final @Nullable String getSubLevel() {
        UUID id = transponder.subLevel();
        return id == null ? null : id.toString();
    }
}
