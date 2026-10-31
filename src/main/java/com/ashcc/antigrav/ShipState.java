package com.ashcc.antigrav;

import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Motion of the ship a core sits on, as Lua sees it. position = the core block's centre in the world,
 * velocity in blocks/s (world), localVelocity = right/up/forward of the ship, angles and rates use the IMU's signs
 * (yaw north 0 / east +90, pitch nose up +, roll right side down +; rates in deg/s).
 */
public record ShipState(UUID id, Vec3 position, Vec3 centerOfMass, Vec3 velocity, Vec3 localVelocity,
                        double yaw, double pitch, double roll, double yawRate, double pitchRate, double rollRate,
                        double mass, Vec3 gravity) {
}
