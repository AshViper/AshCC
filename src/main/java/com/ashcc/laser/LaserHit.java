package com.ashcc.laser;

import com.ashcc.radar.LuaUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Result of one beam trace. {@code from}/{@code end} are world coordinates; {@code blockPos} is the hit block in
 * block coordinates (the plot grid when it is on a Sable sub-level), {@code subLevel} the ship it belongs to.
 */
public record LaserHit(Vec3 from, Vec3 direction, Vec3 end, @Nullable BlockPos blockPos, @Nullable BlockState block,
                       @Nullable UUID subLevel, @Nullable Entity entity) {
    public static final String NONE = "none";
    public static final String BLOCK = "block";
    public static final String ENTITY = "entity";

    public String type() {
        return entity != null ? ENTITY : blockPos != null ? BLOCK : NONE;
    }

    public double distance() {
        return from.distanceTo(end);
    }

    /** {type, x, y, z, distance, [block], [subLevel], [entity = {id, type, name}]}; x/y/z is where the beam ends. */
    public Map<String, Object> toLua() {
        Map<String, Object> t = LuaUtil.vec(end);
        t.put("type", type());
        t.put("distance", distance());
        if (entity != null) {
            Map<String, Object> e = new HashMap<>();
            e.put("id", entity.getUUID().toString());
            e.put("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            e.put("name", entity.getName().getString());
            t.put("entity", e);
        } else if (block != null) {
            t.put("block", BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString());
        }
        if (subLevel != null) t.put("subLevel", subLevel.toString());
        return t;
    }
}
