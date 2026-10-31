package com.ashcc.compat.createarms;

import com.defpwoq.createarms.block.VlsHatchBlockEntity;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/** Lua API of a createarms VLS hatch (peripheral type "vls"). The hatch needs its six VLS cells underneath. */
public class VlsPeripheral extends ArmsLauncherPeripheral<VlsHatchBlockEntity> {
    VlsPeripheral(VlsHatchBlockEntity vls) {
        super(vls);
    }

    @Override
    public String getType() {
        return "vls";
    }

    /** { name, displayName, count } or nil when the cell is empty. */
    @LuaFunction(mainThread = true)
    public final @Nullable Map<String, Object> getMissile() {
        return MissileRackPeripheral.stack(block.getStoredItem());
    }

    /** Whether the six VLS cells are under the hatch. */
    @LuaFunction(mainThread = true)
    public final boolean isValidStructure() {
        return block.isValidVlsStructure();
    }

    /** Loaded and on a complete cell. */
    @LuaFunction(mainThread = true)
    public final boolean isReady() {
        return !block.getStoredItem().isEmpty() && block.isValidVlsStructure();
    }

    /** Opens the hatch (launch() also opens it; opening a second or so early looks like the CIC launch). */
    @LuaFunction(mainThread = true)
    public final void openHatch() {
        block.triggerHatchOpen();
    }

    @LuaFunction(mainThread = true)
    public final void closeHatch() {
        block.triggerHatchClose();
    }

    /**
     * launch([target]) -> id, or nil and "empty" / "no cell" / "blocked" / "unsupported".
     * target: nil (straight up), {x,y,z}, x,y,z, or a ship / entity id. Anti-ship missiles (LRASM, Tomahawk) want a point.
     */
    @LuaFunction(mainThread = true)
    public final MethodResult launch(IArguments args) throws LuaException {
        ArmsTarget target = ArmsTarget.read(level(), args, 0);
        ArmsMissiles.Launch launch = ArmsMissiles.launchVls(block, target, ownerKey());
        return launch.id() == null ? MethodResult.of(null, launch.error()) : MethodResult.of(launch.id().toString());
    }
}
