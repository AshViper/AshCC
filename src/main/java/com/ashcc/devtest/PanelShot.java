package com.ashcc.devtest;

import com.ashcc.AshCC;
import com.ashcc.panel.ControlPanelBlock;
import com.ashcc.panel.ControlPanelBlockEntity;
import com.ashcc.panel.PanelControl;
import com.ashcc.panel.client.PanelClient;
import com.ashcc.panel.client.PanelScreen;
import dan200.computercraft.api.lua.ObjectArguments;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.Map;

/**
 * Dev-only (excluded from the jar). {@code gradlew runTestClient -PpanelShot -Pworld=ashcc_panel}: an F-16 style cockpit of
 * control panels: a front instrument panel (two MFDs with bezel buttons, an up-front keypad), a HUD on the glareshield, a side
 * stick on the right console and a throttle on the left, both tilted. Draws on the MFDs and HUD through the panel peripheral,
 * grabs stick and throttle together with the real keys, logs the channels as "PanelShot", takes screenshots and quits.
 */
@EventBusSubscriber(modid = AshCC.MOD_ID, value = Dist.CLIENT)
public final class PanelShot {
    private static final boolean ENABLED = System.getProperty("ashcc.panelshot") != null;
    private static final int Y = -60;
    // Pilot stands at (0, Y, 0) facing north
    private static final BlockPos LEFT_MFD = new BlockPos(-1, Y + 1, -2);
    private static final BlockPos ICP = new BlockPos(0, Y + 1, -2);
    private static final BlockPos RIGHT_MFD = new BlockPos(1, Y + 1, -2);
    private static final BlockPos HUD = new BlockPos(0, Y + 2, -2);
    private static final BlockPos STICK = new BlockPos(1, Y, 0);
    private static final BlockPos THROTTLE = new BlockPos(-1, Y, 0);
    private static int tick;

