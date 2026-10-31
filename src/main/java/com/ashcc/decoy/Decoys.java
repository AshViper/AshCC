package com.ashcc.decoy;

import com.ashcc.AshCC;
import com.ashcc.radar.Contact;
import com.ashcc.radar.LuaUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decoys as sensors see them: chaff is a ship-shaped radar echo, a flare is a ship-shaped heat source for seekers.
 * Both are described exactly like a sub-level contact, so a program cannot tell them apart by the table alone.
 */
public final class Decoys {
    private Decoys() {
    }

    /** Chaff (radar) or flare (seeker) echoes whose box is within {@code range} of {@code origin}. */
    public static List<Contact> echoes(Level level, Vec3 origin, double range, byte kind) {
        List<Contact> out = new ArrayList<>();
        AABB box = new AABB(origin, origin).inflate(range + 16);
        for (DecoyEntity e : level.getEntities(AshCC.DECOY.get(), box, e -> e.isAlive() && e.kind() == kind)) {
            Contact c = contact(e, origin);
            if (c.distance() <= range) out.add(c);
        }
        return out;
    }

    public static Contact contact(DecoyEntity e, Vec3 origin) {
        Vec3 pos = e.position();
        Vec3 vel = e.getDeltaMovement().scale(20);
        Map<String, Object> echo = e.echo();
        Map<String, Object> extra = new HashMap<>();
        Vec3 size = new Vec3(3, 3, 3);
        if (echo.get("size") instanceof Map<?, ?> s && s.get("x") instanceof Double x && s.get("y") instanceof Double y && s.get("z") instanceof Double z) {
            size = new Vec3(x, y, z);
        }
        if (echo.get("mass") instanceof Double m) extra.put("mass", m);
        extra.put("orientation", echo.get("orientation") instanceof Map<?, ?> o ? new HashMap<>(o) : identity());
        extra.put("posePosition", LuaUtil.vec(pos));
        Vec3 half = size.scale(0.5);
        Vec3 min = pos.subtract(half);
        Vec3 max = pos.add(half);
        Map<String, Object> bounds = new HashMap<>();
        bounds.put("minX", min.x);
        bounds.put("minY", min.y);
        bounds.put("minZ", min.z);
        bounds.put("maxX", max.x);
        bounds.put("maxY", max.y);
        bounds.put("maxZ", max.z);
        extra.put("bounds", bounds);
        extra.put("size", LuaUtil.vec(size));
        extra.put("angularVelocity", LuaUtil.vec(0, 0, 0));
        extra.put("centerDistance", pos.distanceTo(origin));
        double dx = Math.max(Math.max(min.x - origin.x, 0), origin.x - max.x);
        double dy = Math.max(Math.max(min.y - origin.y, 0), origin.y - max.y);
        double dz = Math.max(Math.max(min.z - origin.z, 0), origin.z - max.z);
        return new Contact(Contact.SUB_LEVEL, e.getUUID(), pos, vel, Math.sqrt(dx * dx + dy * dy + dz * dz), extra);
    }

    private static Map<String, Object> identity() {
        Map<String, Object> q = new HashMap<>();
        q.put("w", 1.0);
        q.put("x", 0.0);
        q.put("y", 0.0);
        q.put("z", 0.0);
        return q;
    }
}
