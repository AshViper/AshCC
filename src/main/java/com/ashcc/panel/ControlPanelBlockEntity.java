package com.ashcc.panel;

import com.ashcc.AshCC;
import com.ashcc.AshCCConfig;
import com.ashcc.monitor.MonitorHost;
import com.ashcc.monitor.MonitorScreen;
import com.ashcc.monitor.ScreenOwner;
import dan200.computercraft.api.peripheral.AttachedComputerSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Controls of a panel and the channel values they make. Every tick: buttons time out, controls nobody holds spring back,
 * then any channel that changed is sent to drivebywire, CC and the redstone output, and the new state to the clients.
 * The panel also owns a canvas (like an HD monitor) that its screen and HUD controls show parts of.
 */
public class ControlPanelBlockEntity extends BlockEntity implements MonitorHost, ScreenOwner {
    /** Canvas size in pixels; a control covering cells shows the same share of it. */
    public static final int CANVAS = 256;
    public static final int[] GRIDS = {4, 8};
    public static final int MAX_TILT = 60;
    /** Ticks a button stays down after the last "still holding" message. */
    private static final int BUTTON_HOLD = 4;
    /** Ticks without input after which a grabbed control is let go. */
    private static final int GRAB_TIMEOUT = 10;

    final AttachedComputerSet computers = new AttachedComputerSet();
    private @Nullable ControlPanelPeripheral peripheral;
    private @Nullable MonitorScreen screen;

    private final List<PanelControl> controls = new ArrayList<>();
    private int grid = 4;
    private int tilt;
    private boolean glow = true;
    /** Channel values sent last (index = channel, 1..64). */
    private final int[] sent = new int[PanelControl.MAX_CHANNEL + 1];
    private int redstone;
    private boolean dirty;

    /** Who holds which grabbed control (a player may hold a stick and a throttle at once). */
    private record Grab(UUID player, long tick, double x, double y, int buttons) {
    }

    private final Map<Integer, Grab> grabs = new HashMap<>();

    public ControlPanelBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public ControlPanelBlockEntity(BlockPos pos, BlockState state) {
        this(AshCC.CONTROL_PANEL_BE.get(), pos, state);
    }

    public ControlPanelPeripheral peripheral() {
        if (peripheral == null) {
            MonitorScreen s = screen();
            if (s == null) return null;
            peripheral = new ControlPanelPeripheral(this, s);
        }
        return peripheral;
    }

    // ---- Canvas ----

    /** The canvas the screens show (server, created on first use). */
    @Override
    public @Nullable MonitorScreen screen() {
        if (level == null || level.isClientSide || isRemoved()) return null;
        if (screen == null || screen.disposed()) screen = new MonitorScreen(this, worldPosition, 1, 1, CANVAS, CANVAS);
        return screen;
    }

    @Override
    public void setHologram(boolean on) {
    }

    @Override
    public boolean hologram() {
        return false;
    }

    @Override
    public void setGlow(boolean on) {
        glow = on;
        changed();
    }

    @Override
    public boolean glow() {
        return glow;
    }

    /** Pixel rectangle {x, y, w, h} of a control on the canvas. */
    public int[] pixels(PanelControl c) {
        int cell = CANVAS / grid;
        return new int[]{c.col * cell, c.row * cell, c.width * cell, c.height * cell};
    }

    public boolean hasDisplay() {
        return controls.stream().anyMatch(c -> c.type.display());
    }

    // ---- Layout ----

    public List<PanelControl> controls() {
        return controls;
    }

    public int grid() {
        return grid;
    }

    public int tilt() {
        return tilt;
    }

    public PanelFrame frame() {
        return PanelFrame.of(getBlockState(), tilt);
    }

    public int indexAt(int col, int row) {
        for (int i = 0; i < controls.size(); i++) if (controls.get(i).covers(col, row)) return i;
        return -1;
    }

    public int redstone() {
        return redstone;
    }

    /** Current value of a channel (1..64), as last sent. */
    public int channel(int ch) {
        return ch >= 1 && ch <= PanelControl.MAX_CHANNEL ? sent[ch] : 0;
    }

    public boolean held(int index) {
        return grabs.containsKey(index);
    }

