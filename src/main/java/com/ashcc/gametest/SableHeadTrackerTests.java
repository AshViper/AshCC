package com.ashcc.gametest;

import com.ashcc.AshCC;
import com.ashcc.link.LinkHubBlockEntity;
import com.ashcc.tracker.HeadTrackerPeripheral;
import com.ashcc.tracker.TrackedPlayer;
import dan200.computercraft.api.lua.LuaException;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Head tracker of a player sitting on a turned Sable ship (own batch: ships outlive the area reset). */
@GameTestHolder(AshCC.MOD_ID)
@PrefixGameTestTemplate(false)
public class SableHeadTrackerTests {

    @GameTest(template = "empty_17", timeoutTicks = 100, batch = "tracker")
    public static void seatedOnTurnedShip(GameTestHelper helper) {
        if (!AshCC.sableLoaded) {
            helper.succeed();
            return;
        }
        Impl.seated(helper);
    }

    private static final class Impl {
        /**
         * Ship turned 90 degrees about +Y (its north is world west). A player seated on it with a ship-relative
         * rotation facing north looks west in the world, and north in the ship's frame (the hub is on the ship).
         */
        static void seated(GameTestHelper helper) {
            BlockPos hubPos = new BlockPos(8, 11, 8);
            helper.setBlock(hubPos, AshCC.LINK_HUB.get());
            List<BlockPos> blocks = new ArrayList<>();
            for (int x = 7; x <= 9; x++) for (int z = 7; z <= 9; z++) {
                helper.setBlock(x, 10, z, Blocks.IRON_BLOCK);
                blocks.add(helper.absolutePos(new BlockPos(x, 10, z)));
            }
            blocks.add(helper.absolutePos(hubPos));
            BoundingBox3i bounds = new BoundingBox3i(helper.absolutePos(new BlockPos(7, 10, 7)), helper.absolutePos(new BlockPos(9, 11, 9)));
            ServerSubLevel ship = SubLevelAssemblyHelper.assembleBlocks(helper.getLevel(), helper.absolutePos(new BlockPos(8, 10, 8)), blocks, bounds);
            helper.assertTrue(ship != null, "assembly failed");
            LinkHubBlockEntity hub = findHub(helper, ship);

            RigidBodyHandle handle = RigidBodyHandle.of(ship);
            helper.assertTrue(handle != null, "no physics handle");
            handle.teleport(new Vector3d(ship.logicalPose().position()), new Quaterniond().rotateY(Math.PI / 2));

            // A "seat" in the ship's plot next to the hub, and the player riding it facing ship-north, level
            ServerPlayer player = HeadTrackerTests.mockPlayer(helper);
            Vec3 seatPos = Vec3.atCenterOf(hub.getBlockPos().east());
            ArmorStand seat = new ArmorStand(helper.getLevel(), seatPos.x, seatPos.y, seatPos.z);
            setVehicle(player, seat);
            helper.assertTrue(player.getVehicle() == seat, "riding");
            player.setXRot(0);
            player.setYRot(180);
            TrackedPlayer tracked = new TrackedPlayer(player.getUUID(), player.getGameProfile().getName());
            HeadTrackerPeripheral t = new HeadTrackerPeripheral(hub, tracked);

            helper.runAfterDelay(2, () -> {
                try {
                    Object[] a = t.getAngles().getResult();
                    helper.assertTrue(near(a[0], -90) && near(a[1], 0), "world angles should be west: " + a[0] + ", " + a[1]);
                    Object[] l = t.getLocalAngles().getResult();
                    helper.assertTrue(near(l[0], 0) && near(l[1], 0), "local angles should be ship-north: " + l[0] + ", " + l[1]);
                } catch (LuaException e) {
                    helper.fail(e.getMessage());
                }
                setVehicle(player, null);
                HeadTrackerTests.logout(helper, player);
                SubLevelContainer container = SubLevelContainer.getContainer(helper.getLevel());
                if (container != null && !ship.isRemoved()) container.removeSubLevel(ship, SubLevelRemovalReason.REMOVED);
                helper.succeed();
            });
        }

        /** ServerPlayer.startRiding sends packets, which the mock player has no connection for. */
        static void setVehicle(Entity passenger, @Nullable Entity vehicle) {
            try {
                Field f = Entity.class.getDeclaredField("vehicle");
                f.setAccessible(true);
                f.set(passenger, vehicle);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        static boolean near(Object value, double expected) {
            return Math.abs(((Number) value).doubleValue() - expected) < 0.5;
        }

        static LinkHubBlockEntity findHub(GameTestHelper helper, ServerSubLevel ship) {
            LevelPlot plot = ship.getPlot();
            for (int cx = plot.getChunkMin().x; cx <= plot.getChunkMax().x; cx++) {
                for (int cz = plot.getChunkMin().z; cz <= plot.getChunkMax().z; cz++) {
                    for (BlockEntity be : helper.getLevel().getChunk(cx, cz).getBlockEntities().values()) {
                        if (be instanceof LinkHubBlockEntity h) return h;
                    }
                }
            }
            throw new AssertionError("link hub not found in plot");
        }
    }
}
