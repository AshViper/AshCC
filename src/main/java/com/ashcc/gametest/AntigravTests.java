package com.ashcc.gametest;

import com.ashcc.AshCC;
import com.ashcc.antigrav.AntigravBlockEntity;
import com.ashcc.antigrav.AntigravPeripheral;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.ObjectArguments;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Anti-gravity core: a 3x3 iron deck with the core in its centre, assembled into a ship. Only vertical motion and
 * rotation are checked, since GameTest ships do not move horizontally without players.
 */
@GameTestHolder(AshCC.MOD_ID)
@PrefixGameTestTemplate(false)
public class AntigravTests {

    /** Off a ship the core still answers, but has nothing to drive. */
    @GameTest(template = "empty_9x6x9", batch = "antigrav_ground")
    public static void groundCoreIsIdle(GameTestHelper helper) {
        BlockPos pos = new BlockPos(4, 1, 4);
        helper.setBlock(pos, AshCC.ANTIGRAV.get());
        AntigravBlockEntity core = helper.getBlockEntity(pos);
        helper.runAfterDelay(2, () -> {
            AntigravPeripheral p = core.peripheral();
            Map<String, Object> state = p.getState();
            helper.assertTrue(Boolean.FALSE.equals(state.get("active")), "core on the ground should not be active");
            helper.assertTrue(p.getSubLevel() == null, "no sub-level on the ground");
            try {
                p.getPosition();
                helper.fail("getPosition should fail off a ship");
            } catch (LuaException expected) {
                // ok
            }
            helper.succeed();
        });
    }

    /** release(): no control at all, only gravity cancelled. The ship must hang where it was. */
    @GameTest(template = "empty_17", timeoutTicks = 80, batch = "antigrav_float")
    public static void cancelsGravity(GameTestHelper helper) {
        if (!AshCC.sableLoaded) {
            helper.succeed();
            return;
        }
        Impl.floats(helper, false);
    }

    /** Two cores on one ship must not cancel gravity twice (the ship would fall upwards). */
    @GameTest(template = "empty_17", timeoutTicks = 80, batch = "antigrav_two")
    public static void twoCoresDoNotStack(GameTestHelper helper) {
        if (!AshCC.sableLoaded) {
            helper.succeed();
            return;
        }
        Impl.floats(helper, true);
    }

    @GameTest(template = "empty_17", timeoutTicks = 80, batch = "antigrav_off")
    public static void disabledCoreFalls(GameTestHelper helper) {
        if (!AshCC.sableLoaded) {
            helper.succeed();
            return;
        }
        Impl.disabled(helper);
    }

    @GameTest(template = "empty_17", timeoutTicks = 80, batch = "antigrav_velocity")
    public static void climbsAtSetVelocity(GameTestHelper helper) {
        if (!AshCC.sableLoaded) {
            helper.succeed();
            return;
        }
        Impl.velocity(helper);
    }

    @GameTest(template = "empty_17", timeoutTicks = 160, batch = "antigrav_move")
    public static void movesToPoint(GameTestHelper helper) {
        if (!AshCC.sableLoaded) {
            helper.succeed();
            return;
        }
        Impl.move(helper);
    }

    @GameTest(template = "empty_17", timeoutTicks = 260, batch = "antigrav_rotate")
    public static void turnsToRotation(GameTestHelper helper) {
        if (!AshCC.sableLoaded) {
            helper.succeed();
            return;
        }
        Impl.rotate(helper);
    }

    @GameTest(template = "empty_17", timeoutTicks = 100, batch = "antigrav_rate")
    public static void turnsAtRate(GameTestHelper helper) {
        if (!AshCC.sableLoaded) {
            helper.succeed();
            return;
        }
        Impl.rate(helper);
    }

