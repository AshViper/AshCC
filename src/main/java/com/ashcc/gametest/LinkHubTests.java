package com.ashcc.gametest;

import com.ashcc.AshCC;
import com.ashcc.link.LinkCardItem;
import com.ashcc.link.LinkHubBlockEntity;
import com.ashcc.link.LinkHubPeripheral;
import com.ashcc.link.LinkTarget;
import dan200.computercraft.api.filesystem.Mount;
import dan200.computercraft.api.filesystem.WritableMount;
import dan200.computercraft.api.lua.ILuaContext;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaTask;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.lua.ObjectArguments;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.WorkMonitor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@GameTestHolder(AshCC.MOD_ID)
@PrefixGameTestTemplate(false)
public class LinkHubTests {
    /** Minimal computer that records queued events. */
    static final class FakeComputer implements IComputerAccess {
        final List<String> events = new ArrayList<>();

        @Override
        public @Nullable String mount(String desiredLocation, Mount mount, String driveName) {
            return null;
        }

        @Override
        public @Nullable String mountWritable(String desiredLocation, WritableMount mount, String driveName) {
            return null;
        }

        @Override
        public void unmount(@Nullable String location) {
        }

        @Override
        public int getID() {
            return 1;
        }

        @Override
        public void queueEvent(String event, @Nullable Object... arguments) {
            events.add(event + " " + (arguments != null && arguments.length > 0 ? arguments[0] : ""));
        }

        @Override
        public String getAttachmentName() {
            return "back";
        }

        @Override
        public Map<String, IPeripheral> getAvailablePeripherals() {
            return Map.of();
        }

        @Override
        public @Nullable IPeripheral getAvailablePeripheral(String name) {
            return null;
        }

        @Override
        public WorkMonitor getMainThreadMonitor() {
            return new WorkMonitor() {
                @Override
                public boolean canWork() {
                    return true;
                }

                @Override
                public boolean shouldWork() {
                    return true;
                }

                @Override
                public void trackWork(long time, TimeUnit unit) {
                }
            };
        }
    }

    static final ILuaContext NO_MAIN_THREAD = new ILuaContext() {
        @Override
        public long issueMainThreadTask(LuaTask task) throws LuaException {
            throw new LuaException("main thread tasks are not available in this test");
        }
    };

    static LinkTarget target(GameTestHelper helper, BlockPos rel, String type) {
        return new LinkTarget(GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(rel)), Direction.UP, type);
    }

    @GameTest(template = RadarTests.EMPTY)
    public static void linksAndCallsRemotePeripherals(GameTestHelper helper) throws LuaException {
        BlockPos hubPos = new BlockPos(1, 1, 1);
        helper.setBlock(hubPos, AshCC.LINK_HUB.get());
        LinkHubBlockEntity hub = (LinkHubBlockEntity) helper.getBlockEntity(hubPos);
        RadarTests.placeRadar(helper, new BlockPos(7, 1, 7), Direction.NORTH);
        ThrusterTests.placeThruster(helper, new BlockPos(7, 1, 1), Direction.DOWN);

        LinkHubBlockEntity.Result r = hub.addTargets(List.of(
            target(helper, new BlockPos(7, 1, 7), "radar"),
            target(helper, new BlockPos(7, 1, 1), "thruster"),
            target(helper, new BlockPos(7, 1, 7), "radar"), // duplicate
            target(helper, hubPos, "link_hub")));          // itself
        helper.assertTrue(r.changed() == 2 && r.skipped() == 2, "added/skipped " + r);

        LinkHubPeripheral p = hub.peripheral();
        FakeComputer computer = new FakeComputer();
        p.attach(computer);

        helper.runAfterDelay(2, () -> {
            try {
                List<String> names = p.getNamesRemote(computer);
                helper.assertTrue(names.containsAll(List.of("radar_0", "thruster_0")), "names " + names);
                helper.assertTrue(computer.events.contains("peripheral radar_0"), "events " + computer.events);
                Object[] type = p.getTypeRemote(computer, "thruster_0");
                helper.assertTrue(type != null && "thruster".equals(type[0]), "type");
                Object[] has = p.hasTypeRemote(computer, "radar_0", "radar");
                helper.assertTrue(has != null && Boolean.TRUE.equals(has[0]), "hasType");

                // Remote call through CC's own method dispatch
                MethodResult res = p.callRemote(computer, NO_MAIN_THREAD, new ObjectArguments("thruster_0", "getMaxThrust"));
                helper.assertTrue(res.getResult() != null && ((Number) res.getResult()[0]).doubleValue() > 0, "callRemote result");

                p.renameLink("thruster_0", "main_engine");
            } catch (LuaException e) {
                helper.fail(e.getMessage());
            }

            // Breaking the radar detaches it; the rename shows up on the next refresh
            helper.setBlock(new BlockPos(7, 1, 7), Blocks.AIR);
            helper.runAfterDelay(12, () -> {
                List<String> names = p.getNamesRemote(computer);
                helper.assertTrue(!names.contains("radar_0") && names.contains("main_engine") && !names.contains("thruster_0"), "names after " + names);
                helper.assertTrue(computer.events.contains("peripheral_detach radar_0"), "events " + computer.events);
                helper.assertTrue(p.getLinks().size() == 2, "the broken radar's link stays registered");
                helper.succeed();
            });
        });
    }

    @GameTest(template = RadarTests.EMPTY)
    public static void cardTogglesTargets(GameTestHelper helper) {
        ItemStack card = new ItemStack(AshCC.LINK_CARD.get());
        LinkTarget t = target(helper, new BlockPos(2, 1, 2), "radar");
        helper.assertTrue(LinkCardItem.toggle(card, t), "first use adds");
        helper.assertTrue(LinkCardItem.targets(card).size() == 1, "one target");
        helper.assertTrue(!LinkCardItem.toggle(card, new LinkTarget(t.pos(), Direction.NORTH, "radar")), "same block again removes");
        helper.assertTrue(LinkCardItem.targets(card).isEmpty(), "empty");
        helper.succeed();
    }
}
