package com.ashcc.compat.cbc;

import com.ashcc.AshCC;
import com.ashcc.AshCCConfig;
import com.ashcc.compat.SableCompat;
import com.ashcc.laser.LaserBeam;
import com.ashcc.laser.LaserHit;
import com.simibubi.create.api.contraption.ContraptionType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.CBCCompatTransformers;
import rbasamoyai.createbigcannons.cannon_control.ControlPitchContraption;
import rbasamoyai.createbigcannons.cannon_control.cannon_types.ICannonContraptionType;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

import java.util.UUID;

/**
 * A laser cannon on a CBC cannon mount: an emitter and 1..maxLenses lenses in one line. While the trigger is held
 * (fire-side redstone, or Lua) it uses energyPerTick FE and fires a continuous beam along the barrel that burns through
 * blocks, hurts entities and pushes Sable ships, like the AshCC laser but stronger with every lens. It heats up and
 * overheats after maxFireSeconds of fire.
 */
public class MountedLaserContraption extends AbstractLineCannonContraption {
    private @Nullable LaserBeam beam;
    private boolean wasFiring;
    private double length;
    private double sentLength = -1;
    private int sinceSent;

    public MountedLaserContraption() {
    }

    @Override
    protected boolean isPart(Block block) {
        return block instanceof LaserCannonBlock;
    }

    @Override
    protected boolean isBack(Block block) {
        return block instanceof LaserEmitterBlock;
    }

    @Override
    protected int maxParts() {
        return AshCCConfig.LASER_CANNON_MAX_LENSES.get();
    }

    @Override
    protected String errorKey() {
        return "laser_cannon";
    }

    public @Nullable LaserEmitterBlockEntity emitter() {
        return presentBlockEntities.get(startPos) instanceof LaserEmitterBlockEntity e ? e : null;
    }

    @Override
    public @Nullable IEnergyStorage energyStorage() {
        LaserEmitterBlockEntity e = emitter();
        return e == null ? null : e.energy();
    }

    public int lenses() {
        return parts();
    }

    public boolean firing() {
        return wasFiring;
    }

    public double beamLength() {
        return length;
    }

    public double range() {
        return Math.min(AshCCConfig.LASER_CANNON_BASE_RANGE.get() + AshCCConfig.LASER_CANNON_RANGE_PER_LENS.get() * Math.max(0, lenses() - 1), 4096.0);
    }

    private LaserBeam.Power power() {
        int extra = Math.max(0, lenses() - 1);
        return new LaserBeam.Power(
            AshCCConfig.LASER_CANNON_BASE_BLOCK_POWER.get() + AshCCConfig.LASER_CANNON_BLOCK_POWER_PER_LENS.get() * extra,
            AshCCConfig.LASER_CANNON_BREAK_BLOCKS.get(), AshCCConfig.LASER_CANNON_DROP_BLOCKS.get(), AshCCConfig.LASER_CANNON_DAMAGE_OWN_SUB_LEVEL.get(),
            (float) (AshCCConfig.LASER_CANNON_BASE_DAMAGE.get() + AshCCConfig.LASER_CANNON_DAMAGE_PER_LENS.get() * extra),
            AshCCConfig.LASER_CANNON_IGNITE_SECONDS.get(), AshCCConfig.LASER_CANNON_IMPACT_FORCE.get(), AshCCConfig.LASER_CANNON_RECOIL.get());
    }

    /** Where the beam leaves the front lens, in contraption coordinates. */
    @Override
    public Vec3 fxMuzzle() {
        return localMuzzle();
    }

    public Vec3 localMuzzle() {
        Vec3 n = Vec3.atLowerCornerOf(initialOrientation.getNormal());
        return Vec3.atCenterOf(startPos.relative(initialOrientation, parts())).add(n.scale(0.5));
    }

    /** Distance along the barrel from the turning point to the muzzle (for aiming). */
    double muzzleAlong() {
        return localMuzzle().dot(Vec3.atLowerCornerOf(initialOrientation.getNormal()));
    }

    @Override
    public void tick(Level level, PitchOrientedContraptionEntity entity) {
        super.tick(level, entity);
        if (!(level instanceof ServerLevel slevel)) return;
        LaserEmitterBlockEntity emitter = emitter();
        if (emitter == null) return;
        if (beam == null) beam = new LaserBeam(-1 - (entity.getId() & 0x3fffffff));
        boolean on = emitter.tickFiring();
        if (on) {
            fireBeam(slevel, entity);
        } else {
            beam.reset(level);
            length = 0;
        }
        if (on != wasFiring) {
            Vec3 m = CBCCompatTransformers.transformVec3(level, entity.toGlobalVector(localMuzzle(), 0.0F), entity.position());
            level.playSound(null, m.x, m.y, m.z, on ? SoundEvents.BEACON_ACTIVATE : SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 2.0f, 1.2f);
        }
        boolean changed = on != wasFiring;
        wasFiring = on;
        sinceSent++;
        if (changed || (on && (Math.abs(length - sentLength) > 0.25 || sinceSent >= 20))) {
            sentLength = on ? length : -1;
            sinceSent = 0;
            LaserCannonFx.send(slevel, entity, on, length);
        }
    }

    private void fireBeam(ServerLevel level, PitchOrientedContraptionEntity entity) {
        Vec3 local = localMuzzle();
        Vec3 n = Vec3.atLowerCornerOf(initialOrientation.getNormal());
        Vec3 from = CBCCompatTransformers.transformVec3(level, entity.toGlobalVector(local, 0.0F), entity.position());
        Vec3 ahead = CBCCompatTransformers.transformVec3(level, entity.toGlobalVector(local.add(n), 0.0F), entity.position());
        Vec3 dir = ahead.subtract(from).normalize();
        Entity vehicle = entity.getVehicle();
        LaserHit hit = LaserBeam.trace(level, from, dir, range(), e -> e == entity || e == vehicle || entity.hasPassenger(e));
        length = hit.distance();

        BlockPos ownPos = entity.getController() instanceof BlockEntity be ? be.getBlockPos() : entity.blockPosition();
        UUID own = AshCC.sableLoaded ? SableCompat.subLevelAt(level, ownPos) : null;
        beam.apply(level, hit, power(), own, ownPos);
        if (level.getGameTime() % 40 == 0) level.playSound(null, from.x, from.y, from.z, SoundEvents.BEACON_AMBIENT, SoundSource.BLOCKS, 1.5f, 1.4f);
    }

    @Override
    public void onRedstoneUpdate(ServerLevel level, PitchOrientedContraptionEntity entity, boolean togglePower, int firePower, ControlPitchContraption controller) {
        LaserEmitterBlockEntity e = emitter();
        if (e != null) e.redstone = firePower;
    }

    /** One-off fireShot (CBC calls this for a single shot): a pulse is not a laser's thing, so it does nothing. */
    @Override
    public void fireShot(ServerLevel level, PitchOrientedContraptionEntity entity) {
    }

    /** Holds or releases the trigger from Lua. */
    public void setLuaTrigger(boolean on) {
        LaserEmitterBlockEntity e = emitter();
        if (e != null) e.luaTrigger = on;
    }

    public boolean luaTrigger() {
        LaserEmitterBlockEntity e = emitter();
        return e != null && e.luaTrigger;
    }

    @Override
    public ICannonContraptionType getCannonType() {
        return CbcCompat.LASER_CANNON_TYPE;
    }

    @Override
    public ContraptionType getType() {
        return CbcCompat.MOUNTED_LASER.get();
    }
}
