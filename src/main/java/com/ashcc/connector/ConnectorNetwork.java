package com.ashcc.connector;

import com.ashcc.AshCC;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** The wrench screen sends the new tilt to the server. */
public final class ConnectorNetwork {
    private static final double REACH = 12;

    private ConnectorNetwork() {
    }

    public record SetAngles(int entity, float yaw, float pitch, float roll) implements CustomPacketPayload {
        public static final Type<SetAngles> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AshCC.MOD_ID, "connector_angles"));
        public static final StreamCodec<ByteBuf, SetAngles> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetAngles::entity, ByteBufCodecs.FLOAT, SetAngles::yaw,
            ByteBufCodecs.FLOAT, SetAngles::pitch, ByteBufCodecs.FLOAT, SetAngles::roll, SetAngles::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(IEventBus modBus) {
        modBus.addListener((RegisterPayloadHandlersEvent e) -> e.registrar("1").playToServer(SetAngles.TYPE, SetAngles.CODEC, ConnectorNetwork::onSetAngles));
    }

    public static void send(int entity, float yaw, float pitch, float roll) {
        PacketDistributor.sendToServer(new SetAngles(entity, yaw, pitch, roll));
    }

    private static void onSetAngles(SetAngles msg, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player) || player.isSpectator() || !AshCC.sableLoaded) return;
        if (!(player.level().getEntity(msg.entity) instanceof ConnectorEntity plate)) return;
        if (player.getEyePosition().distanceTo(plate.position()) > REACH) return;
        plate.setAngles(msg.yaw, msg.pitch, msg.roll);
    }
}
