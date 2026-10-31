package com.ashcc.compat;

import com.ashcc.AshCC;
import com.ashcc.antigrav.AntigravBlockEntity;
import com.ashcc.antigrav.ShipAngles;
import com.ashcc.antigrav.ShipState;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3dc;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Anti-gravity core on a ship. Every physics step the driving core cancels gravity (mass x g at the centre of mass)
 * and adds the acceleration / angular acceleration its controller asks for. Forces are computed from mass and
 * inertia, so the ship moves the same however heavy it is; maxAcceleration / maxAngularAcceleration cap the control part.
 */
public class SableAntigravBlockEntity extends AntigravBlockEntity implements BlockEntitySubLevelActor {
    /** Velocity loop gain (1/s): acceleration = (target velocity - velocity) x KV. */
    private static final double KV = 6;
    /** Position loop gain (1/s): target speed = distance x KP. */
    private static final double KP = 1.5;
    private static final double KW = 8;
    private static final double KR = 2.5;
    /** Share of maxAcceleration planned for braking, leaving headroom for the velocity loop. */
    private static final double BRAKE_SHARE = 0.7;
    private static final double POSITION_TOLERANCE = 0.15;
    private static final double ROTATION_TOLERANCE = Math.toRadians(1.0);

    // Motion measured from the pose of consecutive physics steps (the body's own velocity reads ~6% low against the
    // distance actually travelled, which left the velocity loop that far off)
    private final Vector3d lastCom = new Vector3d();
    private final Quaterniond lastOrientation = new Quaterniond();
    private double lastStep;
    private long lastStepTime = Long.MIN_VALUE;
    private volatile Vector3d measuredOmega = new Vector3d();
    private volatile long measuredTime = Long.MIN_VALUE;

