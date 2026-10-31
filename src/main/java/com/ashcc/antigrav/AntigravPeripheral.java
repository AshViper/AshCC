package com.ashcc.antigrav;

import com.ashcc.AshCCConfig;
import com.ashcc.antigrav.AntigravBlockEntity.AngularMode;
import com.ashcc.antigrav.AntigravBlockEntity.Command;
import com.ashcc.antigrav.AntigravBlockEntity.LinearMode;
import com.ashcc.radar.LuaUtil;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Lua API of the anti-gravity core (peripheral type "antigravity").
 * Linear control: free / world velocity / ship-relative velocity / move to a point.
 * Angular control: free / turn rates / hold a heading, pitch and roll. Defaults: weightless, holding still.
 * Event: antigravity_arrived(name, "position" | "rotation", x|yaw, y|pitch, z|roll) when a target is reached.
 */
public class AntigravPeripheral implements IPeripheral {
    private final AntigravBlockEntity core;

    AntigravPeripheral(AntigravBlockEntity core) {
        this.core = core;
    }

    @Override
    public String getType() {
        return "antigravity";
    }

    @Override
    public Object getTarget() {
        return core;
    }

    @Override
    public void attach(IComputerAccess computer) {
        core.computers.add(computer);
    }

    @Override
    public void detach(IComputerAccess computer) {
        core.computers.remove(computer);
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof AntigravPeripheral o && o.core == core;
    }

    private static double finite(double v, String name) throws LuaException {
        if (!Double.isFinite(v)) throw new LuaException(name + " must be a finite number");
        return v;
    }

    private static Vec3 vec(IArguments args, int first, String a, String b, String c) throws LuaException {
        return new Vec3(finite(args.getDouble(first), a), finite(args.getDouble(first + 1), b), finite(args.getDouble(first + 2), c));
    }

    private ShipState ship() throws LuaException {
        ShipState s = core.shipState();
        if (s == null) throw new LuaException("not on a Sable sub-level");
        return s;
    }

    // ---- Power and gravity ----

    /** Turns the core on or off. A redstone signal also turns it off. Off = the ship falls normally. */
    @LuaFunction(mainThread = true)
    public final void setEnabled(boolean enabled) {
        core.setEnabled(enabled);
    }

    @LuaFunction
    public final boolean isEnabled() {
        return core.enabled();
    }

    /** true while this core is driving its ship (on a ship, enabled, no redstone, and the first core on that ship). */
    @LuaFunction
    public final boolean isActive() {
        return core.driving();
    }

    /** Gravity the ship feels: 0 = none (default), 1 = normal, 0.5 = half, negative = falls upwards. */
    @LuaFunction(mainThread = true)
    public final void setGravity(double scale) throws LuaException {
        core.setGravity(finite(scale, "gravity"));
    }

    @LuaFunction
    public final double getGravity() {
        return core.gravity();
    }

    // ---- Linear ----

    /** setVelocity(x, y, z): world velocity in blocks/s (limited by maxSpeed). */
    @LuaFunction(mainThread = true)
    public final void setVelocity(IArguments args) throws LuaException {
        core.setLinear(LinearMode.VELOCITY, vec(args, 0, "x", "y", "z"));
    }

    /** setLocalVelocity(right, up, forward): velocity in the ship's own frame, turns with the ship. */
    @LuaFunction(mainThread = true)
    public final void setLocalVelocity(IArguments args) throws LuaException {
        core.setLinear(LinearMode.LOCAL_VELOCITY, vec(args, 0, "right", "up", "forward"));
    }

    /** moveTo(x, y, z): flies this block's centre to the world point and holds it there. */
    @LuaFunction(mainThread = true)
    public final void moveTo(IArguments args) throws LuaException {
        core.setLinear(LinearMode.POSITION, vec(args, 0, "x", "y", "z"));
    }

    /** move(dx, dy, dz): moveTo relative to where this block is now. */
    @LuaFunction(mainThread = true)
    public final void move(IArguments args) throws LuaException {
        Vec3 d = vec(args, 0, "dx", "dy", "dz");
        core.setLinear(LinearMode.POSITION, ship().position().add(d));
    }

    /** Stops controlling position: the ship drifts (with the set gravity). */
    @LuaFunction(mainThread = true)
    public final void releaseLinear() {
        core.setLinear(LinearMode.FREE, Vec3.ZERO);
    }

