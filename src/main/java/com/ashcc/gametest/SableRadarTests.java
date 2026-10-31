package com.ashcc.gametest;

import com.ashcc.AshCC;
import com.ashcc.radar.RadarBlockEntity;
import com.ashcc.radar.RadarFrame;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.ObjectArguments;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds a small ship, assembles it into a Sable sub-level and checks both sides of the radar. */
@GameTestHolder(AshCC.MOD_ID)
@PrefixGameTestTemplate(false)
public class SableRadarTests {

    @GameTest(template = RadarTests.EMPTY, timeoutTicks = 100)
    public static void detectsSubLevelsAndWorksOnBoard(GameTestHelper helper) {
        if (!AshCC.sableLoaded) {
            helper.succeed(); // Sable not installed: nothing to test
            return;
        }
        Impl.run(helper);
    }

    /** Kept separate so the test class can be verified and registered without Sable on the classpath. */
    private static final class Impl {
        static void run(GameTestHelper helper) {
            RadarBlockEntity ground = RadarTests.placeRadar(helper, new BlockPos(0, 1, 0), Direction.NORTH);
            ArmorStand stand = RadarTests.stand(helper, 0.5, 4, 8.5);

            // 3x3 iron deck with a radar in the middle, assembled into a ship
            List<BlockPos> blocks = new ArrayList<>();
            for (int x = 3; x <= 5; x++) for (int z = 3; z <= 5; z++) {
                helper.setBlock(x, 2, z, Blocks.IRON_BLOCK);
                blocks.add(helper.absolutePos(new BlockPos(x, 2, z)));
            }
            RadarTests.placeRadar(helper, new BlockPos(4, 3, 4), Direction.EAST);
            blocks.add(helper.absolutePos(new BlockPos(4, 3, 4)));
            BoundingBox3i bounds = new BoundingBox3i(helper.absolutePos(new BlockPos(3, 2, 3)), helper.absolutePos(new BlockPos(5, 3, 5)));
            ServerSubLevel ship = SubLevelAssemblyHelper.assembleBlocks(helper.getLevel(), helper.absolutePos(new BlockPos(4, 2, 4)), blocks, bounds);
            helper.assertTrue(ship != null, "assembly failed");

            helper.runAfterDelay(5, () -> {
                try {
                    // Ground radar sees the ship
                    List<Map<String, Object>> subs = ground.peripheral().scanSubLevels(new ObjectArguments(32.0));
                    helper.assertTrue(subs.stream().anyMatch(s -> s.get("id").equals(ship.getUniqueId().toString())), "ground radar did not see the ship: " + subs);

                    // The radar block now lives in the plot grid; its frame must be projected back near the build site
                    RadarBlockEntity onBoard = findRadar(helper, ship);
                    RadarFrame frame = onBoard.frame();
                    helper.assertTrue(ship.getUniqueId().equals(frame.subLevel()), "on-board frame has no sub-level");
                    Vec3 expected = Vec3.atCenterOf(helper.absolutePos(new BlockPos(4, 3, 4)));
                    helper.assertTrue(frame.origin().distanceTo(expected) < 6, "on-board origin " + frame.origin() + " far from " + expected);

                    // It does not report its own ship, but does see the armor stand in the world
                    List<Map<String, Object>> own = onBoard.peripheral().scanSubLevels(new ObjectArguments(32.0));
                    helper.assertTrue(own.stream().noneMatch(s -> s.get("id").equals(ship.getUniqueId().toString())), "own ship listed");
                    List<Map<String, Object>> ents = onBoard.peripheral().scanEntities(new ObjectArguments(32.0, Map.of("type", "minecraft:armor_stand")));
                    helper.assertTrue(ents.stream().anyMatch(e -> e.get("id").equals(stand.getStringUUID())), "on-board radar did not see the stand");
                    helper.assertTrue(onBoard.peripheral().getSelf() != null, "getSelf() is nil on board");
                    AshCC.LOGGER.info("ship contact: {}", subs.get(0));
                    AshCC.LOGGER.info("on-board position: {}", onBoard.peripheral().getPosition());
                    TestShips.remove(helper, ship);
                    helper.succeed();
                } catch (LuaException e) {
                    helper.fail(e.getMessage());
                }
            });
        }

        private static RadarBlockEntity findRadar(GameTestHelper helper, ServerSubLevel ship) {
            LevelPlot plot = ship.getPlot();
            for (int cx = plot.getChunkMin().x; cx <= plot.getChunkMax().x; cx++) {
                for (int cz = plot.getChunkMin().z; cz <= plot.getChunkMax().z; cz++) {
                    for (BlockEntity be : helper.getLevel().getChunk(cx, cz).getBlockEntities().values()) {
                        if (be instanceof RadarBlockEntity radar) return radar;
                    }
                }
            }
            throw new AssertionError("radar not found in plot");
        }
    }
}
