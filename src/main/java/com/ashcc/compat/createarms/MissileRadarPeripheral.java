package com.ashcc.compat.createarms;

import com.defpwoq.createarms.block.RadarBlockEntity;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lua API of a createarms radar (search radar and APG radar; peripheral type "missile_radar"). Its contacts' ids are
 * the targets missiles take, and the selected target is the one the MFD's radar-guided launch fires at.
 */
public class MissileRadarPeripheral extends ArmsPeripheral<RadarBlockEntity> {
    MissileRadarPeripheral(RadarBlockEntity radar) {
        super(radar);
    }

    @Override
    public String getType() {
        return "missile_radar";
    }

    static Map<String, Object> contact(RadarBlockEntity.RadarContact c) {
        Map<String, Object> t = new HashMap<>();
        t.put("id", c.uuid().toString());
        t.put("x", c.position().x);
        t.put("y", c.position().y);
        t.put("z", c.position().z);
        t.put("distance", c.distance());
        t.put("bearing", c.bearing());
        t.put("type", c.entityType());
        t.put("friendly", c.isFriendly());
        return t;
    }

    /** List of { id, x, y, z, distance, bearing (degrees, atan2(dx, dz)), type, friendly }. */
    static List<Map<String, Object>> contacts(RadarBlockEntity radar) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RadarBlockEntity.RadarContact c : List.copyOf(radar.getDetectedTargets())) out.add(contact(c));
        return out;
    }

    @LuaFunction(mainThread = true)
    public final List<Map<String, Object>> getContacts() throws LuaException {
        level();
        return contacts(block);
    }

    /** The selected (locked) contact, or nil. */
    @LuaFunction(mainThread = true)
    public final @Nullable Map<String, Object> getSelectedTarget() throws LuaException {
        level();
        RadarBlockEntity.RadarContact c = block.getSelectedRadarContact();
        return c == null ? null : contact(c);
    }

    /** selectTarget(id or nil) -> true if selected. Friendly contacts cannot be selected. */
    @LuaFunction(mainThread = true)
    public final boolean selectTarget(IArguments args) throws LuaException {
        level();
        Object arg = args.get(0);
        if (arg == null) {
            block.setSelectedTargetUUID(null);
            return true;
        }
        UUID id;
        try {
            id = UUID.fromString(args.getString(0));
        } catch (IllegalArgumentException e) {
            throw new LuaException("not an id: " + arg);
        }
        block.setSelectedTargetUUID(id);
        return id.equals(block.getSelectedTargetUUID());
    }

    /** Missiles and ships with the same non-zero code are friendly. */
    @LuaFunction(mainThread = true)
    public final int getIffCode() {
        return block.getIffCode();
    }

    @LuaFunction(mainThread = true)
    public final void setIffCode(int code) {
        block.setIffCode(code);
    }

    /** False while a redstone signal switches it off. */
    @LuaFunction(mainThread = true)
    public final boolean isRadiating() {
        return block.isRadiating();
    }

    @LuaFunction(mainThread = true)
    public final double getScanRange() {
        return block.getScanRange();
    }
}
