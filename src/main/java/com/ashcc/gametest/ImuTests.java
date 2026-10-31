package com.ashcc.gametest;

import com.ashcc.AshCC;
import com.ashcc.compat.SableCompat;
import com.ashcc.imu.ComputerImuApi;
import com.ashcc.imu.ImuSampler;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The imu API's maths, and a spinning ship (see {@link ComputerImuTests} for the computer on the ground and falling). */
@GameTestHolder(AshCC.MOD_ID)
@PrefixGameTestTemplate(false)
public class ImuTests {

    static double get(Map<String, Object> t, String vec, String axis) {
        return RadarTests.num((Map<?, ?>) t.get(vec), axis);
    }

    /** dq -> angular velocity: 0.1 rad about +Y in one tick = 2 rad/s, which turns north towards west (heading falls). */
    @GameTest(template = RadarTests.EMPTY)
    public static void angularVelocityFromOrientation(GameTestHelper helper) {
        Quaterniond q0 = new Quaterniond().rotateX(0.3);
        Quaterniond q1 = new Quaterniond().rotateY(0.1).mul(q0); // world-space turn about Y
        Vec3 w = ImuSampler.angularVelocity(q0, q1);
        helper.assertTrue(w.distanceTo(new Vec3(0, 2, 0)) < 1e-9, "w " + w);
        Vec3 back = ImuSampler.angularVelocity(q1, q0);
        helper.assertTrue(back.distanceTo(new Vec3(0, -2, 0)) < 1e-9, "reverse " + back);
        // Sign of the quaternion must not matter (q and -q are the same rotation)
        Quaterniond neg = new Quaterniond(-q1.x, -q1.y, -q1.z, -q1.w);
        helper.assertTrue(ImuSampler.angularVelocity(q0, neg).distanceTo(new Vec3(0, 2, 0)) < 1e-9, "-q");
        helper.succeed();
    }

    /** A computer on a spinning ship: yawRate agrees with the change of yaw between ticks. */
    @GameTest(template = "empty_17", timeoutTicks = 80, batch = "imu_spin")
    public static void spinRateMatchesHeading(GameTestHelper helper) {
        if (!AshCC.sableLoaded) {
            helper.succeed();
            return;
        }
        Impl.spin(helper);
    }

    private static final class Impl {
        static void spin(GameTestHelper helper) {
            List<BlockPos> blocks = new ArrayList<>();
            for (int x = 6; x <= 10; x++) {
                helper.setBlock(new BlockPos(x, 8, 8), Blocks.IRON_BLOCK);
                blocks.add(helper.absolutePos(new BlockPos(x, 8, 8)));
            }
            helper.setBlock(new BlockPos(8, 9, 8), ComputerImuTests.computer().defaultBlockState());
            blocks.add(helper.absolutePos(new BlockPos(8, 9, 8)));
            ServerSubLevel ship = SubLevelAssemblyHelper.assembleBlocks(helper.getLevel(), blocks.get(0), blocks,
                new BoundingBox3i(blocks.get(0), helper.absolutePos(new BlockPos(10, 9, 8))));
            helper.runAfterDelay(4, () -> {
                BlockPos pc = ComputerImuTests.Impl.computerInPlot(helper, ship);
                // Push the east end of the deck sideways to spin the ship about the vertical
                Vec3 end = SableCompat.pointToWorld(helper.getLevel(), pc, Vec3.atCenterOf(pc).add(2, -1, 0));
                SableCompat.applyImpulse(helper.getLevel(), pc, end, new Vec3(0, 0, 40));
                ComputerImuTests.FakeComputer computer = new ComputerImuTests.FakeComputer(helper.getLevel(), pc);
                ComputerImuApi api = new ComputerImuApi(computer);
                api.startup();
                try {
                    api.setStream(1);
                } catch (Exception e) {
                    helper.fail(e.getMessage());
                }
                for (int i = 0; i < 6; i++) {
                    int n = i;
                    helper.runAfterDelay(2 + i, () -> {
                        api.update();
                        if (n == 5) {
                            check(helper, computer);
                            TestShips.remove(helper, ship);
                        }
                    });
                }
            });
        }

        @SuppressWarnings("unchecked")
        static void check(GameTestHelper helper, ComputerImuTests.FakeComputer computer) {
            List<Object[]> ev = computer.events;
            Map<String, Object> a = (Map<String, Object>) ((Object[]) ev.get(ev.size() - 2)[1])[1];
            Map<String, Object> b = computer.lastReading();
            double dyaw = RadarTests.num(b, "yaw") - RadarTests.num(a, "yaw");
            if (dyaw > 180) dyaw -= 360;
            if (dyaw < -180) dyaw += 360;
            double rate = RadarTests.num(b, "yawRate");
            AshCC.LOGGER.info("imu spin: dyaw*20={} yawRate={} pitchRate={} rollRate={}", dyaw * 20, rate, b.get("pitchRate"), b.get("rollRate"));
            helper.assertTrue(Math.abs(rate) > 2, "ship does not spin: yawRate " + rate);
            helper.assertTrue(Math.abs(dyaw * 20 - rate) < Math.max(1, Math.abs(rate) * 0.1), "yawRate " + rate + " vs heading change " + dyaw * 20);
            helper.succeed();
        }
    }
}
