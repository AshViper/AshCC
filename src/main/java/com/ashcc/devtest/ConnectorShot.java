package com.ashcc.devtest;

import com.ashcc.AshCC;
import com.ashcc.connector.ConnectorEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only (excluded from the jar). {@code gradlew runTestClient -PconnectorShot -Pworld=ashcc_connector}: sticks offset
 * connector plates on the nine ninths of a row of stone tops and puts a gold block on each (so the shift shows against
 * the grid), one tilted with the wrench angles, two left bare (one tilted), one on a small ship, and five plates on one stone
 * (four corners and the middle, overlapping); takes screenshots and quits.
 */
@EventBusSubscriber(modid = AshCC.MOD_ID, value = Dist.CLIENT)
public final class ConnectorShot {
    private static final boolean ENABLED = System.getProperty("ashcc.connectorshot") != null;
    private static final int Y = -60;
    private static int tick;

    private ConnectorShot() {
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!ENABLED || mc.player == null || mc.getSingleplayerServer() == null) return;
        MinecraftServer server = mc.getSingleplayerServer();
        mc.getToasts().clear();
        mc.options.pauseOnLostFocus = false;
        if (mc.screen != null && tick > 30) mc.setScreen(null);
        switch (++tick) {
            case 40 -> {
                cmd(server, "time set noon");
                cmd(server, "gamerule doDaylightCycle false");
                cmd(server, "fill -10 -60 -10 20 -40 20 air");
                cmd(server, "gamemode creative @p");
                mc.player.getAbilities().flying = true;
            }
            case 50 -> server.execute(() -> buildRow(server.overworld(), server.getPlayerList().getPlayers().get(0)));
            case 60 -> server.execute(() -> buildShip(server.overworld()));
            case 100 -> server.execute(() -> onShip(server.overworld(), server.getPlayerList().getPlayers().get(0)));
            case 110 -> cmd(server, "tp @p 4 -55 7 facing 4 -59 0");
            case 160 -> shot(mc, "connector_1_ninths");
            case 165 -> cmd(server, "tp @p 4 -58.5 5 facing 4 -59 0");
            case 200 -> shot(mc, "connector_2_low");
            case 205 -> cmd(server, "tp @p 15.5 -56.5 8 facing 15.5 -58.5 3.5");
            case 250 -> shot(mc, "connector_3_ship");
            case 255 -> cmd(server, "tp @p 19.5 -58 3 facing 17 -59 0");
            case 290 -> shot(mc, "connector_4_tilted");
            case 300 -> server.execute(() -> breakSome(server.overworld()));
            case 305 -> cmd(server, "tp @p 9 -56 7 facing 9 -59.5 0");
            case 350 -> {
                server.execute(() -> report(server.overworld()));
                shot(mc, "connector_5_broken");
            }
            case 355 -> cmd(server, "tp @p 12.5 -56 6.5 facing 12.5 -59 3.5");
            case 360 -> server.execute(() -> reportMany(server.overworld(), "early"));
            case 400 -> {
                server.execute(() -> reportMany(server.overworld(), "late"));
                reportClient(mc);
                shot(mc, "connector_6_many_on_one_face");
            }
            case 410 -> mc.stop();
            default -> {
            }
        }
    }

    /** Stone at x = -1, 1, .. 17 with a plate on ninth i of its top and a gold block on it; the last one tilted; two bare plates at z = 3. */
    private static void buildRow(ServerLevel level, ServerPlayer player) {
        for (int x = -2; x <= 18; x++) for (int z = -2; z <= 4; z++) level.setBlockAndUpdate(new BlockPos(x, Y - 1, z), Blocks.SMOOTH_STONE.defaultBlockState());
        for (int i = 0; i < 12; i++) {
            BlockPos stone = i < 10 ? new BlockPos(i * 2 - 1, Y, 0) : new BlockPos(i == 10 ? 3 : 7, Y, 3);
            level.setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
            int cell = i % 9;
            Vec3 hit = new Vec3(stone.getX() + (cell % 3 + 0.5) / 3, stone.getY() + 1, stone.getZ() + (cell / 3 + 0.5) / 3);
            ConnectorEntity plate = place(level, player, stone, Direction.UP, hit);
            if (plate == null) continue;
            if (i == 9 || i == 11) plate.setAngles(30, 20, 0);
            if (i < 10) putOn(player, plate, Blocks.GOLD_BLOCK);
        }
        // Several plates on one face: the four corner ninths (block centres on the corners) and the middle
        BlockPos many = new BlockPos(12, Y, 3);
        level.setBlockAndUpdate(many, Blocks.STONE.defaultBlockState());
        net.minecraft.world.level.block.Block[] blocks = {Blocks.GOLD_BLOCK, Blocks.EMERALD_BLOCK, Blocks.LAPIS_BLOCK, Blocks.REDSTONE_BLOCK, Blocks.DIAMOND_BLOCK};
        int[] cells = {0, 2, 6, 8, 4};
        for (int k = 0; k < cells.length; k++) {
            int cell = cells[k];
            Vec3 hit = new Vec3(many.getX() + (cell % 3 + 0.5) / 3, many.getY() + 1, many.getZ() + (cell / 3 + 0.5) / 3);
            ConnectorEntity plate = place(level, player, many, Direction.UP, hit);
            if (plate != null) putOn(player, plate, blocks[k]);
        }
        // The same ninth again must be refused
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(AshCC.CONNECTOR_ITEM.get()));
        var again = player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
            new BlockHitResult(new Vec3(many.getX() + 0.5, many.getY() + 1, many.getZ() + 0.5), Direction.UP, many, false)));
        AshCC.LOGGER.info("ConnectorShot: second plate in the same ninth -> {}", again);
    }

    /** Where the five blocks on the shared face are: they should stay put (no shaking from overlapping). */
    /** Every plate with a block: how far its block is turned from the plate's set tilt and moved from the anchor. */
    private static void reportAll(ServerLevel level, String when) {
        double worstAngle = 0, worstMove = 0;
        for (ConnectorEntity plate : level.getEntities(AshCC.CONNECTOR.get(), p -> p.child() != null)) {
            double[] e = Sable.error(level, plate);
            if (e == null) continue;
            AshCC.LOGGER.info("ConnectorShot: {} error plate {} anchor {} turned {} deg moved {}", when, plate.parentPos(), plate.anchor(),
                String.format("%.2f", e[0]), String.format("%.3f", e[1]));
            worstAngle = Math.max(worstAngle, e[0]);
            worstMove = Math.max(worstMove, e[1]);
        }
        AshCC.LOGGER.info("ConnectorShot: {} worst turned {} deg, moved {}", when, String.format("%.2f", worstAngle), String.format("%.3f", worstMove));
    }

    /** The blocks' tilt on the client's copy of the ships (what is drawn); the attach point is server-only, so no position here. */
    private static void reportClient(Minecraft mc) {
        double worst = 0;
        int n = 0;
        for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof ConnectorEntity plate) || plate.child() == null) continue;
            double[] err = Sable.error(mc.level, plate);
            if (err == null) continue;
            n++;
            worst = Math.max(worst, err[0]);
        }
        AshCC.LOGGER.info("ConnectorShot: client {} plates, worst turned {} deg", n, String.format("%.2f", worst));
    }

    private static void reportMany(ServerLevel level, String when) {
        reportAll(level, when);
        for (ConnectorEntity plate : level.getEntities(AshCC.CONNECTOR.get(), p -> p.parentPos().equals(new BlockPos(12, Y, 3)))) {
            AshCC.LOGGER.info("ConnectorShot: {} plate at {} child {} block at {}", when, plate.anchor(), plate.child(), Sable.childWorld(level, plate));
        }
    }

    /** Breaks the gold block on the plate at x = 7 (it should come bare again) and the stone under the one at x = 11 (it should drop). */
    private static void breakSome(ServerLevel level) {
        for (ConnectorEntity plate : level.getEntities(AshCC.CONNECTOR.get(), p -> true)) {
            if (plate.parentPos().equals(new BlockPos(7, Y, 0)) && plate.child() != null) level.setBlockAndUpdate(plate.firstBlock(), Blocks.AIR.defaultBlockState());
        }
        level.setBlockAndUpdate(new BlockPos(11, Y, 0), Blocks.AIR.defaultBlockState());
    }

    private static void report(ServerLevel level) {
        for (ConnectorEntity plate : level.getEntities(AshCC.CONNECTOR.get(), p -> p.parentPos().getX() == 7 || p.parentPos().getX() == 11)) {
            AshCC.LOGGER.info("ConnectorShot: after breaking, plate on {} child {}", plate.parentPos(), plate.child());
        }
        AshCC.LOGGER.info("ConnectorShot: plates on x=11 left: {}", level.getEntities(AshCC.CONNECTOR.get(), p -> p.parentPos().getX() == 11).size());
    }

    private static ConnectorEntity place(ServerLevel level, ServerPlayer player, BlockPos clicked, Direction face, Vec3 hit) {
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(AshCC.CONNECTOR_ITEM.get()));
        player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, clicked, false)));
        // A plate on a ship shows up in world space, far from its block in the plot
        Vec3 anchor = ConnectorEntity.anchorFor(clicked, face, hit);
        List<? extends ConnectorEntity> plates = level.getEntities(AshCC.CONNECTOR.get(), p -> p.child() == null && p.samePlace(clicked, face, anchor));
        ConnectorEntity plate = plates.isEmpty() ? null : plates.get(0);
        AshCC.LOGGER.info("ConnectorShot: plate on {} at {} -> {}", clicked, hit, plate == null ? null : plate.position());
        return plate;
    }

    private static void putOn(ServerPlayer player, ConnectorEntity plate, net.minecraft.world.level.block.Block block) {
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(block));
        var r = plate.interact(player, InteractionHand.MAIN_HAND);
        AshCC.LOGGER.info("ConnectorShot: block on plate -> {} child {}", r, plate.child());
    }

    private static final List<BlockPos> SHIP = new ArrayList<>();

    private static void buildShip(ServerLevel level) {
        SHIP.clear();
        for (int x = 14; x <= 16; x++) for (int z = 2; z <= 4; z++) {
            BlockPos p = new BlockPos(x, Y + 1, z);
            level.setBlockAndUpdate(p, Blocks.IRON_BLOCK.defaultBlockState());
            SHIP.add(p);
        }
        Sable.assemble(level, SHIP);
    }

    /** A connector on the ship's top, in the north-east ninth of the middle block, with two blocks stacked on it. */
    private static void onShip(ServerLevel level, ServerPlayer player) {
        BlockPos mid = null;
        for (double y = Y - 2; mid == null && y <= Y + 2; y += 0.25) mid = Sable.plotOf(level, new Vec3(15.5, y, 3.5));
        AshCC.LOGGER.info("ConnectorShot: ship middle block {}", mid);
        if (mid == null) return;
        Vec3 hit = new Vec3(mid.getX() + 5 / 6.0, mid.getY() + 1, mid.getZ() + 1 / 6.0);
        ConnectorEntity plate = place(level, player, mid, Direction.UP, hit);
        if (plate == null) return;
        putOn(player, plate, Blocks.DIAMOND_BLOCK);
        BlockPos first = Sable.childBlock(level, plate);
        if (first != null) level.setBlockAndUpdate(first.above(), Blocks.GOLD_BLOCK.defaultBlockState());
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), msg -> AshCC.LOGGER.info("ConnectorShot: {}", msg.getString()));
    }

    private static void cmd(MinecraftServer server, String command) {
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command));
    }

    /** Sable types stay in here so the class loads without them. */
    private static final class Sable {
        /** The first block on the plate's child sub-level, in its plot. */
        static BlockPos childBlock(ServerLevel level, ConnectorEntity plate) {
            return BlockPos.containing(plate.childAttach().add(Vec3.atLowerCornerOf(plate.face().getNormal()).scale(0.5)));
        }

        /** {angle off the expected orientation in degrees, distance of the attach point from the anchor} in world space. */
        static double[] error(net.minecraft.world.level.Level level, ConnectorEntity plate) {
            var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
            var sub = container.getSubLevel(plate.child());
            if (sub == null) return null;
            var parent = dev.ryanhcode.sable.Sable.HELPER.getContaining(level, plate.parentPos());
            org.joml.Quaterniond rel = new org.joml.Quaterniond().rotateYXZ(Math.toRadians(-plate.yawDeg()), Math.toRadians(plate.pitchDeg()), Math.toRadians(plate.rollDeg()));
            org.joml.Quaterniond expected = parent != null ? new org.joml.Quaterniond(parent.logicalPose().orientation()).mul(rel) : rel;
            org.joml.Quaterniond diff = new org.joml.Quaterniond(expected).conjugate().mul(sub.logicalPose().orientation());
            double angle = Math.toDegrees(2 * Math.acos(Math.min(1, Math.abs(diff.w))));
            Vec3 anchor = parent != null ? parent.logicalPose().transformPosition(plate.anchor()) : plate.anchor();
            Vec3 attach = sub.logicalPose().transformPosition(plate.childAttach());
            return new double[]{angle, anchor.distanceTo(attach)};
        }

        static Vec3 childWorld(ServerLevel level, ConnectorEntity plate) {
            if (plate.child() == null) return null;
            var sub = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level).getSubLevel(plate.child());
            return sub == null ? null : sub.logicalPose().transformPosition(plate.childAttach());
        }

        static void assemble(ServerLevel level, List<BlockPos> blocks) {
            Object sub = dev.ryanhcode.sable.api.SubLevelAssemblyHelper.assembleBlocks(level, blocks.get(0), blocks,
                new dev.ryanhcode.sable.companion.math.BoundingBox3i(new BlockPos(14, Y + 1, 2), new BlockPos(16, Y + 1, 4)));
            AshCC.LOGGER.info("ConnectorShot: ship {}", sub);
        }

        /** The plot block of the ship block that shows up at a world point. */
        static BlockPos plotOf(ServerLevel level, Vec3 world) {
            for (dev.ryanhcode.sable.sublevel.SubLevel sub : dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level).getAllSubLevels()) {
                Vec3 local = sub.logicalPose().transformPositionInverse(world);
                BlockPos p = BlockPos.containing(local);
                if (level.getBlockState(p).is(Blocks.IRON_BLOCK)) return p;
            }
            return null;
        }
    }
}
