package com.ashcc.panel.client;

import com.ashcc.panel.ControlPanelBlockEntity;
import com.ashcc.panel.PanelControl;
import com.ashcc.panel.PanelNetwork;
import net.minecraft.client.Minecraft;
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
 * The right side shows the whole panel: clicking a cell edits that cell instead, so a cockpit can be laid out in one visit.
 * Types, display modes and colours are picked from buttons and a palette rather than by cycling one button.
 */
public class PanelScreen extends Screen {
    private final BlockPos pos;
    private int col, row;
    private @Nullable PanelControl.Type type;
    private int cellsWide = 1, cellsHigh = 1, channel = 1;
    private int mode = PanelControl.MODE_CANVAS;
    private DyeColor color = DyeColor.RED;
    private int anchorCol, anchorRow;
    private int grid = 4, tilt;

    public PanelScreen(BlockPos pos, int col, int row) {
        super(Component.translatable("screen.ashcc.panel"));
        this.pos = pos;
        loadCell(col, row);
    }

    /** Loads the control at a cell (or the defaults for an empty one) into the form. */
    private void loadCell(int c, int r) {
        col = anchorCol = c;
        row = anchorRow = r;
        type = null;
        cellsWide = 1;
        cellsHigh = 1;
        channel = 1;
        mode = PanelControl.MODE_CANVAS;
        color = DyeColor.RED;
        var mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getBlockEntity(pos) instanceof ControlPanelBlockEntity be) {
            grid = be.grid();
            tilt = be.tilt();
            int i = be.indexAt(c, r);
            if (i >= 0) {
                PanelControl ctl = be.controls().get(i);
                type = ctl.type;
                cellsWide = ctl.width;
                cellsHigh = ctl.height;
                channel = ctl.channel;
                color = ctl.color;
                mode = ctl.mode;
                anchorCol = ctl.col;
                anchorRow = ctl.row;
            } else {
                // Next free channel after the panel's controls
                int next = 1;
                for (PanelControl ctl : be.controls()) next = Math.max(next, ctl.channel + ctl.type.channels);
                channel = Math.min(next, PanelControl.MAX_CHANNEL);
            }
        }
    }

    @Override
    protected void init() {
        rebuild();
    }

    // ---- Layout geometry (shared by rebuild, render and mouse handling) ----

    private int formX() {
        return this.width / 2 - 180;
    }

    private int overviewX() {
        return this.width / 2 + 44;
    }

    private int overviewY() {
        return this.height / 2 - 78;
    }

    private int cellPx() {
        return grid == 4 ? 15 : 9;
    }

    private int paletteX() {
        return formX();
    }

    private int paletteY() {
        return this.height / 2 + 52;
    }

    private void rebuild() {
        clearWidgets();
        int cx = formX();
        int y = this.height / 2 - 78;
        // Type: two rows of direct-pick buttons (first row starts with "none")
        PanelControl.Type[] rowA = {null, PanelControl.Type.BUTTON, PanelControl.Type.TOGGLE, PanelControl.Type.KNOB, PanelControl.Type.SLIDER};
        PanelControl.Type[] rowB = {PanelControl.Type.STICK, PanelControl.Type.THROTTLE, PanelControl.Type.SCREEN, PanelControl.Type.HUD};
        for (int i = 0; i < rowA.length; i++) typeButton(cx + i * 40, y, rowA[i]);
        y += 20;
        for (int i = 0; i < rowB.length; i++) typeButton(cx + i * 40, y, rowB[i]);
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
                for (int m = 0; m <= PanelControl.MODE_MAX; m++) {
                    final int mm = m;
                    Component label = Component.translatable("screen.ashcc.panel.mode.short." + PanelControl.modeName(m));
                    if (m == mode) label = Component.literal("✔ ").append(label);
                    addRenderableWidget(Button.builder(label, b -> {
                        mode = mm;
                        rebuild();
                    }).bounds(cx + m * 40, y, 38, 20).build());
                }
            } else {
                addRenderableWidget(Button.builder(Component.translatable("screen.ashcc.panel.canvas"), b -> {
                }).bounds(cx, y, 200, 20).build()).active = false;
            }
        }
        // Whole panel: grid size and tilt
        int py = this.height / 2 + 86;
        addRenderableWidget(Button.builder(Component.translatable("screen.ashcc.panel.grid", grid, grid), b -> {
            grid = grid == 4 ? 8 : 4;
            rebuild();
        }).bounds(cx, py, 98, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.ashcc.panel.tilt", tilt), b -> {
            tilt = tilt >= ControlPanelBlockEntity.MAX_TILT ? 0 : tilt + 15;
            rebuild();
        }).bounds(cx + 102, py, 98, 20).build());
        int by = this.height / 2 + 110;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> {
            send();
            onClose();
        }).bounds(cx, by, 98, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(cx + 102, by, 98, 20).build());
    }

    private void typeButton(int x, int y, @Nullable PanelControl.Type t) {
        Component label = Component.translatable("screen.ashcc.panel.type." + (t == null ? "none" : t.id()));
        if (t == type) label = Component.literal("✔ ").append(label);
        addRenderableWidget(Button.builder(label, b -> {
            applyType(t);
        }).bounds(x, y, 38, 18).build());
    }

    /** Picks a type, growing a fresh 1x1 control to the usual default size of the big types. */
    private void applyType(@Nullable PanelControl.Type t) {
        type = t;
        if (cellsWide == 1 && cellsHigh == 1 && t != null) {
            int big = grid / 2;
            switch (t) {
                case STICK, SCREEN, HUD -> cellsWide = cellsHigh = big;
                case SLIDER, THROTTLE -> cellsHigh = big;
                default -> {
                }
            }
            cellsWide = Math.min(cellsWide, grid - anchorCol);
            cellsHigh = Math.min(cellsHigh, grid - anchorRow);
        }
        rebuild();
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

    private void send() {
        PacketDistributor.sendToServer(new PanelNetwork.Layout(pos, grid, tilt));
        // A changed grid rescales the controls first; the cell then refers to the new grid
        if (grid != originalGrid()) return;
        PacketDistributor.sendToServer(new PanelNetwork.Configure(pos, col, row, type == null ? "" : type.id(), cellsWide, cellsHigh, channel, color.getId(), mode));
    }

    private int originalGrid() {
        var mc = Minecraft.getInstance();
        return mc.level != null && mc.level.getBlockEntity(pos) instanceof ControlPanelBlockEntity be ? be.grid() : grid;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // Panel overview: jump to another cell
            int ox = overviewX(), oy = overviewY(), cp = cellPx();
            if (mouseX >= ox && mouseX < ox + cp * grid && mouseY >= oy && mouseY < oy + cp * grid) {
                int c = (int) ((mouseX - ox) / cp), r = (int) ((mouseY - oy) / cp);
                if (c != col || r != row) {
                    loadCell(c, r);
                    rebuild();
                }
                return true;
            }
            // Colour palette
            if (type != null) {
                for (int i = 0; i < 16; i++) {
                    int sx = paletteX() + (i % 8) * 24, sy = paletteY() + (i / 8) * 14;
                    if (mouseX >= sx && mouseX < sx + 22 && mouseY >= sy && mouseY < sy + 12) {
                        color = DyeColor.byId(i);
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int cx = formX();
        g.drawCenteredString(font, title, this.width / 2, this.height / 2 - 100, 0xFFFFFFFF);
        g.drawCenteredString(font, Component.translatable("screen.ashcc.panel.cell", anchorCol + 1, anchorRow + 1), cx + 100, this.height / 2 - 89, 0xFFA0A0A0);
        if (type != null) {
            // Colour name above the palette (the palette itself is drawn here; clicks are hit-tested in mouseClicked)
            g.drawString(font, Component.translatable("screen.ashcc.panel.color",
                Component.translatable("color.minecraft." + color.getSerializedName())), cx, paletteY() - 10, 0xFFFFFFFF);
            for (int i = 0; i < 16; i++) {
                DyeColor d = DyeColor.byId(i);
                int sx = paletteX() + (i % 8) * 24, sy = paletteY() + (i / 8) * 14;
                if (d == color) g.fill(sx - 1, sy - 1, sx + 23, sy + 13, 0xFFFFFFFF);
                g.fill(sx, sy, sx + 22, sy + 12, 0xFF000000 | d.getTextureDiffuseColor());
            }
        }
        renderOverview(g);
    }

    /** The whole panel as a grid: filled cells take their control's colour, the edited control is outlined. */
    private void renderOverview(GuiGraphics g) {
        int ox = overviewX(), oy = overviewY(), cp = cellPx();
        g.drawString(font, Component.translatable("screen.ashcc.panel.overview"), ox, oy - 11, 0xFFA0A0A0);
        var mc = Minecraft.getInstance();
        ControlPanelBlockEntity be = mc.level != null && mc.level.getBlockEntity(pos) instanceof ControlPanelBlockEntity b ? b : null;
        for (int r = 0; r < grid; r++) {
            for (int c = 0; c < grid; c++) {
                int sx = ox + c * cp, sy = oy + r * cp;
                g.fill(sx, sy, sx + cp - 1, sy + cp - 1, 0xFF20242A);
                if (be == null) continue;
                int i = be.indexAt(c, r);
                if (i >= 0) {
                    PanelControl ctl = be.controls().get(i);
                    g.fill(sx, sy, sx + cp - 1, sy + cp - 1, 0xFF000000 | ctl.color.getTextureDiffuseColor());
                }
            }
        }
        if (be != null) {
            int i = be.indexAt(col, row);
            if (i >= 0) {
                PanelControl ctl = be.controls().get(i);
                outline(g, ox + ctl.col * cp - 1, oy + ctl.row * cp - 1, ctl.width * cp + 1, ctl.height * cp + 1);
            } else {
                outline(g, ox + col * cp - 1, oy + row * cp - 1, cp + 1, cp + 1);
            }
        }
    }

    private void outline(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + 1, 0xFFFFFFFF);
        g.fill(x, y + h - 1, x + w, y + h, 0xFFFFFFFF);
        g.fill(x, y, x + 1, y + h, 0xFFFFFFFF);
        g.fill(x + w - 1, y, x + w, y + h, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
