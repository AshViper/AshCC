package com.ashcc.compat.cbc;

import com.ashcc.AshCCConfig;
import com.simibubi.create.api.contraption.ContraptionType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.CBCCompatTransformers;
import rbasamoyai.createbigcannons.cannon_control.ControlPitchContraption;
import rbasamoyai.createbigcannons.cannon_control.cannon_types.ICannonContraptionType;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.effects.particles.plumes.BigCannonPlumeParticleData;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;
import rbasamoyai.createbigcannons.remix.GetItemStorage;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * A railgun on a CBC cannon mount: a breech and 1..maxRails rails in one line. Each shot takes one round from the
 * breech and energyPerShot FE and leaves the muzzle at baseSpeed + speedPerRail x (rails - 1) blocks/tick (up to maxSpeed),
 * with no propellant, no failure and almost no spread. Fires on a rising fire-side redstone edge or from Lua.
 */
public class MountedRailgunContraption extends AbstractLineCannonContraption implements GetItemStorage {
    public MountedRailgunContraption() {
    }

    @Override
    protected boolean isPart(Block block) {
        return block instanceof RailgunBlock;
    }

    @Override
    protected boolean isBack(Block block) {
        return block instanceof RailgunBreechBlock;
    }

    @Override
    protected int maxParts() {
        return AshCCConfig.RAILGUN_MAX_RAILS.get();
    }

    @Override
    protected String errorKey() {
        return "railgun";
    }

    @Override
    public @Nullable IEnergyStorage energyStorage() {
        RailgunBreechBlockEntity b = breech();
        return b == null ? null : b.energy();
    }

    public @Nullable RailgunBreechBlockEntity breech() {
        return presentBlockEntities.get(startPos) instanceof RailgunBreechBlockEntity b ? b : null;
    }

    public int rails() {
        return parts();
    }

    public double muzzleSpeed() {
        return Math.min(AshCCConfig.RAILGUN_BASE_SPEED.get() + AshCCConfig.RAILGUN_SPEED_PER_RAIL.get() * Math.max(0, rails() - 1),
            AshCCConfig.RAILGUN_MAX_SPEED.get());
    }

    @Nullable CannonBallistics.Shot nextShot(Level level) {
        RailgunBreechBlockEntity b = breech();
        if (b == null || b.ammo().isEmpty()) return null;
        AbstractBigCannonProjectile p = CannonBallistics.railgunProjectile(level, b.ammo());
        if (p == null) return null;
        double muzzle = along(muzzlePos());
        return CannonBallistics.railgunShot(level, p, muzzleSpeed(), muzzle, CannonBallistics.itemName(b.ammo()));
    }

    @Override
    public void tick(Level level, PitchOrientedContraptionEntity entity) {
        super.tick(level, entity);
        RailgunBreechBlockEntity b = breech();
        if (!level.isClientSide && b != null) b.tickCooldown();
    }

    @Override
    public void onRedstoneUpdate(ServerLevel level, PitchOrientedContraptionEntity entity, boolean togglePower, int firePower, ControlPitchContraption controller) {
        if (togglePower && firePower > 0) fireShot(level, entity);
    }

    @Override
    public void fireShot(ServerLevel level, PitchOrientedContraptionEntity entity) {
        tryFire(level, entity);
    }

