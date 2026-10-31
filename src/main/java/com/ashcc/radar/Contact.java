package com.ashcc.radar;

import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One detected object. Velocity is in blocks per second.
 * {@code extra} holds kind-specific fields that are copied into the Lua table as-is.
 */
public record Contact(String kind, UUID id, Vec3 position, Vec3 velocity, double distance, Map<String, Object> extra) {
    public static final String ENTITY = "entity";
    public static final String SUB_LEVEL = "sublevel";

    public Map<String, Object> toLua(RadarFrame frame) {
        Map<String, Object> t = new HashMap<>(extra);
        t.put("kind", kind);
        t.put("id", id.toString());
        t.put("x", position.x);
        t.put("y", position.y);
        t.put("z", position.z);
        t.put("vx", velocity.x);
        t.put("vy", velocity.y);
        t.put("vz", velocity.z);
        t.put("distance", distance);
        Vec3 local = frame.toLocal(position);
        t.put("local", LuaUtil.vec(local));
        t.put("localVelocity", LuaUtil.vec(frame.dirToLocal(velocity)));
        t.put("azimuth", RadarFrame.azimuth(local));
        t.put("elevation", RadarFrame.elevation(local));
        return t;
    }
}
