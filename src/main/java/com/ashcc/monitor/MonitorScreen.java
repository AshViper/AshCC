package com.ashcc.monitor;

import com.ashcc.AshCC;
import com.ashcc.AshCCConfig;
import com.ashcc.compat.SableCompat;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.peripheral.AttachedComputerSet;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.Deflater;

/**
 * Server state of one HD monitor (owned by its origin block). Lua calls (on computer threads) encode draw operations,
 * apply them to the canvas and queue them; every tick the queue goes to the players who already hold a copy, and new
 * viewers get a snapshot. With auto-present off, operations wait in a frame buffer until {@link #present()}.
 */
public final class MonitorScreen {
    /** Raw bytes per network packet before compression (vanilla's limit is 1 MiB per payload). */
    static final int PART_BYTES = 512 * 1024;

    final MonitorHost origin;
    final BlockPos pos;
    final int id = ThreadLocalRandom.current().nextInt();
    public final int blocksWide, blocksHigh;
    public final int pixelWidth, pixelHeight;
    public final AttachedComputerSet computers = new AttachedComputerSet();
    final MonitorTerm term;

    private final Object lock = new Object();
    private final Canvas canvas;
    private final ByteBuf outgoing = Unpooled.buffer();
    private final ByteBuf frame = Unpooled.buffer();
    private boolean autoPresent = true;
    private boolean resyncAll;
    private final Set<UUID> viewers = new HashSet<>();
    private volatile boolean disposed;

    MonitorScreen(HdMonitorBlockEntity origin) {
        this(origin, origin.getBlockPos(), origin.width(), origin.height(),
            origin.width() * MonitorGeometry.PIXELS_PER_BLOCK, origin.height() * MonitorGeometry.PIXELS_PER_BLOCK);
    }

    /** A screen of any pixel size owned by {@code host} at {@code pos} (the client keeps one copy per block position). */
    public MonitorScreen(MonitorHost host, BlockPos pos, int blocksWide, int blocksHigh, int pixelWidth, int pixelHeight) {
        this.origin = host;
        this.pos = pos;
        this.blocksWide = blocksWide;
        this.blocksHigh = blocksHigh;
        this.pixelWidth = pixelWidth;
        this.pixelHeight = pixelHeight;
        this.canvas = new Canvas(pixelWidth, pixelHeight);
        this.term = new MonitorTerm(this);
    }

    public boolean disposed() {
        return disposed;
    }

    public void dispose() {
        disposed = true;
    }

    void checkValid() throws LuaException {
        if (disposed) throw new LuaException("This monitor has been changed or unloaded");
    }

    // ---- Drawing ----

    /** Upper bound of queued bytes before falling back to a full snapshot. */
    private int outgoingLimit() {
        return Math.max(4 << 20, pixelWidth * pixelHeight * 4);
    }

    /** Applies (or, without auto-present, buffers) one or more encoded operations. */
    void submit(ByteBuf ops) throws LuaException {
        checkValid();
        synchronized (lock) {
            if (autoPresent) {
                canvas.apply(ops.duplicate());
                queue(ops);
            } else {
                if (frame.readableBytes() + ops.readableBytes() > AshCCConfig.MONITOR_MAX_FRAME_BYTES.get()) {
                    throw new LuaException("Frame too large, call present() more often");
                }
                frame.writeBytes(ops);
            }
        }
    }

    private void queue(ByteBuf ops) {
        if (resyncAll) return;
        if (outgoing.readableBytes() + ops.readableBytes() > outgoingLimit()) {
            // Too much to replay: everyone gets a fresh snapshot instead
            outgoing.clear();
            resyncAll = true;
            return;
        }
        outgoing.writeBytes(ops, ops.readerIndex(), ops.readableBytes());
    }

    /** Applies drawing operations made by the mod itself (built-in displays); these always present right away. */
    public void drawBuiltIn(ByteBuf ops) {
        if (disposed) return;
        synchronized (lock) {
            canvas.apply(ops.duplicate());
            queue(ops);
        }
    }

    void present() throws LuaException {
        checkValid();
        synchronized (lock) {
            if (!frame.isReadable()) return;
            canvas.apply(frame.duplicate());
            queue(frame);
            frame.clear();
        }
    }

    void setAutoPresent(boolean on) throws LuaException {
        checkValid();
        synchronized (lock) {
            autoPresent = on;
        }
        if (on) present();
    }

