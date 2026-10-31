package com.ashcc.radar;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Lead-angle solver for a constant-velocity target. */
public final class Intercept {
    private Intercept() {
    }

    /** @param aim offset from the shooter to the point to aim at */
    public record Solution(Vec3 aim, double time) {
    }

    /**
     * @param d       target position relative to the shooter
     * @param v       target velocity (blocks/s)
     * @param speed   projectile speed (blocks/s)
     * @param gravity downward acceleration of the projectile (blocks/s^2), 0 for none
     */
    public static @Nullable Solution solve(Vec3 d, Vec3 v, double speed, double gravity) {
        // |d + v t| = speed * t  ->  (v.v - s^2) t^2 + 2 (d.v) t + d.d = 0
        double a = v.dot(v) - speed * speed;
        double b = 2 * d.dot(v);
        double c = d.dot(d);
        double t;
        if (Math.abs(a) < 1e-9) {
            if (Math.abs(b) < 1e-9) return null;
            t = -c / b;
        } else {
            double disc = b * b - 4 * a * c;
            if (disc < 0) return null;
            double sq = Math.sqrt(disc);
            double t1 = (-b - sq) / (2 * a);
            double t2 = (-b + sq) / (2 * a);
            t = Math.min(t1, t2) > 0 ? Math.min(t1, t2) : Math.max(t1, t2);
        }
        if (!(t > 0)) return null;

        // Gravity: aim above the predicted point by the drop, then re-solve the flight time (fixed-point iteration)
        if (gravity > 0) {
            for (int i = 0; i < 20; i++) {
                Vec3 aim = d.add(v.scale(t)).add(0, 0.5 * gravity * t * t, 0);
                t = aim.length() / speed;
            }
        }
        Vec3 aim = d.add(v.scale(t)).add(0, 0.5 * gravity * t * t, 0);
        return new Solution(aim, t);
    }
}
