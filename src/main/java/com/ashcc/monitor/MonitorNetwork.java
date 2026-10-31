package com.ashcc.monitor;

import com.ashcc.AshCC;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * HD monitor packets. Data: a part of a compressed operation stream (server to client; the client applies the stream
 * when the last part arrives). Request: the client has no copy and wants a snapshot. Touch: pointer input in pixels.
 */
public final class MonitorNetwork {
    /** How far from the monitor a touching player may be (blocks). */
    private static final double TOUCH_REACH = 12;

    private MonitorNetwork() {
    }

    public record Data(BlockPos pos, int screen, boolean reset, int width, int height, boolean last, byte[] data) implements CustomPacketPayload {
        public static final Type<Data> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AshCC.MOD_ID, "monitor_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Data> CODEC = StreamCodec.of((buf, d) -> {
            buf.writeBlockPos(d.pos);
            buf.writeInt(d.screen);
            buf.writeBoolean(d.reset);
            buf.writeVarInt(d.width);
            buf.writeVarInt(d.height);
            buf.writeBoolean(d.last);
            buf.writeByteArray(d.data);
        }, buf -> new Data(buf.readBlockPos(), buf.readInt(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readByteArray()));

        @Override
        public Type<Data> type() {
            return TYPE;
        }
    }

    public record Request(BlockPos pos) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AshCC.MOD_ID, "monitor_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, Request::pos, Request::new);

        @Override
        public Type<Request> type() {
            return TYPE;
        }
    }

    /** action: 0 press, 1 drag, 2 release, 3 scroll (button = direction -1 / 1). */
    public record Touch(BlockPos pos, int action, int button, int x, int y) implements CustomPacketPayload {
        public static final int PRESS = 0, DRAG = 1, RELEASE = 2, SCROLL = 3;
        public static final Type<Touch> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AshCC.MOD_ID, "monitor_touch"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Touch> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, Touch::pos, ByteBufCodecs.VAR_INT, Touch::action, ByteBufCodecs.VAR_INT, Touch::button,
            ByteBufCodecs.VAR_INT, Touch::x, ByteBufCodecs.VAR_INT, Touch::y, Touch::new);

        @Override
        public Type<Touch> type() {
            return TYPE;
        }
    }

    public static void register(IEventBus modBus) {
        modBus.addListener((RegisterPayloadHandlersEvent e) -> e.registrar("1")
            .playToClient(Data.TYPE, Data.CODEC, MonitorNetwork::onData)
            .playToServer(Request.TYPE, Request.CODEC, MonitorNetwork::onRequest)
            .playToServer(Touch.TYPE, Touch.CODEC, MonitorNetwork::onTouch));
    }

    private static void onData(Data data, IPayloadContext ctx) {
        // Only resolved on the client, so the client class never loads on a dedicated server
        if (FMLEnvironment.dist.isClient()) com.ashcc.monitor.client.MonitorClient.onData(data);
    }

    private static MonitorScreen screenFor(IPayloadContext ctx, BlockPos pos) {
        if (!(ctx.player() instanceof ServerPlayer player)) return null;
        if (!player.level().isLoaded(pos)) return null;
        var be = player.level().getBlockEntity(pos);
        MonitorScreen screen = be instanceof HdMonitorBlockEntity m ? m.screen() : be instanceof ScreenOwner o ? o.screen() : null;
        if (screen == null) return null;
        return screen;
    }

    private static void onRequest(Request req, IPayloadContext ctx) {
        MonitorScreen screen = screenFor(ctx, req.pos);
        if (screen != null) screen.forget(ctx.player().getUUID());
    }

    private static void onTouch(Touch t, IPayloadContext ctx) {
        MonitorScreen screen = screenFor(ctx, t.pos);
        if (screen == null || screen.disposed()) return;
        ServerPlayer player = (ServerPlayer) ctx.player();
        if (player.isSpectator() || player.getEyePosition().distanceToSqr(screen.worldCenter()) > sq(TOUCH_REACH + Math.max(screen.blocksWide, screen.blocksHigh))) return;
        int x = Math.max(0, Math.min(screen.pixelWidth - 1, t.x));
        int y = Math.max(0, Math.min(screen.pixelHeight - 1, t.y));
        String name = player.getGameProfile().getName();
        switch (t.action) {
            case Touch.PRESS -> {
                int[] cell = screen.term.cellAt(x, y);
                screen.queueEvent("monitor_touch", cell[0], cell[1]);
                screen.queueEvent("hd_touch", x, y, button(t.button), name);
            }
            case Touch.DRAG -> screen.queueEvent("hd_drag", x, y, button(t.button), name);
            case Touch.RELEASE -> screen.queueEvent("hd_release", x, y, button(t.button), name);
            case Touch.SCROLL -> screen.queueEvent("hd_scroll", x, y, t.button < 0 ? -1 : 1, name);
            default -> {
            }
        }
    }

    private static int button(int b) {
        return b == 2 ? 2 : 1;
    }

    private static double sq(double v) {
        return v * v;
    }
}