    /** Grid 4 or 8 (controls are rescaled; any that no longer fit are dropped) and tilt 0..60 in steps of 15. */
    public void setLayout(int newGrid, int newTilt) {
        newTilt = Mth.clamp(Math.round(newTilt / 15f) * 15, 0, MAX_TILT);
        if (newGrid != 4 && newGrid != 8) newGrid = grid;
        if (newGrid != grid) {
            double f = newGrid / (double) grid;
            List<PanelControl> old = new ArrayList<>(controls);
            controls.clear();
            grabs.clear();
            for (PanelControl c : old) {
                PanelControl r = c.rescaled(f, newGrid);
                if (controls.stream().noneMatch(o -> o.overlaps(r))) controls.add(r);
            }
            grid = newGrid;
        }
        if (newTilt != tilt) {
            tilt = newTilt;
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
        changed();
    }

    /** Configures a cell with a canvas display (see the overload taking a display mode). */
    public boolean configure(int col, int row, @Nullable PanelControl.Type type, int width, int height, int channel, DyeColor color) {
        return configure(col, row, type, width, height, channel, color, PanelControl.MODE_CANVAS);
    }

    /**
     * Puts a control at (col,row), replacing the one there; type null removes it.
     * @return false when it would overlap another control
     */
    public boolean configure(int col, int row, @Nullable PanelControl.Type type, int width, int height, int channel, DyeColor color, int mode) {
        int old = indexAt(col, row);
        int anchorCol = old >= 0 ? controls.get(old).col : col, anchorRow = old >= 0 ? controls.get(old).row : row;
        if (type == null) {
            if (old >= 0) remove(old);
            changed();
            return true;
        }
        PanelControl c = new PanelControl(type, anchorCol, anchorRow, width, height, channel, color, grid);
        c.mode = mode == PanelControl.MODE_INSTRUMENTS ? PanelControl.MODE_INSTRUMENTS : PanelControl.MODE_CANVAS;
        for (int i = 0; i < controls.size(); i++) {
            if (i != old && controls.get(i).overlaps(c)) return false;
        }
        if (old >= 0) {
            PanelControl prev = controls.get(old);
            if (prev.type == type) {
                c.value = prev.value;
                c.on = type == PanelControl.Type.TOGGLE && prev.on;
            }
            controls.set(old, c);
            grabs.remove(old);
        } else {
            controls.add(c);
        }
        changed();
        return true;
    }

    private void remove(int index) {
        controls.remove(index);
        Map<Integer, Grab> shifted = new HashMap<>();
        grabs.forEach((i, g) -> {
            if (i < index) shifted.put(i, g);
            else if (i > index) shifted.put(i - 1, g);
        });
        grabs.clear();
        grabs.putAll(shifted);
    }

    // ---- Use ----

    /** Right-click on a cell: press a button, flip a toggle. Grabbed controls are held through {@link #input}. */
    public void click(Player player, int col, int row) {
        int i = indexAt(col, row);
        if (i < 0) return;
        PanelControl c = controls.get(i);
        switch (c.type) {
            case BUTTON -> {
                c.on = true;
                c.pressTicks = BUTTON_HOLD;
                sound(0.6f);
            }
            case TOGGLE -> {
                c.on = !c.on;
                sound(c.on ? 0.6f : 0.5f);
            }
            default -> {
            }
        }
        changed();
    }

    /** The client still holds the use key on a button. */
    public void hold(int index) {
        if (index < 0 || index >= controls.size()) return;
        PanelControl c = controls.get(index);
        if (c.type == PanelControl.Type.BUTTON && c.on) c.pressTicks = BUTTON_HOLD;
    }

    /**
     * Input from a player holding a grabbed control: x, y -1..1 (keys), buttons (bits of {@link PanelControl}).
     * release lets go of that control.
     */
    public void input(Player player, int index, double x, double y, int buttons, boolean release) {
        if (level == null || index < 0 || index >= controls.size() || !controls.get(index).type.grabbed) return;
        Grab g = grabs.get(index);
        if (release) {
            if (g != null && g.player.equals(player.getUUID())) grabs.remove(index);
            return;
        }
        // Someone else's hands are on it
        if (g != null && !g.player.equals(player.getUUID()) && level.getGameTime() - g.tick < GRAB_TIMEOUT) return;
        grabs.put(index, new Grab(player.getUUID(), level.getGameTime(), Mth.clamp(x, -1, 1), Mth.clamp(y, -1, 1), buttons));
    }

    private void sound(float pitch) {
        if (level != null) level.playSound(null, worldPosition, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.3f, pitch);
    }

    private void changed() {
        dirty = true;
        setChanged();
    }

    // ---- Tick ----

    public static void serverTick(Level level, BlockPos pos, BlockState state, ControlPanelBlockEntity be) {
        be.grabs.values().removeIf(g -> level.getGameTime() - g.tick > GRAB_TIMEOUT);
        double dt = 1 / 20.0;
        for (int i = 0; i < be.controls.size(); i++) {
            PanelControl c = be.controls.get(i);
            Grab g = be.grabs.get(i);
            double gx = g == null ? 0 : g.x, gy = g == null ? 0 : g.y;
            int gb = g == null ? 0 : g.buttons;
            switch (c.type) {
                case BUTTON -> {
                    if (c.on && --c.pressTicks <= 0) {
                        c.on = false;
                        be.dirty = true;
                    }
                }
                case SLIDER, THROTTLE -> {
                    if (gy != 0) {
                        c.value = Mth.clamp(c.value + gy * AshCCConfig.PANEL_SLIDER_RATE.get() * dt, 0, 1);
                        be.dirty = true;
                    }
                    if (c.type == PanelControl.Type.THROTTLE && c.buttons != gb) {
                        c.buttons = gb;
                        be.dirty = true;
                    }
                }
                case KNOB -> {
                    // One step per 3 ticks while W/S is held
                    if (gy != 0 && level.getGameTime() % 3 == 0) {
                        c.value = Mth.clamp(Math.round(c.value * 15 + Math.signum(gy)) / 15.0, 0, 1);
                        be.dirty = true;
                    }
                }
                case STICK -> {
                    // Moves towards the keys while held, back to the centre otherwise
                    double step = AshCCConfig.PANEL_STICK_RATE.get() * dt;
                    double nx = c.x + Mth.clamp(gx - c.x, -step, step), ny = c.y + Mth.clamp(gy - c.y, -step, step);
                    if (nx != c.x || ny != c.y || gb != c.buttons) be.dirty = true;
                    c.x = nx;
                    c.y = ny;
                    c.buttons = gb;
                }
                default -> {
                }
            }
        }
        if (be.screen != null && !be.screen.disposed()) be.screen.tick();
        else if (be.hasDisplay()) be.screen();
        if (level.getGameTime() % 4 == 0) PanelInstruments.refresh(be);
        if (be.dirty) {
            be.dirty = false;
            be.publish(level, pos, state);
        }
    }

    /** Sends changed channels to drivebywire and CC, updates the redstone output and the clients. */
    private void publish(Level level, BlockPos pos, BlockState state) {
        int[] now = new int[PanelControl.MAX_CHANNEL + 1];
        for (PanelControl c : controls) {
            int[] out = c.outputs();
            for (int k = 0; k < out.length; k++) {
                int ch = c.channel + k;
                if (ch <= PanelControl.MAX_CHANNEL) now[ch] = Math.max(now[ch], out[k]);
            }
        }
        int max = 0;
        for (int ch = 1; ch <= PanelControl.MAX_CHANNEL; ch++) {
            max = Math.max(max, now[ch]);
            if (now[ch] == sent[ch]) continue;
            sent[ch] = now[ch];
            final int c = ch, v = now[ch];
            if (AshCC.driveByWireLoaded) com.ashcc.compat.drivebywire.DriveByWireCompat.setSignal(level, pos, c, v);
            computers.forEach(comp -> comp.queueEvent("control_panel", comp.getAttachmentName(), c, v));
        }
        if (max != redstone) {
            redstone = max;
            level.updateNeighborsAt(pos, state.getBlock());
            level.updateNeighborsAt(pos.relative(frame().attached()), state.getBlock());
        }
        level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
    }

    /** Turns every channel off (the block is going away). */
    public void clearOutputs() {
        if (level == null || level.isClientSide) return;
        for (int ch = 1; ch <= PanelControl.MAX_CHANNEL; ch++) {
            if (sent[ch] != 0 && AshCC.driveByWireLoaded) com.ashcc.compat.drivebywire.DriveByWireCompat.setSignal(level, worldPosition, ch, 0);
            sent[ch] = 0;
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (screen != null) screen.dispose();
        screen = null;
        peripheral = null;
    }

    /** Whether the player can reach the panel (on a Sable ship, measured to where the panel shows up in the world). */
    public boolean inReach(Player player) {
        if (level == null) return false;
        Vec3 c = AshCC.sableLoaded ? com.ashcc.compat.SableCompat.worldCenter(level, worldPosition) : Vec3.atCenterOf(worldPosition);
        return player.distanceToSqr(c) < 64;
    }

    // ---- Save / sync ----

    private void write(CompoundTag tag, boolean withState) {
        tag.putByte("Grid", (byte) grid);
        tag.putByte("Tilt", (byte) tilt);
        tag.putBoolean("Glow", glow);
        ListTag list = new ListTag();
        for (PanelControl c : controls) list.add(c.save(withState));
        tag.put("Controls", list);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        write(tag, true);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        int oldTilt = tilt;
        grid = tag.contains("Grid") && tag.getByte("Grid") == 8 ? 8 : 4;
        tilt = Mth.clamp(tag.getByte("Tilt"), 0, MAX_TILT);
        glow = !tag.contains("Glow") || tag.getBoolean("Glow");
        controls.clear();
        for (Tag t : tag.getList("Controls", Tag.TAG_COMPOUND)) {
            PanelControl c = PanelControl.load((CompoundTag) t, grid);
            if (controls.stream().noneMatch(o -> o.overlaps(c))) controls.add(c);
        }
        if (tag.contains("Held")) {
            grabs.clear();
            for (int i : tag.getIntArray("Held")) grabs.put(i, new Grab(new UUID(0, 0), 0, 0, 0, 0));
        }
        if (level != null && level.isClientSide && oldTilt != tilt) requestModelDataUpdate();
        dirty = true;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        write(tag, true);
        tag.putIntArray("Held", grabs.keySet().stream().mapToInt(Integer::intValue).toArray());
        return tag;
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider registries) {
        loadAdditional(pkt.getTag(), registries);
    }
}