    // ---- Angular ----

    /** setAngularVelocity(yawRate, pitchRate, rollRate) in deg/s: yaw clockwise from above +, nose up +, right side down +. */
    @LuaFunction(mainThread = true)
    public final void setAngularVelocity(IArguments args) throws LuaException {
        core.setAngular(AngularMode.RATE, vec(args, 0, "yawRate", "pitchRate", "rollRate"));
    }

    /** setRotation(yaw, [pitch], [roll]) in degrees: heading north 0 / east 90, nose up +, right side down +. Turns and holds. */
    @LuaFunction(mainThread = true)
    public final void setRotation(double yaw, Optional<Double> pitch, Optional<Double> roll) throws LuaException {
        core.setAngular(AngularMode.ROTATION, new Vec3(finite(yaw, "yaw"), finite(pitch.orElse(0.0), "pitch"), finite(roll.orElse(0.0), "roll")));
    }

    /** Turns to level flight on the current heading. */
    @LuaFunction(mainThread = true)
    public final void level() throws LuaException {
        core.setAngular(AngularMode.ROTATION, new Vec3(ship().yaw(), 0, 0));
    }

    /** Stops controlling rotation: the ship spins freely. */
    @LuaFunction(mainThread = true)
    public final void releaseAngular() {
        core.setAngular(AngularMode.FREE, Vec3.ZERO);
    }

    // ---- Both ----

    /** Brakes to a standstill: velocity 0 and turn rates 0 (the default). */
    @LuaFunction(mainThread = true)
    public final void stop() {
        core.setLinear(LinearMode.VELOCITY, Vec3.ZERO);
        core.setAngular(AngularMode.RATE, Vec3.ZERO);
    }

    /** Holds the current position and rotation (moveTo here + setRotation as now). */
    @LuaFunction(mainThread = true)
    public final void hold() throws LuaException {
        ShipState s = ship();
        core.setLinear(LinearMode.POSITION, s.position());
        core.setAngular(AngularMode.ROTATION, new Vec3(s.yaw(), s.pitch(), s.roll()));
    }

    /** Lets go of both position and rotation. Gravity stays as set. */
    @LuaFunction(mainThread = true)
    public final void release() {
        core.setLinear(LinearMode.FREE, Vec3.ZERO);
        core.setAngular(AngularMode.FREE, Vec3.ZERO);
    }

    /** true when the moveTo / setRotation targets in use are reached (modes without a target count as reached). */
    @LuaFunction
    public final boolean isAtTarget() {
        boolean p = core.linearMode() != LinearMode.POSITION || core.positionReached();
        boolean r = core.angularMode() != AngularMode.ROTATION || core.rotationReached();
        return p && r;
    }

    // ---- Limits ----

    /** {speed, acceleration, angularSpeed, angularAcceleration}: blocks/s, blocks/s^2, deg/s, deg/s^2, plus the server caps. */
    @LuaFunction
    public final Map<String, Object> getLimits() {
        Command c = core.command();
        Map<String, Object> t = new HashMap<>();
        t.put("speed", c.maxSpeed());
        t.put("acceleration", c.maxAcceleration());
        t.put("angularSpeed", c.maxAngularSpeed());
        t.put("angularAcceleration", c.maxAngularAcceleration());
        Map<String, Object> caps = new HashMap<>();
        caps.put("speed", AshCCConfig.ANTIGRAV_SPEED_CAP.get());
        caps.put("acceleration", AshCCConfig.ANTIGRAV_ACCELERATION_CAP.get());
        caps.put("angularSpeed", AshCCConfig.ANTIGRAV_ANGULAR_SPEED_CAP.get());
        caps.put("angularAcceleration", AshCCConfig.ANTIGRAV_ANGULAR_ACCELERATION_CAP.get());
        t.put("max", caps);
        return t;
    }

    /** setLimits{speed=, acceleration=, angularSpeed=, angularAcceleration=}; missing keys keep their value. Clamped to the caps. */
    @LuaFunction(mainThread = true)
    public final void setLimits(Map<?, ?> limits) throws LuaException {
        Command c = core.command();
        core.setLimits(
            limit(limits, "speed", c.maxSpeed()),
            limit(limits, "acceleration", c.maxAcceleration()),
            limit(limits, "angularSpeed", c.maxAngularSpeed()),
            limit(limits, "angularAcceleration", c.maxAngularAcceleration()));
    }

