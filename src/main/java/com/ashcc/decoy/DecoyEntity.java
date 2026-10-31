package com.ashcc.decoy;

import com.ashcc.AshCCConfig;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * A flare (seen by seekers) or a chaff cloud (seen by radars as a ship). Short-lived and never saved.
 * Invisible to entity scans and lasers; drawn with particles only.
 */
public class DecoyEntity extends Entity {
    public static final byte FLARE = 0;
    public static final byte CHAFF = 1;

    private static final EntityDataAccessor<Byte> KIND = SynchedEntityData.defineId(DecoyEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> LIFETIME = SynchedEntityData.defineId(DecoyEntity.class, EntityDataSerializers.INT);

    /** What the echo looks like on a radar (size, mass, orientation of the ship that launched it). Server only. */
    private Map<String, Object> echo = Map.of();

    public DecoyEntity(EntityType<? extends DecoyEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(KIND, FLARE);
        builder.define(LIFETIME, 100);
    }

    public void setup(byte kind, int lifetime, Map<String, Object> echo) {
        entityData.set(KIND, kind);
        entityData.set(LIFETIME, Math.max(1, lifetime));
        this.echo = echo;
    }

    public byte kind() {
        return entityData.get(KIND);
    }

    public boolean isFlare() {
        return kind() == FLARE;
    }

    public int lifetime() {
        return entityData.get(LIFETIME);
    }

    /** 1 when launched, falling to 0 when it burns out. */
    public double heat() {
        return Math.max(0, 1 - (double) tickCount / lifetime());
    }

    public Map<String, Object> echo() {
        return echo;
    }

    @Override
    public void tick() {
        super.tick();
        boolean flare = isFlare();
        Vec3 v = getDeltaMovement();
        // Gravity in blocks/s^2 -> blocks/tick per tick
        double g = (flare ? AshCCConfig.DECOY_FLARE_GRAVITY.get() : AshCCConfig.DECOY_CHAFF_GRAVITY.get()) / 400.0;
        double drag = flare ? AshCCConfig.DECOY_FLARE_DRAG.get() : AshCCConfig.DECOY_CHAFF_DRAG.get();
        v = v.scale(drag).add(0, -g, 0);
        setDeltaMovement(v);
        move(MoverType.SELF, v);
        if (onGround()) setDeltaMovement(getDeltaMovement().multiply(0.5, 0, 0.5));

        if (level().isClientSide) {
            particles(flare);
        } else if (tickCount >= lifetime()) {
            discard();
        }
    }

    private void particles(boolean flare) {
        Level level = level();
        if (flare) {
            level.addParticle(ParticleTypes.FLAME, getX(), getY(), getZ(), 0, 0, 0);
            level.addParticle(ParticleTypes.SMOKE, xo, yo, zo, 0, 0.02, 0);
            if (random.nextInt(3) == 0) level.addParticle(ParticleTypes.END_ROD, getX(), getY(), getZ(), 0, 0, 0);
        } else {
            for (int i = 0; i < 3; i++) {
                double r = 0.4 + 1.6 * Math.min(1, tickCount / 20.0); // the cloud blooms over the first second
                level.addParticle(random.nextBoolean() ? ParticleTypes.WHITE_ASH : ParticleTypes.ELECTRIC_SPARK,
                    getX() + (random.nextDouble() - 0.5) * r * 2, getY() + (random.nextDouble() - 0.5) * r * 2,
                    getZ() + (random.nextDouble() - 0.5) * r * 2, 0, -0.01, 0);
            }
        }
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
