package com.ashcc.compat.createarms;

import com.defpwoq.createarms.block.MunitionRackBlock;
import com.defpwoq.createarms.block.MunitionRackBlockEntity;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Lua API of a createarms munition rack (peripheral type "missile_rack"): every rack, pylon and launcher that holds
 * munitions for the MFD (munition rack, dual / AIM-120 / sidewinder / AGM-114 / GBU-39 racks, trapeze, Stinger launcher).
 */
public class MissileRackPeripheral extends ArmsLauncherPeripheral<MunitionRackBlockEntity> {
    MissileRackPeripheral(MunitionRackBlockEntity rack) {
        super(rack);
    }

    @Override
    public String getType() {
        return "missile_rack";
    }

    static @Nullable Map<String, Object> stack(ItemStack stack) {
        if (stack.isEmpty()) return null;
        Map<String, Object> t = new HashMap<>();
        t.put("name", ArmsMissiles.itemName(stack));
        t.put("displayName", stack.getHoverName().getString());
        t.put("count", stack.getCount());
        return t;
    }

    /** { name, displayName, count, capacity, firable } or nil when empty. firable is false for munitions CC cannot fire. */
    @LuaFunction(mainThread = true)
    public final @Nullable Map<String, Object> getMissile() {
        Map<String, Object> t = stack(block.getStoredItem());
        if (t != null) {
            t.put("capacity", ArmsMissiles.rackCapacity(block));
            t.put("firable", ArmsMissiles.canFireFromRack(block.getStoredItem()));
        }
        return t;
    }

    @LuaFunction(mainThread = true)
    public final int getCount() {
        return block.getStoredItem().getCount();
    }

    @LuaFunction(mainThread = true)
    public final int getCapacity() {
        return ArmsMissiles.rackCapacity(block);
    }

    /** Direction the rack launches towards, in block coordinates (the ship's frame when on a ship). */
    @LuaFunction(mainThread = true)
    public final String getFacing() {
        var state = block.getBlockState();
        return state.hasProperty(MunitionRackBlock.FACING) ? state.getValue(MunitionRackBlock.FACING).getSerializedName() : "north";
    }

    /**
     * fire([target]) -> id, or nil and "empty" / "unsupported" / "blocked".
     * target: nil (500 blocks ahead / own seeker), {x,y,z}, x,y,z, or a ship / entity id.
     */
    @LuaFunction(mainThread = true)
    public final MethodResult fire(IArguments args) throws LuaException {
        ArmsTarget target = ArmsTarget.read(level(), args, 0);
        ArmsMissiles.Launch launch = ArmsMissiles.fireRack(block, target, ownerKey());
        return launch.id() == null ? MethodResult.of(null, launch.error()) : MethodResult.of(launch.id().toString());
    }
}
