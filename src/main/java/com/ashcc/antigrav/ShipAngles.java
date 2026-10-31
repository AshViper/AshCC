package com.ashcc.antigrav;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

/**
 * Ship orientation as yaw / pitch / roll in degrees, with the IMU's signs. The ship's own frame is
 * right = +X, up = +Y, forward = -Z (north as assembled).
 */
public final class ShipAngles {
    private ShipAngles() {
    }

    /** Orientation that turns the ship's frame to the given heading (east +), pitch (nose up +) and roll (right side down +). */
    public static Quaterniond toOrientation(double yaw, double pitch, double roll) {
        return new Quaterniond().rotateY(Math.toRadians(-yaw)).rotateX(Math.toRadians(pitch)).rotateZ(Math.toRadians(-roll));
    }

    /** {yaw, pitch, roll} in degrees. */
    public static Vec3 fromOrientation(Quaterniondc q) {
        Vector3d fw = q.transform(new Vector3d(0, 0, -1));
        Vector3d r = q.transform(new Vector3d(1, 0, 0));
        Vector3d u = q.transform(new Vector3d(0, 1, 0));
        return new Vec3(
            Math.toDegrees(Math.atan2(fw.x, -fw.z)),
            Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, fw.y)))),
            Math.toDegrees(Math.atan2(-r.y, u.y)));
    }

    /** World angular velocity (rad/s) to {yawRate, pitchRate, rollRate} in deg/s. */
    public static Vec3 rates(Quaterniondc q, Vector3d worldOmega) {
        Vector3d local = q.transformInverse(new Vector3d(worldOmega));
        return new Vec3(-Math.toDegrees(local.y), Math.toDegrees(local.x), -Math.toDegrees(local.z));
    }

    /** {yawRate, pitchRate, rollRate} in deg/s to angular velocity in the ship's frame (rad/s). */
    public static Vector3d localOmega(Vec3 rates) {
        return new Vector3d(Math.toRadians(rates.y), Math.toRadians(-rates.x), Math.toRadians(-rates.z));
    }

    /** right / up / forward to the ship's block frame. */
    public static Vector3d localVector(Vec3 rightUpForward) {
        return new Vector3d(rightUpForward.x, rightUpForward.y, -rightUpForward.z);
    }
}
