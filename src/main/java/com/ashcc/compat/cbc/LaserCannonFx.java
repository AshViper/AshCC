package com.ashcc.compat.cbc;

import com.ashcc.AshCC;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

/**
 * Laser cannon beams for clients. The server sends the beam's on/off state and length (blocks from the muzzle) when
 * they change and once a second while firing; the client draws it from the contraption entity's current pose
 * ({@link LaserCannonFxClient}), so it turns smoothly with the mount.
 */
public final class LaserCannonFx {
    static final double RANGE = 256;

    private LaserCannonFx() {
    }

    public record Beam(int entity, boolean on, float length) implements CustomPacketPayload {
        public static final Type<Beam> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AshCC.MOD_ID, "laser_cannon_beam"));
        public static final StreamCodec<ByteBuf, Beam> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Beam::entity, ByteBufCodecs.BOOL, Beam::on, ByteBufCodecs.FLOAT, Beam::length, Beam::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    static void register(IEventBus modBus) {
        modBus.addListener((RegisterPayloadHandlersEvent e) -> e.registrar("1").optional()
            .playToClient(Beam.TYPE, Beam.CODEC, LaserCannonFx::onBeam));
        if (FMLEnvironment.dist.isClient()) LaserCannonFxClient.register();
    }

    private static void onBeam(Beam beam, IPayloadContext context) {
        context.enqueueWork(() -> LaserCannonFxClient.onBeam(beam));
    }

    static void send(ServerLevel level, PitchOrientedContraptionEntity entity, boolean on, double length) {
        Beam beam = new Beam(entity.getId(), on, (float) length);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(entity) < RANGE * RANGE) player.connection.send(beam);
        }
    }
}
