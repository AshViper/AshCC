package com.ashcc.imu;

import com.ashcc.AshCC;
import com.ashcc.AshCCConfig;
import com.ashcc.compat.SableCompat;
import com.ashcc.radar.RadarFrame;
import dan200.computercraft.api.component.ComputerComponents;
import dan200.computercraft.api.lua.IComputerSystem;
import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.ILuaContext;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.pocket.IPocketAccess;
import dan200.computercraft.api.turtle.ITurtleAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The global {@code imu} API on every computer, turtle and pocket computer: attitude, angular velocity, velocity and
 * acceleration of the computer itself, with no block or CC: Sable needed.
 * Forward is the computer's front, a turtle's facing, or the horizontal look of whoever holds a pocket computer.
 * It only measures while a program uses it (or a stream is on), so idle computers cost nothing.
 */
public class ComputerImuApi implements ILuaAPI {
    /** Stop measuring this long after the last call (unless streaming). */
    private static final long IDLE_NANOS = 5_000_000_000L;

    private final IComputerSystem system;
    private final ImuSampler sampler = new ImuSampler();
    private volatile long lastUse = Long.MIN_VALUE;
    private volatile int streamInterval;
    private boolean active;
    private long ticks;

    public ComputerImuApi(IComputerSystem system) {
        this.system = system;
    }

    @Override
    public String[] getNames() {
        return new String[]{"imu"};
    }

    @Override
    public void startup() {
        sampler.restart();
        streamInterval = 0;
        active = false;
    }

    @Override
    public void update() {
        ServerLevel level = system.getLevel();
        if (level == null || !level.getServer().isSameThread()) return;
        ticks++;
        boolean wanted = streamInterval > 0 || System.nanoTime() - lastUse < IDLE_NANOS;
        if (!wanted) {
            if (active) sampler.restart();
            active = false;
            return;
        }
        active = true;
        Map<String, Object> s = sample();
        if (streamInterval > 0 && ticks % streamInterval == 0) system.queueEvent("imu", "computer", s);
    }

    private Map<String, Object> sample() {
        ServerLevel level = system.getLevel();
        BlockPos pos = system.getPosition();
        IPocketAccess pocket = system.getComponent(ComputerComponents.POCKET);
        Entity holder = pocket == null ? null : pocket.getEntity();
        RadarFrame f;
        Quaterniond ship;
        if (holder != null) {
            f = holderFrame(holder);
            ship = new Quaterniond();
        } else {
            ITurtleAccess turtle = system.getComponent(ComputerComponents.TURTLE);
            f = RadarFrame.of(level, pos, turtle != null ? turtle.getDirection() : facingOf(level.getBlockState(pos)));
            ship = ImuSampler.shipOrientation(level, pos);
        }
        return sampler.sample(f, ship, level.getGameTime());
    }

    /** A computer's front (computers, monitors etc. have a horizontal FACING); north if the block has none. */
    public static Direction facingOf(BlockState state) {
        return state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? state.getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.NORTH;
    }

    /** Frame of an entity carrying a pocket computer: forward = its horizontal look (in world space), up = world up. */
    public static RadarFrame holderFrame(Entity e) {
        Vec3 look = Vec3.directionFromRotation(0, e.getYRot());
        if (AshCC.sableLoaded) look = SableCompat.lookToWorld(e, look);
        Vec3 fwd = new Vec3(look.x, 0, look.z);
        fwd = fwd.lengthSqr() < 1e-9 ? new Vec3(0, 0, -1) : fwd.normalize();
        Vec3 up = new Vec3(0, 1, 0);
        return new RadarFrame(e.getEyePosition(), fwd.cross(up), up, fwd, AshCC.sableLoaded ? SableCompat.entitySubLevel(e) : null);
    }

    private MethodResult read(ILuaContext context, Function<Map<String, Object>, Object> pick) throws LuaException {
        lastUse = System.nanoTime();
        Map<String, Object> s = active ? sampler.latest() : null;
        if (s != null) return MethodResult.of(pick.apply(s));
        // First call (or after a pause): measure now on the main thread; from the next tick on update() keeps it fresh
        return context.executeMainThreadTask(() -> {
            sampler.restart();
            Map<String, Object> now = sample();
            active = true;
            return new Object[]{pick.apply(now)};
        });
    }

    private static Map<String, Object> pickKeys(Map<String, Object> s, String... keys) {
        Map<String, Object> t = new HashMap<>();
        for (String k : keys) t.put(k, s.get(k));
        return t;
    }

    @LuaFunction
    public final MethodResult getAll(ILuaContext context) throws LuaException {
        return read(context, s -> s);
    }

    @LuaFunction
    public final MethodResult getPosition(ILuaContext context) throws LuaException {
        return read(context, s -> s.get("position"));
    }

    @LuaFunction
    public final MethodResult getAngles(ILuaContext context) throws LuaException {
        return read(context, s -> pickKeys(s, "yaw", "pitch", "roll"));
    }

    @LuaFunction
    public final MethodResult getOrientation(ILuaContext context) throws LuaException {
        return read(context, s -> s.get("orientation"));
    }

    @LuaFunction
    public final MethodResult getAxes(ILuaContext context) throws LuaException {
        return read(context, s -> s.get("axes"));
    }

    @LuaFunction
    public final MethodResult getAngularVelocity(ILuaContext context) throws LuaException {
        return read(context, ImuSampler::rates);
    }

    @LuaFunction
    public final MethodResult getVelocity(ILuaContext context) throws LuaException {
        return read(context, s -> s.get("velocity"));
    }

    @LuaFunction
    public final MethodResult getLocalVelocity(ILuaContext context) throws LuaException {
        return read(context, s -> s.get("localVelocity"));
    }

    @LuaFunction
    public final MethodResult getAcceleration(ILuaContext context) throws LuaException {
        return read(context, s -> s.get("acceleration"));
    }

    @LuaFunction
    public final MethodResult getGForce(ILuaContext context) throws LuaException {
        return read(context, s -> s.get("gForce"));
    }

    @LuaFunction
    public final MethodResult isOnShip(ILuaContext context) throws LuaException {
        return read(context, s -> s.get("onShip"));
    }

    @LuaFunction
    public final double getGravity() {
        return AshCCConfig.IMU_GRAVITY.get();
    }

    /** setStream(ticks): queue "imu"("computer", table) every that many ticks; 0 stops. */
    @LuaFunction
    public final int setStream(int ticks) throws LuaException {
        if (ticks < 0 || ticks > 1200) throw new LuaException("ticks must be between 0 and 1200");
        streamInterval = ticks;
        return ticks;
    }

    @LuaFunction
    public final int getStream() {
        return streamInterval;
    }
}
