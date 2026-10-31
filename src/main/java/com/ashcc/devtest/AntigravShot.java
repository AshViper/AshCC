package com.ashcc.devtest;

import com.ashcc.AshCC;
import com.ashcc.antigrav.AntigravBlockEntity;
import com.ashcc.antigrav.ShipState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dev-only (excluded from the jar). {@code gradlew runTestClient -PantigravShot -Pworld=ashcc_antigrav}:
 * builds a ship with an anti-gravity core and an advanced computer, runs the real {@code lua/antigrav_pilot.lua fly 5}
 * on it, feeds it key events (Space, W, E, W, X, Backspace), logs the ship's motion and the computer's screen as
 * "AntigravShot", takes screenshots into run-test/screenshots and quits.
 * {@code -PantigravShot=test} runs {@code lua/antigrav_test.lua} instead and copies its log into the game log.
 */
@EventBusSubscriber(modid = AshCC.MOD_ID, value = Dist.CLIENT)
public final class AntigravShot {
    private static final boolean ENABLED = System.getProperty("ashcc.antigravshot") != null;
    private static final boolean TEST = "test".equals(System.getProperty("ashcc.antigravshot"));
    private static final int KEY_W = 87, KEY_E = 69, KEY_X = 88, KEY_SPACE = 32, KEY_BACKSPACE = 259;
    private static final BlockPos CORE = new BlockPos(0, -56, 0);
    private static int tick;
    private static Object ship;

