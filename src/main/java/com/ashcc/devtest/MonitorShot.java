package com.ashcc.devtest;

import com.ashcc.AshCC;
import com.ashcc.monitor.HdMonitorBlock;
import com.ashcc.monitor.HdMonitorBlockEntity;
import com.ashcc.monitor.HdMonitorPeripheral;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.ObjectArguments;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Dev-only (excluded from the jar). {@code gradlew runTestClient -PmonitorShot -Pworld=ashcc_monitorshot}:
 * builds a 4x3 wall monitor, a 2x2 hologram floor monitor and a 2x1 monitor on a Sable ship, draws on them,
 * screenshots, checks where the crosshair hits in pixels ("MonitorShot" in the log) and quits.
 */
@EventBusSubscriber(modid = AshCC.MOD_ID, value = Dist.CLIENT)
public final class MonitorShot {
    private static final boolean ENABLED = System.getProperty("ashcc.monitorshot") != null;
    private static int tick;
    private static Object ship;

    private MonitorShot() {
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!ENABLED || mc.player == null || mc.getSingleplayerServer() == null) return;
        MinecraftServer server = mc.getSingleplayerServer();
        mc.getToasts().clear();
        switch (++tick) {
            case 40 -> {
                cmd(server, "clear @p");
                cmd(server, "time set noon");
                cmd(server, "fill -12 -60 -12 24 -50 24 air");
                cmd(server, "fill -12 -61 -6 24 -61 -1 stone_bricks");
                server.execute(() -> build(server.overworld()));
            }
            case 60 -> {
                mc.player.getAbilities().flying = true;
                cmd(server, "tp @p 2 -59.6 4.5 facing 2 -57.5 0");
            }
            case 80 -> server.execute(() -> draw(server.overworld()));
            case 110 -> shot(mc, "monitor_1_front");
            case 115 -> cmd(server, "tp @p 1.25 -59.12 4 facing 1.25 -59.12 0.5");
            case 125 -> logTarget("aim (1.25, -57.5) expect ~(157, 192)");
            case 130 -> cmd(server, "tp @p 7 -56.5 4.5 facing 7 -60 1");
            case 150 -> shot(mc, "monitor_2_hologram_floor");
            case 155 -> server.execute(() -> ship = ShipPart.build(server.overworld()));
            case 175 -> server.execute(() -> ShipPart.draw(server.overworld(), ship));
            case 180 -> server.execute(() -> ShipPart.turn(ship, 30));
            case 185 -> cmd(server, "tp @p -3 -59.6 -4 facing -3 -58.5 -8");
            case 215 -> shot(mc, "monitor_3_ship_turned_30");
            case 225 -> mc.stop();
            default -> {
            }
        }
    }

    static BlockState monitor(Direction facing, Direction rotation) {
        return AshCC.HD_MONITOR.get().defaultBlockState().setValue(HdMonitorBlock.FACING, facing).setValue(HdMonitorBlock.ROTATION, rotation);
    }

    private static void build(ServerLevel level) {
        for (int x = 0; x < 4; x++) for (int y = -59; y <= -57; y++) level.setBlockAndUpdate(new BlockPos(x, y, 0), monitor(Direction.SOUTH, Direction.NORTH));
        for (int x = 6; x < 8; x++) for (int z = 0; z < 2; z++) level.setBlockAndUpdate(new BlockPos(x, -60, z), monitor(Direction.UP, Direction.NORTH));
        level.setBlockAndUpdate(new BlockPos(6, -60, 4), Blocks.REDSTONE_LAMP.defaultBlockState());
    }

    static String utf8(String s) {
        return new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    private static void draw(ServerLevel level) {
        try {
            HdMonitorPeripheral p = ((HdMonitorBlockEntity) level.getBlockEntity(new BlockPos(0, -57, 0))).peripheral();
            AshCC.LOGGER.info("MonitorShot: wall {}", java.util.Arrays.toString(p.getResolution()));
            p.setAutoPresent(false);
            p.fill(new ObjectArguments(0x0B1622));
            for (int x = 0; x < 512; x += 4) p.fillRect(new ObjectArguments(x, 0, 4, 40, (x / 2) << 16 | (255 - x / 2)));
            for (int r = 40; r <= 160; r += 40) p.drawCircle(new ObjectArguments(380, 220, r, 0x33DD66, 1.5));
            for (int a = 0; a < 360; a += 30) {
                double t = Math.toRadians(a);
                p.drawLine(new ObjectArguments(380, 220, 380 + 160 * Math.cos(t), 220 + 160 * Math.sin(t), "#33dd6680", 1));
            }
            p.fillCircle(new ObjectArguments(430, 170, 6, 0xFF4040));
            double[] star = new double[20];
            for (int i = 0; i < 10; i++) {
                double rr = i % 2 == 0 ? 70 : 28, t = Math.toRadians(-90 + i * 36);
                star[2 * i] = 110 + rr * Math.cos(t);
                star[2 * i + 1] = 200 + rr * Math.sin(t);
            }
            Map<Double, Object> pts = new java.util.HashMap<>();
            for (int i = 0; i < star.length; i++) pts.put((double) (i + 1), star[i]);
            p.fillPolygon(new ObjectArguments(pts, "#ffcc00c0"));
            p.drawPolygon(new ObjectArguments(pts, 0xFFFFFF, 2));
            p.drawText(new ObjectArguments(8, 48, utf8("高精細モニター 512x384"), 0xFFFFFF, Map.of("scale", 2.0)));
            p.drawText(new ObjectArguments(8, 84, utf8("Unicode: ÄÖÜ αβγ Привет ♥ →"), 0xA0D0FF));
            p.fillRect(new ObjectArguments(200, 120, 120, 80, "#ff000060"));
            p.setTextScale(0.5);
            p.setCursorPos(1, 24);
            p.setBackgroundColour(1 << 11);
            p.setTextColour(1);
            p.write(new ObjectArguments(" term.write OK "));
            p.blit(new ObjectArguments("blit", "e145", "ffff"));
            p.setCursorBlink(true);
            p.present();

            HdMonitorPeripheral holo = ((HdMonitorBlockEntity) level.getBlockEntity(new BlockPos(6, -60, 0))).peripheral();
            holo.setHologram(true);
            holo.fill(new ObjectArguments("transparent"));
            holo.drawCircle(new ObjectArguments(128, 128, 100, "#40c0ff", 4));
            holo.fillCircle(new ObjectArguments(128, 128, 60, "#40c0ff40"));
            holo.drawText(new ObjectArguments(128, 120, utf8("ホログラム"), 0xFFFFFF, Map.of("scale", 2.0, "align", "center")));
        } catch (LuaException e) {
            AshCC.LOGGER.error("MonitorShot: draw failed", e);
        }
    }

    private static void logTarget(String label) {
        try {
            var m = Class.forName("com.ashcc.monitor.client.MonitorClient").getDeclaredMethod("target");
            m.setAccessible(true);
            Minecraft mc = Minecraft.getInstance();
            AshCC.LOGGER.info("MonitorShot: {} -> {} (eye {}, hit {})", label, m.invoke(null), mc.player.getEyePosition(), mc.hitResult == null ? null : mc.hitResult.getLocation());
        } catch (ReflectiveOperationException e) {
            AshCC.LOGGER.error("MonitorShot: target failed", e);
        }
    }

    private static void cmd(MinecraftServer server, String command) {
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command));
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), msg -> AshCC.LOGGER.info("MonitorShot: {}", msg.getString()));
    }

    /** Sable types stay in here so the class loads without them. */
    private static final class ShipPart {
        static Object build(ServerLevel level) {
            java.util.List<BlockPos> blocks = new java.util.ArrayList<>();
            for (int x = -4; x <= -2; x++) for (int z = -9; z <= -7; z++) {
                BlockPos p = new BlockPos(x, -60, z);
                level.setBlockAndUpdate(p, Blocks.IRON_BLOCK.defaultBlockState());
                blocks.add(p);
            }
            for (int x = -4; x <= -3; x++) {
                BlockPos p = new BlockPos(x, -59, -8);
                level.setBlockAndUpdate(p, monitor(Direction.SOUTH, Direction.NORTH));
                blocks.add(p);
            }
            var sub = dev.ryanhcode.sable.api.SubLevelAssemblyHelper.assembleBlocks(level, new BlockPos(-3, -60, -8), blocks,
                new dev.ryanhcode.sable.companion.math.BoundingBox3i(new BlockPos(-4, -60, -9), new BlockPos(-2, -59, -7)));
            AshCC.LOGGER.info("MonitorShot: ship {}", sub == null ? null : sub.getUniqueId());
            return sub;
        }

        static void draw(ServerLevel level, Object ship) {
            if (!(ship instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel sub)) return;
            var plot = sub.getPlot();
            for (int cx = plot.getChunkMin().x; cx <= plot.getChunkMax().x; cx++) {
                for (int cz = plot.getChunkMin().z; cz <= plot.getChunkMax().z; cz++) {
                    for (BlockEntity be : level.getChunk(cx, cz).getBlockEntities().values()) {
                        if (!(be instanceof HdMonitorBlockEntity m) || !m.isOrigin()) continue;
                        AshCC.LOGGER.info("MonitorShot: ship monitor at {} size {}x{}", be.getBlockPos(), m.width(), m.height());
                        try {
                            HdMonitorPeripheral p = m.peripheral();
                            p.fill(new ObjectArguments(0x202830));
                            p.drawText(new ObjectArguments(128, 40, utf8("機体の上"), 0xFFFF80, Map.of("scale", 3.0, "align", "center")));
                            p.drawLine(new ObjectArguments(10, 110, 246, 110, 0x80FF80, 3));
                        } catch (LuaException e) {
                            AshCC.LOGGER.error("MonitorShot: ship draw failed", e);
                        }
                    }
                }
            }
        }

        static void turn(Object ship, double degrees) {
            if (!(ship instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel sub)) return;
            var handle = dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(sub);
            if (handle == null) return;
            handle.teleport(new org.joml.Vector3d(sub.logicalPose().position()).add(0, 0.05, 0),
                new org.joml.Quaterniond().rotateY(Math.toRadians(degrees)));
        }
    }
}
