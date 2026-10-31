package com.ashcc.compat.createarms;

import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/**
 * Where a missile should go, as createarms' missiles take it: a point, plus the id of an entity or of a Sable ship
 * (sub-level) to follow. A ship id goes in both {@code entity} and {@code subLevel}, like the radar-guided rack launch.
 */
record ArmsTarget(@Nullable Vec3 pos, @Nullable UUID entity, @Nullable UUID subLevel) {
    static final ArmsTarget NONE = new ArmsTarget(null, null, null);

    boolean isNone() {
        return pos == null && entity == null;
    }

    /** The id the missile follows (ship or entity), or null for a fixed point. */
    @Nullable UUID followed() {
        return subLevel != null ? subLevel : entity;
    }

    /**
     * Reads a target at argument {@code index}: nil, {@code {x=, y=, z=}}, three numbers x, y, z, or the id string of a
     * Sable ship (radar contact id) or an entity.
     */
    static ArmsTarget read(ServerLevel level, IArguments args, int index) throws LuaException {
        Object arg = args.get(index);
        if (arg == null) return NONE;
        if (arg instanceof Number) return new ArmsTarget(new Vec3(args.getDouble(index), args.getDouble(index + 1), args.getDouble(index + 2)), null, null);
        if (arg instanceof Map<?, ?> table) return new ArmsTarget(new Vec3(number(table, "x"), number(table, "y"), number(table, "z")), null, null);
        if (arg instanceof String id) return ofId(level, id);
        throw new LuaException("bad argument #" + (index + 1) + " (expected target: nil, {x,y,z}, x,y,z or id string)");
    }

    static ArmsTarget ofId(ServerLevel level, String text) throws LuaException {
        UUID id;
        try {
            id = UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new LuaException("not an id: " + text);
        }
        SubLevel sub = SubLevelContainer.getContainer(level).getSubLevel(id);
        if (sub != null && !sub.isRemoved()) {
            var p = sub.logicalPose().position();
            return new ArmsTarget(new Vec3(p.x(), p.y(), p.z()), id, id);
        }
        Entity entity = level.getEntity(id);
        if (entity != null && entity.isAlive()) return new ArmsTarget(entity.position().add(0, entity.getBbHeight() / 2, 0), id, null);
        throw new LuaException("no ship or entity with id " + text);
    }

    private static double number(Map<?, ?> table, String key) throws LuaException {
        if (table.get(key) instanceof Number n) return n.doubleValue();
        throw new LuaException("target table needs numbers x, y and z");
    }
}
