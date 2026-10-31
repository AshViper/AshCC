package com.ashcc.gametest;

import com.ashcc.AshCC;
import com.ashcc.link.LinkHubBlockEntity;
import com.ashcc.link.LinkHubPeripheral;
import com.ashcc.tracker.HeadTrackerPeripheral;
import com.ashcc.tracker.TrackedPlayer;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.lua.ObjectArguments;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.players.PlayerList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@GameTestHolder(AshCC.MOD_ID)
@PrefixGameTestTemplate(false)
public class HeadTrackerTests {
    private static boolean near(Object value, double expected) {
        return Math.abs(((Number) value).doubleValue() - expected) < 0.01;
    }

    /**
     * A player that the player list reports as online. Not logged in for real: login handlers (Create Simulated's
     * data sync) send payloads that a mock connection cannot carry.
     */
    static ServerPlayer mockPlayer(GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
            new GameProfile(UUID.randomUUID(), "tracker-mock"), ClientInformation.createDefault());
        PlayerList list = helper.getLevel().getServer().getPlayerList();
        playersByUuid(list).put(player.getUUID(), player);
        return player;
    }

    static void logout(GameTestHelper helper, ServerPlayer player) {
        PlayerList list = helper.getLevel().getServer().getPlayerList();
        playersByUuid(list).remove(player.getUUID());
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, ServerPlayer> playersByUuid(PlayerList list) {
        try {
            Field f = PlayerList.class.getDeclaredField("playersByUUID");
            f.setAccessible(true);
            return (Map<UUID, ServerPlayer>) f.get(list);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @GameTest(template = RadarTests.EMPTY)
    public static void tracksHeadThroughLinkHub(GameTestHelper helper) {
        BlockPos hubPos = new BlockPos(1, 1, 1);
        helper.setBlock(hubPos, AshCC.LINK_HUB.get());
        LinkHubBlockEntity hub = (LinkHubBlockEntity) helper.getBlockEntity(hubPos);

        ServerPlayer player = mockPlayer(helper);
        Vec3 at = helper.absoluteVec(new Vec3(4.5, 1, 7.5));
        // Minecraft yaw 90 = west, pitch -30 = 30 degrees up
        player.moveTo(at.x, at.y, at.z, 90, -30);
        TrackedPlayer tracked = new TrackedPlayer(player.getUUID(), player.getGameProfile().getName());

        helper.assertTrue(helper.getLevel().getServer().getPlayerList().getPlayer(player.getUUID()) == player, "mock player is online");
        helper.assertTrue(hub.addPlayer(tracked), "first add");
        helper.assertTrue(!hub.addPlayer(tracked), "same player again is skipped");

        LinkHubPeripheral p = hub.peripheral();
        LinkHubTests.FakeComputer computer = new LinkHubTests.FakeComputer();
        p.attach(computer);

        helper.runAfterDelay(2, () -> {
            try {
                List<String> names = p.getNamesRemote(computer);
                helper.assertTrue(names.contains("head_tracker_0"), "names " + names);
                Object[] type = p.getTypeRemote(computer, "head_tracker_0");
                helper.assertTrue(type != null && "head_tracker".equals(type[0]), "type");
                MethodResult who = p.callRemote(computer, LinkHubTests.NO_MAIN_THREAD, new ObjectArguments("head_tracker_0", "getPlayer"));
                helper.assertTrue(tracked.name().equals(who.getResult()[0]), "getPlayer");

                HeadTrackerPeripheral t = new HeadTrackerPeripheral(hub, tracked);
                Object[] a = t.getAngles().getResult();
                helper.assertTrue(near(a[0], -90) && near(a[1], 30), "world angles west/up " + a[0] + ", " + a[1]);
                Object[] l = t.getLocalAngles().getResult();
                helper.assertTrue(near(l[0], -90) && near(l[1], 30), "local angles off-ship " + l[0] + ", " + l[1]);

                // Look north, level, at a stone block 4 blocks away
                player.moveTo(at.x, at.y, at.z, 180, 0);
                helper.setBlock(new BlockPos(4, 2, 3), Blocks.STONE);
                helper.assertTrue(near(t.getYaw(), 0) && near(t.getPitch(), 0), "north " + t.getYaw());
                Map<String, Object> hit = t.getLookTarget(new ObjectArguments());
                helper.assertTrue("block".equals(hit.get("type")) && "minecraft:stone".equals(hit.get("block")), "look target " + hit);
                helper.assertTrue(near(hit.get("distance"), 3.5), "distance " + hit.get("distance"));
            } catch (LuaException e) {
                helper.fail(e.getMessage());
            }

            // Logging out detaches the peripheral, the link stays registered
            logout(helper, player);
            helper.runAfterDelay(12, () -> {
                helper.assertTrue(!p.getNamesRemote(computer).contains("head_tracker_0"), "detached when offline");
                helper.assertTrue(computer.events.contains("peripheral_detach head_tracker_0"), "events " + computer.events);
                helper.assertTrue(p.getLinks().size() == 1, "link kept");
                helper.assertTrue(hub.removePlayer(tracked) && hub.linkCount() == 0, "removed");
                helper.succeed();
            });
        });
    }
}
