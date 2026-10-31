package com.ashcc.compat.cbc;

import com.ashcc.radar.RadarFrame;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedBigCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannons.autocannon.AutocannonBlock;
import rbasamoyai.createbigcannons.cannons.autocannon.IAutocannonBlockEntity;
import rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity;
import rbasamoyai.createbigcannons.cannons.autocannon.material.AutocannonMaterialProperties;
import rbasamoyai.createbigcannons.cannons.big_cannons.IBigCannonBlockEntity;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.autocannon.AutocannonAmmoItem;
import rbasamoyai.createbigcannons.munitions.autocannon.ammo_container.AutocannonAmmoContainerItem;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;
import rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCannonPropellantBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.IntegratedPropellantProjectile;
import rbasamoyai.createbigcannons.munitions.config.DimensionMunitionProperties;
import rbasamoyai.createbigcannons.munitions.config.DimensionMunitionPropertiesHandler;
import rbasamoyai.createbigcannons.munitions.config.components.BallisticPropertiesComponent;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Predicts where the next shot of a mounted CBC cannon goes and solves the aim for a target.
 * CBC moves a projectile once per tick with pos += v + a/2, v += a, where a = gravity (down) minus drag along v;
 * drag = drag * |v| (or |v|^2 when quadratic), never more than |v|. Spread and recoil are ignored.
 */
public final class CannonBallistics {
    private CannonBallistics() {
    }

    /**
     * @param speed    muzzle speed (blocks/tick)
     * @param gravity  per-tick change of the vertical speed (blocks/tick^2, negative = down)
     * @param drag     drag coefficient after the dimension's multiplier
     * @param lifetime ticks until the projectile despawns in flight (0 = until it lands)
     * @param muzzle   distance from the turning point to where the projectile spawns along the barrel (blocks)
     */
    public record Shot(double speed, double gravity, double drag, boolean quadratic, int lifetime, double muzzle, String ammo) {
    }

    record Aim(double yaw, double pitch, double time, Vec3 direction) {
    }

    private static final Method BALLISTICS;

    static {
        Method m;
        try {
            m = AbstractCannonProjectile.class.getDeclaredMethod("getBallisticProperties");
            m.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            m = null;
        }
        BALLISTICS = m;
    }

