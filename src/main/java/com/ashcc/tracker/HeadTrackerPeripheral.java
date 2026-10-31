package com.ashcc.tracker;

import com.ashcc.AshCC;
import com.ashcc.compat.SableCompat;
import com.ashcc.laser.LaserHit;
import com.ashcc.link.LinkHubBlockEntity;
import com.ashcc.radar.LuaUtil;
import com.ashcc.radar.RadarFrame;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Head direction of one player, exposed through a link hub. Angles use the laser's convention: yaw 0 = north,
 * positive towards east (-180..180), pitch positive up. "Local" angles are in the hub's frame (its ship's north and
 * up on a Sable sub-level), so they can go straight into laser.setAim on the same ship.
 */
public class HeadTrackerPeripheral implements IPeripheral {
    private static final double DEFAULT_RANGE = 128;
    private static final double MAX_RANGE = 1024;

    private final LinkHubBlockEntity hub;
    private final TrackedPlayer tracked;

    public HeadTrackerPeripheral(LinkHubBlockEntity hub, TrackedPlayer tracked) {
        this.hub = hub;
        this.tracked = tracked;
    }

    @Override
    public String getType() {
        return "head_tracker";
    }

    @Override
    public Object getTarget() {
        return tracked;
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof HeadTrackerPeripheral o && o.hub == hub && o.tracked.id().equals(tracked.id());
    }

    private @Nullable ServerPlayer online() {
        Level level = hub.getLevel();
        return level == null || level.getServer() == null ? null : level.getServer().getPlayerList().getPlayer(tracked.id());
    }

    private ServerPlayer player() throws LuaException {
        ServerPlayer p = online();
        if (p == null) throw new LuaException("Player is offline: " + tracked.name());
        return p;
    }

    /**
     * Where the camera points, in world space. Not getViewVector: on living entities that follows yHeadRot, which
     * lags the rotation the client sends. In a seat on a Sable ship the rotation is relative to the ship.
     */
    public static Vec3 look(ServerPlayer player) {
        Vec3 dir = Vec3.directionFromRotation(player.getXRot(), player.getYRot());
        return AshCC.sableLoaded ? SableCompat.lookToWorld(player, dir) : dir;
    }

    /** {yaw, pitch} of a direction in block coordinates (x = east, y = up, z = south). */
    public static double[] angles(Vec3 d) {
        double yaw = Math.toDegrees(Math.atan2(d.x, -d.z));
        double pitch = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, d.y))));
        return new double[]{yaw, pitch};
    }

    /** World direction to the hub's block coordinates (its ship's east/up/south; the world's when not on a ship). */
    private Vec3 toLocal(Vec3 world) throws LuaException {
        Level level = hub.getLevel();
        if (level == null) throw new LuaException("Link hub is not loaded");
        Vec3 l = RadarFrame.of(level, hub.getBlockPos(), Direction.NORTH, Direction.UP).dirToLocal(world);
        return new Vec3(l.x, l.y, -l.z);
    }

    /** name, uuid of the tracked player. */
    @LuaFunction
    public final MethodResult getPlayer() {
        return MethodResult.of(tracked.name(), tracked.id().toString());
    }

    @LuaFunction(mainThread = true)
    public final boolean isOnline() {
        return online() != null;
    }

    @LuaFunction(mainThread = true)
    public final double getYaw() throws LuaException {
        return angles(look(player()))[0];
    }

    @LuaFunction(mainThread = true)
    public final double getPitch() throws LuaException {
        return angles(look(player()))[1];
    }

    /** yaw, pitch in world space. */
    @LuaFunction(mainThread = true)
    public final MethodResult getAngles() throws LuaException {
        double[] a = angles(look(player()));
        return MethodResult.of(a[0], a[1]);
    }

    /** yaw, pitch in the hub's frame (relative to its ship). */
    @LuaFunction(mainThread = true)
    public final MethodResult getLocalAngles() throws LuaException {
        double[] a = angles(toLocal(look(player())));
        return MethodResult.of(a[0], a[1]);
    }

    /** {yaw, pitch, localYaw, localPitch, direction, localDirection, eye, dimension, name, [subLevel]}. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getLook() throws LuaException {
        ServerPlayer p = player();
        Vec3 dir = look(p);
        Vec3 local = toLocal(dir);
        double[] a = angles(dir);
        double[] l = angles(local);
        Map<String, Object> t = new HashMap<>();
        t.put("yaw", a[0]);
        t.put("pitch", a[1]);
        t.put("localYaw", l[0]);
        t.put("localPitch", l[1]);
        t.put("direction", LuaUtil.vec(dir));
        t.put("localDirection", LuaUtil.vec(local));
        t.put("eye", LuaUtil.vec(p.getEyePosition()));
        t.put("dimension", p.level().dimension().location().toString());
        t.put("name", p.getGameProfile().getName());
        UUID sub = AshCC.sableLoaded ? SableCompat.entitySubLevel(p) : null;
        if (sub != null) t.put("subLevel", sub.toString());
        return t;
    }

    /** What the player is looking at: {type = "block"|"entity"|"none", x, y, z, distance, ...} like laser.trace(). */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getLookTarget(IArguments args) throws LuaException {
        double range = args.optFiniteDouble(0).orElse(DEFAULT_RANGE);
        if (range <= 0) throw new LuaException("range must be positive");
        return trace(player(), Math.min(range, MAX_RANGE)).toLua();
    }

    public static LaserHit trace(ServerPlayer player, double range) {
        Level level = player.level();
        Vec3 from = player.getEyePosition();
        Vec3 dir = look(player);
        Vec3 to = from.add(dir.scale(range));

        Vec3 end = to;
        BlockPos hitPos = null;
        BlockHitResult bh = level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (bh.getType() == HitResult.Type.BLOCK) {
            hitPos = bh.getBlockPos();
            // Hits on a ship come back in plot coordinates
            end = AshCC.sableLoaded ? SableCompat.projectOut(level, bh.getLocation()) : bh.getLocation();
        }

        Entity entity = null;
        double best = Double.MAX_VALUE;
        AABB box = new AABB(from, end).inflate(1);
        for (Entity e : level.getEntities(player, box, e -> e.isPickable() && !e.isSpectator() && e.isAlive() && !player.isPassengerOfSameVehicle(e))) {
            Optional<Vec3> p = e.getBoundingBox().inflate(e.getPickRadius() + 0.1).clip(from, end);
            if (p.isEmpty()) continue;
            double d = from.distanceToSqr(p.get());
            if (d < best) {
                best = d;
                entity = e;
                end = p.get();
            }
        }
        if (entity != null) {
            UUID sub = AshCC.sableLoaded ? SableCompat.entitySubLevel(entity) : null;
            return new LaserHit(from, dir, end, null, null, sub, entity);
        }
        if (hitPos != null) {
            UUID sub = AshCC.sableLoaded ? SableCompat.subLevelAt(level, hitPos) : null;
            return new LaserHit(from, dir, end, hitPos, level.getBlockState(hitPos), sub, null);
        }
        return new LaserHit(from, dir, end, null, null, null, null);
    }
}
