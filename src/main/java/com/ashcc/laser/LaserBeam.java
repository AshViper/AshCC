package com.ashcc.laser;

import com.ashcc.AshCC;
import com.ashcc.compat.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * A laser beam's trace and what it does to what it hits: burns through blocks (progress kept between ticks),
 * hurts and ignites entities, pushes the Sable ship it hits and recoils the ship it is fired from.
 * Shared by the laser block and the Create Big Cannons laser cannon.
 */
public final class LaserBeam {
    /** What one beam does per tick. */
    public record Power(double blockPower, boolean breakBlocks, boolean dropBlocks, boolean damageOwnSubLevel, float entityDamage,
                        int igniteSeconds, double impactForce, boolean recoil) {
    }

    /** A block the beam burnt through this tick. */
    public record Broken(String block, Vec3 at, @Nullable UUID subLevel) {
    }

    private final int breakerId;
    private @Nullable BlockPos burnPos;
    private double burnProgress;
    private int brokenCount;
    private @Nullable BlockPos lastBroken;

    /** @param breakerId id for the block-crack overlay; negative so it never matches a player (who would not see it) */
    public LaserBeam(int breakerId) {
        this.breakerId = breakerId;
    }

    public int brokenCount() {
        return brokenCount;
    }

    public @Nullable BlockPos lastBroken() {
        return lastBroken;
    }

    /**
     * Traces a beam: blocks (including other ships' blocks through Sable), then the first entity before that block
     * that {@code ignore} does not exclude. Nothing is damaged.
     */
    public static LaserHit trace(Level level, Vec3 from, Vec3 dir, double range, Predicate<Entity> ignore) {
        Vec3 to = from.add(dir.scale(range));
        Vec3 end = to;
        BlockPos hitPos = null;
        BlockHitResult bh = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (bh.getType() == HitResult.Type.BLOCK) {
            hitPos = bh.getBlockPos();
            // Hits on a ship come back in plot coordinates
            end = AshCC.sableLoaded ? SableCompat.projectOut(level, bh.getLocation()) : bh.getLocation();
        }

        Entity entity = null;
        double best = Double.MAX_VALUE;
        AABB box = new AABB(from, end).inflate(1);
        for (Entity e : level.getEntities((Entity) null, box, e -> e.isPickable() && !e.isSpectator() && e.isAlive() && !ignore.test(e))) {
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

    /**
     * Applies one tick of the beam. {@code own} is the ship the emitter is on (or null) and {@code ownPos} a block of it,
     * used for the recoil. Returns the block burnt through this tick, if any.
     */
    public @Nullable Broken apply(ServerLevel level, LaserHit hit, Power power, @Nullable UUID own, BlockPos ownPos) {
        boolean hitsOwnShip = own != null && own.equals(hit.subLevel()) && hit.entity() == null;
        double impulse = power.impactForce() / 20.0;
        Broken broken = null;

        if (hit.entity() != null) {
            reset(level);
            Entity e = hit.entity();
            DamageSource source = new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(LaserBlockEntity.DAMAGE));
            e.hurt(source, power.entityDamage());
            if (power.igniteSeconds() > 0) e.igniteForSeconds(power.igniteSeconds());
        } else if (hit.blockPos() != null) {
            if (power.breakBlocks() && (!hitsOwnShip || power.damageOwnSubLevel())) {
                broken = burn(level, hit, power);
            } else {
                reset(level);
            }
            // Push the ship that was hit (radiation pressure). A hit on our own ship is an internal force.
            if (AshCC.sableLoaded && hit.subLevel() != null && !hitsOwnShip && impulse > 0) {
                SableCompat.applyImpulse(level, hit.blockPos(), hit.end(), hit.direction().scale(impulse));
            }
        } else {
            reset(level);
        }
        if (AshCC.sableLoaded && own != null && !hitsOwnShip && impulse > 0 && power.recoil()) {
            SableCompat.applyImpulse(level, ownPos, hit.from(), hit.direction().scale(-impulse));
        }

        if (!hit.type().equals(LaserHit.NONE)) {
            Vec3 p = hit.end().subtract(hit.direction().scale(0.05));
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, 3, 0.08, 0.08, 0.08, 0.15);
            if (level.getGameTime() % 4 == 0) level.sendParticles(ParticleTypes.SMOKE, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0.02);
        }
        return broken;
    }

    /** Heats the hit block: it breaks once {@code blockPower} per tick adds up to its hardness. */
    private @Nullable Broken burn(ServerLevel level, LaserHit hit, Power power) {
        BlockPos pos = Objects.requireNonNull(hit.blockPos());
        BlockState state = level.getBlockState(pos);
        float hardness = state.getDestroySpeed(level, pos);
        if (state.isAir() || hardness < 0) {
            reset(level);
            return null;
        }
        if (!pos.equals(burnPos)) {
            reset(level);
            burnPos = pos.immutable();
            burnProgress = 0;
        }
        burnProgress += power.blockPower();
        if (burnProgress >= hardness) {
            boolean drop = power.dropBlocks() && hit.subLevel() == null;
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            reset(level);
            if (level.destroyBlock(pos, drop)) {
                brokenCount++;
                lastBroken = pos.immutable();
                return new Broken(id, hit.end(), hit.subLevel());
            }
        } else {
            level.destroyBlockProgress(breakerId, pos, (int) (burnProgress / hardness * 10));
        }
        return null;
    }

    /** Forgets the block being burnt (and clears its crack overlay). */
    public void reset(Level level) {
        if (burnPos != null) {
            level.destroyBlockProgress(breakerId, burnPos, -1);
            burnPos = null;
        }
        burnProgress = 0;
    }

    /** 0..1 progress of burning through the current block. */
    public double burnFraction(Level level) {
        if (burnPos == null) return 0;
        float h = level.getBlockState(burnPos).getDestroySpeed(level, burnPos);
        return h <= 0 ? 0 : Math.min(1, burnProgress / h);
    }
}