    public static BallisticPropertiesComponent ballistics(AbstractCannonProjectile p) {
        if (BALLISTICS != null) {
            try {
                return (BallisticPropertiesComponent) BALLISTICS.invoke(p);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return BallisticPropertiesComponent.DEFAULT;
    }

    private static Shot shot(Level level, AbstractCannonProjectile p, double speed, int lifetime, double muzzle, String ammo) {
        BallisticPropertiesComponent b = ballistics(p);
        DimensionMunitionProperties dim = DimensionMunitionPropertiesHandler.getProperties(level);
        double gravity = p.isNoGravity() ? 0.0 : b.gravity() * dim.gravityMultiplier();
        return new Shot(speed, gravity, b.drag() * dim.dragMultiplier(), b.isQuadraticDrag(), lifetime, muzzle, ammo);
    }

    /** Distance along the barrel from the turning point (centre of local block 0) to the centre of {@code pos}. */
    private static double along(BlockPos pos, Direction dir) {
        return Vec3.atLowerCornerOf(pos).dot(Vec3.atLowerCornerOf(dir.getNormal()));
    }

    static String itemName(ItemStack stack) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** The next shot of the cannon, or null when it has nothing loaded it could fire. */
    static @Nullable Shot next(Level level, PitchOrientedContraptionEntity entity) {
        if (!(entity.getContraption() instanceof AbstractMountedCannonContraption c)) return null;
        Direction dir = c.initialOrientation();
        if (c instanceof MountedRailgunContraption rail) return rail.nextShot(level);
        // A beam: straight and (for aiming) instant
        if (c instanceof MountedLaserContraption laser) return new Shot(1000.0, 0.0, 0.0, false, 0, laser.muzzleAlong(), "laser");
        if (c instanceof MountedAutocannonContraption auto) return autocannon(level, auto, dir);
        if (c instanceof MountedBigCannonContraption big && !big.isDropMortar()) return bigCannon(level, big, dir);
        return null;
    }

    private static @Nullable Shot autocannon(Level level, MountedAutocannonContraption c, Direction dir) {
        AbstractAutocannonBreechBlockEntity breech = Mounts.autocannonBreech(c);
        if (breech == null) return null;
        ItemStack round = breech.getInputBuffer().isEmpty() ? ItemStack.EMPTY : breech.getInputBuffer().peek();
        if ((round == null || round.isEmpty()) && !breech.getMagazine().isEmpty()) {
            round = AutocannonAmmoContainerItem.getMainAmmoStack(breech.getMagazine());
            if (round.isEmpty()) round = AutocannonAmmoContainerItem.getTracerAmmoStack(breech.getMagazine());
        }
        if (round == null || round.isEmpty() || !(round.getItem() instanceof AutocannonAmmoItem ammo)) return null;
        AbstractCannonProjectile p = ammo.getAutocannonProjectile(round, level);
        if (p == null) return null;
        StructureBlockInfo breechInfo = c.getBlocks().get(c.getStartPos());
        if (breechInfo == null || !(breechInfo.state().getBlock() instanceof AutocannonBlock ab)) return null;
        AutocannonMaterialProperties props = ab.getAutocannonMaterial().properties();
        BlockPos pos = c.getStartPos().relative(dir);
        int barrels = 0;
        while (c.presentBlockEntities.get(pos) instanceof IAutocannonBlockEntity) {
            barrels++;
            pos = pos.relative(dir);
        }
        double speed = props.baseSpeed() + props.speedIncreasePerBarrel() * Math.min(barrels, props.maxSpeedIncreases());
        // CBC spawns 1.5 blocks back from the block past the muzzle
        double muzzle = along(pos.relative(dir), dir) - 1.5;
        return shot(level, p, speed, props.projectileLifetime(), muzzle, itemName(round));
    }

    private static @Nullable Shot bigCannon(Level level, MountedBigCannonContraption c, Direction dir) {
        BlockPos pos = c.getStartPos();
        float charges = 0;
        AbstractBigCannonProjectile projectile = null;
        String ammo = null;
        while (c.presentBlockEntities.get(pos) instanceof IBigCannonBlockEntity cbe) {
            StructureBlockInfo info = cbe.cannonBehavior().block();
            if (projectile == null) {
                if (info.state().isAir()) {
                    charges = Math.max(charges - 1, 0);
                } else if (info.state().getBlock() instanceof ProjectileBlock<?> pb) {
                    projectile = pb.getProjectile(level, List.of(info));
                    ammo = itemName(new ItemStack(pb));
                    if (projectile != null) {
                        charges += projectile.addedChargePower();
                        if (projectile instanceof IntegratedPropellantProjectile ip) charges += Math.max(0, ip.getChargePower());
                    }
                } else if (info.state().getBlock() instanceof BigCannonPropellantBlock propellant) {
                    charges += Math.max(0, propellant.getChargePower(info));
                }
            }
            pos = pos.relative(dir);
        }
        if (projectile == null || charges <= 0) return null;
        // CBC spawns the shell in the block just past the muzzle
        return shot(level, projectile, charges, 0, along(pos, dir), ammo);
    }

    /** Projectile for a railgun round, or null when the item is not a single-block CBC big cannon projectile. */
    static @Nullable AbstractBigCannonProjectile railgunProjectile(Level level, ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem bi) || !(bi.getBlock() instanceof ProjectileBlock<?> pb)) return null;
        if (pb.getExpectedSize() != 1) return null;
        return pb.getProjectile(level, stack.copyWithCount(1));
    }

    public static Shot railgunShot(Level level, AbstractBigCannonProjectile p, double speed, double muzzle, String ammo) {
        return shot(level, p, speed, 0, muzzle, ammo);
    }

    // ---- Aim solver ----

    private static final int MAX_TICKS = 1200;

