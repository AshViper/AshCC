package com.ashcc.panel.client;

import com.ashcc.AshCC;
import com.ashcc.panel.ControlPanelBlockEntity;
import com.ashcc.panel.PanelControl;
import com.ashcc.panel.PanelFrame;
import com.ashcc.panel.PanelNetwork;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Using a panel from the client. The cell is found along the line of sight (panels can be tilted). Sneak-right-click opens the
 * set-up screen. Right-click on a stick, throttle, slider or knob grabs it; a stick and one other control can be held at once
 * (HOTAS): the stick takes W/S/A/D, Space (trigger), the arrow keys (hat) and X (button 2); the other takes R/F (or W/S when
 * it is held alone) and C/V (thumb buttons). Movement keys stop moving the player while anything is held; sneak lets go of all.
 */
public final class PanelClient {
    private static final String CATEGORY = "key.categories.ashcc.panel";
    public static final KeyMapping HAT_UP = key("hat_up", GLFW.GLFW_KEY_UP);
    public static final KeyMapping HAT_DOWN = key("hat_down", GLFW.GLFW_KEY_DOWN);
    public static final KeyMapping HAT_LEFT = key("hat_left", GLFW.GLFW_KEY_LEFT);
    public static final KeyMapping HAT_RIGHT = key("hat_right", GLFW.GLFW_KEY_RIGHT);
    public static final KeyMapping STICK_BUTTON = key("stick_button", GLFW.GLFW_KEY_X);
    public static final KeyMapping THROTTLE_UP = key("throttle_up", GLFW.GLFW_KEY_R);
    public static final KeyMapping THROTTLE_DOWN = key("throttle_down", GLFW.GLFW_KEY_F);
    public static final KeyMapping THUMB1 = key("thumb1", GLFW.GLFW_KEY_C);
    public static final KeyMapping THUMB2 = key("thumb2", GLFW.GLFW_KEY_V);

    private record Held(BlockPos pos, int index) {
    }

    /** The stick in the right hand and anything else in the left. */
    private static @Nullable Held stick, other;
    private static int grabTicks;
    private static @Nullable BlockPos buttonPos;
    private static int buttonIndex = -1;

    private PanelClient() {
    }

    private static KeyMapping key(String name, int key) {
        return new KeyMapping("key.ashcc." + name, InputConstants.Type.KEYSYM, key, CATEGORY);
    }

