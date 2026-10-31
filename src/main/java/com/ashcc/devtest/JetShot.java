package com.ashcc.devtest;

import com.ashcc.AshCC;
import com.ashcc.jet.JetEngineBlockEntity;
import com.ashcc.jet.JetPartBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.List;

/**
 * Dev-only (excluded from the jar). {@code gradlew runTestClient -PjetShot -Pworld=ashcc_jetshot}: builds a jet engine on
 * the ground, starts it, screenshots idle, military, minimum and maximum afterburner (day and night) and quits.
 */
@EventBusSubscriber(modid = AshCC.MOD_ID, value = Dist.CLIENT)
public final class JetShot {
    private static final boolean ENABLED = System.getProperty("ashcc.jetshot") != null && !System.getProperty("ashcc.jetshot").startsWith("ship") && !"hose".equals(System.getProperty("ashcc.jetshot"));
    private static final BlockPos NOZZLE = new BlockPos(6, -58, 0);
    private static int tick;

    private JetShot() {
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
                cmd(server, "gamerule doDaylightCycle false");
                cmd(server, "fill -12 -60 -12 24 -50 24 air");
                server.execute(() -> build(server.overworld()));
            }
            case 60 -> {
                mc.player.getAbilities().flying = true;
                cmd(server, "tp @p 9 -56 -6 facing 8 -58 0");
            }
            case 65 -> cmd(server, "tp @p 9.5 -56 8.5 facing 3.5 -57.8 1.5");
            case 68 -> shot(mc, server, "jet_0_hoses");
            case 69 -> cmd(server, "tp @p -1 -56.5 -7 facing -1 -57.8 -2");
            case 72 -> shot(mc, server, "jet_0_hoses_long");
            case 73 -> engine(server, e -> e.start());
            // Casing shapes and the turning fan / turbine seen through the open ends
            case 370, 570 -> cmd(server, "tp @p 1.8 -56.2 3.4 facing 5.5 -57.5 0.5");
            case 374 -> shot(mc, server, "jet_m_overview_idle");
            case 375, 575 -> cmd(server, "gamemode spectator");
            case 376, 576 -> cmd(server, "tp @p 3.1 -58.9 0.75 facing 4.5 -57.5 0.5");
            case 379 -> shot(mc, server, "jet_m_fan_idle");
            case 579 -> shot(mc, server, "jet_m_fan_military");
            case 380, 580 -> cmd(server, "tp @p 7.9 -58.9 0.75 facing 6.5 -57.5 0.5");
            case 384 -> shot(mc, server, "jet_m_turbine_idle");
            case 584 -> shot(mc, server, "jet_m_turbine_military");
            case 390, 590 -> cmd(server, "gamemode creative");
            case 395, 595, 695, 795 -> cmd(server, "tp @p 9 -56 -6 facing 8 -58 0");
            case 400 -> shot(mc, server, "jet_1_idle");
            case 405 -> engine(server, e -> e.setThrottle(1));
            case 600 -> shot(mc, server, "jet_2_military");
            case 605 -> engine(server, e -> e.setAfterburner(0.15));
            case 700 -> shot(mc, server, "jet_3_min_afterburner");
            case 705 -> engine(server, e -> e.setAfterburner(1));
            case 800 -> shot(mc, server, "jet_4_max_afterburner");
            case 805 -> cmd(server, "time set midnight");
            case 840 -> shot(mc, server, "jet_5_max_afterburner_night");
            case 845 -> cmd(server, "tp @p 9 -51 2 facing 9 -58 0.3");
            case 880 -> shot(mc, server, "jet_6_night_side");
            // Engineer's goggles (Create): look at the core, the readout comes from the nozzle
            case 885 -> {
                cmd(server, "time set noon");
                cmd(server, "item replace entity @p armor.head with create:goggles");
                cmd(server, "tp @p 5.5 -56 -2.5 facing 5.5 -58 0.5");
            }
            case 930 -> shot(mc, server, "jet_7_goggles_core");
            case 935 -> engine(server, e -> e.shutdown());
            case 1000 -> shot(mc, server, "jet_8_goggles_spooling_down");
            case 1010 -> mc.stop();
            default -> {
            }
        }
    }

    private static void build(ServerLevel level) {
        List<Block> parts = List.of(AshCC.JET_INTAKE.get(), AshCC.JET_CORE.get(), AshCC.JET_AFTERBURNER_NOZZLE.get());
        for (int i = 0; i < parts.size(); i++) {
            BlockPos p = new BlockPos(NOZZLE.getX() - (parts.size() - 1 - i), NOZZLE.getY(), NOZZLE.getZ());
            level.setBlockAndUpdate(p, parts.get(i).defaultBlockState().setValue(JetPartBlock.FACING, Direction.EAST));
        }
        JetEngineBlockEntity e = (JetEngineBlockEntity) level.getBlockEntity(NOZZLE);
        e.fluidHandler().fill(new FluidStack(Fluids.LAVA, 16000), IFluidHandler.FluidAction.EXECUTE);
        e.setThrottle(0);
        // Fuel hoses: a lava cauldron to the core over a wall (blocks can stand where a hose runs), and a long hose
        level.setBlockAndUpdate(new BlockPos(5, -58, 7), net.minecraft.world.level.block.Blocks.LAVA_CAULDRON.defaultBlockState());
        for (int y = -58; y <= -57; y++) for (int x = 3; x <= 7; x++) {
            level.setBlockAndUpdate(new BlockPos(x, y, 4), net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState());
        }
        level.setBlockAndUpdate(new BlockPos(-6, -58, -4), net.minecraft.world.level.block.Blocks.LAVA_CAULDRON.defaultBlockState());
        com.ashcc.hose.HoseData data = com.ashcc.hose.HoseData.get(level);
        data.add(level, new BlockPos(5, -58, 7), Direction.UP, new BlockPos(5, -58, 0), Direction.UP);
        data.add(level, new BlockPos(-6, -58, -4), Direction.EAST, new BlockPos(4, -58, 0), Direction.NORTH);
    }

    private static void engine(MinecraftServer server, java.util.function.Consumer<JetEngineBlockEntity> action) {
        server.execute(() -> {
            if (server.overworld().getBlockEntity(NOZZLE) instanceof JetEngineBlockEntity e) action.accept(e);
        });
    }

    private static void cmd(MinecraftServer server, String command) {
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command));
    }

    private static void shot(Minecraft mc, MinecraftServer server, String name) {
        engine(server, e -> AshCC.LOGGER.info("JetShot {}: {}", name, e.peripheral().getStatus()));
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), msg -> AshCC.LOGGER.info("JetShot: {}", msg.getString()));
    }
}
