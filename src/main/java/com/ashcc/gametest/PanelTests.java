package com.ashcc.gametest;

import com.ashcc.AshCC;
import com.ashcc.panel.ControlPanelBlock;
import com.ashcc.panel.ControlPanelBlockEntity;
import com.ashcc.panel.PanelControl;
import com.ashcc.panel.PanelFrame;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Control panel: layout rules, grid and tilt, each control's channels, the stick spring, holding a stick and a throttle at once,
 * screens on the canvas, redstone output, and the drivebywire cable source.
 */
@GameTestHolder(AshCC.MOD_ID)
@PrefixGameTestTemplate(false)
public class PanelTests {
    private static final BlockPos PANEL = new BlockPos(4, 2, 4);

    private static ControlPanelBlockEntity place(GameTestHelper helper) {
        helper.setBlock(PANEL, AshCC.CONTROL_PANEL.get().defaultBlockState().setValue(ControlPanelBlock.FACE, AttachFace.FLOOR)
            .setValue(ControlPanelBlock.FACING, Direction.NORTH));
        return (ControlPanelBlockEntity) helper.getBlockEntity(PANEL);
    }

    @GameTest(template = "empty_9x6x9")
    public static void panelLayout(GameTestHelper helper) {
        ControlPanelBlockEntity be = place(helper);
        helper.assertTrue(be.configure(0, 0, PanelControl.Type.STICK, 2, 2, 1, DyeColor.RED), "stick 2x2 at the top left");
        helper.assertTrue(be.configure(2, 0, PanelControl.Type.SLIDER, 1, 4, 11, DyeColor.BLUE), "slider down the third column");
        helper.assertTrue(!be.configure(0, 2, PanelControl.Type.BUTTON, 3, 1, 12, DyeColor.GREEN), "a button across the slider is refused");
        helper.assertTrue(!be.configure(3, 1, PanelControl.Type.BUTTON, 2, 1, 12, DyeColor.GREEN),
            "a control too wide for its place is pulled back inside the grid, onto the slider, and refused");
        helper.assertTrue(be.configure(3, 1, PanelControl.Type.BUTTON, 1, 1, 12, DyeColor.GREEN) && be.indexAt(3, 1) == 2, "fits at the edge");
        // Clicking any cell of the stick edits the stick: resize it from its top-left cell
        helper.assertTrue(be.configure(1, 1, PanelControl.Type.STICK, 1, 1, 1, DyeColor.RED), "resize from another cell");
        helper.assertTrue(be.controls().get(0).col == 0 && be.controls().get(0).width == 1 && be.indexAt(1, 1) < 0, "stick shrank in place");
        helper.assertTrue(be.configure(0, 0, null, 1, 1, 1, DyeColor.RED) && be.indexAt(0, 0) < 0, "removed");
        // 4x4 -> 8x8 doubles every control
        be.setLayout(8, 0);
        PanelControl slider = be.controls().get(be.indexAt(4, 0));
        helper.assertTrue(be.grid() == 8 && slider.col == 4 && slider.width == 2 && slider.height == 8,
            "rescaled to 8x8: " + slider.col + "," + slider.width + "x" + slider.height);
        // The grid on the floor panel: top row is the far (north) side
        PanelFrame f = PanelFrame.of(helper.getBlockState(PANEL));
        int[] nw = f.cell(new Vec3(0.1, PanelFrame.THICKNESS, 0.1), 4);
        int[] se = f.cell(new Vec3(0.9, PanelFrame.THICKNESS, 0.9), 4);
        helper.assertTrue(nw[0] == 0 && nw[1] == 0 && se[0] == 3 && se[1] == 3, "floor grid orientation " + nw[0] + "," + nw[1] + " / " + se[0] + "," + se[1]);
        helper.succeed();
    }

