package com.ashcc.radar;

import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

public final class LuaUtil {
    private LuaUtil() {
    }

    public static Map<String, Object> vec(Vec3 v) {
        return vec(v.x, v.y, v.z);
    }

    public static Map<String, Object> vec(double x, double y, double z) {
        Map<String, Object> t = new HashMap<>();
        t.put("x", x);
        t.put("y", y);
        t.put("z", z);
        return t;
    }
}
