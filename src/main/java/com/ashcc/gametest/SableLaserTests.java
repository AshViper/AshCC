package com.ashcc.gametest;

import com.ashcc.AshCC;
import com.ashcc.AshCCConfig;
import com.ashcc.laser.LaserBlockEntity;
import com.ashcc.laser.LaserPeripheral;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.ObjectArguments;
import dev.ryanhcode.sable.Sable;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Laser against and on Sable ships. Pushes are measured against an identical control ship falling next to it
 * (without players Sable only moves ships vertically, so all beams here are vertical).
 * Own batch; lasers stop and ships are removed when a test passes, because the next batch reuses the same test
 * areas and Sable ships outlive the area reset.
 */
@GameTestHolder(AshCC.MOD_ID)
@PrefixGameTestTemplate(false)
public class SableLaserTests {

    @GameTest(template = "empty_17", timeoutTicks = 100, batch = "laser")
    public static void carvesShipBlocks(GameTestHelper helper) {
        if (!AshCC.sableLoaded) {
            helper.succeed();
            return;
        }
        Impl.carve(helper);
    }

    @GameTest(template = "empty_17", timeoutTicks = 100, batch = "laser")
    public static void pushesShipItHits(GameTestHelper helper) {
        if (!AshCC.sableLoaded) {
            helper.succeed();
            return;
        }
        Impl.push(helper);
    }

    @GameTest(template = "empty_17", timeoutTicks = 100, batch = "laser")
    public static void recoilsOwnShip(GameTestHelper helper) {
        if (!AshCC.sableLoaded) {
            helper.succeed();
            return;
        }
        Impl.recoil(helper);
    }

    @GameTest(template = "empty_17", timeoutTicks = 100, batch = "laser")
    public static void beamTurnsWithShip(GameTestHelper helper) {
        if (!AshCC.sableLoaded) {
            helper.succeed();
            return;
        }
        Impl.turn(helper);
    }

    private static final class Impl {
        /** 3x3 deck at height y around (cx, cz) with an optional different centre block, assembled into a ship. */
        static ServerSubLevel deck(GameTestHelper helper, int cx, int y, int cz, Block centre, List<BlockPos> extra) {
            List<BlockPos> blocks = new ArrayList<>();
            for (int x = cx - 1; x <= cx + 1; x++) for (int z = cz - 1; z <= cz + 1; z++) {
                helper.setBlock(x, y, z, x == cx && z == cz ? centre : Blocks.IRON_BLOCK);
                blocks.add(helper.absolutePos(new BlockPos(x, y, z)));
            }
            int minY = y;
            int maxY = y;
            for (BlockPos p : extra) {
                blocks.add(helper.absolutePos(p));
                minY = Math.min(minY, p.getY());
                maxY = Math.max(maxY, p.getY());
            }
            BoundingBox3i bounds = new BoundingBox3i(helper.absolutePos(new BlockPos(cx - 1, minY, cz - 1)), helper.absolutePos(new BlockPos(cx + 1, maxY, cz + 1)));
            ServerSubLevel ship = SubLevelAssemblyHelper.assembleBlocks(helper.getLevel(), helper.absolutePos(new BlockPos(cx, y, cz)), blocks, bounds);
            helper.assertTrue(ship != null, "assembly failed");
            return ship;
        }

        static void finish(GameTestHelper helper, ServerSubLevel... ships) {
            SubLevelContainer container = SubLevelContainer.getContainer(helper.getLevel());
            for (ServerSubLevel ship : ships) {
                if (container != null && !ship.isRemoved()) container.removeSubLevel(ship, SubLevelRemovalReason.REMOVED);
            }
            helper.succeed();
        }

        static LaserBlockEntity findLaser(GameTestHelper helper, ServerSubLevel ship) {
            LevelPlot plot = ship.getPlot();
            for (int cx = plot.getChunkMin().x; cx <= plot.getChunkMax().x; cx++) {
                for (int cz = plot.getChunkMin().z; cz <= plot.getChunkMax().z; cz++) {
                    for (BlockEntity be : helper.getLevel().getChunk(cx, cz).getBlockEntities().values()) {
                        if (be instanceof LaserBlockEntity l) return l;
                    }
                }
            }
            throw new AssertionError("laser not found in plot");
        }

