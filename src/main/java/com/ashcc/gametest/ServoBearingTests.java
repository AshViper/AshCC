package com.ashcc.gametest;

import com.ashcc.AshCC;
import com.ashcc.compat.simulated.ServoBearingBlock;
import com.ashcc.compat.simulated.ServoBearingBlockEntity;
import com.ashcc.compat.simulated.ServoBearingPeripheral;
import com.ashcc.compat.simulated.SimulatedCompat;
import dan200.computercraft.api.lua.LuaException;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Arrays;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Servo bearing: a swivel-style sub-level turntable driven from Lua. Skipped (passes) without Create Simulated. */
@GameTestHolder(AshCC.MOD_ID)
@PrefixGameTestTemplate(false)
public class ServoBearingTests {

    @GameTest(template = "empty_17", timeoutTicks = 400, batch = "servo")
    public static void servoTurnsArmAndKeepsPlacedBlocks(GameTestHelper helper) {
        if (!loaded()) {
            helper.succeed();
            return;
        }
        Impl.turnsArm(helper);
    }

    @GameTest(template = "empty_17", timeoutTicks = 300, batch = "servo")
    public static void emptyTurntableCanBeBuiltOn(GameTestHelper helper) {
        if (!loaded()) {
            helper.succeed();
            return;
        }
        Impl.emptyTurntable(helper);
    }

    private static boolean loaded() {
        return AshCC.sableLoaded && ModList.get().isLoaded("simulated");
    }

    /** Simulated and Sable types live here so the test class loads without them. */
    private static final class Impl {
        static final BlockPos BEARING = new BlockPos(8, 2, 8);

        static ServoBearingPeripheral place(GameTestHelper helper) {
            helper.setBlock(BEARING, SimulatedCompat.SERVO_BEARING.get().defaultBlockState().setValue(ServoBearingBlock.FACING, Direction.UP));
            return ((ServoBearingBlockEntity) helper.getBlockEntity(BEARING)).peripheral();
        }

        static ServoBearingBlockEntity be(GameTestHelper helper) {
            return (ServoBearingBlockEntity) helper.getBlockEntity(BEARING);
        }

        static void assemble(GameTestHelper helper, ServoBearingPeripheral p) {
            Object[] r = p.assemble().getResult();
            helper.assertTrue(Boolean.TRUE.equals(r[0]), "assemble failed: " + Arrays.toString(r));
            helper.assertTrue(p.isAssembled(), "not assembled");
            helper.assertTrue(p.getSubLevel() != null, "no sub-level id");
            helper.assertTrue(helper.getBlockState(BEARING).getValue(ServoBearingBlock.ASSEMBLED), "block state not assembled");
        }

        static SubLevel subLevel(GameTestHelper helper, ServoBearingPeripheral p) {
            SubLevel sub = SubLevelContainer.getContainer(helper.getLevel()).getSubLevel(UUID.fromString(p.getSubLevel()));
            helper.assertTrue(sub != null, "sub-level missing");
            return sub;
        }

        static void setAngle(GameTestHelper helper, ServoBearingPeripheral p, double deg) {
            try {
                p.setAngle(deg);
            } catch (LuaException e) {
                helper.fail(e.getMessage());
            }
        }

        /** Runs {@code then} once {@code done} holds, failing after {@code maxTicks}. */
        static void waitFor(GameTestHelper helper, String what, int maxTicks, BooleanSupplier done, Runnable then) {
            if (done.getAsBoolean()) {
                then.run();
                return;
            }
            if (maxTicks <= 0) helper.fail("timed out waiting for " + what + " (" + state(helper) + ")");
            helper.runAfterDelay(1, () -> waitFor(helper, what, maxTicks - 1, done, then));
        }

        static String state(GameTestHelper helper) {
            ServoBearingBlockEntity be = be(helper);
            return "angle=" + be.currentAngle() + " target=" + be.targetAngle() + " assembled=" + be.isAssembled();
        }

        static void cleanUp(GameTestHelper helper, SubLevel sub) {
            if (!sub.isRemoved()) SubLevelContainer.getContainer(helper.getLevel()).removeSubLevel(sub, SubLevelRemovalReason.REMOVED);
        }

