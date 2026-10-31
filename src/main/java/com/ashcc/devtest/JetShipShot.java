package com.ashcc.devtest;

import com.ashcc.AshCC;
import com.ashcc.compat.SableCompat;
import com.ashcc.jet.JetEngineBlockEntity;
import com.ashcc.jet.JetPartBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only (excluded from the jar). {@code gradlew runTestClient -PjetShot=ship -Pworld=ashcc_jetship}: a 5x5 iron deck on
 * four legs with a jet engine hanging under it, exhaust down, assembled into a Sable ship. Starts the engine, climbs on
 * dry power, then lights the afterburner. Logs the ship's height and the server and client nozzle positions
 * (the sound follows the client one), takes screenshots from beside the ship and quits.
 */
@EventBusSubscriber(modid = AshCC.MOD_ID, value = Dist.CLIENT)
public final class JetShipShot {
    private static final boolean ENABLED = System.getProperty("ashcc.jetshot", "").startsWith("ship");
    /** -PjetShot=shipheavy: a deck too heavy to lift, so the camera can watch the plume on a ship standing still. */
    private static final boolean HEAVY = "shipheavy".equals(System.getProperty("ashcc.jetshot"));
    private static int tick;
    private static Object ship;
    private static BlockPos nozzle;
    private static final BlockPos GROUND_CAULDRON = new BlockPos(-6, -60, 3);
    private static Vec3 last;

