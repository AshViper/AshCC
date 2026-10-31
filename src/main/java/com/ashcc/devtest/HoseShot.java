package com.ashcc.devtest;

import com.ashcc.AshCC;
import com.ashcc.hose.HoseData;
import com.ashcc.jet.JetEngineBlockEntity;
import com.ashcc.jet.JetPartBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Dev-only (excluded from the jar). {@code gradlew runTestClient -PjetShot=hose -Pworld=ashcc_hoseclick}: the player
 * connects a lava cauldron to a jet engine with real right-clicks (the same packets as clicking), checks the preview,
 * the fuel arriving, building over the hose, removing it with a sneak-click and cancelling a start. Logs "HoseShot".
 */
@EventBusSubscriber(modid = AshCC.MOD_ID, value = Dist.CLIENT)
public final class HoseShot {
    private static final boolean ENABLED = "hose".equals(System.getProperty("ashcc.jetshot"));
    private static final BlockPos CAULDRON = new BlockPos(2, -60, 6);
    private static final BlockPos CORE = new BlockPos(5, -60, 0);
    private static final BlockPos NOZZLE = new BlockPos(6, -60, 0);
    private static int tick;

    private HoseShot() {
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
                cmd(server, "gamemode survival @p");
                cmd(server, "fill -12 -60 -12 24 -50 24 air");
                server.execute(() -> build(server.overworld()));
                cmd(server, "give @p ashcc:hose 3");
            }
            case 60 -> {
                // The given hose lands in the first slot; hold it
                mc.player.getInventory().selected = 0;
                cmd(server, "tp @p 3.5 -59 9.5 facing 3.5 -60 2");
            }
            // First click: the cauldron's top
            case 70 -> click(mc, CAULDRON, Direction.UP);
            case 75 -> log(mc, server, "after first click");
            case 80 -> cmd(server, "tp @p 7.5 -58 5.5 facing 5.5 -60 0.5");
            case 90 -> shot(mc, server, "hose_1_preview");
            // Second click: the engine core's top
            case 95 -> click(mc, CORE, Direction.UP);
            case 100 -> log(mc, server, "after second click");
            // Build a wall across the hose: blocks can go where it runs
            case 105 -> cmd(server, "fill 2 -60 3 6 -58 3 stone_bricks");
            case 140 -> {
                log(mc, server, "after wall");
                cmd(server, "tp @p 9.5 -57 7.5 facing 4 -60 2");
            }
            case 150 -> shot(mc, server, "hose_2_connected_over_wall");
            // Start, then cancel with a sneak-click in the air
            case 160 -> click(mc, CAULDRON, Direction.NORTH);
            case 165 -> mc.options.keyShift.setDown(true);
            case 170 -> {
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            }
            case 175 -> {
                mc.options.keyShift.setDown(false);
                log(mc, server, "after cancel");
            }
            // Remove by sneak-clicking the cauldron
            case 185 -> cmd(server, "tp @p 3.5 -59 9.5 facing 2.5 -60 6.5");
            case 190 -> mc.options.keyShift.setDown(true);
            case 195 -> click(mc, CAULDRON, Direction.UP);
            case 200 -> {
                mc.options.keyShift.setDown(false);
                log(mc, server, "after sneak remove");
            }
            // Create wrench on the core: three clicks down (70%), one sneak-click up (80%)
            case 205 -> {
                cmd(server, "item replace entity @p weapon.mainhand with create:wrench");
                cmd(server, "item replace entity @p armor.head with create:goggles");
                cmd(server, "tp @p 5.5 -58.5 -2.5 facing 5.5 -60 0.5");
            }
            case 212, 215, 218 -> click(mc, CORE, Direction.UP);
            case 222 -> mc.options.keyShift.setDown(true);
            case 226 -> click(mc, CORE, Direction.UP);
            case 230 -> {
                mc.options.keyShift.setDown(false);
                server.execute(() -> {
                    if (server.overworld().getBlockEntity(NOZZLE) instanceof JetEngineBlockEntity e) {
                        AshCC.LOGGER.info("HoseShot wrench: limit={} effective={}", e.throttleLimit(), e.effectiveThrottle());
                    }
                });
            }
            case 260 -> shot(mc, server, "hose_3_wrench_limit");
            // Rigid pipe: from the cauldron's east face to the core's south face, then build over it
            case 265 -> {
                cmd(server, "item replace entity @p armor.head with air");
                cmd(server, "fill 2 -60 3 6 -58 3 air");
                cmd(server, "item replace entity @p weapon.mainhand with ashcc:fluid_pipe 4");
                // Within survival reach (4.5 blocks) of each block clicked
                cmd(server, "tp @p 4.5 -59 8 facing 3 -59.5 6.5");
            }
            case 275 -> click(mc, CAULDRON, Direction.EAST);
            case 278 -> {
                log(mc, server, "after pipe start");
                AshCC.LOGGER.info("HoseShot shift={} overlay={}", mc.player.isShiftKeyDown(), mc.gui.getChat().getRecentChat());
            }
            case 280 -> cmd(server, "tp @p 7.5 -58 4 facing 5.5 -59.5 1");
            case 290 -> shot(mc, server, "pipe_1_preview");
            case 295 -> click(mc, CORE, Direction.SOUTH);
            case 292 -> cmd(server, "tp @p 6 -59 3 facing 5.5 -59.5 1");
            case 300 -> log(mc, server, "after pipe");
            case 305 -> cmd(server, "fill 4 -60 3 6 -59 3 stone_bricks");
            case 320 -> {
                cmd(server, "tp @p 9.5 -57 8.5 facing 4 -60 2.5");
                log(mc, server, "after wall over pipe");
            }
            case 330 -> shot(mc, server, "pipe_2_connected_under_wall");
            case 335 -> cmd(server, "tp @p 3.5 -55 3.5 facing 3.5 -60 3.6");
            case 345 -> shot(mc, server, "pipe_3_top");
            case 355 -> mc.stop();
            default -> {
            }
        }
    }

    private static void build(ServerLevel level) {
        level.setBlockAndUpdate(new BlockPos(4, -60, 0), AshCC.JET_INTAKE.get().defaultBlockState().setValue(JetPartBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(CORE, AshCC.JET_CORE.get().defaultBlockState().setValue(JetPartBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(NOZZLE, AshCC.JET_NOZZLE.get().defaultBlockState().setValue(JetPartBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(CAULDRON, Blocks.LAVA_CAULDRON.defaultBlockState());
    }

    private static void click(Minecraft mc, BlockPos pos, Direction face) {
        Vec3 hit = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
        var result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
        AshCC.LOGGER.info("HoseShot click {} {}: {}", pos, face, result);
    }

    private static void log(Minecraft mc, MinecraftServer server, String label) {
        var held = mc.player.getMainHandItem();
        var start = held.get(AshCC.HOSE_START.get());
        server.execute(() -> {
            ServerLevel level = server.overworld();
            int fuel = level.getBlockEntity(NOZZLE) instanceof JetEngineBlockEntity e ? e.fuel().getAmount() : -1;
            int dropped = level.getEntitiesOfClass(ItemEntity.class, new AABB(CAULDRON).inflate(8)).size();
            AshCC.LOGGER.info("HoseShot {}: hoses={} held={}x{} start={} fuel={} cauldron={} droppedItems={}",
                label, HoseData.get(level).hoses(), held.getItem(), held.getCount(), start, fuel,
                level.getBlockState(CAULDRON).getBlock(), dropped);
        });
    }

    private static void shot(Minecraft mc, MinecraftServer server, String name) {
        log(mc, server, name);
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), msg -> AshCC.LOGGER.info("HoseShot: {}", msg.getString()));
    }

    private static void cmd(MinecraftServer server, String command) {
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command));
    }
}
