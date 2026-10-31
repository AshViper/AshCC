package com.ashcc.compat;

import com.ashcc.radar.Contact;
import com.ashcc.radar.LuaUtil;
import com.ashcc.radar.RadarFrame;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything that touches Sable classes. Only call when {@code AshCC.sableLoaded} is true.
 * Sub-level blocks live in a far-away "plot grid"; their logical pose maps plot coordinates to the world.
 */
public final class SableCompat {
    private SableCompat() {
    }

    public static @Nullable RadarFrame frameOnSubLevel(Level level, BlockPos pos, Direction facing, Direction upDir) {
        SubLevel sub = Sable.HELPER.getContaining(level, pos);
        if (sub == null) return null;
        Pose3dc pose = sub.logicalPose();
        Vec3 origin = pose.transformPosition(Vec3.atCenterOf(pos));
        Vec3 forward = pose.transformNormal(Vec3.atLowerCornerOf(facing.getNormal())).normalize();
        Vec3 up = pose.transformNormal(Vec3.atLowerCornerOf(upDir.getNormal())).normalize();
        return new RadarFrame(origin, forward.cross(up), up, forward, sub.getUniqueId());
    }

    public static @Nullable UUID subLevelAt(Level level, BlockPos pos) {
        SubLevel sub = Sable.HELPER.getContaining(level, pos);
        return sub == null ? null : sub.getUniqueId();
    }

    /** World position of a block's centre (projected out of the plot grid when it is on a ship). */
    /** Sable's mass of a block (its physics_block_properties), as if it stood at {@code pos}. */
    public static double blockMass(Level level, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        return dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper.getMass(level, pos, state);
    }

    public static Vec3 worldCenter(Level level, BlockPos pos) {
        return Sable.HELPER.projectOutOfSubLevel(level, Vec3.atCenterOf(pos));
    }

    /** Rotates a direction in block coordinates into world space (identity off a ship). */
    public static Vec3 dirToWorld(Level level, BlockPos pos, Vec3 dir) {
        SubLevel sub = Sable.HELPER.getContaining(level, pos);
        return sub == null ? dir : sub.logicalPose().transformNormal(dir);
    }

    /** Orientation of the ship containing {@code pos} (block space to world), or null off a ship. */
    public static @Nullable org.joml.Quaterniond orientation(Level level, BlockPos pos) {
        SubLevel sub = Sable.HELPER.getContaining(level, pos);
        return sub == null ? null : new org.joml.Quaterniond(sub.logicalPose().orientation());
    }

    /** A point in block coordinates (the plot grid on a ship) to world space. */
    public static Vec3 pointToWorld(Level level, BlockPos pos, Vec3 point) {
        SubLevel sub = Sable.HELPER.getContaining(level, pos);
        return sub == null ? point : sub.logicalPose().transformPosition(point);
    }

    /** World velocity (blocks/s) of the ship containing {@code pos}; zero off a ship or on the client. */
    public static Vec3 velocityAt(Level level, BlockPos pos) {
        if (!(Sable.HELPER.getContaining(level, pos) instanceof ServerSubLevel sub)) return Vec3.ZERO;
        Vector3dc v = sub.latestLinearVelocity;
        return new Vec3(v.x(), v.y(), v.z());
    }

    /** Projects a point out of the plot grid (ray hits on ships come back in plot coordinates). */
    public static Vec3 projectOut(Level level, Vec3 point) {
        return Sable.HELPER.projectOutOfSubLevel(level, point);
    }

    /**
     * Applies an impulse (mass x blocks/s) at a world point to the sub-level containing {@code pos}.
     * Both are given in world space and turned into the ship's frame, like Sable's own explosion push.
     * Returns false when {@code pos} is not on a ship.
     */
    public static boolean applyImpulse(Level level, BlockPos pos, Vec3 worldPoint, Vec3 worldImpulse) {
        if (!(Sable.HELPER.getContaining(level, pos) instanceof ServerSubLevel sub) || sub.isRemoved()) return false;
        RigidBodyHandle handle = RigidBodyHandle.of(sub);
        if (handle == null || !handle.isValid()) return false;
        Pose3dc pose = sub.logicalPose();
        handle.applyImpulseAtPoint(pose.transformPositionInverse(worldPoint), pose.transformNormalInverse(worldImpulse));
        return true;
    }

