package com.ashcc.panel.client;

import com.ashcc.panel.ControlPanelBlockEntity;
import com.ashcc.panel.PanelControl;
import com.ashcc.panel.PanelNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * Set-up of one panel cell: control type, size in cells, first channel and colour. The control's top-left cell stays where
 * it is (or is the clicked cell for a new one); a size that would hit another control is refused by the server.
 */
public class PanelScreen extends Screen {
    private final BlockPos pos;
    private final int col, row;
    private @Nullable PanelControl.Type type;
    private int cellsWide = 1, cellsHigh = 1, channel = 1;
    private int mode = PanelControl.MODE_CANVAS;
    private DyeColor color = DyeColor.RED;
    private int anchorCol, anchorRow;
    private int grid = 4, tilt;

    public PanelScreen(BlockPos pos, int col, int row) {
        super(Component.translatable("screen.ashcc.panel"));
        this.pos = pos;
        this.col = col;
        this.row = row;
        anchorCol = col;
        anchorRow = row;
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level != null && mc.level.getBlockEntity(pos) instanceof ControlPanelBlockEntity be) {
            grid = be.grid();
            tilt = be.tilt();
            int i = be.indexAt(col, row);
            if (i >= 0) {
                PanelControl c = be.controls().get(i);
                type = c.type;
                cellsWide = c.width;
                cellsHigh = c.height;
                channel = c.channel;
                color = c.color;
                mode = c.mode;
                anchorCol = c.col;
                anchorRow = c.row;
            } else {
                // Next free channel after the panel's controls
                int next = 1;
                for (PanelControl c : be.controls()) next = Math.max(next, c.channel + c.type.channels);
                channel = Math.min(next, PanelControl.MAX_CHANNEL);
            }
        }
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        int cx = this.width / 2 - 100;
        int y = this.height / 2 - 70;
        addRenderableWidget(Button.builder(typeName(), b -> {
            PanelControl.Type[] all = PanelControl.Type.values();
            if (type == null) type = all[0];
            else type = type.ordinal() + 1 < all.length ? all[type.ordinal() + 1] : null;
            if (cellsWide == 1 && cellsHigh == 1 && type != null) {
                int big = grid / 2;
                switch (type) {
                    case STICK, SCREEN, HUD -> cellsWide = cellsHigh = big;
                    case SLIDER, THROTTLE -> cellsHigh = big;
                    default -> {
                    }
                }
                cellsWide = Math.min(cellsWide, grid - anchorCol);
                cellsHigh = Math.min(cellsHigh, grid - anchorRow);
            }
            rebuild();
        }).bounds(cx, y, 200, 20).build());
        if (type != null) {
            y += 26;
            stepper(cx, y, Component.translatable("screen.ashcc.panel.width", cellsWide), () -> cellsWide = Math.max(1, cellsWide - 1),
                () -> cellsWide = Math.min(grid - anchorCol, cellsWide + 1));
            y += 24;
            stepper(cx, y, Component.translatable("screen.ashcc.panel.height", cellsHigh), () -> cellsHigh = Math.max(1, cellsHigh - 1),
                () -> cellsHigh = Math.min(grid - anchorRow, cellsHigh + 1));
            y += 24;
            if (type.channels > 0) {
                int last = channel + type.channels - 1;
                Component ch = type.channels > 1 ? Component.translatable("screen.ashcc.panel.channels." + type.id(), channel, last)
                    : Component.translatable("screen.ashcc.panel.channel", channel);
                stepper(cx, y, ch, () -> channel = Math.max(1, channel - 1),
                    () -> channel = Math.min(PanelControl.MAX_CHANNEL - type.channels + 1, channel + 1));
            } else if (type == PanelControl.Type.SCREEN) {
                addRenderableWidget(Button.builder(Component.translatable("screen.ashcc.panel.mode",
                    Component.translatable(mode == PanelControl.MODE_INSTRUMENTS ? "screen.ashcc.panel.mode.instruments" : "screen.ashcc.panel.mode.canvas")), b -> {
                    mode = mode == PanelControl.MODE_INSTRUMENTS ? PanelControl.MODE_CANVAS : PanelControl.MODE_INSTRUMENTS;
                    rebuild();
                }).bounds(cx, y, 200, 20).build());
            } else {
                addRenderableWidget(Button.builder(Component.translatable("screen.ashcc.panel.canvas"), b -> {
                }).bounds(cx, y, 200, 20).build()).active = false;
            }
            y += 24;
            addRenderableWidget(Button.builder(Component.translatable("screen.ashcc.panel.color",
                Component.translatable("color.minecraft." + color.getSerializedName())), b -> {
                color = DyeColor.byId((color.getId() + 1) % 16);
                rebuild();
            }).bounds(cx, y, 200, 20).build());
        }
        // Whole panel: grid size and tilt
        int py = this.height / 2 + 40;
        addRenderableWidget(Button.builder(Component.translatable("screen.ashcc.panel.grid", grid, grid), b -> {
            grid = grid == 4 ? 8 : 4;
            rebuild();
        }).bounds(cx, py, 98, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.ashcc.panel.tilt", tilt), b -> {
            tilt = tilt >= ControlPanelBlockEntity.MAX_TILT ? 0 : tilt + 15;
            rebuild();
        }).bounds(cx + 102, py, 98, 20).build());
        int by = this.height / 2 + 64;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> {
            send();
            onClose();
        }).bounds(cx, by, 98, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(cx + 102, by, 98, 20).build());
    }

    private void stepper(int x, int y, Component label, Runnable minus, Runnable plus) {
        addRenderableWidget(Button.builder(Component.literal("-"), b -> {
            minus.run();
            rebuild();
        }).bounds(x, y, 20, 20).build());
        addRenderableWidget(Button.builder(label, b -> {
        }).bounds(x + 24, y, 152, 20).build()).active = false;
        addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            plus.run();
            rebuild();
        }).bounds(x + 180, y, 20, 20).build());
    }

    private Component typeName() {
        return Component.translatable("screen.ashcc.panel.type",
            Component.translatable(type == null ? "panel_control.ashcc.none" : "panel_control.ashcc." + type.id()));
    }

    private void send() {
        PacketDistributor.sendToServer(new PanelNetwork.Layout(pos, grid, tilt));
        // A changed grid rescales the controls first; the cell then refers to the new grid
        if (grid != originalGrid()) return;
        PacketDistributor.sendToServer(new PanelNetwork.Configure(pos, col, row, type == null ? "" : type.id(), cellsWide, cellsHigh, channel, color.getId(), mode));
    }

    private int originalGrid() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        return mc.level != null && mc.level.getBlockEntity(pos) instanceof ControlPanelBlockEntity be ? be.grid() : grid;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, this.width / 2, this.height / 2 - 92, 0xFFFFFFFF);
        g.drawCenteredString(font, Component.translatable("screen.ashcc.panel.cell", anchorCol + 1, anchorRow + 1), this.width / 2, this.height / 2 - 81, 0xFFA0A0A0);
        if (type != null) {
            // Colour swatch next to the colour button
            int sx = this.width / 2 + 106, sy = this.height / 2 - 70 + 26 + 24 * 3;
            g.fill(sx, sy + 2, sx + 16, sy + 18, 0xFF000000 | color.getTextureDiffuseColor());
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
