package com.ashcc.laser;

import com.ashcc.AshCC;
import com.ashcc.AshCCConfig;
import com.ashcc.compat.SableCompat;
import com.ashcc.radar.RadarFrame;
import dan200.computercraft.api.peripheral.AttachedComputerSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Laser state and beam. The block has no front: the beam leaves it in any direction given by yaw/pitch in block
 * coordinates (yaw 0 = north, positive towards east; pitch positive up) and is traced in world space every firing tick.
 * On a Sable sub-level it turns with the ship, hits blocks of other ships (Sable's clip includes them),
 * pushes the ship it hits and recoils the ship it is mounted on.
 */
public class LaserBlockEntity extends BlockEntity {
    public static final ResourceKey<DamageType> DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE,
        ResourceLocation.fromNamespaceAndPath(AshCC.MOD_ID, "laser"));

    final AttachedComputerSet computers = new AttachedComputerSet();
    private @Nullable LaserPeripheral peripheral;

    // Controls
    private double targetYaw;
    private double targetPitch;
    private boolean redstoneControl = true;
    private int redstoneInput;
    /** Computer control: remaining firing ticks, -1 = until stopped, 0 = off. */
    private int fireTicks;

    // State
    private double yaw;
    private double pitch;
    private double heat;
    private boolean overheated;
    private boolean firing;
    private @Nullable LaserHit lastHit;
    private final LaserBeam beam;

    // Client view: beam length from the block centre, sent with the update packet
    private double beamLength;
    private double sentYaw = Double.NaN;
    private double sentPitch = Double.NaN;
    private double sentLength = Double.NaN;
    private boolean sentFiring;

    public LaserBlockEntity(BlockPos pos, BlockState state) {
        super(AshCC.LASER_BE.get(), pos, state);
        beam = new LaserBeam(-1 - (pos.hashCode() & 0x3fffffff));
    }

    public LaserPeripheral peripheral() {
        if (peripheral == null) peripheral = new LaserPeripheral(this);
        return peripheral;
    }

    // ---- Controls ----

    /** Starts firing for {@code ticks} (-1 = until stopped) and switches to computer control. False when overheated. */
    public boolean fire(int ticks) {
        redstoneControl = false;
        fireTicks = ticks;
        setChanged();
        return !overheated;
    }

    public void stopFiring() {
        redstoneControl = false;
        fireTicks = 0;
        setChanged();
    }

    /** Sets the aim: yaw is wrapped to -180..180, pitch clamped to -90..90. Returns the resulting {yaw, pitch}. */
    public double[] setAim(double yawDeg, double pitchDeg) {
        targetYaw = Mth.wrapDegrees(yawDeg);
        targetPitch = Mth.clamp(pitchDeg, -90, 90);
        setChanged();
        return new double[]{targetYaw, targetPitch};
    }

    /** Sets the aim and turns there at once (tests and scripted scenes). */
    public void aimNow(double yawDeg, double pitchDeg) {
        setAim(yawDeg, pitchDeg);
        yaw = targetYaw;
        pitch = targetPitch;
    }

    public void setRedstoneControl(boolean enabled) {
        redstoneControl = enabled;
        setChanged();
    }

    public double yaw() {
        return yaw;
    }

    public double pitch() {
        return pitch;
    }

    public double targetYaw() {
        return targetYaw;
    }

    public double targetPitch() {
        return targetPitch;
    }

    public boolean redstoneControl() {
        return redstoneControl;
    }

    public int redstoneInput() {
        return redstoneInput;
    }

    public double heat() {
        return heat;
    }

    /** For tests: start from a given heat (0..1). */
    public void setHeat(double h) {
        heat = Mth.clamp(h, 0, 1);
    }

    public boolean overheated() {
        return overheated;
    }

    public boolean firing() {
        return firing;
    }

    public @Nullable LaserHit lastHit() {
        return firing ? lastHit : null;
    }

    public int brokenCount() {
        return beam.brokenCount();
    }

    public @Nullable BlockPos lastBroken() {
        return beam.lastBroken();
    }

    public double beamLength() {
        return beamLength;
    }

    private boolean wantsFire() {
        return redstoneControl ? redstoneInput > 0 : fireTicks != 0;
    }

    // ---- Geometry ----

    /**
     * Beam direction for the given aim in block coordinates (the ship's own frame when on a sub-level):
     * yaw 0 = north (-Z), 90 = east (+X); pitch 90 = up.
     */
    public static Vec3 blockDirection(double yawDeg, double pitchDeg) {
        double y = Math.toRadians(yawDeg);
        double p = Math.toRadians(pitchDeg);
        return new Vec3(Math.sin(y) * Math.cos(p), Math.sin(p), -Math.cos(y) * Math.cos(p));
    }

    /** Distance from the block centre to where a beam in direction {@code d} leaves the block. */
    public static double muzzleOffset(Vec3 d) {
        return 0.5 / Math.max(Math.abs(d.x), Math.max(Math.abs(d.y), Math.abs(d.z))) + 0.02;
    }

    /** Aim frame in world space: forward = block north, right = block east, up = block up (rotates with the ship). */
    public RadarFrame frame() {
        return RadarFrame.of(level, worldPosition, Direction.NORTH, Direction.UP);
    }

    public Vec3 worldDirection(double yawDeg, double pitchDeg) {
        Vec3 d = blockDirection(yawDeg, pitchDeg);
        if (AshCC.sableLoaded && level != null) d = SableCompat.dirToWorld(level, worldPosition, d);
        return d.normalize();
    }

    /** World position where the current beam starts. */
    public Vec3 muzzle() {
        Vec3 d = blockDirection(yaw, pitch);
        Vec3 p = Vec3.atCenterOf(worldPosition).add(d.scale(muzzleOffset(d)));
        return AshCC.sableLoaded && level != null ? SableCompat.pointToWorld(level, worldPosition, p) : p;
    }

    public @Nullable UUID subLevel() {
        return AshCC.sableLoaded && level != null ? SableCompat.subLevelAt(level, worldPosition) : null;
    }

    /**
     * Traces the beam for the current aim: blocks (including other ships' blocks through Sable), then the first
     * entity before that block. Nothing is damaged.
     */
    public LaserHit trace() {
        return LaserBeam.trace(Objects.requireNonNull(level), muzzle(), worldDirection(yaw, pitch), AshCCConfig.LASER_RANGE.get(), e -> false);
    }

    // ---- Tick ----

    public static void serverTick(Level level, BlockPos pos, BlockState state, LaserBlockEntity be) {
        be.tick((ServerLevel) level, state);
    }

    private void tick(ServerLevel level, BlockState state) {
        if (redstoneControl) redstoneInput = level.getBestNeighborSignal(worldPosition);

        boolean slewing = yaw != targetYaw || pitch != targetPitch;
        if (slewing) slew(Math.toRadians(AshCCConfig.LASER_AIM_SPEED.get() / 20.0));

        boolean want = wantsFire();
        double maxFire = AshCCConfig.LASER_MAX_FIRE_SECONDS.get();
        if (want && !overheated) {
            if (maxFire > 0) heat += 1.0 / (maxFire * 20);
            if (heat >= 1) {
                heat = 1;
                overheated = true;
                computers.forEach(c -> c.queueEvent("laser_overheat", c.getAttachmentName()));
            }
        } else if (heat > 0) {
            heat = Math.max(0, heat - 1.0 / (AshCCConfig.LASER_COOLDOWN_SECONDS.get() * 20));
            if (heat == 0 && overheated) {
                overheated = false;
                computers.forEach(c -> c.queueEvent("laser_ready", c.getAttachmentName()));
            }
        }
        if (!redstoneControl && fireTicks > 0) fireTicks--;

        boolean nowFiring = want && !overheated;
        if (nowFiring != firing) {
            Vec3 m = muzzle();
            level.playSound(null, m.x, m.y, m.z, nowFiring ? SoundEvents.BEACON_ACTIVATE : SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 1.0f, 1.6f);
        }
        firing = nowFiring;
        if (firing) {
            fireBeam(level);
        } else {
            lastHit = null;
            beamLength = 0;
            beam.reset(level);
        }

        if (state.getValue(LaserBlock.ACTIVE) != firing) {
            level.setBlock(worldPosition, state.setValue(LaserBlock.ACTIVE, firing), Block.UPDATE_CLIENTS);
        }
        if (firing != sentFiring || Math.abs(yaw - sentYaw) > 0.05 || Math.abs(pitch - sentPitch) > 0.05
            || (firing && Math.abs(beamLength - sentLength) > 0.05) || Double.isNaN(sentYaw)) {
            sentFiring = firing;
            sentYaw = yaw;
            sentPitch = pitch;
            sentLength = beamLength;
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
        if (slewing || firing || heat > 0) setChanged();
    }

    private static LaserBeam.Power power() {
        return new LaserBeam.Power(AshCCConfig.LASER_BLOCK_POWER.get(), AshCCConfig.LASER_BREAK_BLOCKS.get(), AshCCConfig.LASER_DROP_BLOCKS.get(),
            AshCCConfig.LASER_DAMAGE_OWN_SUB_LEVEL.get(), AshCCConfig.LASER_ENTITY_DAMAGE.get().floatValue(), AshCCConfig.LASER_IGNITE_SECONDS.get(),
            AshCCConfig.LASER_IMPACT_FORCE.get(), AshCCConfig.LASER_RECOIL.get());
    }

    private void fireBeam(ServerLevel level) {
        LaserHit hit = trace();
        lastHit = hit;
        beamLength = muzzleOffset(blockDirection(yaw, pitch)) + hit.distance();
        LaserBeam.Broken broken = beam.apply(level, hit, power(), subLevel(), worldPosition);
        if (broken != null) {
            String sub = broken.subLevel() == null ? null : broken.subLevel().toString();
            Vec3 at = broken.at();
            computers.forEach(c -> c.queueEvent("laser_break", c.getAttachmentName(), broken.block(), at.x, at.y, at.z, sub));
        }
        if (level.getGameTime() % 40 == 0) {
            Vec3 m = hit.from();
            level.playSound(null, m.x, m.y, m.z, SoundEvents.BEACON_AMBIENT, SoundSource.BLOCKS, 0.8f, 1.8f);
        }
    }

    /**
     * Turns the beam towards the target along the shortest arc, at most {@code step} radians. Turning yaw and pitch
     * separately would sweep the beam sideways near straight up/down, where a large yaw change is a small turn.
     */
    private void slew(double step) {
        Vec3 cur = blockDirection(yaw, pitch);
        Vec3 tgt = blockDirection(targetYaw, targetPitch);
        double angle = Math.acos(Mth.clamp(cur.dot(tgt), -1, 1));
        if (angle <= step) {
            yaw = targetYaw;
            pitch = targetPitch;
            return;
        }
        Vec3 next;
        if (angle > Math.PI - 1e-6) {
            // Opposite directions: turn about any axis perpendicular to the beam
            Vec3 axis = cur.cross(Math.abs(cur.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize();
            next = cur.scale(Math.cos(step)).add(axis.cross(cur).scale(Math.sin(step)));
        } else {
            double sin = Math.sin(angle);
            next = cur.scale(Math.sin(angle - step) / sin).add(tgt.scale(Math.sin(step) / sin));
        }
        next = next.normalize();
        pitch = Math.toDegrees(Math.asin(Mth.clamp(next.y, -1, 1)));
        // Keep the old yaw when passing straight up/down, where yaw is undefined
        if (next.x * next.x + next.z * next.z > 1e-12) yaw = Math.toDegrees(Math.atan2(next.x, -next.z));
    }

    /** 0..1 progress of burning through the current block. */
    public double burnFraction() {
        return level == null ? 0 : beam.burnFraction(level);
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) beam.reset(level);
        super.setRemoved();
    }

    // ---- Save / sync ----

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putDouble("TargetYaw", targetYaw);
        tag.putDouble("TargetPitch", targetPitch);
        tag.putDouble("Yaw", yaw);
        tag.putDouble("Pitch", pitch);
        tag.putBoolean("RedstoneControl", redstoneControl);
        tag.putInt("FireTicks", fireTicks);
        tag.putDouble("Heat", heat);
        tag.putBoolean("Overheated", overheated);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        targetYaw = tag.getDouble("TargetYaw");
        targetPitch = tag.getDouble("TargetPitch");
        yaw = tag.getDouble("Yaw");
        pitch = tag.getDouble("Pitch");
        redstoneControl = !tag.contains("RedstoneControl") || tag.getBoolean("RedstoneControl");
        fireTicks = tag.getInt("FireTicks");
        heat = tag.getDouble("Heat");
        overheated = tag.getBoolean("Overheated");
        // Only present in the client update packet
        if (tag.contains("Firing")) {
            firing = tag.getBoolean("Firing");
            beamLength = tag.getDouble("Length");
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("Yaw", yaw);
        tag.putDouble("Pitch", pitch);
        tag.putBoolean("Firing", firing);
        tag.putDouble("Length", beamLength);
        return tag;
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
