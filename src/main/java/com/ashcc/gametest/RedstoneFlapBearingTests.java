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

/** Redstone flap bearing: side signal turns it +, back signal turns it -, comparator reads the deflection. Skipped without Create. */
@GameTestHolder(AshCC.MOD_ID)
@PrefixGameTestTemplate(false)
public class RedstoneFlapBearingTests {

    @GameTest(template = RadarTests.EMPTY, timeoutTicks = 200)
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
            BlockPos side = pos.east();
            BlockPos back = pos.below();
            helper.setBlock(pos, CreateCompat.REDSTONE_FLAP_BEARING.get().defaultBlockState().setValue(DirectionalKineticBlock.FACING, Direction.UP));
            helper.setBlock(flap, Blocks.OAK_PLANKS);
            RedstoneFlapBearingBlockEntity be = (RedstoneFlapBearingBlockEntity) helper.getBlockEntity(pos);
            helper.assertTrue(be.tryAssemble() == null, "assemble failed");
            helper.setBlock(side, Blocks.REDSTONE_BLOCK);

            // 45 degrees at 90 degrees/second: 10 ticks
            helper.runAfterDelay(15, () -> {
                helper.assertTrue(Math.abs(be.currentAngle() - 45) < 1e-3, "side: angle " + be.currentAngle());
                helper.assertTrue(be.comparatorOutput() == 15, "comparator " + be.comparatorOutput());
                helper.setBlock(side, Blocks.AIR);
                helper.setBlock(back, Blocks.REDSTONE_BLOCK);
                helper.runAfterDelay(25, () -> {
                    helper.assertTrue(Math.abs(be.currentAngle() + 45) < 1e-3, "back: angle " + be.currentAngle());
                    // Both at 15 cancel out
                    helper.setBlock(side, Blocks.REDSTONE_BLOCK);
                    helper.runAfterDelay(15, () -> {
                        helper.assertTrue(Math.abs(be.currentAngle()) < 1e-3, "both: angle " + be.currentAngle());
                        helper.assertTrue(be.comparatorOutput() == 0, "comparator " + be.comparatorOutput());
                        helper.setBlock(side, Blocks.AIR);
                        helper.setBlock(back, Blocks.AIR);
                        helper.assertTrue(be.requestDisassemble(), "disassemble at 0");
                        helper.assertBlockPresent(Blocks.OAK_PLANKS, flap);
                        helper.succeed();
                    });
                });
            });
        }
    }
}