        /**
         * One block on the bearing (blocks only join through glue, as with the swivel bearing). After assembly the arm is
         * extended by placing blocks on the turning sub-level; they have to turn with it and come back on disassembly.
         * The arm is symmetric: without players Sable does not move bodies sideways, so an off-centre centre of mass
         * could not swing round here (it can in a real world).
         */
        static void turnsArm(GameTestHelper helper) {
            helper.setBlock(new BlockPos(8, 3, 8), Blocks.IRON_BLOCK);
            ServoBearingPeripheral p = place(helper);
            assemble(helper, p);
            helper.assertBlockNotPresent(Blocks.IRON_BLOCK, new BlockPos(8, 3, 8));
            SubLevel sub = subLevel(helper, p);

            // Build on the turntable after assembly, in the sub-level's plot: gold 1 block out along +X and -X
            BlockPos plate = be(helper).platePos();
            helper.assertTrue(plate != null && helper.getLevel().getBlockState(plate).is(SimulatedCompat.SERVO_BEARING_PLATE.get()), "turntable missing");
            helper.assertTrue(helper.getLevel().getBlockState(plate.above()).is(Blocks.IRON_BLOCK), "rotor block not above the turntable");
            BlockPos gold = plate.offset(1, 1, 0);
            helper.getLevel().setBlockAndUpdate(gold, Blocks.GOLD_BLOCK.defaultBlockState());
            helper.getLevel().setBlockAndUpdate(plate.offset(-1, 1, 0), Blocks.GOLD_BLOCK.defaultBlockState());
            helper.assertTrue(Sable.HELPER.getContaining(helper.getLevel(), gold) == sub, "placed block is not on the turntable");

            try {
                helper.assertTrue(p.setSpeed(360) == 360, "speed");
                helper.assertTrue(p.setAngle(270) == -90, "270 wraps to -90");
            } catch (LuaException e) {
                helper.fail(e.getMessage());
            }
            setAngle(helper, p, 90);
            Vec3 axis = Vec3.atCenterOf(helper.absolutePos(BEARING));
            waitFor(helper, "90 degrees", 80, p::isAtTarget, () -> {
                helper.assertTrue(Math.abs(p.getAngle() - 90) < 3, "angle " + p.getAngle());
                // The gold block was 1 block out along +X; a quarter turn about Y puts it 1 block out along Z
                // Measured from the turntable: new sub-levels sometimes settle about half a block off (a Sable quirk)
                Vec3 platePos = sub.logicalPose().transformPosition(Vec3.atCenterOf(plate));
                helper.assertTrue(platePos.subtract(axis).horizontalDistance() < 0.75, "turntable left the axis: " + platePos.subtract(axis));
                Vec3 g = sub.logicalPose().transformPosition(Vec3.atCenterOf(gold)).subtract(platePos);
                helper.assertTrue(Math.abs(g.x) < 0.3 && Math.abs(Math.abs(g.z) - 1) < 0.3, "gold block at " + g);

                // 170 -> -170 must go the short way through 180, not back through 0
                setAngle(helper, p, 170);
                waitFor(helper, "170 degrees", 80, p::isAtTarget, () -> {
                    double[] minAbs = {180};
                    setAngle(helper, p, -170);
                    waitFor(helper, "-170 degrees", 80, () -> {
                        minAbs[0] = Math.min(minAbs[0], Math.abs(p.getAngle()));
                        return p.isAtTarget();
                    }, () -> {
                        helper.assertTrue(minAbs[0] > 150, "went the long way (passed " + minAbs[0] + " degrees)");

                        // Not at 0: returns to 0 first, then puts the blocks back
                        helper.assertTrue(!p.disassemble(), "should return to 0 before disassembling");
                        waitFor(helper, "disassembly", 120, () -> !p.isAssembled(), () -> {
                            helper.assertBlockPresent(Blocks.IRON_BLOCK, new BlockPos(8, 3, 8));
                            helper.assertBlockPresent(Blocks.GOLD_BLOCK, new BlockPos(9, 3, 8));
                            helper.assertBlockPresent(Blocks.GOLD_BLOCK, new BlockPos(7, 3, 8));
                            helper.assertBlockPresent(SimulatedCompat.SERVO_BEARING.get(), BEARING);
                            cleanUp(helper, sub);
                            helper.succeed();
                        });
                    });
                });
            });
        }

        /** Nothing in front: the bearing makes a sub-level with just the turntable, which can then be built on. */
        static void emptyTurntable(GameTestHelper helper) {
            ServoBearingPeripheral p = place(helper);
            assemble(helper, p);
            SubLevel sub = subLevel(helper, p);
            BlockPos plate = be(helper).platePos();
            helper.assertTrue(plate != null && Sable.HELPER.getContaining(helper.getLevel(), plate) == sub, "turntable not on the sub-level");
            helper.getLevel().setBlockAndUpdate(plate.above(), Blocks.OAK_PLANKS.defaultBlockState());
            helper.getLevel().setBlockAndUpdate(plate.above().east(), Blocks.OAK_PLANKS.defaultBlockState());
            helper.getLevel().setBlockAndUpdate(plate.above().west(), Blocks.OAK_PLANKS.defaultBlockState());
            setAngle(helper, p, -45);
            waitFor(helper, "-45 degrees", 80, p::isAtTarget, () -> {
                helper.assertTrue(Math.abs(p.getAngle() + 45) < 3, "angle " + p.getAngle());
                helper.assertTrue(!p.disassemble(), "should return to 0 before disassembling");
                waitFor(helper, "disassembly", 120, () -> !p.isAssembled(), () -> {
                    helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(8, 3, 8));
                    helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(9, 3, 8));
                    helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(7, 3, 8));
                    helper.assertBlockPresent(SimulatedCompat.SERVO_BEARING.get(), BEARING);
                    helper.assertFalse(helper.getBlockState(BEARING).getValue(ServoBearingBlock.ASSEMBLED), "still assembled");
                    cleanUp(helper, sub);
                    helper.succeed();
                });
            });
        }
    }
}