        static void fire(GameTestHelper helper, LaserPeripheral p) {
            try {
                p.fire(new ObjectArguments());
            } catch (LuaException e) {
                helper.fail(e.getMessage());
            }
        }

        /**
         * A stone layer hanging under a ship is burnt into by a laser on the ground; the hit names the ship.
         * (3x3 so a ship that settles half a block off is still hit in the stone.)
         */
        static void carve(GameTestHelper helper) {
            BlockPos stone = new BlockPos(8, 9, 8);
            List<BlockPos> layer = new ArrayList<>();
            for (int x = 7; x <= 9; x++) for (int z = 7; z <= 9; z++) {
                helper.setBlock(x, 9, z, Blocks.STONE);
                layer.add(new BlockPos(x, 9, z));
            }
            ServerSubLevel ship = deck(helper, 8, 10, 8, Blocks.IRON_BLOCK, layer);
            LaserPeripheral p = LaserTests.placeLaser(helper, new BlockPos(8, 2, 8), 0, 90).peripheral();
            LaserBlockEntity laser = (LaserBlockEntity) p.getTarget();
            String id = ship.getUniqueId().toString();
            helper.runAfterDelay(2, () -> {
                Map<String, Object> t;
                try {
                    t = p.trace();
                } catch (LuaException e) {
                    throw new AssertionError(e);
                }
                helper.assertTrue("minecraft:stone".equals(t.get("block")) && id.equals(t.get("subLevel")), "trace should hit the ship's stone: " + t
                    + " ship=" + id + " pose=" + ship.logicalPose().position() + " rot=" + ship.logicalPose().orientation() + " stoneLeft=" + helper.getBlockState(stone));
                fire(helper, p);
            });
            helper.runAfterDelay(10, () -> {
                BlockPos broken = laser.lastBroken();
                helper.assertTrue(laser.brokenCount() >= 1 && broken != null, "nothing broken, hit " + p.getHit());
                helper.assertTrue(ship.getUniqueId().equals(Sable.HELPER.getContaining(helper.getLevel(), broken).getUniqueId()), "broken block was not on the ship");
                helper.assertTrue(helper.getLevel().getBlockState(broken).isAir(), "ship block still there");
                Map<String, Object> hit = p.getHit();
                helper.assertTrue(hit != null && id.equals(hit.get("subLevel")), "beam should still be on the ship: " + hit);
                p.stop();
                finish(helper, ship);
            });
        }

        /**
         * Fires at {@code start} (after the new bodies exist) and returns how much faster {@code ship} gained upward
         * velocity than {@code control} over the next {@code ticks}, compared with impactForce x time / mass.
         */
        static void measurePush(GameTestHelper helper, String what, ServerSubLevel ship, ServerSubLevel control, LaserPeripheral p) {
            int start = 3;
            int ticks = 8;
            double[] v0 = new double[1];
            helper.runAfterDelay(start, () -> {
                v0[0] = ship.latestLinearVelocity.y() - control.latestLinearVelocity.y();
                fire(helper, p);
            });
            helper.runAfterDelay(start + ticks, () -> {
                double dv = ship.latestLinearVelocity.y() - control.latestLinearVelocity.y() - v0[0];
                double mass = ship.getMassTracker().getMass();
                double expected = AshCCConfig.LASER_IMPACT_FORCE.get() * (ticks - 1) / 20.0 / mass;
                AshCC.LOGGER.info("laser {}: mass={} dv={} expected~{} hit={} w={}", what, mass, dv, expected, p.getHit(), ship.latestAngularVelocity);
                helper.assertTrue(dv > 0.5 * expected && dv < 1.5 * expected, what + ": dv=" + dv + " expected~" + expected);
                // An off-centre hit (0.6 block) spins it at ~1 rad/s; the first ticks of aiming leave a few hundredths
                helper.assertTrue(ship.latestAngularVelocity.length() < 0.2, what + ": centred force should not spin the ship, w=" + ship.latestAngularVelocity);
                Map<String, Object> hit = p.getHit();
                p.stop();
                helper.assertTrue(hit != null, "not firing");
                if (what.equals("recoil")) {
                    helper.assertTrue("minecraft:obsidian".equals(hit.get("block")) && hit.get("subLevel") == null, "should hit the ground: " + hit);
                } else {
                    helper.assertTrue(ship.getUniqueId().toString().equals(hit.get("subLevel")), "should hit the ship: " + hit);
                }
                finish(helper, ship, control);
            });
        }

