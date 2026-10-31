package com.ashcc.gametest;

import com.ashcc.AshCC;
import com.ashcc.radar.RadarFrame;
import com.ashcc.seeker.SeekerBlock;
import com.ashcc.seeker.SeekerBlockEntity;
import com.ashcc.seeker.SeekerPeripheral;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.ObjectArguments;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;

@GameTestHolder(AshCC.MOD_ID)
@PrefixGameTestTemplate(false)
public class SeekerTests {

    /** Frames of a vertical seeker are still right-handed and orthonormal (local y = north). */
    @GameTest(template = RadarTests.EMPTY)
    public static void verticalFrame(GameTestHelper helper) {
        for (Direction facing : Direction.values()) {
            RadarFrame f = RadarFrame.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO), facing, RadarFrame.upFor(facing));
            helper.assertTrue(Math.abs(f.right().dot(f.up())) < 1e-9 && Math.abs(f.up().dot(f.forward())) < 1e-9
                && f.right().lengthSqr() > 0.99 && f.right().cross(f.up()).distanceTo(f.forward().scale(-1)) < 1e-9, "bad frame for " + facing);
        }
        helper.succeed();
    }

    // Own batch: ships from the thruster tests can fly into a neighbouring test area
    @GameTest(template = "empty_17", timeoutTicks = 60, batch = "seeker")
    public static void picksTheShipClosestToTheCentre(GameTestHelper helper) {
        if (!AshCC.sableLoaded) {
            helper.succeed();
            return;
        }
        Impl.run(helper);
    }

    private static final class Impl {
        static ServerSubLevel ship(GameTestHelper helper, BlockPos rel) {
            helper.setBlock(rel, Blocks.IRON_BLOCK);
            BlockPos abs = helper.absolutePos(rel);
            return SubLevelAssemblyHelper.assembleBlocks(helper.getLevel(), abs, List.of(abs), new BoundingBox3i(abs, abs));
        }

        static void run(GameTestHelper helper) {
            BlockPos pos = new BlockPos(1, 8, 8);
            helper.setBlock(pos, AshCC.SEEKER.get().defaultBlockState().setValue(SeekerBlock.FACING, Direction.EAST));
            SeekerPeripheral p = ((SeekerBlockEntity) helper.getBlockEntity(pos)).peripheral();
            ServerSubLevel ahead = ship(helper, new BlockPos(9, 8, 8));   // 8 blocks, on the line of sight
            ServerSubLevel off = ship(helper, new BlockPos(9, 8, 14));   // ~37 deg off
            ServerSubLevel near = ship(helper, new BlockPos(5, 8, 10));  // 4.5 blocks, ~27 deg off
            java.util.Set<String> ours = java.util.Set.of(ahead.getUniqueId().toString(), off.getUniqueId().toString(), near.getUniqueId().toString());

            // Sable can shift a fresh ship's pose by its centre-of-mass offset on the first tick; read after that
            helper.runAfterDelay(3, () -> {
                try {
                    Map<String, Object> a = p.seek(new ObjectArguments(45.0));
                    helper.assertTrue(a != null && a.get("id").equals(ahead.getUniqueId().toString()), "seek(45) should pick the ship ahead: " + a);
                    // A fresh ship sometimes settles up to half a block off (atan(0.7/8) = 5 deg); exact geometry is checked below
                    helper.assertTrue(RadarTests.num(a, "offAxis") < 6, "offAxis " + a.get("offAxis"));

                    Map<String, Object> d = p.seek(new ObjectArguments());
                    helper.assertTrue(d != null && d.get("id").equals(ahead.getUniqueId().toString()), "seek() with defaults");

                    Map<String, Object> b = p.seek(new ObjectArguments(45.0, 6.0));
                    helper.assertTrue(b != null && b.get("id").equals(near.getUniqueId().toString()), "seek(45, 6) should pick the near ship: " + b);
                    // offAxis must match the actual geometry (the ships fall a little, so compare with where it really is)
                    double offAxis = RadarTests.num(b, "offAxis");
                    Vec3 origin = Vec3.atCenterOf(helper.absolutePos(pos));
                    Vec3 toNear = new Vec3(RadarTests.num(b, "x"), RadarTests.num(b, "y"), RadarTests.num(b, "z")).subtract(origin);
                    double want = Math.toDegrees(Math.acos(toNear.normalize().x)); // line of sight = +X (east)
                    helper.assertTrue(Math.abs(offAxis - want) < 1e-6, "near offAxis " + offAxis + " expected " + want);

                    // Only this test's ships count: ships of other tests can be near the test area
                    Map<String, Object> none = p.seek(new ObjectArguments(20.0, 6.0));
                    helper.assertTrue(none == null || !ours.contains(String.valueOf(none.get("id"))), "seek(20, 6) should find none of ours: " + none);
                    try {
                        p.seek(new ObjectArguments(-1.0));
                        helper.fail("negative angle accepted");
                    } catch (LuaException expected) {
                        // ok
                    }
                    AshCC.LOGGER.info("seeker: ahead offAxis={} near offAxis={}", a.get("offAxis"), offAxis);
                    // Ships outlive the test area; leave nothing for the next batch
                    var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(helper.getLevel());
                    for (ServerSubLevel s : List.of(ahead, off, near)) {
                        container.removeSubLevel(s, dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason.REMOVED);
                    }
                    helper.succeed();
                } catch (LuaException e) {
                    helper.fail(e.getMessage());
                }
            });
        }
    }
}