    /** A tilted floor panel rises to the north; a ray from above-south hits the far row on the raised surface. */
    @GameTest(template = "empty_9x6x9")
    public static void panelTilt(GameTestHelper helper) {
        BlockState state = AshCC.CONTROL_PANEL.get().defaultBlockState().setValue(ControlPanelBlock.FACE, AttachFace.FLOOR)
            .setValue(ControlPanelBlock.FACING, Direction.NORTH);
        PanelFrame f = PanelFrame.of(state, 30);
        Vec3 far = f.point(0.5, 1), near = f.point(0.5, 0);
        helper.assertTrue(far.y > near.y + 0.45 && far.z < near.z, "far edge raised: near " + near + " far " + far);
        helper.assertTrue(f.normal().z > 0.4 && f.normal().y > 0.8, "surface leans towards the pilot (south): " + f.normal());
        Vec3 eye = new Vec3(0.5, 1.6, 1.8);
        Vec3 hit = f.hit(eye, f.point(0.5, 0.9).subtract(eye));
        int[] cell = hit == null ? null : f.cell(hit, 4);
        helper.assertTrue(cell != null && cell[1] == 0, "aiming at the top of the tilted surface finds row 0: " + (cell == null ? null : cell[1]));
        helper.assertTrue(f.hit(eye, new Vec3(0, 1, 0)) == null, "a ray going away from the surface misses");
        helper.assertTrue(f.bounds().maxY > 0.6, "outline covers the raised edge: " + f.bounds());
        ControlPanelBlockEntity be = place(helper);
        be.setLayout(4, 37);
        helper.assertTrue(be.tilt() == 30, "tilt snaps to 15 degree steps: " + be.tilt());
        helper.succeed();
    }

    @GameTest(template = "empty_9x6x9", timeoutTicks = 100)
    public static void panelChannels(GameTestHelper helper) {
        ControlPanelBlockEntity be = place(helper);
        helper.setBlock(PANEL.east(), Blocks.REDSTONE_LAMP);
        be.configure(0, 0, PanelControl.Type.BUTTON, 1, 1, 1, DyeColor.RED);
        be.configure(1, 0, PanelControl.Type.TOGGLE, 1, 1, 2, DyeColor.RED);
        be.configure(0, 1, PanelControl.Type.STICK, 2, 2, 3, DyeColor.RED);
        be.configure(3, 0, PanelControl.Type.THROTTLE, 1, 4, 20, DyeColor.RED);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        be.click(player, 0, 0);
        be.click(player, 1, 0);
        int stick = be.indexAt(0, 1), throttle = be.indexAt(3, 0);
        // HOTAS: stick forward-right with trigger and hat up, throttle forward with thumb button 1, at the same time
        int stickButtons = PanelControl.TRIGGER | PanelControl.HAT_UP;
        for (int t = 2; t < 22; t++) helper.runAtTickTime(t, () -> {
            be.input(player, stick, 1, 1, stickButtons, false);
            be.input(player, throttle, 0, 1, PanelControl.THUMB1, false);
        });
        helper.runAtTickTime(22, () -> {
            helper.assertTrue(be.channel(2) == 15, "toggle on, ch2=" + be.channel(2));
            helper.assertTrue(be.channel(1) == 0, "button released after its hold time, ch1=" + be.channel(1));
            // Stick: 3 fwd, 4 back, 5 left, 6 right, 7 trigger, 8 hat up, 9 hat down, 10 hat left, 11 hat right, 12 button 2
            helper.assertTrue(be.channel(3) == 15 && be.channel(4) == 0 && be.channel(5) == 0 && be.channel(6) == 15, "stick axes");
            helper.assertTrue(be.channel(7) == 15 && be.channel(8) == 15 && be.channel(9) == 0 && be.channel(12) == 0, "stick buttons");
            // Throttle: 20 value, 21 thumb 1, 22 thumb 2
            int thr = be.channel(20);
            helper.assertTrue(thr >= 6 && thr <= 10 && be.channel(21) == 15 && be.channel(22) == 0,
                "throttle about half after ~1 s and thumb 1: " + thr + "," + be.channel(21) + "," + be.channel(22));
            helper.assertTrue(be.held(stick) && be.held(throttle), "both held at once");
            helper.assertTrue(be.redstone() == 15, "redstone = highest channel");
            helper.assertTrue(helper.getBlockState(PANEL.east()).getValue(RedstoneLampBlock.LIT), "lamp next to the panel is lit");
            be.input(player, stick, 0, 0, 0, true);
            be.input(player, throttle, 0, 0, 0, true);
        });
        helper.runAtTickTime(42, () -> {
            helper.assertTrue(be.channel(3) == 0 && be.channel(6) == 0 && be.channel(7) == 0 && be.channel(8) == 0,
                "stick centred and buttons off after letting go");
            helper.assertTrue(be.channel(20) > 0 && be.channel(21) == 0, "throttle keeps its place, its button is let go");
            helper.succeed();
        });
    }

