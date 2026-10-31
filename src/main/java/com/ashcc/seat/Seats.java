package com.ashcc.seat;

import com.ashcc.AshCC;
import com.ashcc.panel.ControlPanelBlock;
import com.ashcc.panel.ControlPanelBlockEntity;
import com.ashcc.panel.PanelFrame;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Sitting on seat blocks and control panels (the cockpit). All positions are in the block's own coordinates — on a
 * Sable ship that is the plot grid, so a seated player travels with the ship like anyone standing on it.
 */
public final class Seats {
    private Seats() {
    }

    /** Where the seat entity sits for the block at {@code pos}, or null when that block cannot be sat on. */
    public static @Nullable Vec3 anchor(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof SeatBlock) {
            return Vec3.atLowerCornerOf(pos).add(0.5, 0.5, 0.5);
        }
        if (state.getBlock() instanceof ControlPanelBlock && level.getBlockEntity(pos) instanceof ControlPanelBlockEntity be) {
            PanelFrame f = be.frame();
            if (state.getValue(ControlPanelBlock.FACE) == AttachFace.FLOOR) {
                // A console: the pilot sits on the near side, at chair height
                Vec3 back = new Vec3(f.up().x, 0, f.up().z);
                if (back.lengthSqr() < 1e-6) back = new Vec3(0, 0, 1);
                else back = back.normalize();
                return new Vec3(pos.getX() + 0.5 - back.x * 0.92, pos.getY() + 0.45, pos.getZ() + 0.5 - back.z * 0.92);
            }
            Vec3 p = f.point(0.5, 0.40).add(f.normal().scale(0.80));
            return p.add(Vec3.atLowerCornerOf(pos));
        }
        return null;
    }

    /** Where a rider of the block at {@code pos} ends up when getting off: a step further out from the block. */
    public static @Nullable Vec3 dismount(Level level, BlockPos pos) {
        Vec3 a = anchor(level, pos);
        if (a == null) return null;
        Vec3 centre = Vec3.atCenterOf(pos);
        Vec3 away = new Vec3(a.x - centre.x, 0, a.z - centre.z);
        away = away.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : away.normalize();
        return new Vec3(a.x + away.x * 0.5, a.y, a.z + away.z * 0.5);
    }

    /** The way a seated player faces, in world space: out of the seat block's front, into the panel for a console. */
    public static @Nullable Vec3 forward(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Vec3 f;
        if (state.getBlock() instanceof SeatBlock) {
            f = Vec3.atLowerCornerOf(state.getValue(SeatBlock.FACING).getNormal());
        } else if (state.getBlock() instanceof ControlPanelBlock && level.getBlockEntity(pos) instanceof ControlPanelBlockEntity be) {
            PanelFrame frame = be.frame();
            f = Math.abs(frame.normal().y) > 0.5 ? frame.up() : frame.normal().scale(-1);
        } else {
            return null;
        }
        return AshCC.sableLoaded ? com.ashcc.compat.SableCompat.dirToWorld(level, pos, f) : f;
    }

    /** Seats the player on the block at {@code pos} (an existing seat entity is reused). False when taken or not a seat. */
    public static boolean sit(Level level, BlockPos pos, Player player) {
        Vec3 a = anchor(level, pos);
        if (a == null) return false;
        SeatEntity seat = null;
        for (SeatEntity e : level.getEntitiesOfClass(SeatEntity.class, new AABB(pos).inflate(2))) {
            if (pos.equals(e.blockPos())) {
                seat = e;
                break;
            }
        }
        if (seat == null) {
            seat = new SeatEntity(AshCC.SEAT.get(), level);
            seat.setBlock(pos);
            seat.setPos(a.x, a.y, a.z);
            level.addFreshEntity(seat);
        } else if (!seat.getPassengers().isEmpty()) {
            return false;
        }
        if (!player.startRiding(seat, true)) return false;
        Vec3 f = forward(level, pos);
        if (f != null) {
            float yaw = (float) Math.toDegrees(Math.atan2(-f.x, f.z));
            player.setYRot(yaw);
            player.setXRot(0);
            player.yRotO = yaw;
            player.xRotO = 0;
        }
        return true;
    }
}
