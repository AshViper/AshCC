package com.ashcc.panel;

import com.ashcc.AshCC;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client to server: set up a cell or the panel, click a cell, keep a button held, and the keys of grabbed controls. */
public final class PanelNetwork {
    private PanelNetwork() {
    }

    /** kind "" removes the control at (col,row). mode is a display mode of {@link PanelControl} (screens only). */
    public record Configure(BlockPos pos, int col, int row, String kind, int width, int height, int channel, int color, int mode) implements CustomPacketPayload {
        public static final Type<Configure> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AshCC.MOD_ID, "panel_configure"));
        public static final StreamCodec<ByteBuf, Configure> CODEC = new StreamCodec<>() {
            @Override
            public Configure decode(ByteBuf buf) {
                return new Configure(BlockPos.STREAM_CODEC.decode(buf), buf.readByte(), buf.readByte(), ByteBufCodecs.STRING_UTF8.decode(buf),
                    buf.readByte(), buf.readByte(), buf.readByte(), buf.readByte(), buf.readByte());
            }

            @Override
            public void encode(ByteBuf buf, Configure c) {
                BlockPos.STREAM_CODEC.encode(buf, c.pos);
                buf.writeByte(c.col).writeByte(c.row);
                ByteBufCodecs.STRING_UTF8.encode(buf, c.kind);
                buf.writeByte(c.width).writeByte(c.height).writeByte(c.channel).writeByte(c.color).writeByte(c.mode);
            }
        };

        @Override
        public Type<Configure> type() {
            return TYPE;
        }
    }

    public record Hold(BlockPos pos, int index) implements CustomPacketPayload {
        public static final Type<Hold> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AshCC.MOD_ID, "panel_hold"));
        public static final StreamCodec<ByteBuf, Hold> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, Hold::pos, ByteBufCodecs.VAR_INT, Hold::index, Hold::new);

        @Override
        public Type<Hold> type() {
            return TYPE;
        }
    }

    /** Keys of a grabbed control: x, y -1..1, buttons (bits of {@link PanelControl}); release lets go of it. */
    public record Input(BlockPos pos, int index, float x, float y, int buttons, boolean release) implements CustomPacketPayload {
        public static final Type<Input> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AshCC.MOD_ID, "panel_input"));
        public static final StreamCodec<ByteBuf, Input> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, Input::pos, ByteBufCodecs.VAR_INT, Input::index, ByteBufCodecs.FLOAT, Input::x,
            ByteBufCodecs.FLOAT, Input::y, ByteBufCodecs.VAR_INT, Input::buttons, ByteBufCodecs.BOOL, Input::release, Input::new);

        @Override
        public Type<Input> type() {
            return TYPE;
        }
    }

    /** Right-click on a cell (found by the client along its line of sight). */
    public record Click(BlockPos pos, int col, int row) implements CustomPacketPayload {
        public static final Type<Click> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AshCC.MOD_ID, "panel_click"));
        public static final StreamCodec<ByteBuf, Click> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, Click::pos, ByteBufCodecs.VAR_INT, Click::col, ByteBufCodecs.VAR_INT, Click::row, Click::new);

        @Override
        public Type<Click> type() {
            return TYPE;
        }
    }

    /** Grid size (4 or 8) and tilt (0..60). */
    public record Layout(BlockPos pos, int grid, int tilt) implements CustomPacketPayload {
        public static final Type<Layout> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AshCC.MOD_ID, "panel_layout"));
        public static final StreamCodec<ByteBuf, Layout> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, Layout::pos, ByteBufCodecs.VAR_INT, Layout::grid, ByteBufCodecs.VAR_INT, Layout::tilt, Layout::new);

        @Override
        public Type<Layout> type() {
            return TYPE;
        }
    }

    public static void register(IEventBus modBus) {
        modBus.addListener((RegisterPayloadHandlersEvent e) -> e.registrar("1")
            .playToServer(Configure.TYPE, Configure.CODEC, PanelNetwork::onConfigure)
            .playToServer(Hold.TYPE, Hold.CODEC, PanelNetwork::onHold)
            .playToServer(Input.TYPE, Input.CODEC, PanelNetwork::onInput)
            .playToServer(Click.TYPE, Click.CODEC, PanelNetwork::onClick)
            .playToServer(Layout.TYPE, Layout.CODEC, PanelNetwork::onLayout));
    }

    private static ControlPanelBlockEntity panel(IPayloadContext ctx, BlockPos pos) {
        if (!(ctx.player() instanceof ServerPlayer player) || !player.level().isLoaded(pos)) return null;
        return player.level().getBlockEntity(pos) instanceof ControlPanelBlockEntity be && be.inReach(player) ? be : null;
    }

    private static void onConfigure(Configure c, IPayloadContext ctx) {
        ControlPanelBlockEntity be = panel(ctx, c.pos());
        if (be == null || c.col() < 0 || c.col() >= be.grid() || c.row() < 0 || c.row() >= be.grid()) return;
        PanelControl.Type type = c.kind().isEmpty() ? null : PanelControl.Type.byId(c.kind());
        be.configure(c.col(), c.row(), type, c.width(), c.height(), c.channel(), DyeColor.byId(c.color()), c.mode());
    }

    private static void onHold(Hold h, IPayloadContext ctx) {
        ControlPanelBlockEntity be = panel(ctx, h.pos());
        if (be != null) be.hold(h.index());
    }

    private static void onInput(Input in, IPayloadContext ctx) {
        ControlPanelBlockEntity be = panel(ctx, in.pos());
        if (be != null) be.input(ctx.player(), in.index(), in.x(), in.y(), in.buttons(), in.release());
    }

    private static void onClick(Click c, IPayloadContext ctx) {
        ControlPanelBlockEntity be = panel(ctx, c.pos());
        if (be != null && c.col() >= 0 && c.col() < be.grid() && c.row() >= 0 && c.row() < be.grid()) be.click(ctx.player(), c.col(), c.row());
    }

    private static void onLayout(Layout l, IPayloadContext ctx) {
        ControlPanelBlockEntity be = panel(ctx, l.pos());
        if (be != null) be.setLayout(l.grid(), l.tilt());
    }
}