    /** Screens and HUDs show their part of the canvas; the panel is also an hd_monitor for drawing. */
    @GameTest(template = "empty_9x6x9")
    public static void panelScreens(GameTestHelper helper) {
        ControlPanelBlockEntity be = place(helper);
        be.configure(0, 0, PanelControl.Type.SCREEN, 2, 2, 1, DyeColor.BLACK);
        be.configure(2, 2, PanelControl.Type.HUD, 2, 2, 1, DyeColor.BLACK);
        var p = be.peripheral();
        helper.assertTrue(p != null && p.getType().equals("control_panel") && p.getAdditionalTypes().contains("hd_monitor"), "peripheral types");
        List<Map<String, Object>> controls = p.getControls();
        Map<String, Object> scr = controls.get(0), hud = controls.get(1);
        helper.assertTrue(scr.get("px").equals(0) && scr.get("pw").equals(128) && hud.get("px").equals(128) && hud.get("py").equals(128),
            "canvas rectangles " + scr + " " + hud);
        Object[] res = p.getResolution();
        helper.assertTrue(res[0].equals(ControlPanelBlockEntity.CANVAS), "canvas size " + res[0]);
        helper.assertTrue(be.channel(1) == 0, "displays have no channel output");
        helper.succeed();
    }

    /** With drivebywire: the panel is a multi-channel cable source and its channels reach the network. */
    @GameTest(template = "empty_9x6x9", timeoutTicks = 60)
    public static void panelDriveByWire(GameTestHelper helper) {
        if (!ModList.get().isLoaded("drivebywire")) {
            helper.succeed();
            return;
        }
        Impl.wire(helper);
    }

    private static final class Impl {
        static void wire(GameTestHelper helper) {
            ControlPanelBlockEntity be = place(helper);
            BlockState state = helper.getBlockState(PANEL);
            helper.assertTrue(state.getBlock() instanceof edn.stratodonut.drivebywire.wire.MultiChannelWireSource src
                && src.wire$getChannels().contains("panel_64"), "panel is a multi-channel cable source with 64 channels");
            BlockPos lamp = new BlockPos(1, 2, 1);
            helper.setBlock(lamp, Blocks.REDSTONE_LAMP);
            var result = edn.stratodonut.drivebywire.wire.WireNetworkManager.createConnection(helper.getLevel(), helper.absolutePos(PANEL),
                helper.absolutePos(lamp), Direction.UP, "panel_1");
            AshCC.LOGGER.info("panel cable: connection {}", result);
            be.configure(0, 0, PanelControl.Type.TOGGLE, 1, 1, 1, DyeColor.RED);
            be.click(helper.makeMockPlayer(GameType.SURVIVAL), 0, 0);
            helper.runAfterDelay(5, () -> {
                var signals = edn.stratodonut.drivebywire.wire.WireNetworkManager.get(helper.getLevel()).getSourceSignals(helper.absolutePos(PANEL));
                AshCC.LOGGER.info("panel cable: source signals {} lamp lit {}", signals, helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT));
                helper.assertTrue(Integer.valueOf(15).equals(signals.get("panel_1")), "panel_1 = 15 on the cable network: " + signals);
                helper.succeed();
            });
        }
    }
}