    /**
     * An entity's look direction to world space. Sable keeps the rotation of an entity that rides something on a ship
     * (a seat) relative to that ship, and the client turns the camera with the ship; standing players stay in world space.
     */
    public static Vec3 lookToWorld(Entity entity, Vec3 dir) {
        Entity vehicle = entity.getVehicle();
        SubLevel sub = vehicle == null ? null : Sable.HELPER.getContaining(vehicle);
        if (sub == null) return dir;
        Vector3d v = sub.logicalPose().orientation().transform(new Vector3d(dir.x, dir.y, dir.z));
        return new Vec3(v.x, v.y, v.z);
    }

    /** The sub-level the entity is standing on or riding, if any. */
    public static @Nullable UUID entitySubLevel(Entity entity) {
        SubLevel sub = Sable.HELPER.getTrackingOrVehicleSubLevel(entity);
        return sub == null ? null : sub.getUniqueId();
    }

    public static List<Contact> scan(Level level, Vec3 origin, double range, @Nullable UUID exclude) {
        List<Contact> out = new ArrayList<>();
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return out;
        for (SubLevel sub : container.getAllSubLevels()) {
            if (sub.isRemoved() || sub.getUniqueId().equals(exclude)) continue;
            double dist = distanceToBounds(sub.boundingBox(), origin);
            if (dist <= range) out.add(contact(sub, origin, dist));
        }
        return out;
    }

    public static @Nullable Contact find(Level level, Vec3 origin, double range, UUID id) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return null;
        SubLevel sub = container.getSubLevel(id);
        if (sub == null || sub.isRemoved()) return null;
        double dist = distanceToBounds(sub.boundingBox(), origin);
        return dist <= range ? contact(sub, origin, dist) : null;
    }

    /** The sub-level the radar itself is mounted on, described like any other contact. */
    public static @Nullable Contact self(Level level, BlockPos pos, Vec3 origin) {
        SubLevel sub = Sable.HELPER.getContaining(level, pos);
        return sub == null ? null : contact(sub, origin, 0);
    }

    private static Contact contact(SubLevel sub, Vec3 origin, double dist) {
        Pose3dc pose = sub.logicalPose();
        Map<String, Object> extra = new HashMap<>();
        if (sub.getName() != null) extra.put("name", sub.getName());

        Quaterniondc q = pose.orientation();
        Map<String, Object> orientation = new HashMap<>();
        orientation.put("w", q.w());
        orientation.put("x", q.x());
        orientation.put("y", q.y());
        orientation.put("z", q.z());
        extra.put("orientation", orientation);
        extra.put("posePosition", LuaUtil.vec(pose.position().x(), pose.position().y(), pose.position().z()));

        BoundingBox3dc box = sub.boundingBox();
        Map<String, Object> bounds = new HashMap<>();
        bounds.put("minX", box.minX());
        bounds.put("minY", box.minY());
        bounds.put("minZ", box.minZ());
        bounds.put("maxX", box.maxX());
        bounds.put("maxY", box.maxY());
        bounds.put("maxZ", box.maxZ());
        extra.put("bounds", bounds);
        extra.put("size", LuaUtil.vec(box.width(), box.height(), box.length()));

        // Center of mass in world space is the best "where is it" point for a rigid body
        Vec3 position = new Vec3(pose.position().x(), pose.position().y(), pose.position().z());
        Vec3 velocity = Vec3.ZERO;
        if (sub instanceof ServerSubLevel server) {
            MassData mass = server.getMassTracker();
            if (mass != null && !mass.isInvalid()) {
                extra.put("mass", mass.getMass());
                Vector3d com = pose.transformPosition(mass.getCenterOfMass(), new Vector3d());
                position = new Vec3(com.x, com.y, com.z);
            }
            Vector3dc v = server.latestLinearVelocity;
            velocity = new Vec3(v.x(), v.y(), v.z());
            Vector3dc w = server.latestAngularVelocity;
            extra.put("angularVelocity", LuaUtil.vec(w.x(), w.y(), w.z()));
        }
        extra.put("centerDistance", position.distanceTo(origin));
        return new Contact(Contact.SUB_LEVEL, sub.getUniqueId(), position, velocity, dist, extra);
    }

    private static double distanceToBounds(BoundingBox3dc box, Vec3 p) {
        double dx = Math.max(Math.max(box.minX() - p.x, 0), p.x - box.maxX());
        double dy = Math.max(Math.max(box.minY() - p.y, 0), p.y - box.maxY());
        double dz = Math.max(Math.max(box.minZ() - p.z, 0), p.z - box.maxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