    boolean autoPresent() {
        synchronized (lock) {
            return autoPresent;
        }
    }

    int getPixel(int x, int y) {
        synchronized (lock) {
            if (x < 0 || y < 0 || x >= pixelWidth || y >= pixelHeight) return 0;
            return canvas.pixels[y * pixelWidth + x];
        }
    }

    boolean blend() {
        synchronized (lock) {
            return canvas.blend();
        }
    }

    boolean antialias() {
        synchronized (lock) {
            return canvas.antialias();
        }
    }

    // ---- Events ----

    public void queueEvent(String event, Object... args) {
        computers.forEach(c -> {
            Object[] full = new Object[args.length + 1];
            full[0] = c.getAttachmentName();
            System.arraycopy(args, 0, full, 1, args.length);
            c.queueEvent(event, full);
        });
    }

    // ---- Sync (server main thread) ----

    Vec3 worldCenter() {
        if (AshCC.sableLoaded && origin.getLevel() != null) return SableCompat.worldCenter(origin.getLevel(), pos);
        return Vec3.atCenterOf(pos);
    }

    public void tick() {
        if (disposed || !(origin.getLevel() instanceof ServerLevel level)) return;
        double range = AshCCConfig.MONITOR_SYNC_RANGE.get();
        Vec3 center = worldCenter();
        List<ServerPlayer> near = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (p.position().distanceToSqr(center) <= range * range) near.add(p);
        }
        Set<UUID> nearIds = new HashSet<>();
        for (ServerPlayer p : near) nearIds.add(p.getUUID());

        byte[] update;
        List<ServerPlayer> fresh = new ArrayList<>();
        List<byte[]> snapshot = null;
        synchronized (lock) {
            viewers.retainAll(nearIds);
            boolean resync = resyncAll;
            resyncAll = false;
            update = resync || !outgoing.isReadable() ? null : bytes(outgoing);
            outgoing.clear();
            for (ServerPlayer p : near) {
                if (resync || !viewers.contains(p.getUUID())) fresh.add(p);
            }
            if (!fresh.isEmpty()) {
                List<byte[]> parts = new ArrayList<>();
                canvas.snapshot(b -> parts.add(bytes(b)), PART_BYTES);
                snapshot = parts;
            }
            for (ServerPlayer p : fresh) viewers.add(p.getUUID());
        }

        if (snapshot != null) {
            List<MonitorNetwork.Data> packets = packets(snapshot, true);
            for (ServerPlayer p : fresh) for (MonitorNetwork.Data d : packets) PacketDistributor.sendToPlayer(p, d);
        }
        if (update != null) {
            List<MonitorNetwork.Data> packets = packets(List.of(update), false);
            for (ServerPlayer p : near) {
                if (fresh.contains(p)) continue;
                for (MonitorNetwork.Data d : packets) PacketDistributor.sendToPlayer(p, d);
            }
        }
    }

    /** The player lost their copy (client asked again): send a snapshot next tick. */
    void forget(UUID player) {
        synchronized (lock) {
            viewers.remove(player);
        }
    }

    private static byte[] bytes(ByteBuf b) {
        byte[] out = new byte[b.readableBytes()];
        b.getBytes(b.readerIndex(), out);
        return out;
    }

    /** Splits the stream into parts of at most PART_BYTES and compresses each. */
    private List<MonitorNetwork.Data> packets(List<byte[]> chunks, boolean reset) {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] c : chunks) all.writeBytes(c);
        byte[] raw = all.toByteArray();
        List<MonitorNetwork.Data> out = new ArrayList<>();
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            for (int off = 0; off < raw.length || out.isEmpty(); off += PART_BYTES) {
                int len = Math.min(PART_BYTES, raw.length - off);
                deflater.reset();
                deflater.setInput(raw, off, len);
                deflater.finish();
                ByteArrayOutputStream z = new ByteArrayOutputStream(Math.max(64, len / 4));
                byte[] buf = new byte[64 * 1024];
                while (!deflater.finished()) z.write(buf, 0, deflater.deflate(buf));
                boolean last = off + PART_BYTES >= raw.length;
                out.add(new MonitorNetwork.Data(pos, id, reset && off == 0, pixelWidth, pixelHeight, last, z.toByteArray()));
            }
        } finally {
            deflater.end();
        }
        return out;
    }
}
