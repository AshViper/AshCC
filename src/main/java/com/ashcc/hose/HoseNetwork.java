package com.ashcc.hose;

import com.ashcc.AshCC;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/** Ticks the hoses and keeps every client's copy of its dimension's hoses (only ends, so the list is small). */
public final class HoseNetwork {
    private HoseNetwork() {
    }

    public record Sync(List<Hose> hoses) implements CustomPacketPayload {
        public static final Type<Sync> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AshCC.MOD_ID, "hoses"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> CODEC =
            Hose.STREAM_CODEC.apply(ByteBufCodecs.list()).map(Sync::new, Sync::hoses);

        @Override
        public Type<Sync> type() {
            return TYPE;
        }
    }

    public static void register(IEventBus modBus) {
        modBus.addListener((RegisterPayloadHandlersEvent e) -> e.registrar("1").playToClient(Sync.TYPE, Sync.CODEC, HoseNetwork::onSync));
        NeoForge.EVENT_BUS.addListener((LevelTickEvent.Post e) -> {
            if (e.getLevel() instanceof ServerLevel level) HoseData.get(level).tick(level);
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> sendTo(e.getEntity()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> sendTo(e.getEntity()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent e) -> sendTo(e.getEntity()));
    }

    static void sendTo(ServerLevel level) {
        PacketDistributor.sendToPlayersInDimension(level, new Sync(List.copyOf(HoseData.get(level).hoses())));
    }

    private static void sendTo(net.minecraft.world.entity.player.Player player) {
        if (player instanceof ServerPlayer sp) {
            PacketDistributor.sendToPlayer(sp, new Sync(List.copyOf(HoseData.get(sp.serverLevel()).hoses())));
        }
    }

    private static void onSync(Sync sync, IPayloadContext ctx) {
        // Only resolved on the client, so the client class never loads on a dedicated server
        if (FMLEnvironment.dist.isClient()) com.ashcc.hose.client.HoseRenderer.set(sync.hoses());
    }
}