    /** Height at horizontal distance {@code range} and the ticks to get there, or null when the shot never gets that far. */
    private static double @Nullable [] heightAt(Shot s, double pitchDeg, double range) {
        double p = Math.toRadians(pitchDeg);
        double x = s.muzzle() * Math.cos(p);
        double y = s.muzzle() * Math.sin(p);
        if (x >= range) return new double[]{y - (x - range) * Math.tan(p), 0};
        double vx = s.speed() * Math.cos(p);
        double vy = s.speed() * Math.sin(p);
        int max = s.lifetime() > 0 ? s.lifetime() : MAX_TICKS;
        for (int t = 0; t < max; t++) {
            double v = Math.sqrt(vx * vx + vy * vy);
            double ax = 0;
            double ay = s.gravity();
            if (v > 1e-9) {
                double drag = s.drag() * v * (s.quadratic() ? v : 1);
                drag = Math.min(drag, v);
                ax -= vx / v * drag;
                ay -= vy / v * drag;
            }
            double nx = x + vx + ax * 0.5;
            double ny = y + vy + ay * 0.5;
            if (nx >= range) {
                double f = (range - x) / (nx - x);
                return new double[]{y + (ny - y) * f, t + f};
            }
            if (nx <= x + 1e-9) return null;
            x = nx;
            y = ny;
            vx += ax;
            vy += ay;
        }
        return null;
    }

    /** Pitch (degrees, world frame) that puts the shot through (range, height), and its flight ticks; null when out of reach. */
    public static double @Nullable [] solvePitch(Shot s, double range, double height, boolean high) {
        double step = 0.5;
        double prevP = -90;
        double[] prev = heightAt(s, prevP, range);
        double[] best = null;
        for (double p = -90 + step; p <= 90 + 1e-9; p += step) {
            double[] cur = heightAt(s, p, range);
            if (prev != null && cur != null && (prev[0] - height) * (cur[0] - height) <= 0) {
                double lo = prevP;
                double hi = p;
                double flo = prev[0] - height;
                for (int i = 0; i < 40; i++) {
                    double mid = (lo + hi) / 2;
                    double[] m = heightAt(s, mid, range);
                    if (m == null) break;
                    if ((m[0] - height) * flo <= 0) hi = mid;
                    else {
                        lo = mid;
                        flo = m[0] - height;
                    }
                }
                double pitch = (lo + hi) / 2;
                double[] r = heightAt(s, pitch, range);
                if (r != null) {
                    best = new double[]{pitch, r[1]};
                    if (!high) return best;
                }
            }
            prev = cur;
            prevP = p;
        }
        return best;
    }

    /**
     * Aim for hitting {@code target} (moving at {@code velocity} blocks/s) from a cannon turning about {@code pivot}.
     * Gravity is world down; the returned yaw/pitch are in the mount's frame. Null when out of reach.
     */
    static @Nullable Aim solve(Shot s, RadarFrame frame, Vec3 pivot, Vec3 target, Vec3 velocity, boolean high) {
        double ticks = 0;
        double[] sol = null;
        Vec3 aimPoint = target;
        for (int i = 0; i < 8; i++) {
            aimPoint = target.add(velocity.scale(ticks / 20.0));
            Vec3 d = aimPoint.subtract(pivot);
            double range = Math.sqrt(d.x * d.x + d.z * d.z);
            sol = solvePitch(s, range, d.y, high);
            if (sol == null) return null;
            if (Math.abs(sol[1] - ticks) < 0.05) {
                ticks = sol[1];
                break;
            }
            ticks = sol[1];
        }
        Vec3 d = aimPoint.subtract(pivot);
        double heading = Math.atan2(d.x, d.z);
        double pr = Math.toRadians(sol[0]);
        Vec3 world = new Vec3(Math.sin(heading) * Math.cos(pr), Math.sin(pr), Math.cos(heading) * Math.cos(pr));
        Vec3 local = frame.dirToLocal(world);
        return new Aim(RadarFrame.azimuth(local), RadarFrame.elevation(local), ticks / 20.0, world);
    }
}