        /** The ship being hit falls slower than an identical one next to it. */
        static void push(GameTestHelper helper) {
            ServerSubLevel target = deck(helper, 4, 10, 8, Blocks.OBSIDIAN, List.of());
            ServerSubLevel control = deck(helper, 12, 10, 8, Blocks.OBSIDIAN, List.of());
            LaserPeripheral p = LaserTests.placeLaser(helper, new BlockPos(4, 2, 8), 0, 90).peripheral();
            // Keep aiming at the real centre of mass (a fresh ship can settle half a block off), so the push does not spin it
            helper.onEachTick(() -> {
                Vector3d com = target.logicalPose().transformPosition(target.getMassTracker().getCenterOfMass(), new Vector3d());
                try {
                    p.aimAt(com.x, com.y, com.z);
                } catch (LuaException e) {
                    helper.fail(e.getMessage());
                }
            });
            measurePush(helper, "push", target, control, p);
        }

        /** A downward laser under a ship pushes its own ship up (recoil) and hits the ground, not the ship. */
        static void recoil(GameTestHelper helper) {
            BlockPos lp = new BlockPos(4, 9, 8);
            LaserTests.placeLaser(helper, lp, 0, -90); // the aim is saved with the block and moves onto the ship
            // 3x3 target: a fresh ship sometimes settles half a block off its build position
            for (int x = 3; x <= 5; x++) for (int z = 7; z <= 9; z++) helper.setBlock(x, 1, z, Blocks.OBSIDIAN);
            ServerSubLevel ship = deck(helper, 4, 10, 8, Blocks.IRON_BLOCK, List.of(lp));
            ServerSubLevel control = deck(helper, 12, 10, 8, Blocks.IRON_BLOCK, List.of());
            LaserPeripheral p = findLaser(helper, ship).peripheral();
            helper.assertTrue(ship.getUniqueId().toString().equals(p.getSubLevel()), "getSubLevel()");
            measurePush(helper, "recoil", ship, control, p);
        }

        /**
         * A laser aimed north on a ship that is turned 90 degrees about +Y must shoot west in the world
         * (+90 about Y takes -Z to -X), and its trace must find a block placed there.
         */
        static void turn(GameTestHelper helper) {
            BlockPos lp = new BlockPos(8, 11, 8);
            LaserTests.placeLaser(helper, lp, 0, 0);
            ServerSubLevel ship = deck(helper, 8, 10, 8, Blocks.IRON_BLOCK, List.of(lp));
            LaserBlockEntity laser = findLaser(helper, ship);
            RigidBodyHandle handle = RigidBodyHandle.of(ship);
            helper.assertTrue(handle != null, "no physics handle");
            Vector3d pos = new Vector3d(ship.logicalPose().position());
            handle.teleport(pos, new Quaterniond().rotateY(Math.PI / 2));
            helper.runAfterDelay(2, () -> {
                try {
                    Map<String, Object> dir = laser.peripheral().getDirection();
                    helper.assertTrue(RadarTests.num(dir, "x") < -0.99, "beam should point west after the turn: " + dir);
                    // Put a wall where the beam now goes and trace it
                    Map<?, ?> origin = (Map<?, ?>) dir.get("origin");
                    Vec3 o = new Vec3(RadarTests.num(origin, "x"), RadarTests.num(origin, "y"), RadarTests.num(origin, "z"));
                    BlockPos wall = BlockPos.containing(o.add(-4, 0, 0));
                    helper.getLevel().setBlockAndUpdate(wall, Blocks.GOLD_BLOCK.defaultBlockState());
                    Map<String, Object> t = laser.peripheral().trace();
                    helper.assertTrue("minecraft:gold_block".equals(t.get("block")), "trace after the turn: " + t + " origin " + o);
                    finish(helper, ship);
                } catch (LuaException e) {
                    helper.fail(e.getMessage());
                }
            });
        }
    }
}