    private static final class Impl {
        /** 3x3 iron deck at height y with a core in the centre (and optionally a second one on top), assembled. */
        static ServerSubLevel ship(GameTestHelper helper, int y, boolean second) {
            List<BlockPos> blocks = new ArrayList<>();
            for (int x = 7; x <= 9; x++) for (int z = 7; z <= 9; z++) {
                if (x == 8 && z == 8) helper.setBlock(x, y, z, AshCC.ANTIGRAV.get());
                else helper.setBlock(x, y, z, Blocks.IRON_BLOCK);
                blocks.add(helper.absolutePos(new BlockPos(x, y, z)));
            }
            int maxY = y;
            if (second) {
                helper.setBlock(8, y + 1, 8, AshCC.ANTIGRAV.get());
                blocks.add(helper.absolutePos(new BlockPos(8, y + 1, 8)));
                maxY = y + 1;
            }
            BoundingBox3i bounds = new BoundingBox3i(helper.absolutePos(new BlockPos(7, y, 7)), helper.absolutePos(new BlockPos(9, maxY, 9)));
            ServerSubLevel ship = SubLevelAssemblyHelper.assembleBlocks(helper.getLevel(), helper.absolutePos(new BlockPos(8, y, 8)), blocks, bounds);
            helper.assertTrue(ship != null, "assembly failed");
            return ship;
        }

        static List<AntigravBlockEntity> cores(GameTestHelper helper, ServerSubLevel ship) {
            List<AntigravBlockEntity> found = new ArrayList<>();
            LevelPlot plot = ship.getPlot();
            for (int cx = plot.getChunkMin().x; cx <= plot.getChunkMax().x; cx++) {
                for (int cz = plot.getChunkMin().z; cz <= plot.getChunkMax().z; cz++) {
                    for (BlockEntity be : helper.getLevel().getChunk(cx, cz).getBlockEntities().values()) {
                        if (be instanceof AntigravBlockEntity c) found.add(c);
                    }
                }
            }
            helper.assertTrue(!found.isEmpty(), "core not found in plot");
            return found;
        }

        static void finish(GameTestHelper helper, ServerSubLevel ship) {
            SubLevelContainer container = SubLevelContainer.getContainer(helper.getLevel());
            if (container != null && !ship.isRemoved()) container.removeSubLevel(ship, SubLevelRemovalReason.REMOVED);
            helper.succeed();
        }

        static double num(Map<String, Object> t, String key) {
            return ((Number) t.get(key)).doubleValue();
        }

        static void floats(GameTestHelper helper, boolean two) {
            ServerSubLevel ship = ship(helper, 6, two);
            List<AntigravBlockEntity> cores = cores(helper, ship);
            helper.assertTrue(cores.size() == (two ? 2 : 1), "core count " + cores.size());
            helper.assertTrue(cores.get(0).getClass().getSimpleName().equals("SableAntigravBlockEntity"), "not a Sable actor");
            for (AntigravBlockEntity c : cores) c.peripheral().release();
            double startY = ship.logicalPose().position().y();
            helper.runAfterDelay(30, () -> {
                double vy = ship.latestLinearVelocity.y();
                double dy = ship.logicalPose().position().y() - startY;
                long active = cores.stream().filter(AntigravBlockEntity::driving).count();
                AshCC.LOGGER.info("antigrav float two={} vy={} dy={} active={}", two, vy, dy, active);
                helper.assertTrue(Math.abs(vy) < 0.3 && Math.abs(dy) < 0.5, "weightless ship should hang still, vy=" + vy + " dy=" + dy);
                helper.assertTrue(active == 1, "exactly one core should drive, got " + active);
                finish(helper, ship);
            });
        }

        static void disabled(GameTestHelper helper) {
            ServerSubLevel ship = ship(helper, 10, false);
            AntigravPeripheral p = cores(helper, ship).get(0).peripheral();
            p.setEnabled(false);
            helper.runAfterDelay(10, () -> {
                double vy = ship.latestLinearVelocity.y();
                AshCC.LOGGER.info("antigrav disabled vy={}", vy);
                helper.assertTrue(vy < -3, "disabled core should let the ship fall, vy=" + vy);
                helper.assertTrue(!p.isActive(), "disabled core reports active");
                finish(helper, ship);
            });
        }

        static void velocity(GameTestHelper helper) {
            ServerSubLevel ship = ship(helper, 3, false);
            AntigravPeripheral p = cores(helper, ship).get(0).peripheral();
            try {
                p.setVelocity(new ObjectArguments(0, 2, 0));
            } catch (LuaException e) {
                helper.fail(e.getMessage());
            }
            helper.runAfterDelay(30, () -> {
                double vy = ship.latestLinearVelocity.y();
                try {
                    Map<String, Object> v = p.getVelocity();
                    AshCC.LOGGER.info("antigrav velocity vy={} lua={}", vy, v);
                    helper.assertTrue(Math.abs(num(v, "y") - vy) < 1e-6, "getVelocity y");
                } catch (LuaException e) {
                    helper.fail(e.getMessage());
                }
                helper.assertTrue(Math.abs(vy - 2) < 0.1, "should climb at 2 blocks/s, vy=" + vy);
                finish(helper, ship);
            });
        }

