package com.ashcc.monitor.client;

import com.ashcc.AshCC;
import com.ashcc.monitor.Canvas;
import com.ashcc.monitor.HdMonitorBlock;
import com.ashcc.monitor.HdMonitorBlockEntity;
import com.ashcc.monitor.MonitorGeometry;
import com.ashcc.monitor.MonitorNetwork;
import com.mojang.blaze3d.platform.NativeImage;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Client side of the HD monitor: canvas copies per monitor, their textures, and pointer input. Client only. */
public final class MonitorClient {
    private static final Map<BlockPos, Screen> SCREENS = new HashMap<>();
    private static final Map<BlockPos, Long> REQUESTED = new HashMap<>();
    private static @Nullable ClientLevel cacheLevel;
    private static int textureCounter;

    // Pointer state: which monitor is being pressed, with which button, last pixel
    private static @Nullable BlockPos active;
    private static int activeButton, lastX, lastY;
    private static boolean wasDown;

    private MonitorClient() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener((EntityRenderersEvent.RegisterRenderers e) -> e.registerBlockEntityRenderer(AshCC.HD_MONITOR_BE.get(), HdMonitorRenderer::new));
        NeoForge.EVENT_BUS.addListener(MonitorClient::onTick);
        NeoForge.EVENT_BUS.addListener(MonitorClient::onScroll);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> clear());
    }

    /** Client copy of one monitor. */
    public static final class Screen {
        final int id;
        public final Canvas canvas;
        final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        @Nullable DynamicTexture texture;
        @Nullable ResourceLocation location;
        long lastUsed = System.currentTimeMillis();

        Screen(int id, int w, int h) {
            this.id = id;
            this.canvas = new Canvas(w, h);
        }

        /** Copies changed pixels into the texture (render thread). */
        public @Nullable ResourceLocation texture() {
            lastUsed = System.currentTimeMillis();
            if (texture == null) {
                texture = new DynamicTexture(canvas.width, canvas.height, false);
                location = ResourceLocation.fromNamespaceAndPath(AshCC.MOD_ID, "monitor/" + textureCounter++);
                Minecraft.getInstance().getTextureManager().register(location, texture);
            }
            int[] d = canvas.takeDirty();
            NativeImage image = texture.getPixels();
            if (d != null && image != null) {
                int w = canvas.width;
                int[] px = canvas.pixels;
                for (int y = d[1]; y < d[3]; y++) {
                    for (int x = d[0]; x < d[2]; x++) {
                        int c = px[y * w + x];
                        // ARGB -> NativeImage's ABGR
                        image.setPixelRGBA(x, y, (c & 0xFF00FF00) | ((c >> 16) & 0xFF) | ((c & 0xFF) << 16));
                    }
                }
                texture.bind();
                image.upload(0, d[0], d[1], d[0], d[1], d[2] - d[0], d[3] - d[1], false, false);
            }
            return location;
        }

        void close() {
            if (location != null) Minecraft.getInstance().getTextureManager().release(location);
            texture = null;
            location = null;
        }
    }

    private static void checkLevel() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != cacheLevel) {
            clear();
            cacheLevel = level;
        }
    }

    private static void clear() {
        SCREENS.values().forEach(Screen::close);
        SCREENS.clear();
        REQUESTED.clear();
        active = null;
    }

    public static void onData(MonitorNetwork.Data data) {
        checkLevel();
        Screen s = SCREENS.get(data.pos());
        if (data.reset()) {
            if (s != null) s.close();
            s = new Screen(data.screen(), data.width(), data.height());
            SCREENS.put(data.pos(), s);
        } else if (s == null || s.id != data.screen()) {
            request(data.pos());
            return;
        }
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data.data());
            byte[] buf = new byte[64 * 1024];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                s.pending.write(buf, 0, n);
            }
        } catch (DataFormatException e) {
            s.pending.reset();
            request(data.pos());
            return;
        } finally {
            inflater.end();
        }
        if (data.last()) {
            s.canvas.apply(Unpooled.wrappedBuffer(s.pending.toByteArray()));
            s.pending.reset();
        }
    }

    /** The screen for a monitor origin, asking the server for a copy if there is none. */
    public static @Nullable Screen screen(BlockPos origin) {
        checkLevel();
        Screen s = SCREENS.get(origin);
        if (s == null) request(origin);
        return s;
    }

    private static void request(BlockPos pos) {
        long now = System.currentTimeMillis();
        Long last = REQUESTED.get(pos);
        if (last != null && now - last < 2000) return;
        REQUESTED.put(pos.immutable(), now);
        PacketDistributor.sendToServer(new MonitorNetwork.Request(pos));
    }

    // ---- Pointer input ----

    private record Target(BlockPos origin, int x, int y) {
    }

    private static @Nullable Target target() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;
        BlockState state = mc.level.getBlockState(hit.getBlockPos());
        if (!state.is(AshCC.HD_MONITOR.get()) || hit.getDirection() != state.getValue(HdMonitorBlock.FACING)) return null;
        if (!(mc.level.getBlockEntity(hit.getBlockPos()) instanceof HdMonitorBlockEntity be)) return null;
        BlockPos origin = be.originPos();
        if (!(mc.level.getBlockEntity(origin) instanceof HdMonitorBlockEntity o) || !o.isOrigin()) return null;
        MonitorGeometry g = o.geometry();
        Vec3 local = hit.getLocation().subtract(Vec3.atLowerCornerOf(origin));
        double[] p = g.toPixel(local, o.width(), o.height(), state.getValue(HdMonitorBlock.HOLOGRAM));
        int w = o.width() * MonitorGeometry.PIXELS_PER_BLOCK, h = o.height() * MonitorGeometry.PIXELS_PER_BLOCK;
        int x = (int) Math.floor(p[0]), y = (int) Math.floor(p[1]);
        if (x < 0 || y < 0 || x >= w || y >= h) return null;
        return new Target(origin, x, y);
    }

    private static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            active = null;
            wasDown = false;
            return;
        }
        boolean down = mc.options.keyUse.isDown() && mc.screen == null && !mc.player.isSpectator();
        Target t = down || active != null ? target() : null;
        if (active == null) {
            if (down && !wasDown && t != null) {
                active = t.origin;
                activeButton = mc.player.isShiftKeyDown() ? 2 : 1;
                lastX = t.x;
                lastY = t.y;
                send(MonitorNetwork.Touch.PRESS, activeButton, t.x, t.y);
            }
        } else if (!down) {
            send(MonitorNetwork.Touch.RELEASE, activeButton, lastX, lastY);
            active = null;
        } else if (t != null && t.origin.equals(active) && (t.x != lastX || t.y != lastY)) {
            lastX = t.x;
            lastY = t.y;
            send(MonitorNetwork.Touch.DRAG, activeButton, t.x, t.y);
        }
        wasDown = down;

        // Drop copies nobody has looked at for a while
        if (mc.level.getGameTime() % 200 == 0) {
            long now = System.currentTimeMillis();
            for (Iterator<Map.Entry<BlockPos, Screen>> it = SCREENS.entrySet().iterator(); it.hasNext(); ) {
                Screen s = it.next().getValue();
                if (now - s.lastUsed > 30_000) {
                    s.close();
                    it.remove();
                }
            }
        }
    }

    /** Scrolling while holding a touch goes to the monitor instead of the hotbar. */
    private static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (active == null || event.getScrollDeltaY() == 0) return;
        Target t = target();
        if (t == null || !t.origin.equals(active)) return;
        send(MonitorNetwork.Touch.SCROLL, event.getScrollDeltaY() > 0 ? -1 : 1, t.x, t.y);
        event.setCanceled(true);
    }

    private static void send(int action, int button, int x, int y) {
        if (active != null) PacketDistributor.sendToServer(new MonitorNetwork.Touch(active, action, button, x, y));
    }
}