    /** Fires one round; returns null, or why it could not fire. */
    @Nullable String tryFire(ServerLevel level, PitchOrientedContraptionEntity entity) {
        RailgunBreechBlockEntity breech = breech();
        if (breech == null) return "no breech";
        if (breech.cooldown() > 0) return "cooling down";
        if (breech.ammo().isEmpty()) return "no ammo";
        int cost = AshCCConfig.RAILGUN_ENERGY_PER_SHOT.get();
        if (breech.energy().getEnergyStored() < cost) return "not enough energy";
        AbstractBigCannonProjectile projectile = CannonBallistics.railgunProjectile(level, breech.ammo());
        if (projectile == null) return "not railgun ammo";
        breech.takeShot(cost);

        double speed = muzzleSpeed();
        Vec3 centerPos = entity.toGlobalVector(Vec3.atCenterOf(BlockPos.ZERO), 0.0F);
        Vec3 spawnPos = entity.toGlobalVector(Vec3.atCenterOf(muzzlePos()), 0.0F);
        Vec3 breechPos = entity.toGlobalVector(Vec3.atCenterOf(startPos), 0.0F);
        Vec3 vec = spawnPos.subtract(breechPos).normalize();

        projectile.setPos(spawnPos);
        projectile.setChargePower((float) speed);
        projectile.shoot(vec.x, vec.y, vec.z, (float) speed, AshCCConfig.RAILGUN_SPREAD.get().floatValue());
        projectile.addUntouchableEntity(entity, 1);
        level.addFreshEntity(projectile);
        projectile.xRotO = projectile.getXRot();
        projectile.yRotO = projectile.getYRot();

        ControlPitchContraption controller = entity.getController();
        if (controller != null) controller.onRecoil(vec.scale(-AshCCConfig.RAILGUN_RECOIL.get()), centerPos, entity);

        // Effects in world space (the contraption may sit in a ship's plot grid)
        Vec3 worldMuzzle = CBCCompatTransformers.transformVec3(level, spawnPos, entity.position());
        Vec3 worldBreech = CBCCompatTransformers.transformVec3(level, breechPos, entity.position());
        Vec3 plumeDir = CBCCompatTransformers.transformLocationNormal(level, entity.blockPosition(), vec);
        BigCannonPlumeParticleData plume = new BigCannonPlumeParticleData(1.0f, (float) speed * 0.5f, 10);
        for (ServerPlayer player : level.players()) {
            level.sendParticles(player, plume, true, worldMuzzle.x, worldMuzzle.y, worldMuzzle.z, 0, plumeDir.x, plumeDir.y, plumeDir.z, 1.0);
        }
        int sparks = rails() * 4;
        for (int i = 0; i <= sparks; i++) {
            Vec3 p = worldBreech.lerp(worldMuzzle, i / (double) Math.max(1, sparks));
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, 2, 0.25, 0.25, 0.25, 0.2);
        }
        // The rails' discharge is heard at once; the muzzle boom travels with CBC's blast wave (RailgunFx)
        level.playSound(null, worldBreech.x, worldBreech.y, worldBreech.z, SoundEvents.TRIDENT_THUNDER.value(), SoundSource.BLOCKS, 3.0f, 1.6f);
        RailgunFx.send(level, projectile.getId(), entity.getId(), worldMuzzle, plumeDir.normalize(), speed, rails());
        return null;
    }

    @Override
    public ICannonContraptionType getCannonType() {
        return CbcCompat.RAILGUN_TYPE;
    }

    @Override
    public ContraptionType getType() {
        return CbcCompat.MOUNTED_RAILGUN.get();
    }

    @Override
    public @Nullable IItemHandler getItemStorage() {
        RailgunBreechBlockEntity b = breech();
        return b == null ? null : b.items();
    }

    // ---- Energy through the mount ----

    private static final Map<BlockEntity, IEnergyStorage> MOUNT_ENERGY = new WeakHashMap<>();

    /**
     * Energy storage of a CBC mount: forwards to the railgun or laser cannon on it, accepting nothing while another cannon (or none) is mounted.
     * The same object is always returned so cables that cache it keep working across reassembly.
     */
    static IEnergyStorage energyOf(BlockEntity mount) {
        return MOUNT_ENERGY.computeIfAbsent(mount, m -> new IEnergyStorage() {
            private @Nullable IEnergyStorage target() {
                return Mounts.cannon(m) instanceof AbstractLineCannonContraption c ? c.energyStorage() : null;
            }

            @Override
            public int receiveEnergy(int max, boolean simulate) {
                IEnergyStorage t = target();
                return t == null ? 0 : t.receiveEnergy(max, simulate);
            }

            @Override
            public int extractEnergy(int max, boolean simulate) {
                return 0;
            }

            @Override
            public int getEnergyStored() {
                IEnergyStorage t = target();
                return t == null ? 0 : t.getEnergyStored();
            }

            @Override
            public int getMaxEnergyStored() {
                IEnergyStorage t = target();
                return t == null ? 0 : t.getMaxEnergyStored();
            }

            @Override
            public boolean canExtract() {
                return false;
            }

            @Override
            public boolean canReceive() {
                return target() != null;
            }
        });
    }
}
