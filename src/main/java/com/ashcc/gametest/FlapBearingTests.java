package com.ashcc.gametest;

import com.ashcc.AshCC;
import com.ashcc.compat.create.CreateCompat;
import com.ashcc.compat.create.FlapBearingBlockEntity;
import com.ashcc.compat.create.FlapBearingPeripheral;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import dan200.computercraft.api.lua.LuaException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Servo behaviour of the flap bearing. Skipped (passes) when Create is not installed. */
@GameTestHolder(AshCC.MOD_ID)
@PrefixGameTestTemplate(false)
public class FlapBearingTests {

    @GameTest(template = RadarTests.EMPTY, timeoutTicks = 200)
    public static void servoesToAngleAndDisassembles(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            helper.succeed();
            return;
        }
        Impl.run(helper);
    }

    /** Create types live here so the test class loads without Create. */
    private static final class Impl {
        static void run(GameTestHelper helper) {
            BlockPos pos = new BlockPos(4, 1, 4);
            BlockPos flap = pos.above();
            helper.setBlock(pos, CreateCompat.FLAP_BEARING.get().defaultBlockState().setValue(DirectionalKineticBlock.FACING, Direction.UP));
            helper.setBlock(flap, Blocks.OAK_PLANKS);
            FlapBearingBlockEntity be = (FlapBearingBlockEntity) helper.getBlockEntity(pos);
            FlapBearingPeripheral p = be.peripheral();
            try {
                Object[] r = p.assemble().getResult();
                helper.assertTrue(Boolean.TRUE.equals(r[0]), "assemble failed: " + java.util.Arrays.toString(r));
                helper.assertTrue(p.isAssembled(), "not assembled");
                helper.assertBlockNotPresent(Blocks.OAK_PLANKS, flap);
                helper.assertTrue(p.setSpeed(400) == 400, "speed");
                helper.assertTrue(p.setAngle(45) == 45, "target");
                helper.assertTrue(p.setAngle(270) == -90, "270 wraps to -90");
                p.setAngle(45);
            } catch (LuaException e) {
                helper.fail(e.getMessage());
            }

            helper.runAfterDelay(6, () -> {
                helper.assertTrue(Math.abs(p.getAngle() - 45) < 1e-3 && p.isAtTarget(), "angle " + p.getAngle());
                try {
                    p.setAngle(-30);
                } catch (LuaException e) {
                    helper.fail(e.getMessage());
                }
                helper.runAfterDelay(6, () -> {
                    helper.assertTrue(Math.abs(p.getAngle() + 30) < 1e-3, "angle " + p.getAngle());
                    // Not at 0: returns to 0 first, then puts the block back
                    helper.assertTrue(!p.disassemble(), "should return to 0 before disassembling");
                    helper.runAfterDelay(6, () -> {
                        helper.assertTrue(!p.isAssembled(), "still assembled at " + p.getAngle());
                        helper.assertBlockPresent(Blocks.OAK_PLANKS, flap);
                        helper.succeed();
                    });
                });
            });
        }
    }
}
