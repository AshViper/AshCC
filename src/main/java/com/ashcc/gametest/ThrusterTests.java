package com.ashcc.gametest;

import com.ashcc.AshCC;
import com.ashcc.thruster.ThrusterBlock;
import com.ashcc.thruster.ThrusterBlockEntity;
import com.ashcc.thruster.ThrusterPeripheral;
import dan200.computercraft.api.lua.LuaException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;

/** Thruster controls that do not need Sable. Physics tests are in {@link SableThrusterTests}. */
@GameTestHolder(AshCC.MOD_ID)
@PrefixGameTestTemplate(false)
public class ThrusterTests {
    static ThrusterBlockEntity placeThruster(GameTestHelper helper, BlockPos pos, Direction nozzle) {
        helper.setBlock(pos, AshCC.THRUSTER.get().defaultBlockState().setValue(ThrusterBlock.FACING, nozzle));
        return (ThrusterBlockEntity) helper.getBlockEntity(pos);
    }

    @GameTest(template = RadarTests.EMPTY)
    public static void throttleGimbalAndAxes(GameTestHelper helper) throws LuaException {
        BlockPos pos = new BlockPos(4, 2, 4);
        ThrusterBlockEntity be = placeThruster(helper, pos, Direction.DOWN);
        ThrusterPeripheral p = be.peripheral();

        Map<?, ?> thrust = (Map<?, ?>) p.getAxes().get("thrust");
        helper.assertTrue(RadarTests.num(thrust, "y") == 1, "nozzle down must push up: " + thrust);

        p.setThrust(0.5);
        p.setVector(1, 0);
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(Math.abs(p.getCurrentThrust() - 0.5) < 1e-6, "throttle " + p.getCurrentThrust());
            helper.assertTrue(Math.abs(p.getForce() - 0.5 * p.getMaxThrust()) < 1e-6, "force " + p.getForce());
            helper.assertBlockProperty(pos, ThrusterBlock.ACTIVE, true);

            // Full positive x gimbal tilts the thrust by maxGimbal towards +X
            Vec3 dir = be.thrustDirection();
            double angle = Math.toDegrees(Math.atan2(dir.x, dir.y));
            helper.assertTrue(Math.abs(angle - p.getMaxGimbal()) < 1e-6 && Math.abs(dir.z) < 1e-9, "gimbal dir " + dir);

            // Back to redstone control: a redstone block next to it gives full throttle
            p.setRedstoneControl(true);
            helper.setBlock(pos.east(), Blocks.REDSTONE_BLOCK);
            helper.runAfterDelay(2, () -> {
                helper.assertTrue(p.getRedstoneInput() == 15 && p.getCurrentThrust() == 1, "redstone throttle " + p.getCurrentThrust());
                p.stop();
                helper.runAfterDelay(2, () -> {
                    helper.assertBlockProperty(pos, ThrusterBlock.ACTIVE, false);
                    helper.succeed();
                });
            });
        });
    }
}