    private static double limit(Map<?, ?> t, String key, double current) throws LuaException {
        Object v = t.get(key);
        if (v == null) return current;
        if (!(v instanceof Number n)) throw new LuaException(key + " must be a number");
        return finite(n.doubleValue(), key);
    }

    // ---- State ----

    /** World position of this block's centre. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getPosition() throws LuaException {
        return LuaUtil.vec(ship().position());
    }

    /** World velocity (blocks/s), with local = {right, up, forward}. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getVelocity() throws LuaException {
        ShipState s = ship();
        Map<String, Object> t = LuaUtil.vec(s.velocity());
        t.put("local", rightUpForward(s.localVelocity()));
        return t;
    }

    /** {yaw, pitch, roll} in degrees. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getRotation() throws LuaException {
        ShipState s = ship();
        return angles(s.yaw(), s.pitch(), s.roll());
    }

    /** {yawRate, pitchRate, rollRate} in deg/s. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getAngularVelocity() throws LuaException {
        ShipState s = ship();
        Map<String, Object> t = new HashMap<>();
        t.put("yawRate", s.yawRate());
        t.put("pitchRate", s.pitchRate());
        t.put("rollRate", s.rollRate());
        return t;
    }

    @LuaFunction(mainThread = true)
    public final double getMass() throws LuaException {
        return ship().mass();
    }

    /** Id of the ship this core is on, or nil. */
    @LuaFunction(mainThread = true)
    public final @Nullable String getSubLevel() {
        ShipState s = core.shipState();
        return s == null ? null : s.id().toString();
    }

    /** Everything at once: settings, targets and (on a ship) the ship's motion. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getState() {
        Command c = core.command();
        Map<String, Object> t = new HashMap<>();
        t.put("enabled", core.enabled());
        t.put("redstone", core.powered());
        t.put("active", core.driving());
        t.put("gravity", c.gravity());
        t.put("atTarget", isAtTarget());

        Map<String, Object> lin = new HashMap<>();
        lin.put("mode", c.linearMode().name().toLowerCase());
        switch (c.linearMode()) {
            case VELOCITY -> lin.put("velocity", LuaUtil.vec(c.linear()));
            case LOCAL_VELOCITY -> lin.put("velocity", rightUpForward(c.linear()));
            case POSITION -> lin.put("target", LuaUtil.vec(c.linear()));
            case FREE -> {
            }
        }
        t.put("linear", lin);

        Map<String, Object> ang = new HashMap<>();
        ang.put("mode", c.angularMode().name().toLowerCase());
        Vec3 a = c.angular();
        switch (c.angularMode()) {
            case RATE -> {
                Map<String, Object> r = new HashMap<>();
                r.put("yawRate", a.x);
                r.put("pitchRate", a.y);
                r.put("rollRate", a.z);
                ang.put("rates", r);
            }
            case ROTATION -> ang.put("target", angles(a.x, a.y, a.z));
            case FREE -> {
            }
        }
        t.put("angular", ang);

        ShipState s = core.shipState();
        if (s != null) {
            t.put("subLevel", s.id().toString());
            t.put("position", LuaUtil.vec(s.position()));
            t.put("centerOfMass", LuaUtil.vec(s.centerOfMass()));
            Map<String, Object> v = LuaUtil.vec(s.velocity());
            v.put("local", rightUpForward(s.localVelocity()));
            t.put("velocity", v);
            t.put("rotation", angles(s.yaw(), s.pitch(), s.roll()));
            Map<String, Object> w = new HashMap<>();
            w.put("yawRate", s.yawRate());
            w.put("pitchRate", s.pitchRate());
            w.put("rollRate", s.rollRate());
            t.put("angularVelocity", w);
            t.put("mass", s.mass());
        }
        return t;
    }

    private static Map<String, Object> rightUpForward(Vec3 v) {
        Map<String, Object> t = new HashMap<>();
        t.put("right", v.x);
        t.put("up", v.y);
        t.put("forward", v.z);
        return t;
    }

    private static Map<String, Object> angles(double yaw, double pitch, double roll) {
        Map<String, Object> t = new HashMap<>();
        t.put("yaw", yaw);
        t.put("pitch", pitch);
        t.put("roll", roll);
        return t;
    }
}