    private AntigravShot() {
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!ENABLED || mc.player == null || mc.getSingleplayerServer() == null) return;
        MinecraftServer server = mc.getSingleplayerServer();
        mc.getToasts().clear();
        int t = ++tick;
        if (TEST) {
            testTick(mc, server, t);
            return;
        }
        if (t >= 100 && t % 20 == 0) server.execute(() -> log(server, "t=" + t));
        switch (t) {
            case 40 -> {
                cmd(server, "clear @p");
                cmd(server, "time set noon");
                cmd(server, "fill -20 -60 -20 40 -20 40 air");
                server.execute(() -> ship = Sable.build(server.overworld()));
            }
            case 60 -> mc.player.getAbilities().flying = true;
            case 80 -> server.execute(() -> Sable.startComputer(server, "antigrav_pilot fly 5"));
            case 110 -> view(server, mc, "antigrav_1_hover");
            case 120 -> key(server, "key", KEY_SPACE);      // climb at 5 b/s for 2 s
            case 160 -> key(server, "key_up", KEY_SPACE);
            case 170 -> key(server, "key", KEY_W);          // forward (north) for 3 s
            case 200 -> view(server, mc, "antigrav_2_forward");
            case 230 -> key(server, "key_up", KEY_W);
            case 240 -> key(server, "key", KEY_E);          // turn right at 45 deg/s for 2 s
            case 280 -> key(server, "key_up", KEY_E);       // -> level(), holds heading ~90 (east)
            case 300 -> key(server, "key", KEY_W);          // forward = east now
            case 330 -> view(server, mc, "antigrav_3_turned_forward");
            case 360 -> key(server, "key_up", KEY_W);
            case 365 -> key(server, "key", KEY_X);          // stop
            case 400 -> view(server, mc, "antigrav_4_stopped");
            case 410 -> key(server, "key", KEY_BACKSPACE);  // quit fly
            case 440 -> mc.stop();
            default -> {
            }
        }
    }

    /** Runs antigrav_test and waits (up to 3 minutes) for its "done:" line. */
    private static void testTick(Minecraft mc, MinecraftServer server, int t) {
        switch (t) {
            case 40 -> {
                cmd(server, "clear @p");
                cmd(server, "time set noon");
                cmd(server, "fill -20 -60 -20 40 -20 40 air");
                server.execute(() -> ship = Sable.build(server.overworld()));
            }
            case 60 -> mc.player.getAbilities().flying = true;
            case 80 -> server.execute(() -> Sable.startComputer(server, "antigrav_test"));
            case 120 -> view(server, mc, "antigrav_test_start");
            default -> {
            }
        }
        if (t > 100 && t % 100 == 0) server.execute(() -> log(server, "t=" + t));
        if (t > 120 && t % 20 == 0 && !finished) {
            String result = Sable.readLog(server, "antigrav_test.log");
            if (result != null && result.contains("done:") || t > 3700) {
                finished = true;
                AshCC.LOGGER.info("AntigravShot test log:\n{}", result);
                view(server, mc, "antigrav_test_end");
                stopAt = t + 30;
            }
        }
        if (t == stopAt) mc.stop();
    }

    private static boolean finished;
    private static int stopAt = -1;

    private static void cmd(MinecraftServer server, String command) {
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command));
    }

    private static void key(MinecraftServer server, String event, int key) {
        server.execute(() -> {
            AshCC.LOGGER.info("AntigravShot: {} {}", event, key);
            Sable.queue(server, event, key);
        });
    }

    /** Puts the camera 7 blocks south-west of the ship, looking at it, then screenshots a few ticks later. */
    private static void view(MinecraftServer server, Minecraft mc, String name) {
        server.execute(() -> {
            ShipState s = core(server) == null ? null : core(server).shipState();
            if (s == null) return;
            Vec3 p = s.position();
            cmd(server, String.format(java.util.Locale.ROOT, "tp @p %.2f %.2f %.2f facing %.2f %.2f %.2f", p.x - 5, p.y + 2.5, p.z + 5, p.x, p.y, p.z));
        });
        // Let the camera settle for a few frames before grabbing
        new Thread(() -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
            }
            mc.execute(() -> Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), msg -> AshCC.LOGGER.info("AntigravShot: {}", msg.getString())));
        }).start();
    }

    private static AntigravBlockEntity core(MinecraftServer server) {
        return ship == null ? null : Sable.core(server.overworld(), ship);
    }

    private static void log(MinecraftServer server, String when) {
        AntigravBlockEntity c = core(server);
        if (c == null) return;
        ShipState s = c.shipState();
        if (s == null) {
            AshCC.LOGGER.info("AntigravShot {}: core not on a ship", when);
            return;
        }
        AshCC.LOGGER.info("AntigravShot {}: active={} lin={} {} ang={} {} pos=({}, {}, {}) vel=({}, {}, {}) yaw={} pitch={} roll={}",
            when, c.driving(), c.linearMode(), c.linear(), c.angularMode(), c.angular(),
            f(s.position().x), f(s.position().y), f(s.position().z), f(s.velocity().x), f(s.velocity().y), f(s.velocity().z),
            f(s.yaw()), f(s.pitch()), f(s.roll()));
        Sable.logScreen(server, when);
    }

    private static String f(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /** Sable and CC internals stay in here so the class loads without them. */
    private static final class Sable {
        private static BlockPos computerPlot;

        static Object build(ServerLevel level) {
            // 3x3 iron deck hovering in the air, core in the middle, advanced computer on top of the core
            java.util.List<BlockPos> blocks = new java.util.ArrayList<>();
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                BlockPos p = new BlockPos(x, CORE.getY(), z);
                level.setBlockAndUpdate(p, x == 0 && z == 0 ? AshCC.ANTIGRAV.get().defaultBlockState() : Blocks.IRON_BLOCK.defaultBlockState());
                blocks.add(p);
            }
            BlockPos top = CORE.above();
            level.setBlockAndUpdate(top, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("computercraft:computer_advanced")).defaultBlockState());
            blocks.add(top);
            var sub = dev.ryanhcode.sable.api.SubLevelAssemblyHelper.assembleBlocks(level, CORE, blocks,
                new dev.ryanhcode.sable.companion.math.BoundingBox3i(new BlockPos(-1, CORE.getY(), -1), new BlockPos(1, top.getY(), 1)));
            AshCC.LOGGER.info("AntigravShot: ship {}", sub == null ? null : sub.getUniqueId());
            return sub;
        }

        static java.util.List<BlockEntity> plotBlockEntities(ServerLevel level, Object ship) {
            java.util.List<BlockEntity> out = new java.util.ArrayList<>();
            if (ship instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel sub) {
                var plot = sub.getPlot();
                for (int cx = plot.getChunkMin().x; cx <= plot.getChunkMax().x; cx++) {
                    for (int cz = plot.getChunkMin().z; cz <= plot.getChunkMax().z; cz++) {
                        out.addAll(level.getChunk(cx, cz).getBlockEntities().values());
                    }
                }
            }
            return out;
        }

        static AntigravBlockEntity core(ServerLevel level, Object ship) {
            for (BlockEntity be : plotBlockEntities(level, ship)) if (be instanceof AntigravBlockEntity c) return c;
            return null;
        }

        static dan200.computercraft.shared.computer.core.ServerComputer computer(MinecraftServer server) {
            for (BlockEntity be : plotBlockEntities(server.overworld(), ship)) {
                if (be instanceof dan200.computercraft.shared.computer.blocks.AbstractComputerBlockEntity c) return c.createServerComputer();
            }
            return null;
        }

        static Path computerDir(MinecraftServer server, int id) {
            return server.getWorldPath(LevelResource.ROOT).resolve("computercraft/computer/" + id);
        }

        static String readLog(MinecraftServer server, String file) {
            var computer = computer(server);
            if (computer == null) return null;
            try {
                Path p = computerDir(server, computer.getID()).resolve(file);
                return Files.exists(p) ? Files.readString(p) : null;
            } catch (java.io.IOException e) {
                return null;
            }
        }

        /** Copies the pilot and test programs onto the ship's computer and boots it into {@code command}. */
        static void startComputer(MinecraftServer server, String command) {
            var computer = computer(server);
            if (computer == null) {
                AshCC.LOGGER.error("AntigravShot: no computer on the ship");
                return;
            }
            Path dir = computerDir(server, computer.getID());
            try {
                Files.createDirectories(dir);
                for (String program : new String[]{"antigrav_pilot.lua", "antigrav_test.lua"}) {
                    Files.copy(Path.of(System.getProperty("user.dir")).getParent().resolve("lua/" + program), dir.resolve(program),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                Files.writeString(dir.resolve("startup.lua"), "shell.run(\"" + command + "\")\n");
            } catch (java.io.IOException e) {
                AshCC.LOGGER.error("AntigravShot: could not write the computer's files", e);
                return;
            }
            AshCC.LOGGER.info("AntigravShot: computer {} files in {}", computer.getID(), dir);
            if (computer.isOn()) computer.reboot();
            else computer.turnOn();
        }

        static void queue(MinecraftServer server, String event, int key) {
            var computer = computer(server);
            if (computer == null) return;
            if (event.equals("key")) computer.queueEvent(event, new Object[]{key, false});
            else computer.queueEvent(event, new Object[]{key});
        }

        static void logScreen(MinecraftServer server, String when) {
            var computer = computer(server);
            if (computer == null) return;
            var term = computer.getTerminalState().create();
            StringBuilder sb = new StringBuilder();
            for (int y = 0; y < term.getHeight(); y++) {
                String line = term.getLine(y).toString().stripTrailing();
                if (!line.isEmpty()) sb.append("\n    | ").append(line);
            }
            AshCC.LOGGER.info("AntigravShot {} screen (on={}):{}", when, computer.isOn(), sb);
        }
    }
}
