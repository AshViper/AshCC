package com.ashcc.devtest;

import com.ashcc.AshCC;
import com.ashcc.laser.LaserBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Dev-only (excluded from the jar). {@code gradlew runTestClient -PlaserShot -Pworld=ashcc_lasershot}:
 * builds a ground laser and a laser on a Sable ship, both firing at a wall, screenshots the beams,
 * turns the ship 30 degrees, screenshots again and quits. Server-side hits are logged as "LaserShot".
 */
@EventBusSubscriber(modid = AshCC.MOD_ID, value = Dist.CLIENT)
public final class LaserShot {
    private static final boolean ENABLED = System.getProperty("ashcc.lasershot") != null;
    private static int tick;
    private static Object ship;

    private LaserShot() {
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
                cmd(server, "fill 12 -60 -6 12 -54 10 obsidian");
                server.execute(() -> ship = Sable.build(server.overworld()));
            }
            case 60 -> {
                mc.player.getAbilities().flying = true;
                cmd(server, "tp @p 4 -51 -12 facing 6 -58 2");
            }
            case 90 -> shot(mc, server, "laser_1_ground_and_ship");
            case 95 -> server.execute(() -> Sable.turn(ship, 30));
            case 130 -> shot(mc, server, "laser_2_ship_turned_30");
            case 135 -> cmd(server, "tp @p 4 -40 4 facing 4 -60 4.01");
            case 160 -> shot(mc, server, "laser_3_top_down");
            case 170 -> mc.stop();
            default -> {
            }
        }
    }

    private static void cmd(MinecraftServer server, String command) {
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command));
    }

    private static void shot(Minecraft mc, MinecraftServer server, String name) {
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (BlockEntity be : Sable.lasers(level, ship)) {
                LaserBlockEntity l = (LaserBlockEntity) be;
                AshCC.LOGGER.info("LaserShot {}: laser at {} firing={} hit={}", name, be.getBlockPos(), l.firing(), l.peripheral().getHit());
            }
        });
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), msg -> AshCC.LOGGER.info("LaserShot: {}", msg.getString()));
    }

    /** Sable types stay in here so the class loads without them. */
    private static final class Sable {
        static Object build(ServerLevel level) {
            // Ground laser: fires east (yaw 90) along z = 6
            BlockPos ground = new BlockPos(0, -60, 6);
            level.setBlockAndUpdate(ground, AshCC.LASER.get().defaultBlockState());
            ((LaserBlockEntity) level.getBlockEntity(ground)).aimNow(90, 0);
            ((LaserBlockEntity) level.getBlockEntity(ground)).fire(-1);

            // Ship resting on the ground: 3x3 iron deck with a laser on top aimed east
            java.util.List<BlockPos> blocks = new java.util.ArrayList<>();
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                BlockPos p = new BlockPos(x, -60, z);
                level.setBlockAndUpdate(p, Blocks.IRON_BLOCK.defaultBlockState());
                blocks.add(p);
            }
            BlockPos top = new BlockPos(0, -59, 0);
            level.setBlockAndUpdate(top, AshCC.LASER.get().defaultBlockState());
            ((LaserBlockEntity) level.getBlockEntity(top)).aimNow(90, 0);
            blocks.add(top);
            var sub = dev.ryanhcode.sable.api.SubLevelAssemblyHelper.assembleBlocks(level, new BlockPos(0, -60, 0), blocks,
                new dev.ryanhcode.sable.companion.math.BoundingBox3i(new BlockPos(-1, -60, -1), new BlockPos(1, -59, 1)));
            for (BlockEntity be : lasers(level, sub)) ((LaserBlockEntity) be).fire(-1);
            AshCC.LOGGER.info("LaserShot: ship {}", sub == null ? null : sub.getUniqueId());
            return sub;
        }

        static void turn(Object ship, double degrees) {
            if (!(ship instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel sub)) return;
            var handle = dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(sub);
            if (handle == null) return;
            handle.teleport(new org.joml.Vector3d(sub.logicalPose().position()).add(0, 0.05, 0),
                new org.joml.Quaterniond().rotateY(Math.toRadians(degrees)));
        }

        static java.util.List<BlockEntity> lasers(ServerLevel level, Object ship) {
            java.util.List<BlockEntity> out = new java.util.ArrayList<>();
            BlockEntity ground = level.getBlockEntity(new BlockPos(0, -60, 6));
            if (ground instanceof LaserBlockEntity) out.add(ground);
            if (ship instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel sub) {
                var plot = sub.getPlot();
                for (int cx = plot.getChunkMin().x; cx <= plot.getChunkMax().x; cx++) {
                    for (int cz = plot.getChunkMin().z; cz <= plot.getChunkMax().z; cz++) {
                        for (BlockEntity be : level.getChunk(cx, cz).getBlockEntities().values()) {
                            if (be instanceof LaserBlockEntity) out.add(be);
                        }
                    }
                }
            }
            return out;
        }
    }
}
