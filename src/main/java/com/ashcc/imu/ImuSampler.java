package com.ashcc.imu;

import com.ashcc.AshCC;
import com.ashcc.AshCCConfig;
import com.ashcc.compat.SableCompat;
import com.ashcc.radar.LuaUtil;
import com.ashcc.radar.RadarFrame;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a sequence of frames (one per tick) into the readings of the {@code imu} API that every computer has.
 * Velocity and acceleration are finite differences of the frame's world position; the angular
 * velocity comes from consecutive frame orientations (dq = conj(q_prev) q), so it always matches the change in attitude.
 * Main thread only, except {@link #latest()}.
 */
public final class ImuSampler {
    /** Positions further apart than this between two ticks are a teleport / assembly, not motion. */
    private static final double JUMP = 64;

    private volatile boolean restart = true;
    private @Nullable Vec3 lastPos;
    private @Nullable Vec3 lastVel;
    private @Nullable Quaterniond lastQ;
    private @Nullable UUID lastShip;
    private Vec3 velocity = Vec3.ZERO;
    private Vec3 acceleration = Vec3.ZERO;
    private Vec3 angular = Vec3.ZERO; // world, rad/s

    /** Latest reading; read from computer threads. */
    private volatile @Nullable Map<String, Object> snapshot;

    public @Nullable Map<String, Object> latest() {
        return snapshot;
    }

    /** Forget the history: the next sample has zero motion (after a pause, the old samples are stale). */
    public void restart() {
        restart = true;
        snapshot = null;
    }

    /**
     * One measurement. {@code shipOrientation} is reported as-is (identity off a ship); the angular velocity comes from
     * the frame's own axes, so it also works for frames that turn without a ship (a pocket computer's holder).
     */
    public Map<String, Object> sample(RadarFrame f, Quaterniond shipOrientation, long tick) {
        Quaterniond q = orientationOf(f);
        Vec3 pos = f.origin();
        UUID ship = f.subLevel();
        boolean continuous = !restart && lastPos != null && lastQ != null && pos.distanceTo(lastPos) < JUMP
            && (ship == null ? lastShip == null : ship.equals(lastShip));
        if (continuous) {
            Vec3 v = pos.subtract(lastPos).scale(20);
            acceleration = lastVel == null ? Vec3.ZERO : v.subtract(lastVel).scale(20);
            velocity = v;
            lastVel = v;
            angular = angularVelocity(lastQ, q);
        } else {
            velocity = Vec3.ZERO;
            acceleration = Vec3.ZERO;
            angular = Vec3.ZERO;
            lastVel = null;
        }
        restart = false;
        lastPos = pos;
        lastQ = q;
        lastShip = ship;
        Map<String, Object> s = build(f, shipOrientation, tick);
        snapshot = s;
        return s;
    }

    /**
     * Rotation taking the frame's local axes to world space. (right, up, forward) is left-handed, so the rotation
     * maps x to right, y to up and z to -forward.
     */
    static Quaterniond orientationOf(RadarFrame f) {
        Vec3 r = f.right(), u = f.up(), b = f.forward().scale(-1);
        Matrix3d m = new Matrix3d(r.x, r.y, r.z, u.x, u.y, u.z, b.x, b.y, b.z); // columns
        return new Quaterniond().setFromNormalized(m);
    }

    /** Orientation of the ship containing {@code pos}, identity off a ship (or without Sable). */
    static Quaterniond shipOrientation(Level level, BlockPos pos) {
        Quaterniond q = AshCC.sableLoaded ? SableCompat.orientation(level, pos) : null;
        return q == null ? new Quaterniond() : q;
    }

    /** Local angular velocity plus the named rates, as getAngularVelocity returns them. */
    static Map<String, Object> rates(Map<String, Object> s) {
        @SuppressWarnings("unchecked")
        Map<String, Object> t = new HashMap<>((Map<String, Object>) s.get("localAngularVelocity"));
        t.put("yawRate", s.get("yawRate"));
        t.put("pitchRate", s.get("pitchRate"));
        t.put("rollRate", s.get("rollRate"));
        return t;
    }

    /** World-space angular velocity (rad/s) that turns q0 into q1 in one tick. */
    public static Vec3 angularVelocity(Quaterniond q0, Quaterniond q1) {
        Quaterniond dq = new Quaterniond(q0).conjugate().mul(q1); // rotation in body space
        if (dq.w < 0) dq.set(-dq.x, -dq.y, -dq.z, -dq.w);         // shortest way round
        double s = Math.sqrt(dq.x * dq.x + dq.y * dq.y + dq.z * dq.z);
        if (s < 1e-12) return Vec3.ZERO;
        double angle = 2 * Math.atan2(s, dq.w) * 20;               // rad/s
        Vector3d w = new Vector3d(dq.x / s * angle, dq.y / s * angle, dq.z / s * angle);
        q1.transform(w);                                            // body space -> world
        return new Vec3(w.x, w.y, w.z);
    }

    private Map<String, Object> build(RadarFrame f, Quaterniond q, long tick) {
        double g = AshCCConfig.IMU_GRAVITY.get();
        Vec3 r = f.right(), u = f.up(), fw = f.forward();
        Map<String, Object> t = new HashMap<>();
        t.put("position", LuaUtil.vec(f.origin()));
        t.put("velocity", LuaUtil.vec(velocity));
        t.put("localVelocity", LuaUtil.vec(f.dirToLocal(velocity)));
        t.put("speed", velocity.length());
        t.put("acceleration", LuaUtil.vec(acceleration));
        t.put("localAcceleration", LuaUtil.vec(f.dirToLocal(acceleration)));
        // An accelerometer measures acceleration minus gravity: 1 g upwards when standing still
        Vec3 specific = acceleration.add(0, g, 0);
        t.put("gForce", LuaUtil.vec(f.dirToLocal(specific).scale(g > 0 ? 1 / g : 0)));

        // Attitude of the local axes (x = right, y = up, z = forward)
        t.put("yaw", Math.toDegrees(Math.atan2(fw.x, -fw.z)));                       // heading: north 0, east +90
        t.put("pitch", Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, fw.y))))); // nose up +
        t.put("roll", Math.toDegrees(Math.atan2(-r.y, u.y)));                         // right side down +
        Map<String, Object> o = new HashMap<>();
        o.put("w", q.w);
        o.put("x", q.x);
        o.put("y", q.y);
        o.put("z", q.z);
        t.put("orientation", o);
        Map<String, Object> axes = new HashMap<>();
        axes.put("right", LuaUtil.vec(r));
        axes.put("up", LuaUtil.vec(u));
        axes.put("forward", LuaUtil.vec(fw));
        t.put("axes", axes);

        Vec3 wDeg = angular.scale(180 / Math.PI);
        t.put("angularVelocity", LuaUtil.vec(wDeg));
        t.put("localAngularVelocity", LuaUtil.vec(f.dirToLocal(wDeg)));
        t.put("yawRate", -wDeg.dot(u));       // heading change, clockwise from above +
        t.put("pitchRate", wDeg.dot(r));      // nose up +
        t.put("rollRate", wDeg.dot(fw));      // right side down +

        if (f.subLevel() != null) t.put("subLevel", f.subLevel().toString());
        t.put("onShip", f.subLevel() != null);
        t.put("tick", tick);
        return t;
    }
}
