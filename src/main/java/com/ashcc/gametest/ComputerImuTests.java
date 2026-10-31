package com.ashcc.gametest;

import com.ashcc.AshCC;
import com.ashcc.imu.ComputerImuApi;
import dan200.computercraft.api.component.ComputerComponent;
import dan200.computercraft.api.filesystem.Mount;
import dan200.computercraft.api.filesystem.WritableMount;
import dan200.computercraft.api.lua.IComputerSystem;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.WorkMonitor;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The global imu API, driven through a stand-in for the computer (same calls CC makes). */
@GameTestHolder(AshCC.MOD_ID)
@PrefixGameTestTemplate(false)
public class ComputerImuTests {

    /** Just enough of a computer: where it is, and the events it was sent. */
    static final class FakeComputer implements IComputerSystem {
        final ServerLevel level;
        BlockPos pos;
        final List<Object[]> events = new ArrayList<>();

        FakeComputer(ServerLevel level, BlockPos pos) {
            this.level = level;
            this.pos = pos;
        }

        @Override public ServerLevel getLevel() { return level; }
        @Override public BlockPos getPosition() { return pos; }
        @Override public @Nullable String getLabel() { return null; }
        @Override public <T> @Nullable T getComponent(ComputerComponent<T> component) { return null; }
        @Override public @Nullable String mount(String desired, Mount mount, String driveName) { return null; }
        @Override public @Nullable String mountWritable(String desired, WritableMount mount, String driveName) { return null; }
        @Override public void unmount(@Nullable String location) { }
        @Override public int getID() { return 0; }
        @Override public void queueEvent(String event, @Nullable Object... arguments) { events.add(new Object[]{event, arguments}); }
        @Override public String getAttachmentName() { return "computer"; }
        @Override public Map<String, IPeripheral> getAvailablePeripherals() { return Map.of(); }
        @Override public @Nullable IPeripheral getAvailablePeripheral(String name) { return null; }
        @Override public WorkMonitor getMainThreadMonitor() { throw new UnsupportedOperationException(); }

        @SuppressWarnings("unchecked")
        Map<String, Object> lastReading() {
            Object[] e = events.get(events.size() - 1);
            return (Map<String, Object>) ((Object[]) e[1])[1];
        }
    }

    static Block computer() {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse("computercraft:computer_normal"));
    }

    /** A computer on the ground: heading from its front, level, 1 g up, via the stream event and a getter. */
    @GameTest(template = RadarTests.EMPTY)
    public static void computerOnGround(GameTestHelper helper) throws Exception {
        BlockPos rel = new BlockPos(4, 1, 4);
        helper.setBlock(rel, computer().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        FakeComputer pc = new FakeComputer(helper.getLevel(), helper.absolutePos(rel));
        ComputerImuApi api = new ComputerImuApi(pc);
        helper.assertTrue(List.of(api.getNames()).equals(List.of("imu")), "global name");
        api.startup();
        api.update();
        helper.assertTrue(pc.events.isEmpty(), "measured while nobody asked");
        api.setStream(1);
        api.update();
        api.update();
        helper.assertTrue(pc.events.size() == 2 && "imu".equals(pc.events.get(0)[0]), "stream events " + pc.events.size());
        Map<String, Object> t = pc.lastReading();
        helper.assertTrue(Math.abs(RadarTests.num(t, "yaw") - 90) < 1e-6, "yaw " + t.get("yaw"));
        helper.assertTrue(Math.abs(ImuTests.get(t, "gForce", "y") - 1) < 1e-6 && RadarTests.num(t, "speed") == 0, "still, 1 g: " + t);
        // Getters answer from the latest reading without the main thread (no context needed)
        Map<?, ?> angles = (Map<?, ?>) api.getAngles(null).getResult()[0];
        helper.assertTrue(Math.abs(RadarTests.num(angles, "yaw") - 90) < 1e-6, "getAngles " + angles);
        api.setStream(0);
        helper.succeed();
    }

    /** A pocket computer's holder: forward = where it looks horizontally (Minecraft yaw 90 = west = heading -90). */
    @GameTest(template = RadarTests.EMPTY)
    public static void pocketHolderFrame(GameTestHelper helper) {
        ArmorStand stand = RadarTests.stand(helper, 4.5, 2, 4.5);
        stand.setYRot(90);
        var f = ComputerImuApi.holderFrame(stand);
        helper.assertTrue(f.forward().distanceTo(new net.minecraft.world.phys.Vec3(-1, 0, 0)) < 1e-6, "forward " + f.forward());
        helper.assertTrue(f.right().distanceTo(new net.minecraft.world.phys.Vec3(0, 0, -1)) < 1e-6, "right " + f.right());
        helper.succeed();
    }

    /** A computer on a falling ship reads ~0 g and a falling velocity. */
    @GameTest(template = "empty_17", timeoutTicks = 80, batch = "imu_computer_fall")
    public static void computerOnFallingShip(GameTestHelper helper) {
        if (!AshCC.sableLoaded) {
            helper.succeed();
            return;
        }
        Impl.run(helper);
    }

    static final class Impl {
        static void run(GameTestHelper helper) {
            BlockPos deck = new BlockPos(8, 12, 8), top = new BlockPos(8, 13, 8);
            helper.setBlock(deck, Blocks.IRON_BLOCK);
            helper.setBlock(top, computer().defaultBlockState());
            BlockPos a = helper.absolutePos(deck), b = helper.absolutePos(top);
            ServerSubLevel ship = SubLevelAssemblyHelper.assembleBlocks(helper.getLevel(), a, List.of(a, b), new BoundingBox3i(a, b));
            helper.runAfterDelay(4, () -> {
                FakeComputer pc = new FakeComputer(helper.getLevel(), computerInPlot(helper, ship));
                ComputerImuApi api = new ComputerImuApi(pc);
                api.startup();
                try {
                    api.setStream(1);
                } catch (Exception e) {
                    helper.fail(e.getMessage());
                }
                for (int i = 0; i < 6; i++) {
                    int n = i;
                    helper.runAfterDelay(i, () -> {
                        api.update();
                        if (n < 5) return;
                        Map<String, Object> t = pc.lastReading();
                        AshCC.LOGGER.info("computer imu fall: v={} g={} onShip={}", t.get("velocity"), t.get("gForce"), t.get("onShip"));
                        helper.assertTrue((Boolean) t.get("onShip"), "not on ship");
                        helper.assertTrue(ImuTests.get(t, "velocity", "y") < -1, "not falling " + t.get("velocity"));
                        helper.assertTrue(Math.abs(ImuTests.get(t, "gForce", "y")) < 0.3, "free fall g " + t.get("gForce"));
                        TestShips.remove(helper, ship);
                        helper.succeed();
                    });
                }
            });
        }

        static BlockPos computerInPlot(GameTestHelper helper, ServerSubLevel ship) {
            LevelPlot plot = ship.getPlot();
            for (int cx = plot.getChunkMin().x; cx <= plot.getChunkMax().x; cx++) {
                for (int cz = plot.getChunkMin().z; cz <= plot.getChunkMax().z; cz++) {
                    for (BlockEntity be : helper.getLevel().getChunk(cx, cz).getBlockEntities().values()) {
                        if (be.getBlockState().is(computer())) return be.getBlockPos();
                    }
                }
            }
            throw new AssertionError("computer not found in plot");
        }
    }
}
