package com.ashcc.monitor;

import com.ashcc.AshCC;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * One block of an HD monitor. Every block knows its place (x, y from the top-left) and the size of its monitor;
 * the top-left block (the origin) owns the {@link MonitorScreen} on the server and is the one the client renders.
 */
public class HdMonitorBlockEntity extends BlockEntity implements MonitorHost {
    private int xIndex, yIndex;
    private int width = 1, height = 1;
    /** Origin only: draw at full brightness instead of with the world light. */
    private boolean glow = true;

    private @Nullable MonitorScreen screen;

    public HdMonitorBlockEntity(BlockPos pos, BlockState state) {
        super(AshCC.HD_MONITOR_BE.get(), pos, state);
    }

    public int xIndex() {
        return xIndex;
    }

    public int yIndex() {
        return yIndex;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public boolean isOrigin() {
        return xIndex == 0 && yIndex == 0;
    }

    @Override
    public boolean glow() {
        return glow;
    }

    @Override
    public boolean hologram() {
        return getBlockState().getValue(HdMonitorBlock.HOLOGRAM);
    }

    public MonitorGeometry geometry() {
        return MonitorGeometry.of(getBlockState());
    }

    public BlockPos originPos() {
        return geometry().offset(worldPosition, -xIndex, -yIndex);
    }

    public @Nullable HdMonitorBlockEntity origin() {
        if (isOrigin()) return this;
        if (level == null) return null;
        BlockPos o = originPos();
        if (!level.isLoaded(o)) return null;
        return level.getBlockEntity(o) instanceof HdMonitorBlockEntity be && be.isOrigin() ? be : null;
    }

    /** The screen of this block's monitor (server), created on first use. Null if the origin is not loaded. */
    public @Nullable MonitorScreen screen() {
        HdMonitorBlockEntity o = origin();
        if (o == null || o.level == null || o.level.isClientSide || o.isRemoved()) return null;
        if (o.screen == null) o.screen = new MonitorScreen(o);
        return o.screen;
    }

    public @Nullable HdMonitorPeripheral peripheral() {
        MonitorScreen s = screen();
        return s == null ? null : new HdMonitorPeripheral(s);
    }

    /** Called by {@link MonitorShapes}: this block is at (x, y) of a w x h monitor whose top-left is origin. */
    void setPart(int x, int y, int w, int h, BlockPos origin) {
        if (level == null) return;
        boolean changed = x != xIndex || y != yIndex || w != width || h != height;
        if (changed) {
            disposeScreen();
            xIndex = x;
            yIndex = y;
            width = w;
            height = h;
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            level.invalidateCapabilities(worldPosition);
        }
        // Every block of a monitor shows the origin's hologram setting
        if (!isOrigin()) {
            BlockState o = level.getBlockState(origin);
            BlockState mine = getBlockState();
            if (o.is(mine.getBlock()) && o.getValue(HdMonitorBlock.HOLOGRAM) != mine.getValue(HdMonitorBlock.HOLOGRAM)) {
                level.setBlock(worldPosition, mine.setValue(HdMonitorBlock.HOLOGRAM, o.getValue(HdMonitorBlock.HOLOGRAM)), Block.UPDATE_ALL);
            }
        }
    }

    void disposeScreen() {
        if (screen != null) {
            screen.dispose();
            screen = null;
        }
    }

    /** Sets the hologram look on every block of the monitor (origin only, main thread). */
    @Override
    public void setHologram(boolean on) {
        if (level == null) return;
        MonitorGeometry g = geometry();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                BlockPos p = g.offset(worldPosition, x, y);
                BlockState s = level.getBlockState(p);
                if (s.is(getBlockState().getBlock()) && s.getValue(HdMonitorBlock.HOLOGRAM) != on) {
                    level.setBlock(p, s.setValue(HdMonitorBlock.HOLOGRAM, on), Block.UPDATE_ALL);
                }
            }
        }
    }

    @Override
    public void setGlow(boolean on) {
        if (glow == on || level == null) return;
        glow = on;
        setChanged();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, HdMonitorBlockEntity be) {
        if (be.screen != null) be.screen.tick();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // Blocks loaded before their origin returned no peripheral; let them look again
        if (level != null && !level.isClientSide && isOrigin() && (width > 1 || height > 1)) {
            MonitorGeometry g = geometry();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (x != 0 || y != 0) level.invalidateCapabilities(g.offset(worldPosition, x, y));
                }
            }
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        disposeScreen();
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        disposeScreen();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("X", xIndex);
        tag.putInt("Y", yIndex);
        tag.putInt("Width", width);
        tag.putInt("Height", height);
        tag.putBoolean("Glow", glow);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        xIndex = tag.getInt("X");
        yIndex = tag.getInt("Y");
        width = Math.max(1, tag.getInt("Width"));
        height = Math.max(1, tag.getInt("Height"));
        glow = !tag.contains("Glow") || tag.getBoolean("Glow");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Whole monitor, for the renderer's culling. */
    public AABB screenBounds() {
        BlockPos far = geometry().offset(worldPosition, width - 1, height - 1);
        return new AABB(worldPosition).minmax(new AABB(far));
    }
}