        static void move(GameTestHelper helper) {
            ServerSubLevel ship = ship(helper, 4, false);
            AntigravPeripheral p = cores(helper, ship).get(0).peripheral();
            helper.runAfterDelay(5, () -> {
                double targetY;
                try {
                    targetY = num(p.getPosition(), "y") + 4;
                    p.move(new ObjectArguments(0, 4, 0));
                } catch (LuaException e) {
                    helper.fail(e.getMessage());
                    return;
                }
                helper.runAfterDelay(120, () -> {
                    try {
                        double y = num(p.getPosition(), "y");
                        AshCC.LOGGER.info("antigrav move y={} target={} atTarget={} vy={}", y, targetY, p.isAtTarget(), ship.latestLinearVelocity.y());
                        helper.assertTrue(Math.abs(y - targetY) < 0.2, "should arrive at y=" + targetY + ", y=" + y);
                        helper.assertTrue(p.isAtTarget(), "isAtTarget");
                    } catch (LuaException e) {
                        helper.fail(e.getMessage());
                    }
                    finish(helper, ship);
                });
            });
        }

        /** yawRate 30 deg/s: the heading must really advance 30 degrees per second, clockwise from above. */
        static void rate(GameTestHelper helper) {
            ServerSubLevel ship = ship(helper, 8, false);
            AntigravPeripheral p = cores(helper, ship).get(0).peripheral();
            try {
                p.setAngularVelocity(new ObjectArguments(30, 0, 0));
            } catch (LuaException e) {
                helper.fail(e.getMessage());
            }
            double[] yaw40 = new double[1];
            helper.runAfterDelay(40, () -> {
                try {
                    yaw40[0] = num(p.getRotation(), "yaw");
                } catch (LuaException e) {
                    helper.fail(e.getMessage());
                }
            });
            helper.runAfterDelay(60, () -> {
                try {
                    double turned = net.minecraft.util.Mth.wrapDegrees(num(p.getRotation(), "yaw") - yaw40[0]);
                    Map<String, Object> w = p.getAngularVelocity();
                    AshCC.LOGGER.info("antigrav rate turned={} in 1s, w={}", turned, w);
                    helper.assertTrue(Math.abs(turned - 30) < 1.5, "should turn 30 deg in a second, turned " + turned);
                    helper.assertTrue(Math.abs(num(w, "yawRate") - 30) < 1.5, "getAngularVelocity yawRate " + w);
                } catch (LuaException e) {
                    helper.fail(e.getMessage());
                }
                finish(helper, ship);
            });
        }

        static void rotate(GameTestHelper helper) {
            ServerSubLevel ship = ship(helper, 8, false);
            AntigravPeripheral p = cores(helper, ship).get(0).peripheral();
            try {
                p.setLimits(Map.of("angularSpeed", 90.0, "angularAcceleration", 180.0));
                p.setRotation(90, Optional.of(20.0), Optional.of(10.0));
            } catch (LuaException e) {
                helper.fail(e.getMessage());
            }
            helper.runAfterDelay(200, () -> {
                try {
                    Map<String, Object> r = p.getRotation();
                    AshCC.LOGGER.info("antigrav rotate {} atTarget={} w={}", r, p.isAtTarget(), p.getAngularVelocity());
                    helper.assertTrue(Math.abs(num(r, "yaw") - 90) < 3, "yaw " + r);
                    helper.assertTrue(Math.abs(num(r, "pitch") - 20) < 3, "pitch " + r);
                    helper.assertTrue(Math.abs(num(r, "roll") - 10) < 3, "roll " + r);
                    helper.assertTrue(p.isAtTarget(), "isAtTarget");
                } catch (LuaException e) {
                    helper.fail(e.getMessage());
                }
                finish(helper, ship);
            });
        }
    }
}
