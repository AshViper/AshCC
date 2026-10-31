package com.ashcc.gametest;

import com.ashcc.AshCC;
import com.ashcc.compat.create.CreateCompat;
import com.ashcc.compat.create.RedstoneFlapBearingBlockEntity;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Redstone flap bearing, facing up: north is coarse + (6 deg/level), east is fine + (1 deg/level, max 5),
 * west fine -, the back (below) coarse -. Comparator reads the deflection. Skipped without Create.
 */
@GameTestHolder(AshCC.MOD_ID)
@PrefixGameTestTemplate(false)
public class RedstoneFlapBearingTests {

    @GameTest(template = RadarTests.EMPTY, timeoutTicks = 400)
    public static void followsRedstone(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            helper.succeed();
            return;
        }
        Impl.run(helper);
    }

    private static final class Impl {
        static void run(GameTestHelper helper) {
            BlockPos pos = new BlockPos(4, 2, 4);
            BlockPos flap = pos.above();
            BlockPos north = pos.north();
            BlockPos east = pos.east();
            BlockPos west = pos.west();
            BlockPos back = pos.below();
            helper.setBlock(pos, CreateCompat.REDSTONE_FLAP_BEARING.get().defaultBlockState().setValue(DirectionalKineticBlock.FACING, Direction.UP));
            helper.setBlock(flap, Blocks.OAK_PLANKS);
            RedstoneFlapBearingBlockEntity be = (RedstoneFlapBearingBlockEntity) helper.getBlockEntity(pos);
            helper.assertTrue(be.tryAssemble() == null, "assemble failed");

            // Coarse + at full strength: 15 levels * 6 degrees = 90
            helper.setBlock(north, Blocks.REDSTONE_BLOCK);
            helper.runAfterDelay(30, () -> {
                helper.assertTrue(Math.abs(be.currentAngle() - 90) < 1e-3, "north: angle " + be.currentAngle());
                helper.assertTrue(be.comparatorOutput() == 15, "comparator " + be.comparatorOutput());
                // Fine + alone: capped at 5 degrees even at full strength
                helper.setBlock(north, Blocks.AIR);
                helper.setBlock(east, Blocks.REDSTONE_BLOCK);
                helper.runAfterDelay(30, () -> {
                    helper.assertTrue(Math.abs(be.currentAngle() - 5) < 1e-3, "east: angle " + be.currentAngle());
                    // Fine - cancels it back to 0
                    helper.setBlock(west, Blocks.REDSTONE_BLOCK);
                    helper.runAfterDelay(15, () -> {
                        helper.assertTrue(Math.abs(be.currentAngle()) < 1e-3, "east+west: angle " + be.currentAngle());
                        helper.assertTrue(be.comparatorOutput() == 0, "comparator " + be.comparatorOutput());
                        helper.setBlock(east, Blocks.AIR);
                        helper.setBlock(west, Blocks.AIR);
                        // The back is coarse -: -90
                        helper.setBlock(back, Blocks.REDSTONE_BLOCK);
                        helper.runAfterDelay(30, () -> {
                            helper.assertTrue(Math.abs(be.currentAngle() + 90) < 1e-3, "back: angle " + be.currentAngle());
                            helper.setBlock(back, Blocks.AIR);
                            helper.runAfterDelay(30, () -> {
                                helper.assertTrue(Math.abs(be.currentAngle()) < 1e-3, "released: angle " + be.currentAngle());
                                helper.assertTrue(be.requestDisassemble(), "disassemble at 0");
                                helper.assertBlockPresent(Blocks.OAK_PLANKS, flap);
                                helper.succeed();
                            });
                        });
                    });
                });
            });
        }
    }
}