    private JetShipShot() {
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!ENABLED || mc.player == null || mc.getSingleplayerServer() == null) return;
        MinecraftServer server = mc.getSingleplayerServer();
        mc.getToasts().clear();
        tick++;
        // Keep the camera beside the ship: aim at where it will be when the next screenshot is taken
        if (nozzle != null && mc.level != null && tick > 320 && !HEAVY) {
            Vec3 c = SableCompat.worldCenter(mc.level, nozzle);
            Vec3 v = last == null ? Vec3.ZERO : c.subtract(last);
            last = c;
            Vec3 p = c.add(v.scale(3));
            if (tick % 2 == 0) cmd(server, String.format("tp @p %.2f %.2f %.2f facing %.2f %.2f %.2f",
                p.x + 9, p.y - 3, p.z - 9, p.x, p.y - 3, p.z));
        }
        if (tick > 80 && tick % 10 == 0) log(mc, server, "t" + tick);
        switch (tick) {
            case 40 -> {
                cmd(server, "clear @p");
                cmd(server, "time set noon");
                cmd(server, "gamerule doDaylightCycle false");
                cmd(server, "fill -14 -60 -14 14 -20 14 air");
                // Out of the way first: a player under the deck would be pulled into the intake and shove the ship
                cmd(server, "tp @p 9 -55 -9 facing 0 -58 0");
                mc.player.getAbilities().flying = true;
            }
            case 50 -> server.execute(() -> build(server.overworld()));
            case 70 -> engine(server, JetEngineBlockEntity::start);
            case 300 -> {
                if (HEAVY) cmd(server, "tp @p 6 -57 -6 facing 0 -60 0");
                else cmd(server, "tp @p 4 -55 -8 facing -1 -58 1");
            }
            case 310 -> shot(mc, server, "jetship_0_hoses");
            case 380 -> shot(mc, server, "jetship_1_idle_on_ground");
            case 390 -> engine(server, e -> e.setThrottle(0.75));
            case 470 -> shot(mc, server, "jetship_2_climb_dry");
            case 480 -> engine(server, e -> {
                e.setThrottle(1);
                e.setAfterburner(1);
            });
            case 505 -> shot(mc, server, "jetship_3_afterburner_light");
            case 525 -> shot(mc, server, "jetship_4_afterburner");
            case 535 -> mc.stop();
            default -> {
            }
        }
    }

    private static void build(ServerLevel level) {
        List<BlockPos> blocks = new ArrayList<>();
        int r = HEAVY ? 4 : 2;
        int top = HEAVY ? -54 : -56;
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) for (int y = -56; y <= top; y++) {
            if (x != 0 || z != 0) put(level, blocks, new BlockPos(x, y, z), Blocks.IRON_BLOCK.defaultBlockState());
        }
        for (int x : new int[]{-r, r}) for (int z : new int[]{-r, r}) for (int y = -60; y <= -57; y++) {
            put(level, blocks, new BlockPos(x, y, z), Blocks.IRON_BLOCK.defaultBlockState());
        }
        put(level, blocks, new BlockPos(0, -57, 0), AshCC.JET_INTAKE.get().defaultBlockState().setValue(JetPartBlock.FACING, Direction.DOWN));
        put(level, blocks, new BlockPos(0, -58, 0), AshCC.JET_CORE.get().defaultBlockState().setValue(JetPartBlock.FACING, Direction.DOWN));
        put(level, blocks, new BlockPos(0, -59, 0), AshCC.JET_AFTERBURNER_NOZZLE.get().defaultBlockState().setValue(JetPartBlock.FACING, Direction.DOWN));
        // The intake sits right under the deck; the deck has a hole above it so it can breathe
        ((JetEngineBlockEntity) level.getBlockEntity(new BlockPos(0, -59, 0))).fluidHandler()
            .fill(new FluidStack(Fluids.LAVA, 13000), IFluidHandler.FluidAction.EXECUTE);
        // A lava cauldron on the deck, and one on the ground beside the ship
        put(level, blocks, new BlockPos(1, -55, 1), Blocks.LAVA_CAULDRON.defaultBlockState());
        level.setBlockAndUpdate(GROUND_CAULDRON, Blocks.LAVA_CAULDRON.defaultBlockState());
        ship = Sable.assemble(level, blocks);
        nozzle = Sable.findNozzle(level, ship);
        if (nozzle != null) {
            // Hoses are connected after assembly, to the blocks' new places on the ship (the nozzle was at 0, -59, 0)
            BlockPos core = nozzle.above();
            BlockPos deckCauldron = nozzle.offset(1, 4, 1);
            var hoses = com.ashcc.hose.HoseData.get(level);
            hoses.add(level, deckCauldron, Direction.UP, core, Direction.EAST);
            hoses.add(level, GROUND_CAULDRON, Direction.UP, core, Direction.WEST);
        }
        if (nozzle != null && level.getBlockEntity(nozzle) instanceof JetEngineBlockEntity e) e.setThrottle(0);
        AshCC.LOGGER.info("JetShipShot: ship {} nozzle in plot {}", ship, nozzle);
    }

    private static void put(ServerLevel level, List<BlockPos> blocks, BlockPos p, net.minecraft.world.level.block.state.BlockState s) {
        level.setBlockAndUpdate(p, s);
        blocks.add(p);
    }

    private static void engine(MinecraftServer server, java.util.function.Consumer<JetEngineBlockEntity> action) {
        server.execute(() -> {
            if (nozzle != null && server.overworld().getBlockEntity(nozzle) instanceof JetEngineBlockEntity e) action.accept(e);
        });
    }

    private static void log(Minecraft mc, MinecraftServer server, String label) {
        if (nozzle == null) return;
        Vec3 client = mc.level == null ? null : SableCompat.worldCenter(mc.level, nozzle);
        server.execute(() -> {
            if (!(server.overworld().getBlockEntity(nozzle) instanceof JetEngineBlockEntity e)) return;
            Vec3 world = SableCompat.worldCenter(server.overworld(), nozzle);
            Vec3 down = SableCompat.dirToWorld(server.overworld(), nozzle, new Vec3(0, -1, 0));
            AshCC.LOGGER.info("JetShipShot {}: hoses {} fuel {}", label, com.ashcc.hose.HoseData.get(server.overworld()).hoses().size(), e.fuel().getAmount());
            AshCC.LOGGER.info("JetShipShot {}: nozzle server {} client {} exhaust {} state {} n2 {} thrust {} ab {} v {}",
                label, fmt(world), client == null ? "-" : fmt(client), fmt(down), e.state(), String.format("%.2f", e.n2()),
                Math.round(e.thrust()), String.format("%.2f", e.afterburnerLevel()), fmt(SableCompat.velocityAt(server.overworld(), nozzle)));
        });
    }

    private static String fmt(Vec3 v) {
        return String.format("(%.1f, %.1f, %.1f)", v.x, v.y, v.z);
    }

    private static void shot(Minecraft mc, MinecraftServer server, String name) {
        log(mc, server, name);
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), msg -> AshCC.LOGGER.info("JetShipShot: {}", msg.getString()));
    }

    private static void cmd(MinecraftServer server, String command) {
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command));
    }

    /** Sable types stay in here so the class loads without them. */
    private static final class Sable {
        static Object assemble(ServerLevel level, List<BlockPos> blocks) {
            return dev.ryanhcode.sable.api.SubLevelAssemblyHelper.assembleBlocks(level, new BlockPos(0, -56, 0), blocks,
                new dev.ryanhcode.sable.companion.math.BoundingBox3i(new BlockPos(-2, -60, -2), new BlockPos(2, -56, 2)));
        }

        static BlockPos findNozzle(ServerLevel level, Object ship) {
            if (!(ship instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel sub)) return null;
            var plot = sub.getPlot();
            for (int cx = plot.getChunkMin().x; cx <= plot.getChunkMax().x; cx++) {
                for (int cz = plot.getChunkMin().z; cz <= plot.getChunkMax().z; cz++) {
                    for (BlockEntity be : level.getChunk(cx, cz).getBlockEntities().values()) {
                        if (be instanceof JetEngineBlockEntity) return be.getBlockPos();
                    }
                }
            }
            return null;
        }
    }
}
