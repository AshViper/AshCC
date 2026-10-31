package com.ashcc.compat.cbc;

import com.ashcc.AshCC;
import com.ashcc.AshCCConfig;
import com.ashcc.compat.SableCompat;
import com.ashcc.radar.LuaUtil;
import com.ashcc.radar.RadarFrame;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedBigCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.IBigCannonBlockEntity;
import rbasamoyai.createbigcannons.munitions.autocannon.ammo_container.AutocannonAmmoContainerItem;
import rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCannonPropellantBlock;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lua API of a Create Big Cannons cannon mount or fixed cannon mount (peripheral type "cannon_mount").
 * Angles are AshCC's: degrees in the mount's block frame (the ship's on a sub-level), yaw 0 = north, positive towards
 * east (-180..180), pitch positive up. A cannon mount turns to a set aim at aimSpeed without rotational power; a fixed
 * mount cannot turn. Big cannons, drop mortars and railguns fire one shot per fire(); autocannons fire while setFiring(true).
 */
public class CannonMountPeripheral implements IPeripheral {
    private final BlockEntity mount;

    CannonMountPeripheral(BlockEntity mount) {
        this.mount = mount;
    }

    @Override
    public String getType() {
        return "cannon_mount";
    }

    @Override
    public Object getTarget() {
        return mount;
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof CannonMountPeripheral o && o.mount == mount;
    }

    private ServerLevel level() throws LuaException {
        if (!(mount.getLevel() instanceof ServerLevel level) || mount.isRemoved()) throw new LuaException("Cannon mount is not loaded");
        return level;
    }

    private PitchOrientedContraptionEntity entity() throws LuaException {
        level();
        PitchOrientedContraptionEntity e = Mounts.contraption(mount);
        if (e == null) throw new LuaException("No cannon is assembled");
        return e;
    }

    private AbstractMountedCannonContraption cannon() throws LuaException {
        if (!(entity().getContraption() instanceof AbstractMountedCannonContraption c)) throw new LuaException("No cannon is assembled");
        return c;
    }

    private RadarFrame frame(Level level) {
        return RadarFrame.of(level, mount.getBlockPos(), Direction.NORTH, Direction.UP);
    }

    private Vec3 toWorld(Level level, Vec3 point) {
        return AshCC.sableLoaded ? SableCompat.pointToWorld(level, mount.getBlockPos(), point) : point;
    }

    /** World position the cannon turns about (the centre of the block it was assembled from). */
    private Vec3 pivot(Level level, PitchOrientedContraptionEntity e) {
        return toWorld(level, e.toGlobalVector(Vec3.atCenterOf(BlockPos.ZERO), 0.0F));
    }

    private static Vec3 localDirection(double yaw, double pitch) {
        double yr = Math.toRadians(yaw);
        double pr = Math.toRadians(pitch);
        return new Vec3(Math.sin(yr) * Math.cos(pr), Math.sin(pr), Math.cos(yr) * Math.cos(pr));
    }

    // ---- Assembly ----

    @LuaFunction(mainThread = true)
    public final boolean isAssembled() throws LuaException {
        level();
        return Mounts.contraption(mount) != null;
    }

    /** assemble() -> true, or false and the reason (as the mount's goggle tooltip shows it). */
    @LuaFunction(mainThread = true)
    public final MethodResult assemble() throws LuaException {
        level();
        if (Mounts.contraption(mount) != null) return MethodResult.of(true);
        String err = Mounts.assemble(mount);
        return err == null ? MethodResult.of(true) : MethodResult.of(false, err);
    }

    @LuaFunction(mainThread = true)
    public final void disassemble() throws LuaException {
        level();
        MountControl c = MountControl.get(mount);
        if (c != null) {
            c.aiming = false;
            c.setTrigger(0);
        }
        if (Mounts.cannon(mount) instanceof MountedLaserContraption laser) laser.setLuaTrigger(false);
        Mounts.disassemble(mount);
    }

    /**
     * getCannon() -> nil when nothing is assembled, else { type = "big_cannon"/"drop_mortar"/"autocannon"/
     * "rotary_autocannon"/"railgun", yaw, pitch, maxElevation, maxDepression, canAim, blocks, fixed,
     * firing, fireRate (autocannons), spin, rpm (rotary), energy, energyCapacity, cooldown (railgun) }.
     */
    @LuaFunction(mainThread = true)
    public final @Nullable Map<String, Object> getCannon() throws LuaException {
        level();
        PitchOrientedContraptionEntity e = Mounts.contraption(mount);
        if (e == null || !(e.getContraption() instanceof AbstractMountedCannonContraption c)) return null;
        Map<String, Object> t = new HashMap<>();
        t.put("type", Mounts.typeName(c));
        t.put("yaw", Mounts.yaw(e));
        t.put("pitch", Mounts.pitch(e));
        t.put("maxElevation", Mounts.maxElevation(e));
        t.put("maxDepression", Mounts.maxDepression(e));
        t.put("canAim", Mounts.canAim(mount));
        t.put("fixed", !(mount instanceof CannonMountBlockEntity));
        t.put("blocks", c.getBlocks().size());
        AbstractAutocannonBreechBlockEntity breech = Mounts.autocannonBreech(c);
        if (breech != null) {
            t.put("fireRate", breech.getFireRate());
            t.put("firing", breech.getFireRate() > 0);
            t.put("roundsPerMinute", breech.getActualFireRate());
        }
        if (breech instanceof RotaryBreechBlockEntity rotary) t.put("spin", rotary.spin());
        if (c instanceof MountedRailgunContraption rail) {
            RailgunBreechBlockEntity b = rail.breech();
            if (b != null) {
                t.put("energy", b.energy().getEnergyStored());
                t.put("energyCapacity", b.energy().getMaxEnergyStored());
                t.put("energyPerShot", AshCCConfig.RAILGUN_ENERGY_PER_SHOT.get());
                t.put("cooldown", b.cooldown() / 20.0);
            }
            t.put("rails", rail.rails());
        }
        if (c instanceof MountedLaserContraption laser) {
            LaserEmitterBlockEntity em = laser.emitter();
            if (em != null) {
                t.put("energy", em.energy().getEnergyStored());
                t.put("energyCapacity", em.energy().getMaxEnergyStored());
                t.put("heat", em.heat());
                t.put("overheated", em.overheated());
            }
            t.put("energyPerTick", AshCCConfig.LASER_CANNON_ENERGY_PER_TICK.get());
            t.put("firing", laser.firing());
            t.put("trigger", laser.luaTrigger());
            t.put("range", laser.range());
            t.put("beamLength", laser.beamLength());
            t.put("lenses", laser.lenses());
        }
        return t;
    }

    // ---- Aim ----

    private MountControl aimControl() throws LuaException {
        PitchOrientedContraptionEntity e = entity();
        if (!(mount instanceof CannonMountBlockEntity)) throw new LuaException("A fixed cannon mount cannot turn");
        if (!Mounts.canAim(mount)) throw new LuaException("This cannon cannot be turned by its mount");
        return MountControl.of(mount);
    }

    /** setAim(yaw, pitch): turn towards it at aimSpeed. Returns the yaw (-180..180) and pitch clamped to the mount's limits. */
    @LuaFunction(mainThread = true)
    public final MethodResult setAim(double yaw, double pitch) throws LuaException {
        if (!Double.isFinite(yaw) || !Double.isFinite(pitch)) throw new LuaException("yaw and pitch must be finite numbers");
        MountControl c = aimControl();
        PitchOrientedContraptionEntity e = entity();
        c.aiming = true;
        c.targetYaw = Mth.wrapDegrees(yaw);
        c.targetPitch = MountControl.clampPitch(e, pitch);
        return MethodResult.of(c.targetYaw, c.targetPitch);
    }

    /**
     * aimAt(x, y, z, [options]) aims so the next shot hits the world point, simulating the loaded round's flight
     * (gravity and drag). options: { velocity = {x, y, z} (target blocks/s, leads it), high = true (high arc),
     * speed = muzzle blocks/s (when the round's speed is not known, e.g. an unloaded big cannon) }.
     * Returns ok, yaw, pitch, flightSeconds; ok is false (with a reason) when out of reach or nothing is loaded.
     */
    @LuaFunction(mainThread = true)
    public final MethodResult aimAt(IArguments args) throws LuaException {
        double x = args.getFiniteDouble(0);
        double y = args.getFiniteDouble(1);
        double z = args.getFiniteDouble(2);
        Map<?, ?> opts = args.optTable(3).orElse(Map.of());
        MountControl c = aimControl();
        ServerLevel level = level();
        PitchOrientedContraptionEntity e = entity();
        CannonBallistics.Shot shot = CannonBallistics.next(level, e);
        if (opts.get("speed") instanceof Number n) {
            if (!(n.doubleValue() > 0)) throw new LuaException("speed must be positive");
            shot = shot == null
                ? new CannonBallistics.Shot(n.doubleValue() / 20.0, -0.05, 0.01, false, 0, 0, "")
                : new CannonBallistics.Shot(n.doubleValue() / 20.0, shot.gravity(), shot.drag(), shot.quadratic(), shot.lifetime(), shot.muzzle(), shot.ammo());
        }
        if (shot == null || shot.speed() <= 0) return MethodResult.of(false, "nothing loaded");
        Vec3 vel = Vec3.ZERO;
        if (opts.get("velocity") instanceof Map<?, ?> v) {
            vel = new Vec3(num(v, "x"), num(v, "y"), num(v, "z"));
        }
        boolean high = Boolean.TRUE.equals(opts.get("high"));
        CannonBallistics.Aim aim = CannonBallistics.solve(shot, frame(level), pivot(level, e), new Vec3(x, y, z), vel, high);
        if (aim == null) return MethodResult.of(false, "out of range");
        c.aiming = true;
        c.targetYaw = Mth.wrapDegrees(aim.yaw());
        c.targetPitch = MountControl.clampPitch(e, aim.pitch());
        if (Math.abs(c.targetPitch - aim.pitch()) > 0.01) return MethodResult.of(false, "beyond the mount's pitch limits", c.targetYaw, aim.pitch(), aim.time());
        return MethodResult.of(true, c.targetYaw, c.targetPitch, aim.time());
    }

    private static double num(Map<?, ?> t, String key) throws LuaException {
        if (t.get(key) instanceof Number n && Double.isFinite(n.doubleValue())) return n.doubleValue();
        throw new LuaException("velocity." + key + " must be a number");
    }

    /** Current angles. */
    @LuaFunction(mainThread = true)
    public final MethodResult getAim() throws LuaException {
        PitchOrientedContraptionEntity e = entity();
        return MethodResult.of(Mounts.yaw(e), Mounts.pitch(e));
    }

    /** The aim set from Lua, or nil while the mount is not under Lua aim control. */
    @LuaFunction(mainThread = true)
    public final MethodResult getTargetAim() throws LuaException {
        level();
        MountControl c = MountControl.get(mount);
        return c == null || !c.aiming ? MethodResult.of() : MethodResult.of(c.targetYaw, c.targetPitch);
    }

    @LuaFunction(mainThread = true)
    public final boolean isAimed() throws LuaException {
        level();
        MountControl c = MountControl.get(mount);
        return c != null && c.aimed();
    }

    /** Stops turning under Lua control; the mount's rotational inputs turn it again. */
    @LuaFunction(mainThread = true)
    public final void releaseAim() throws LuaException {
        level();
        MountControl c = MountControl.get(mount);
        if (c != null) c.aiming = false;
    }

    /** setAimSpeed(degreesPerSecond), clamped to the server's maxAimSpeed. Returns the speed used. */
    @LuaFunction(mainThread = true)
    public final double setAimSpeed(double speed) throws LuaException {
        if (!(speed > 0)) throw new LuaException("speed must be positive");
        level();
        MountControl c = MountControl.of(mount);
        c.speed = Math.min(speed, AshCCConfig.CANNON_MAX_AIM_SPEED.get());
        return c.speed;
    }

    @LuaFunction(mainThread = true)
    public final double getAimSpeed() throws LuaException {
        level();
        MountControl c = MountControl.get(mount);
        return c == null ? AshCCConfig.CANNON_AIM_SPEED.get() : c.speed;
    }

    /** World position the barrel turns about, the barrel's world direction and where the next shot spawns. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getMuzzle() throws LuaException {
        ServerLevel level = level();
        PitchOrientedContraptionEntity e = entity();
        Vec3 pivot = pivot(level, e);
        Vec3 dir = frame(level).dirToWorld(localDirection(Mounts.yaw(e), Mounts.pitch(e)));
        CannonBallistics.Shot shot = CannonBallistics.next(level, e);
        Map<String, Object> t = LuaUtil.vec(shot == null ? pivot : pivot.add(dir.scale(shot.muzzle())));
        t.put("pivot", LuaUtil.vec(pivot));
        t.put("direction", LuaUtil.vec(dir));
        return t;
    }

    /**
     * getBallistics() -> nil when nothing is loaded, else { speed (blocks/s), gravity (blocks/s^2, negative = down),
     * drag, quadraticDrag, lifetime (seconds in flight, 0 = until it lands), muzzle (blocks from the pivot), ammo }.
     * CBC's drag per tick is drag * |v| (|v|^2 when quadratic) in blocks/tick.
     */
    @LuaFunction(mainThread = true)
    public final @Nullable Map<String, Object> getBallistics() throws LuaException {
        CannonBallistics.Shot s = CannonBallistics.next(level(), entity());
        if (s == null) return null;
        Map<String, Object> t = new HashMap<>();
        t.put("speed", s.speed() * 20.0);
        t.put("gravity", s.gravity() * 400.0);
        t.put("drag", s.drag());
        t.put("quadraticDrag", s.quadratic());
        t.put("lifetime", s.lifetime() / 20.0);
        t.put("muzzle", s.muzzle());
        t.put("ammo", s.ammo());
        return t;
    }

    // ---- Firing ----

    /** fire() for a big cannon, drop mortar or railgun: one shot now. Returns true, or false and why it did not fire. */
    @LuaFunction(mainThread = true)
    public final MethodResult fire() throws LuaException {
        ServerLevel level = level();
        PitchOrientedContraptionEntity e = entity();
        AbstractMountedCannonContraption c = cannon();
        if (c instanceof MountedAutocannonContraption) return MethodResult.of(false, "autocannons fire with setFiring");
        if (c instanceof MountedLaserContraption) return MethodResult.of(false, "lasers fire with setFiring");
        if (c instanceof MountedRailgunContraption rail) {
            String err = rail.tryFire(level, e);
            return err == null ? MethodResult.of(true) : MethodResult.of(false, err);
        }
        if (c instanceof MountedBigCannonContraption big) {
            boolean before = big.hasFired;
            big.hasFired = false;
            c.fireShot(level, e);
            boolean fired = big.hasFired;
            if (!fired) big.hasFired = before;
            return fired || big.isDropMortar() ? MethodResult.of(true) : MethodResult.of(false, "did not fire");
        }
        c.fireShot(level, e);
        return MethodResult.of(true);
    }

    /**
     * setFiring(on, [rate]) for autocannons and laser cannons: hold the trigger until setFiring(false). For autocannons
     * rate is CBC's fire rate 1..15 (default 15); a laser cannon fires while it has energy and is not overheated.
     */
    @LuaFunction(mainThread = true)
    public final void setFiring(IArguments args) throws LuaException {
        boolean on = args.getBoolean(0);
        int rate = args.optInt(1, 15);
        if (rate < 1 || rate > 15) throw new LuaException("rate must be 1..15");
        AbstractMountedCannonContraption c = cannon();
        if (c instanceof MountedLaserContraption laser) {
            laser.setLuaTrigger(on);
            return;
        }
        if (!(c instanceof MountedAutocannonContraption)) throw new LuaException("Only autocannons and laser cannons fire continuously");
        MountControl.of(mount).setTrigger(on ? rate : 0);
    }

    @LuaFunction(mainThread = true)
    public final boolean isFiring() throws LuaException {
        level();
        AbstractMountedCannonContraption c = Mounts.cannon(mount);
        if (c instanceof MountedLaserContraption laser) return laser.firing();
        AbstractAutocannonBreechBlockEntity b = c == null ? null : Mounts.autocannonBreech(c);
        return b != null && b.getFireRate() > 0;
    }

    /**
     * getAmmo() -> what is loaded. Autocannons: { rounds, next, magazine }; railgun: { name, count, capacity };
     * big cannon: { projectile, charges }. nil when nothing is assembled.
     */
    @LuaFunction(mainThread = true)
    public final @Nullable Map<String, Object> getAmmo() throws LuaException {
        level();
        AbstractMountedCannonContraption c = Mounts.cannon(mount);
        if (c == null) return null;
        Map<String, Object> t = new HashMap<>();
        AbstractAutocannonBreechBlockEntity breech = Mounts.autocannonBreech(c);
        if (breech != null) {
            int rounds = 0;
            for (ItemStack s : breech.getInputBuffer()) rounds += s.getCount();
            ItemStack mag = breech.getMagazine();
            if (!mag.isEmpty()) {
                rounds += AutocannonAmmoContainerItem.getTotalAmmoCount(mag);
                t.put("magazine", CannonBallistics.itemName(mag));
            }
            t.put("rounds", rounds);
            ItemStack next = breech.getInputBuffer().isEmpty() ? AutocannonAmmoContainerItem.getMainAmmoStack(mag) : breech.getInputBuffer().peek();
            if (next != null && !next.isEmpty()) t.put("next", CannonBallistics.itemName(next));
        } else if (c instanceof MountedRailgunContraption rail) {
            RailgunBreechBlockEntity b = rail.breech();
            ItemStack ammo = b == null ? ItemStack.EMPTY : b.ammo();
            t.put("count", ammo.getCount());
            t.put("capacity", AshCCConfig.RAILGUN_AMMO_CAPACITY.get());
            if (!ammo.isEmpty()) t.put("name", CannonBallistics.itemName(ammo));
        } else if (c instanceof MountedBigCannonContraption) {
            float charges = 0;
            Direction dir = c.initialOrientation();
            BlockPos pos = c.getStartPos();
            while (c.presentBlockEntities.get(pos) instanceof IBigCannonBlockEntity cbe) {
                StructureBlockInfo info = cbe.cannonBehavior().block();
                if (info.state().getBlock() instanceof ProjectileBlock<?> pb) {
                    if (!t.containsKey("projectile")) t.put("projectile", CannonBallistics.itemName(new ItemStack(pb)));
                } else if (info.state().getBlock() instanceof BigCannonPropellantBlock p && !t.containsKey("projectile")) {
                    charges += Math.max(0, p.getChargePower(info));
                }
                pos = pos.relative(dir);
            }
            t.put("charges", charges);
        }
        return t;
    }

    /** ID of the Sable sub-level the mount is on, or nil. */
    @LuaFunction(mainThread = true)
    public final @Nullable String getSubLevel() throws LuaException {
        Level level = level();
        if (!AshCC.sableLoaded) return null;
        UUID id = SableCompat.subLevelAt(level, mount.getBlockPos());
        return id == null ? null : id.toString();
    }
}