    private PanelShot() {
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!ENABLED || mc.player == null || mc.getSingleplayerServer() == null) return;
        MinecraftServer server = mc.getSingleplayerServer();
        mc.getToasts().clear();
        mc.options.pauseOnLostFocus = false;
        int t = ++tick;
        if (mc.screen != null && t > 30 && !(mc.screen instanceof PanelScreen)) mc.setScreen(null);
        switch (t) {
            case 40 -> {
                cmd(server, "time set noon");
                cmd(server, "gamerule doDaylightCycle false");
                cmd(server, "fill -8 -60 -8 8 -50 8 air");
                cmd(server, "gamemode creative @p");
                mc.player.getAbilities().flying = false;
            }
            case 50 -> server.execute(() -> build(server.overworld()));
            case 60 -> server.execute(() -> draw(server.overworld()));
            case 70 -> cmd(server, "tp @p 0.5 -60 0.5 facing 0.5 -58.6 -2");
            case 110 -> shot(mc, "panel_1_cockpit");
            case 112 -> cmd(server, "tp @p 0.5 -60 0.5 facing 0.5 -59.8 -0.2");
            case 140 -> shot(mc, "panel_2_consoles");
            // HOTAS: grab the side stick and the throttle, then stick forward-right with trigger and hat, throttle up with a thumb button
            case 150 -> {
                if (mc.level.getBlockEntity(STICK) instanceof ControlPanelBlockEntity s) PanelClient.click(s, new int[]{2, 2}, false);
                if (mc.level.getBlockEntity(THROTTLE) instanceof ControlPanelBlockEntity th) PanelClient.click(th, new int[]{3, 1}, false);
                AshCC.LOGGER.info("PanelShot: grabbing {}", PanelClient.grabbing());
            }
            case 155 -> {
                press(mc.options.keyUp, true);
                press(mc.options.keyRight, true);
                press(mc.options.keyJump, true);
                press(PanelClient.HAT_UP, true);
                press(PanelClient.THROTTLE_UP, true);
                press(PanelClient.THUMB1, true);
            }
            case 185 -> {
                log(server, "hotas held");
                shot(mc, "panel_3_hotas_held");
            }
            case 190 -> {
                for (KeyMapping k : new KeyMapping[]{mc.options.keyUp, mc.options.keyRight, mc.options.keyJump, PanelClient.HAT_UP,
                    PanelClient.THROTTLE_UP, PanelClient.THUMB1}) press(k, false);
                press(mc.options.keyShift, true);
            }
            case 192 -> press(mc.options.keyShift, false);
            case 215 -> log(server, "let go");
            // The tilted console is clicked along the line of sight
            case 225 -> {
                // Look at a point on the tilted console directly (yaw / pitch from the eye)
                var d = new net.minecraft.world.phys.Vec3(1.4, -59.75, 0.35).subtract(mc.player.getEyePosition());
                mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
                mc.player.setXRot((float) Math.toDegrees(-Math.atan2(d.y, Math.hypot(d.x, d.z))));
            }
            case 230 -> {
                if (mc.level.getBlockEntity(STICK) instanceof ControlPanelBlockEntity s) {
                    int[] cell = PanelClient.aimedCell(s);
                    var f = s.frame();
                    AshCC.LOGGER.info("PanelShot: aimed cell on the tilted stick console {} (eye {} look {} tilt {} centre {} normal {})",
                        cell == null ? null : cell[0] + "," + cell[1], mc.player.getEyePosition(), mc.player.getViewVector(1), s.tilt(),
                        f.surfaceCentre(), f.normal());
                }
            }
            case 235 -> mc.setScreen(new PanelScreen(LEFT_MFD, 2, 2));
            case 255 -> shot(mc, "panel_4_setup_screen");
            case 260 -> mc.setScreen(null);
            case 262 -> cmd(server, "tp @p 3.5 -58 2.5 facing 0 -59 -1.5");
            case 300 -> shot(mc, "panel_5_outside");
            case 310 -> mc.stop();
            default -> {
            }
        }
    }

    private static void press(KeyMapping key, boolean down) {
        key.setDown(down);
    }

    private static BlockState panel(AttachFace face, Direction facing) {
        return AshCC.CONTROL_PANEL.get().defaultBlockState().setValue(ControlPanelBlock.FACE, face).setValue(ControlPanelBlock.FACING, facing);
    }

    private static ControlPanelBlockEntity put(ServerLevel level, BlockPos pos, BlockState state, int grid, int tilt) {
        level.setBlockAndUpdate(pos, state);
        ControlPanelBlockEntity be = (ControlPanelBlockEntity) level.getBlockEntity(pos);
        be.setLayout(grid, tilt);
        return be;
    }

    private static void build(ServerLevel level) {
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 3; z++) level.setBlockAndUpdate(new BlockPos(x, Y - 1, z), Blocks.SMOOTH_STONE.defaultBlockState());
        // Front: a wall of dark blocks behind the instrument panels, and the glareshield on top
        for (int x = -2; x <= 2; x++) for (int y = Y; y <= Y + 1; y++) level.setBlockAndUpdate(new BlockPos(x, y, -3), Blocks.GRAY_CONCRETE.defaultBlockState());
        for (int x = -1; x <= 1; x++) level.setBlockAndUpdate(new BlockPos(x, Y + 1, -2).below(), Blocks.GRAY_CONCRETE.defaultBlockState());
        level.setBlockAndUpdate(HUD.below(), Blocks.GRAY_CONCRETE.defaultBlockState());
        level.setBlockAndUpdate(HUD.below().west(), Blocks.GRAY_CONCRETE.defaultBlockState());
        level.setBlockAndUpdate(HUD.below().east(), Blocks.GRAY_CONCRETE.defaultBlockState());
        // Side consoles under the stick and the throttle
        level.setBlockAndUpdate(STICK.below(), Blocks.GRAY_CONCRETE.defaultBlockState());
        level.setBlockAndUpdate(THROTTLE.below(), Blocks.GRAY_CONCRETE.defaultBlockState());

        // MFDs: 8x8 grid, screen 6x6 in the middle, bezel buttons around it
        for (BlockPos p : new BlockPos[]{LEFT_MFD, RIGHT_MFD}) {
            ControlPanelBlockEntity mfd = put(level, p, panel(AttachFace.WALL, Direction.SOUTH), 8, 0);
            mfd.configure(1, 1, PanelControl.Type.SCREEN, 6, 6, 1, DyeColor.BLACK);
            int ch = 1;
            for (int i = 1; i <= 6; i++) {
                mfd.configure(i, 0, PanelControl.Type.BUTTON, 1, 1, ch++, DyeColor.GRAY);
                mfd.configure(i, 7, PanelControl.Type.BUTTON, 1, 1, ch++, DyeColor.GRAY);
                mfd.configure(0, i, PanelControl.Type.BUTTON, 1, 1, ch++, DyeColor.GRAY);
                mfd.configure(7, i, PanelControl.Type.BUTTON, 1, 1, ch++, DyeColor.GRAY);
            }
        }
        // Up-front control panel: keypad, master arm toggle, a few knobs
        ControlPanelBlockEntity icp = put(level, ICP, panel(AttachFace.WALL, Direction.SOUTH), 8, 0);
        int ch = 1;
        for (int r = 2; r <= 5; r++) for (int c = 2; c <= 4; c++) icp.configure(c, r, PanelControl.Type.BUTTON, 1, 1, ch++, DyeColor.WHITE);
        icp.configure(0, 0, PanelControl.Type.TOGGLE, 1, 1, 20, DyeColor.RED);
        icp.configure(1, 0, PanelControl.Type.TOGGLE, 1, 1, 21, DyeColor.YELLOW);
        icp.configure(6, 1, PanelControl.Type.KNOB, 2, 2, 22, DyeColor.LIGHT_GRAY);
        icp.configure(6, 4, PanelControl.Type.KNOB, 2, 2, 23, DyeColor.LIGHT_GRAY);
        // HUD on the glareshield
        ControlPanelBlockEntity hud = put(level, HUD, panel(AttachFace.FLOOR, Direction.NORTH), 4, 0);
        hud.configure(0, 0, PanelControl.Type.HUD, 4, 4, 1, DyeColor.BLACK);
        // Consoles, tilted towards the pilot: right with the side stick, left with the throttle and switches
        ControlPanelBlockEntity stick = put(level, STICK, panel(AttachFace.FLOOR, Direction.NORTH), 8, 15);
        stick.configure(2, 2, PanelControl.Type.STICK, 4, 4, 1, DyeColor.BLACK);
        stick.configure(0, 7, PanelControl.Type.TOGGLE, 1, 1, 11, DyeColor.ORANGE);
        ControlPanelBlockEntity thr = put(level, THROTTLE, panel(AttachFace.FLOOR, Direction.NORTH), 8, 15);
        thr.configure(3, 1, PanelControl.Type.THROTTLE, 2, 6, 1, DyeColor.BLACK);
        thr.configure(6, 1, PanelControl.Type.TOGGLE, 1, 1, 4, DyeColor.RED);
        thr.configure(6, 3, PanelControl.Type.TOGGLE, 1, 1, 5, DyeColor.LIME);
        thr.configure(0, 5, PanelControl.Type.KNOB, 2, 2, 6, DyeColor.LIGHT_GRAY);
    }

    /** MFD pages and HUD symbology, drawn through the panels' peripherals (the same calls a CC program makes). */
    private static void draw(ServerLevel level) {
        try {
            for (BlockPos p : new BlockPos[]{LEFT_MFD, RIGHT_MFD}) {
                if (!(level.getBlockEntity(p) instanceof ControlPanelBlockEntity be)) continue;
                var per = be.peripheral();
                // Screen = cells 1..6 of 8 = pixels 32..224
                per.fillRect(new ObjectArguments(32, 32, 192, 192, 0x041004));
                int green = 0x30FF60;
                per.drawRect(new ObjectArguments(36, 36, 184, 184, green, 2));
                if (p.equals(LEFT_MFD)) {
                    per.drawText(new ObjectArguments(128, 44, "FCR", green, Map.of("align", "center")));
                    for (int r = 1; r <= 3; r++) per.drawCircle(new ObjectArguments(128, 200, r * 45, green));
                    per.fillRect(new ObjectArguments(150, 110, 8, 8, 0xFFE040));
                    per.drawText(new ObjectArguments(44, 200, "RWS", green));
                } else {
                    per.drawText(new ObjectArguments(128, 44, "SMS", green, Map.of("align", "center")));
                    per.drawText(new ObjectArguments(60, 100, "AIM-9 x2\nAIM-120 x4\nGUN 510", green));
                }
            }
            if (level.getBlockEntity(HUD) instanceof ControlPanelBlockEntity be) {
                var per = be.peripheral();
                per.fill(new ObjectArguments("transparent"));
                long g = 0xFF40FF70L;
                per.drawLine(new ObjectArguments(40, 128, 216, 128, g, 2));
                per.drawCircle(new ObjectArguments(128, 128, 10, g, 2));
                per.drawText(new ObjectArguments(30, 40, "350", g));
                per.drawText(new ObjectArguments(226, 40, "12000", g, Map.of("align", "right")));
                per.drawText(new ObjectArguments(128, 210, "NAV", g, Map.of("align", "center")));
            }
        } catch (Exception e) {
            AshCC.LOGGER.error("PanelShot: drawing failed", e);
        }
    }

    private static void log(MinecraftServer server, String when) {
        server.execute(() -> {
            StringBuilder sb = new StringBuilder();
            if (server.overworld().getBlockEntity(STICK) instanceof ControlPanelBlockEntity s) {
                sb.append("stick");
                for (int ch = 1; ch <= 10; ch++) sb.append(' ').append(ch).append('=').append(s.channel(ch));
            }
            if (server.overworld().getBlockEntity(THROTTLE) instanceof ControlPanelBlockEntity t) {
                sb.append(" | throttle");
                for (int ch = 1; ch <= 3; ch++) sb.append(' ').append(ch).append('=').append(t.channel(ch));
            }
            AshCC.LOGGER.info("PanelShot {}: {}", when, sb);
        });
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), msg -> AshCC.LOGGER.info("PanelShot: {}", msg.getString()));
    }

    private static void cmd(MinecraftServer server, String command) {
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command));
    }
}
