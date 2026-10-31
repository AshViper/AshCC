package com.ashcc.compat.cbc;

import com.ashcc.AshCCConfig;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBlock;

import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * What Lua asked a CBC mount to do: an aim it turns towards at a fixed rate (no rotational power needed) and,
 * for autocannons, a held trigger. Lives only in memory: after a reload the mount is back under redstone/kinetic control.
 */
final class MountControl {
    private static final Map<BlockEntity, MountControl> CONTROLS = new WeakHashMap<>();

    final BlockEntity mount;
    boolean aiming;
    double targetYaw;
    double targetPitch;
    double speed = AshCCConfig.CANNON_AIM_SPEED.get();
    /** CBC fire rate 1..15 while the trigger is held from Lua, 0 when released. */
    int fireRate;
    private boolean releaseTrigger;

    private MountControl(BlockEntity mount) {
        this.mount = mount;
    }

    static MountControl of(BlockEntity mount) {
        return CONTROLS.computeIfAbsent(mount, MountControl::new);
    }

    static @Nullable MountControl get(BlockEntity mount) {
        return CONTROLS.get(mount);
    }

    static void tickAll() {
        for (Iterator<MountControl> it = CONTROLS.values().iterator(); it.hasNext(); ) {
            MountControl c = it.next();
            if (c.mount.isRemoved() || c.mount.getLevel() == null) {
                it.remove();
                continue;
            }
            c.tick();
        }
    }

    void setTrigger(int rate) {
        if (rate <= 0 && fireRate > 0) releaseTrigger = true;
        fireRate = Mth.clamp(rate, 0, 15);
    }

    boolean aimed() {
        PitchOrientedContraptionEntity e = Mounts.contraption(mount);
        if (e == null || !aiming) return false;
        return Math.abs(Mth.wrapDegrees(Mounts.yaw(e) - targetYaw)) < 0.01 && Math.abs(Mounts.pitch(e) - clampPitch(e, targetPitch)) < 0.01;
    }

    static double clampPitch(PitchOrientedContraptionEntity e, double pitch) {
        return Mth.clamp(pitch, -Mounts.maxDepression(e), Mounts.maxElevation(e));
    }

    private void tick() {
        PitchOrientedContraptionEntity e = Mounts.contraption(mount);
        if (e == null) {
            aiming = false;
            fireRate = 0;
            releaseTrigger = false;
            return;
        }
        if (aiming && mount instanceof CannonMountBlockEntity m && e.canBeTurnedByController(m)) {
            double step = speed / 20.0;
            double yaw = Mounts.yaw(e);
            double pitch = Mounts.pitch(e);
            double dy = Mth.wrapDegrees(targetYaw - yaw);
            double dp = clampPitch(e, targetPitch) - pitch;
            if (Math.abs(dy) > 1e-4 || Math.abs(dp) > 1e-4) {
                yaw += Mth.clamp(dy, -step, step);
                pitch += Mth.clamp(dp, -step, step);
                Mounts.setAngles(m, e, yaw, pitch);
                m.sendData();
            }
        }
        // CBC sets an autocannon's fire rate from the fire-side redstone on every block update, so a held trigger is reapplied
        if ((fireRate > 0 || releaseTrigger) && e.getContraption() instanceof AbstractMountedCannonContraption c) {
            AbstractAutocannonBreechBlockEntity breech = Mounts.autocannonBreech(c);
            if (breech != null && breech.getFireRate() != fireRate) {
                breech.setFireRate(fireRate);
                StructureBlockInfo info = c.getBlocks().get(c.getStartPos());
                if (info != null) BigCannonBlock.writeAndSyncSingleBlockData(breech, info, e, c);
            }
            releaseTrigger = false;
        }
    }
}
