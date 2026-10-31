package com.ashcc.gametest;

import com.ashcc.AshCC;
import com.ashcc.thruster.ThrusterBlockEntity;
import dan200.computercraft.api.lua.LuaException;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/** A 3x3 iron deck with a downward nozzle underneath; checks that the thruster really moves the ship. */
@GameTestHolder(AshCC.MOD_ID)
@PrefixGameTestTemplate(false)
public class SableThrusterTests {

    @GameTest(template = "empty_17", timeoutTicks = 100)
    public static void liftsShip(GameTestHelper helper) {
        if (!AshCC.sableLoaded) {
            helper.succeed();
            return;
        }
        Impl.run(helper, false);
    }

    /**
     * Gimbal x = +1 tilts the force towards +X. Applied below the centre of mass that must spin the ship about +Z
     * (torque = r x F). Horizontal motion itself cannot be checked here: without players Sable holds ships in place
     * horizontally (its physics chunk tickets follow players).
     */
    @GameTest(template = "empty_17", timeoutTicks = 100)
    public static void gimbalTorquesShip(GameTestHelper helper) {
        if (!AshCC.sableLoaded) {
            helper.succeed();
            return;
        }
        Impl.run(helper, true);
    }

    private static final class Impl {
        static void run(GameTestHelper helper, boolean gimbal) {
            // 3x3 iron deck, nozzle pointing down under its centre
            List<BlockPos> blocks = new ArrayList<>();
            for (int x = 7; x <= 9; x++) for (int z = 7; z <= 9; z++) {
                helper.setBlock(x, 8, z, Blocks.IRON_BLOCK);
                blocks.add(helper.absolutePos(new BlockPos(x, 8, z)));
            }
            BlockPos tp = new BlockPos(8, 7, 8);
            ThrusterTests.placeThruster(helper, tp, Direction.DOWN);
            blocks.add(helper.absolutePos(tp));
            BoundingBox3i bounds = new BoundingBox3i(helper.absolutePos(new BlockPos(7, 7, 7)), helper.absolutePos(new BlockPos(9, 8, 9)));
            ServerSubLevel ship = SubLevelAssemblyHelper.assembleBlocks(helper.getLevel(), helper.absolutePos(new BlockPos(8, 8, 8)), blocks, bounds);
            helper.assertTrue(ship != null, "assembly failed");

            ThrusterBlockEntity thruster = find(helper, ship);
            helper.assertTrue(thruster.getClass().getSimpleName().equals("SableThrusterBlockEntity"), "not a Sable actor: " + thruster.getClass());
            double startY = ship.logicalPose().position().y();
            try {
                helper.assertTrue(ship.getUniqueId().toString().equals(thruster.peripheral().getSubLevel()), "getSubLevel()");
                thruster.peripheral().setThrust(1);
                if (gimbal) thruster.peripheral().setVector(1, 0);
            } catch (LuaException e) {
                helper.fail(e.getMessage());
            }

            // Check before the ship reaches the test area's barrier ceiling
            helper.runAfterDelay(8, () -> {
                double vy = ship.latestLinearVelocity.y();
                double wz = ship.latestAngularVelocity.z();
                double dy = ship.logicalPose().position().y() - startY;
                AshCC.LOGGER.info("thruster test gimbal={} mass={} force={} vy={} dy={} wz={}",
                    gimbal, ship.getMassTracker().getMass(), thruster.force(), vy, dy, wz);
                helper.assertTrue(vy > 3 && dy > 0, "thrust should lift the ship, vy=" + vy + " dy=" + dy);
                if (gimbal) helper.assertTrue(wz > 0.5, "gimbal x=+1 below the centre of mass should spin about +Z, wz=" + wz);
                else helper.assertTrue(Math.abs(wz) < 1e-3, "straight thrust should not spin the ship, wz=" + wz);
                TestShips.remove(helper, ship);
                helper.succeed();
            });
        }

        static ThrusterBlockEntity find(GameTestHelper helper, ServerSubLevel ship) {
            LevelPlot plot = ship.getPlot();
            for (int cx = plot.getChunkMin().x; cx <= plot.getChunkMax().x; cx++) {
                for (int cz = plot.getChunkMin().z; cz <= plot.getChunkMax().z; cz++) {
                    for (BlockEntity be : helper.getLevel().getChunk(cx, cz).getBlockEntities().values()) {
                        if (be instanceof ThrusterBlockEntity t) return t;
                    }
                }
            }
            throw new AssertionError("thruster not found in plot");
        }
    }
}