    public static void register(IEventBus modBus) {
        modBus.addListener((EntityRenderersEvent.RegisterRenderers e) -> e.registerBlockEntityRenderer(AshCC.CONTROL_PANEL_BE.get(), PanelRenderer::new));
        modBus.addListener((RegisterGuiLayersEvent e) ->
            e.registerAbove(VanillaGuiLayers.HOTBAR, ResourceLocation.fromNamespaceAndPath(AshCC.MOD_ID, "panel_grab"), PanelClient::renderHint));
        modBus.addListener((RegisterKeyMappingsEvent e) -> {
            for (KeyMapping k : new KeyMapping[]{HAT_UP, HAT_DOWN, HAT_LEFT, HAT_RIGHT, STICK_BUTTON, THROTTLE_UP, THROTTLE_DOWN, THUMB1, THUMB2}) e.register(k);
        });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> tick());
        NeoForge.EVENT_BUS.addListener(PanelClient::onMovementInput);
    }

    public static boolean grabbing() {
        return stick != null || other != null;
    }

    /** The cell under the crosshair: the line of sight meets the panel surface (in the block's own coordinates on a ship). */
    public static @Nullable int[] aimedCell(ControlPanelBlockEntity be) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        BlockPos pos = be.getBlockPos();
        Vec3 eye = mc.player.getEyePosition(), look = mc.player.getViewVector(1);
        Vec3 a = toBlock(pos, eye), b = toBlock(pos, eye.add(look));
        PanelFrame f = be.frame();
        Vec3 p = f.hit(a.subtract(Vec3.atLowerCornerOf(pos)), b.subtract(a));
        return p == null ? null : f.cell(p, be.grid());
    }

    private static Vec3 toBlock(BlockPos pos, Vec3 world) {
        Minecraft mc = Minecraft.getInstance();
        return AshCC.sableLoaded ? com.ashcc.compat.SableConnector.toBlockSpace(mc.level, pos, world) : world;
    }

    /** Right-click on the panel (client side). */
    public static void click(ControlPanelBlockEntity be, boolean sneaking) {
        int[] cell = aimedCell(be);
        if (cell != null) click(be, cell, sneaking);
    }

    /** Right-click on a given cell {col, row}. */
    public static void click(ControlPanelBlockEntity be, int[] cell, boolean sneaking) {
        Minecraft mc = Minecraft.getInstance();
        BlockPos pos = be.getBlockPos();
        if (sneaking) {
            releaseAll();
            mc.setScreen(new PanelScreen(pos, cell[0], cell[1]));
            return;
        }
        PacketDistributor.sendToServer(new PanelNetwork.Click(pos, cell[0], cell[1]));
        int i = be.indexAt(cell[0], cell[1]);
        if (i < 0) return;
        PanelControl c = be.controls().get(i);
        if (c.type.grabbed) {
            Held h = new Held(pos, i);
            boolean isStick = c.type == PanelControl.Type.STICK;
            Held current = isStick ? stick : other;
            release(current);
            if (h.equals(current)) {
                if (isStick) stick = null;
                else other = null;
            } else {
                if (isStick) stick = h;
                else other = h;
                grabTicks = 0;
            }
        } else if (c.type == PanelControl.Type.BUTTON) {
            buttonPos = pos;
            buttonIndex = i;
        }
    }

    private static void release(@Nullable Held h) {
        if (h != null) PacketDistributor.sendToServer(new PanelNetwork.Input(h.pos, h.index, 0, 0, 0, true));
    }

    private static void releaseAll() {
        release(stick);
        release(other);
        stick = other = null;
    }

    private static @Nullable PanelControl control(@Nullable Held h) {
        Minecraft mc = Minecraft.getInstance();
        if (h == null || mc.level == null || !(mc.level.getBlockEntity(h.pos) instanceof ControlPanelBlockEntity be)) return null;
        if (h.index >= be.controls().size() || !be.controls().get(h.index).type.grabbed) return null;
        Vec3 c = AshCC.sableLoaded ? com.ashcc.compat.SableCompat.worldCenter(mc.level, h.pos) : Vec3.atCenterOf(h.pos);
        return mc.player != null && mc.player.distanceToSqr(c) <= 64 ? be.controls().get(h.index) : null;
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            stick = other = null;
            buttonPos = null;
            return;
        }
        if (buttonPos != null) {
            boolean still = mc.options.keyUse.isDown() && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(buttonPos);
            if (still) PacketDistributor.sendToServer(new PanelNetwork.Hold(buttonPos, buttonIndex));
            else buttonPos = null;
        }
        if (!grabbing()) return;
        grabTicks++;
        if (mc.options.keyShift.isDown()) {
            releaseAll();
            return;
        }
        if (stick != null && control(stick) == null) {
            release(stick);
            stick = null;
        }
        if (other != null && control(other) == null) {
            release(other);
            other = null;
        }
        boolean typing = mc.screen != null;
        if (stick != null) {
            float x = typing ? 0 : axis(mc.options.keyRight.isDown(), mc.options.keyLeft.isDown());
            float y = typing ? 0 : axis(mc.options.keyUp.isDown(), mc.options.keyDown.isDown());
            int b = 0;
            if (!typing) {
                if (mc.options.keyJump.isDown()) b |= PanelControl.TRIGGER;
                if (STICK_BUTTON.isDown()) b |= PanelControl.BUTTON2;
                if (HAT_UP.isDown()) b |= PanelControl.HAT_UP;
                if (HAT_DOWN.isDown()) b |= PanelControl.HAT_DOWN;
                if (HAT_LEFT.isDown()) b |= PanelControl.HAT_LEFT;
                if (HAT_RIGHT.isDown()) b |= PanelControl.HAT_RIGHT;
            }
            PacketDistributor.sendToServer(new PanelNetwork.Input(stick.pos, stick.index, x, y, b, false));
        }
        if (other != null) {
            float y = typing ? 0 : axis(THROTTLE_UP.isDown(), THROTTLE_DOWN.isDown());
            // Held alone, the W/S keys work too
            if (stick == null && !typing && y == 0) y = axis(mc.options.keyUp.isDown(), mc.options.keyDown.isDown());
            int b = 0;
            if (!typing) {
                if (THUMB1.isDown()) b |= PanelControl.THUMB1;
                if (THUMB2.isDown()) b |= PanelControl.THUMB2;
            }
            PacketDistributor.sendToServer(new PanelNetwork.Input(other.pos, other.index, 0, y, b, false));
        }
    }

    private static float axis(boolean plus, boolean minus) {
        return (plus ? 1 : 0) - (minus ? 1 : 0);
    }

    /** While a control is held the movement keys go to it, not to the player. */
    private static void onMovementInput(MovementInputUpdateEvent e) {
        if (!grabbing()) return;
        var in = e.getInput();
        in.up = in.down = in.left = in.right = in.jumping = in.shiftKeyDown = false;
        in.forwardImpulse = 0;
        in.leftImpulse = 0;
    }

    private static void renderHint(GuiGraphics g, net.minecraft.client.DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!grabbing() || mc.options.hideGui) return;
        int y = g.guiHeight() - 70;
        PanelControl s = control(stick), o = control(other);
        StringBuilder text = new StringBuilder();
        if (s != null) text.append(Component.translatable("hud.ashcc.panel_stick", String.format("%+.2f", s.x), String.format("%+.2f", s.y)).getString());
        if (o != null) {
            if (!text.isEmpty()) text.append("   ");
            text.append(Component.translatable("hud.ashcc.panel_value", Math.round(o.value * 15)).getString());
        }
        g.drawCenteredString(mc.font, text.toString(), g.guiWidth() / 2, y, 0xFFFFFFFF);
        if (grabTicks < 100) {
            Component help = Component.translatable(s != null && o != null ? "hud.ashcc.panel_help_hotas" : s != null ? "hud.ashcc.panel_help" : "hud.ashcc.panel_help_lever");
            g.drawCenteredString(mc.font, help, g.guiWidth() / 2, y + 11, 0xFFB0B0B0);
        }
    }
}
