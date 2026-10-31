package com.ashcc.gametest;

import com.ashcc.AshCC;
import com.ashcc.compat.create.CreateCompat;
import com.ashcc.compat.create.QuadFlapBearingBlock;
import com.ashcc.compat.create.QuadFlapBearingBlockEntity;
import com.ashcc.compat.create.QuadFlapBearingPeripheral;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.ObjectArguments;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;

/** Four independently servoed flaps on one block. Skipped (passes) without Create. */
@GameTestHolder(AshCC.MOD_ID)
@PrefixGameTestTemplate(false)
public class QuadFlapBearingTests {

    @GameTest(template = RadarTests.EMPTY, timeoutTicks = 200)
    public static void fourFlapsMoveIndependently(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            helper.succeed();
            return;
        }
        Impl.run(helper);
    }

    @GameTest(template = RadarTests.EMPTY, timeoutTicks = 60)
    public static void breakingPutsFlapsBack(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            helper.succeed();
            return;
        }
        Impl.breaking(helper);
    }

    private static final class Impl {
        static final BlockPos POS = new BlockPos(4, 2, 4);
        static final List<Direction> SIDES = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);

        static QuadFlapBearingBlockEntity place(GameTestHelper helper) {
            helper.setBlock(POS, CreateCompat.QUAD_FLAP_BEARING.get().defaultBlockState().setValue(QuadFlapBearingBlock.FACING, Direction.UP));
            for (Direction d : SIDES) helper.setBlock(POS.relative(d), Blocks.OAK_PLANKS);
            return (QuadFlapBearingBlockEntity) helper.getBlockEntity(POS);
        }

        static void run(GameTestHelper helper) {
            QuadFlapBearingBlockEntity be = place(helper);
            QuadFlapBearingPeripheral p = be.peripheral();
            try {
                List<String> sides = p.getSides();
                helper.assertTrue(sides.size() == 4 && !sides.contains("up") && !sides.contains("down"), "sides " + sides);

                Object[] r = p.assemble(new ObjectArguments()).getResult();
                helper.assertTrue(((Number) r[0]).intValue() == 4 && ((Map<?, ?>) r[1]).isEmpty(), "assemble " + java.util.Arrays.toString(r));
                for (Direction d : SIDES) helper.assertBlockNotPresent(Blocks.OAK_PLANKS, POS.relative(d));

                try {
                    p.setAngle("up", 10);
                    helper.fail("up is the body axis, not a flap");
                } catch (LuaException expected) {
                    // ok
                }
                p.setSpeed(400);
                p.setAngles(Map.of("north", 30.0, "south", -30.0, "east", 45.0));
                p.setAngle("west", 10);
            } catch (LuaException e) {
                helper.fail(e.getMessage());
            }

            helper.runAfterDelay(6, () -> {
                try {
                    Map<?, ?> angles = (Map<?, ?>) p.getAngle(new ObjectArguments());
                    Map<String, Double> expected = Map.of("north", 30.0, "south", -30.0, "east", 45.0, "west", 10.0);
                    for (var e : expected.entrySet()) {
                        double a = ((Number) angles.get(e.getKey())).doubleValue();
                        helper.assertTrue(Math.abs(a - e.getValue()) < 1e-3, e.getKey() + " angle " + a);
                        ControlledContraptionEntity c = be.contraption(Direction.byName(e.getKey()));
                        helper.assertTrue(c != null && Math.abs(c.getAngle(1) - e.getValue()) < 1e-3, e.getKey() + " contraption angle");
                    }
                    helper.assertTrue(p.isAtTarget(new ObjectArguments()), "all at target");

                    // Which way a positive angle turns (for the docs): a point above the north flap's axis
                    ControlledContraptionEntity north = be.contraption(Direction.NORTH);
                    Vec3 moved = north.applyRotation(new Vec3(0, 1, 0), 1);
                    AshCC.LOGGER.info("quad flap: north +30 deg maps (0,1,0) to {}", moved);

                    helper.assertTrue(!p.disassemble(new ObjectArguments()), "should return to 0 first");
                } catch (LuaException e) {
                    helper.fail(e.getMessage());
                }
                helper.runAfterDelay(8, () -> {
                    for (Direction d : SIDES) helper.assertBlockPresent(Blocks.OAK_PLANKS, POS.relative(d));
                    helper.succeed();
                });
            });
        }

        static void breaking(GameTestHelper helper) {
            QuadFlapBearingBlockEntity be = place(helper);
            try {
                be.peripheral().assemble(new ObjectArguments("east"));
            } catch (LuaException e) {
                helper.fail(e.getMessage());
            }
            helper.assertBlockNotPresent(Blocks.OAK_PLANKS, POS.east());
            helper.runAfterDelay(2, () -> {
                helper.destroyBlock(POS);
                helper.runAfterDelay(2, () -> {
                    helper.assertBlockPresent(Blocks.OAK_PLANKS, POS.east());
                    helper.succeed();
                });
            });
        }
    }
}