    public SableAntigravBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public static AntigravBlockEntity create(BlockPos pos, BlockState state) {
        return new SableAntigravBlockEntity(AshCC.ANTIGRAV_BE.get(), pos, state);
    }

    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep) {
        if (isRemoved()) {
            driving = false;
            return;
        }
        MassData mass = subLevel.getMassTracker();
        if (mass.isInvalid() || mass.getMass() <= 0) return;
        Pose3dc pose = subLevel.logicalPose();
        Quaterniondc q = pose.orientation();
        Vector3d v = new Vector3d();
        Vector3d w = new Vector3d();
        measure(subLevel, handle, pose, mass, timeStep, v, w);

        Command c = command();
        if (!c.enabled() || !isDriver(subLevel)) {
            driving = false;
            return;
        }
        driving = true;
        Vector3d g = new Vector3d(DimensionPhysicsData.getGravity(subLevel.getLevel()));

        // Linear (world frame, blocks/s^2): cancel the share of gravity the ship should not feel, then steer
        Vector3d accel = new Vector3d(g).mul(c.gravity() - 1);
        Vector3d control = linearControl(c, pose, v);
        if (control != null) {
            control.fma(-c.gravity(), g);  // also hold up against the gravity that is left
            accel.add(clampLength(control, c.maxAcceleration()));
        }
        Vector3d impulse = q.transformInverse(accel.mul(mass.getMass() * timeStep));
        QueuedForceGroup group = subLevel.getOrCreateQueuedForceGroup(ForceGroups.LEVITATION.get());
        group.applyAndRecordPointForce(new Vector3d(mass.getCenterOfMass()), impulse);

        // Angular (rad/s^2): torque = I alpha + w x I w in the ship's frame
        Vector3d alpha = angularControl(c, q, w);
        if (alpha != null) {
            clampLength(alpha, Math.toRadians(c.maxAngularAcceleration()));
            Vector3d alphaLocal = q.transformInverse(alpha);
            Vector3d wLocal = q.transformInverse(new Vector3d(w));
            Matrix3dc inertia = mass.getInertiaTensor();
            Vector3d torque = inertia.transform(alphaLocal, new Vector3d());
            torque.add(wLocal.cross(inertia.transform(wLocal, new Vector3d()), new Vector3d()));
            group.getForceTotal().applyAngularImpulse(torque.mul(timeStep));
        }
    }

    /** Velocity of the centre of mass and angular velocity (world), from the motion over the previous physics step. */
    private void measure(ServerSubLevel subLevel, RigidBodyHandle handle, Pose3dc pose, MassData mass, double timeStep, Vector3d v, Vector3d w) {
        Vector3d com = pose.transformPosition(new Vector3d(mass.getCenterOfMass()));
        long now = subLevel.getLevel().getGameTime();
        if (now - lastStepTime <= 1 && lastStep > 0) {
            com.sub(lastCom, v).div(lastStep);
            Quaterniond dq = new Quaterniond(pose.orientation()).mul(new Quaterniond(lastOrientation).conjugate());
            if (dq.w < 0) dq.set(-dq.x, -dq.y, -dq.z, -dq.w);
            double s = Math.sqrt(dq.x * dq.x + dq.y * dq.y + dq.z * dq.z);
            if (s > 1e-12) w.set(dq.x, dq.y, dq.z).mul(2 * Math.atan2(s, dq.w) / s / lastStep);
        } else {
            // First step after a pause: nothing to difference against yet
            handle.getLinearVelocity(v);
            handle.getAngularVelocity(w);
        }
        lastCom.set(com);
        lastOrientation.set(pose.orientation());
        lastStep = timeStep;
        lastStepTime = now;
        measuredOmega = new Vector3d(w);
        measuredTime = now;
    }

    /** Wanted acceleration from the velocity loop, or null when linear motion is free. */
    private @Nullable Vector3d linearControl(Command c, Pose3dc pose, Vector3d v) {
        Vector3d target;
        switch (c.linearMode()) {
            case VELOCITY -> target = clampLength(toJoml(c.linear()), c.maxSpeed());
            case LOCAL_VELOCITY -> target = clampLength(pose.orientation().transform(ShipAngles.localVector(c.linear())), c.maxSpeed());
            case POSITION -> {
                Vector3d at = pose.transformPosition(JOMLConversion.atCenterOf(getBlockPos()));
                Vector3d error = toJoml(c.linear()).sub(at);
                double d = error.length();
                positionReached = d < POSITION_TOLERANCE && v.length() < 0.2;
                double speed = Math.min(c.maxSpeed(), Math.min(KP * d, Math.sqrt(2 * BRAKE_SHARE * c.maxAcceleration() * d)));
                target = d < 1e-9 ? new Vector3d() : error.mul(speed / d);
            }
            default -> {
                return null;
            }
        }
        return target.sub(v).mul(KV);
    }

    /** Wanted angular acceleration (world, rad/s^2), or null when rotation is free. */
    private @Nullable Vector3d angularControl(Command c, Quaterniondc q, Vector3d w) {
        double maxRate = Math.toRadians(c.maxAngularSpeed());
        Vector3d target;
        switch (c.angularMode()) {
            case RATE -> target = clampLength(q.transform(ShipAngles.localOmega(c.angular())), maxRate);
            case ROTATION -> {
                Vec3 a = c.angular();
                Quaterniond error = ShipAngles.toOrientation(a.x, a.y, a.z).mul(new Quaterniond(q).conjugate());
                if (error.w < 0) error.set(-error.x, -error.y, -error.z, -error.w);
                double s = Math.sqrt(error.x * error.x + error.y * error.y + error.z * error.z);
                double angle = 2 * Math.atan2(s, error.w);
                rotationReached = angle < ROTATION_TOLERANCE && w.length() < ROTATION_TOLERANCE;
                double rate = Math.min(maxRate, Math.min(KR * angle, Math.sqrt(2 * BRAKE_SHARE * Math.toRadians(c.maxAngularAcceleration()) * angle)));
                target = s < 1e-12 ? new Vector3d() : new Vector3d(error.x, error.y, error.z).mul(rate / s);
            }
            default -> {
                return null;
            }
        }
        return target.sub(w).mul(KW);
    }

    /** Only the first enabled core Sable lists on a ship drives it, so stacking cores does not stack the force. */
    private boolean isDriver(ServerSubLevel subLevel) {
        for (BlockEntitySubLevelActor actor : subLevel.getPlot().getBlockEntityActors()) {
            if (actor instanceof SableAntigravBlockEntity core && !core.isRemoved() && core.command().enabled()) return core == this;
        }
        return false;
    }

    private @Nullable ServerSubLevel ship() {
        if (level == null) return null;
        SubLevel sub = Sable.HELPER.getContaining(level, getBlockPos());
        return sub instanceof ServerSubLevel s ? s : null;
    }

    @Override
    protected void sableTick() {
        ServerSubLevel ship = ship();
        if (ship == null) {
            driving = false;
            return;
        }
        // Sleeping bodies get no physics steps; keep the ship awake while it still has somewhere to go
        Command c = command();
        if (!c.enabled()) return;
        boolean moving = ship.latestLinearVelocity.lengthSquared() > 1e-6 || ship.latestAngularVelocity.lengthSquared() > 1e-8;
        boolean linearBusy = switch (c.linearMode()) {
            case POSITION -> !positionReached;
            case VELOCITY, LOCAL_VELOCITY -> c.linear().lengthSqr() > 0;
            case FREE -> false;
        };
        boolean angularBusy = switch (c.angularMode()) {
            case ROTATION -> !rotationReached;
            case RATE -> c.angular().lengthSqr() > 0;
            case FREE -> false;
        };
        if (moving || linearBusy || angularBusy) wakeUp(ship);
    }

    @Override
    protected void onCommandChanged() {
        ServerSubLevel ship = ship();
        if (ship != null) wakeUp(ship);
    }

    private void wakeUp(ServerSubLevel ship) {
        SubLevelPhysicsSystem physics = SubLevelPhysicsSystem.get(ship.getLevel());
        if (physics != null) physics.getPipeline().wakeUp(ship);
    }

    @Override
    public @Nullable ShipState shipState() {
        ServerSubLevel ship = ship();
        if (ship == null) return null;
        Pose3dc pose = ship.logicalPose();
        Quaterniondc q = pose.orientation();
        MassData mass = ship.getMassTracker();
        Vector3d w = new Vector3d();
        if (ship.getLevel().getGameTime() - measuredTime <= 1) {
            w.set(measuredOmega);
        } else {
            RigidBodyHandle handle = RigidBodyHandle.of(ship);
            if (handle != null && handle.isValid()) handle.getAngularVelocity(w);
        }
        Vector3d v = new Vector3d(ship.latestLinearVelocity);
        Vec3 angles = ShipAngles.fromOrientation(q);
        Vec3 rates = ShipAngles.rates(q, w);
        Vector3d local = q.transformInverse(new Vector3d(v));
        return new ShipState(ship.getUniqueId(),
            pose.transformPosition(Vec3.atCenterOf(getBlockPos())),
            toVec(pose.transformPosition(new Vector3d(mass.getCenterOfMass()))),
            toVec(v), new Vec3(local.x, local.y, -local.z),
            angles.x, angles.y, angles.z, rates.x, rates.y, rates.z,
            mass.getMass(), toVec(DimensionPhysicsData.getGravity(ship.getLevel())));
    }

    private static Vector3d clampLength(Vector3d v, double max) {
        double len = v.length();
        if (len > max) v.mul(max / len);
        return v;
    }

    private static Vector3d toJoml(Vec3 v) {
        return new Vector3d(v.x, v.y, v.z);
    }

    private static Vec3 toVec(Vector3dc v) {
        return new Vec3(v.x(), v.y(), v.z());
    }
}
