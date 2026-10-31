package com.ashcc.compat.cbc;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlock;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedBigCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannon_control.fixed_cannon_mount.FixedCannonMountBlock;
import rbasamoyai.createbigcannons.cannon_control.fixed_cannon_mount.FixedCannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity;

/**
 * The two CBC mount block entities behind one set of helpers. Angles use AshCC's convention in the mount's block
 * frame (the ship's on a Sable sub-level): yaw 0 = north, positive towards east (-180..180), pitch positive up.
 * CBC's own yaw is Minecraft's (0 = south, 180 = north), so ours is CBC's minus 180.
 */
final class Mounts {
    private Mounts() {
    }

    static @Nullable PitchOrientedContraptionEntity contraption(BlockEntity mount) {
        PitchOrientedContraptionEntity e = null;
        if (mount instanceof CannonMountBlockEntity m) e = m.getContraption();
        else if (mount instanceof FixedCannonMountBlockEntity m) e = m.getContraption();
        return e != null && e.isAlive() ? e : null;
    }

    static @Nullable AbstractMountedCannonContraption cannon(BlockEntity mount) {
        PitchOrientedContraptionEntity e = contraption(mount);
        return e != null && e.getContraption() instanceof AbstractMountedCannonContraption c ? c : null;
    }

    /** "big_cannon", "drop_mortar", "autocannon", "rotary_autocannon", "railgun", "laser_cannon", or the CBC type id for others. */
    static String typeName(AbstractMountedCannonContraption c) {
        if (c instanceof MountedRailgunContraption) return "railgun";
        if (c instanceof MountedLaserContraption) return "laser_cannon";
        if (c instanceof MountedAutocannonContraption) return autocannonBreech(c) instanceof RotaryBreechBlockEntity ? "rotary_autocannon" : "autocannon";
        if (c instanceof MountedBigCannonContraption big) return big.isDropMortar() ? "drop_mortar" : "big_cannon";
        return c.getCannonType().getId().toString();
    }

    static @Nullable AbstractAutocannonBreechBlockEntity autocannonBreech(AbstractMountedCannonContraption c) {
        return c instanceof MountedAutocannonContraption && c.presentBlockEntities.get(c.getStartPos()) instanceof AbstractAutocannonBreechBlockEntity b ? b : null;
    }

    static boolean canAim(BlockEntity mount) {
        PitchOrientedContraptionEntity e = contraption(mount);
        return mount instanceof CannonMountBlockEntity m && e != null && e.canBeTurnedByController(m);
    }

    /** -1 or 1: CBC flips the contraption's pitch for cannons assembled facing south or west. */
    static float pitchSign(PitchOrientedContraptionEntity e) {
        Direction dir = e.getInitialOrientation();
        boolean flag = dir.getAxisDirection() == Direction.AxisDirection.POSITIVE == (dir.getAxis() == Direction.Axis.X);
        return flag ? 1.0f : -1.0f;
    }

    static double yaw(PitchOrientedContraptionEntity e) {
        return Mth.wrapDegrees(e.yaw - 180.0);
    }

    static double pitch(PitchOrientedContraptionEntity e) {
        return e.pitch * pitchSign(e);
    }

    static double maxElevation(PitchOrientedContraptionEntity e) {
        return e.maximumElevation();
    }

    static double maxDepression(PitchOrientedContraptionEntity e) {
        return e.maximumDepression();
    }

    /** Turns a CBC cannon mount right now (both the block entity's angles and the contraption entity's). */
    static void setAngles(CannonMountBlockEntity mount, PitchOrientedContraptionEntity e, double yaw, double pitch) {
        float cbcYaw = (float) Mth.wrapDegrees(yaw + 180.0);
        mount.setYaw(cbcYaw);
        mount.setPitch((float) pitch);
        e.yaw = cbcYaw;
        e.pitch = (float) pitch * pitchSign(e);
    }

    private static BooleanProperty assemblyProperty(BlockEntity mount) {
        return mount instanceof CannonMountBlockEntity ? CannonMountBlock.ASSEMBLY_POWERED : FixedCannonMountBlock.ASSEMBLY_POWERED;
    }

    private static BooleanProperty fireProperty(BlockEntity mount) {
        return mount instanceof CannonMountBlockEntity ? CannonMountBlock.FIRE_POWERED : FixedCannonMountBlock.FIRE_POWERED;
    }

    /**
     * Assembles the cannon in front of the mount as a rising redstone edge would, then puts the block's
     * remembered assembly signal back so the next redstone update does not take it apart again.
     * Returns null on success or the assembly error.
     */
    static @Nullable String assemble(BlockEntity mount) {
        Level level = mount.getLevel();
        if (level == null) return "Mount is not loaded";
        BlockState before = mount.getBlockState();
        BooleanProperty assembly = assemblyProperty(mount);
        boolean fire = before.getValue(fireProperty(mount));
        if (mount instanceof CannonMountBlockEntity m) {
            m.onRedstoneUpdate(true, false, fire, fire, 0);
        } else if (mount instanceof FixedCannonMountBlockEntity m) {
            m.onRedstoneUpdate(true, false, fire, fire, 0);
        }
        BlockState after = level.getBlockState(mount.getBlockPos());
        if (after.hasProperty(assembly) && after.getValue(assembly) != before.getValue(assembly)) {
            level.setBlock(mount.getBlockPos(), after.setValue(assembly, before.getValue(assembly)), 2);
        }
        if (contraption(mount) != null) return null;
        var e = mount instanceof CannonMountBlockEntity m ? m.getLastAssemblyException()
            : mount instanceof FixedCannonMountBlockEntity m ? m.getLastAssemblyException() : null;
        return e != null ? e.component.getString() : "Nothing to assemble";
    }

    static void disassemble(BlockEntity mount) {
        if (mount instanceof CannonMountBlockEntity m) {
            m.disassemble();
            m.sendData();
        } else if (mount instanceof FixedCannonMountBlockEntity m) {
            m.disassemble();
            m.sendData();
        }
    }
}
