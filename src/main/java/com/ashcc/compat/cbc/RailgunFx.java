package com.ashcc.compat.cbc;

import com.ashcc.AshCC;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import rbasamoyai.createbigcannons.effects.particles.explosions.CannonBlastWaveEffectParticleData;
import rbasamoyai.createbigcannons.index.CBCSoundEvents;

/**
 * Railgun shot effects. The server sends one {@link Shot} per shot to nearby players; the client draws the muzzle
 * flash and shock rings, follows the projectile entity to leave a glowing trail with Mach rings, makes a sonic crack
 * when the shell passes the player and kicks up dust under low passes ({@link RailgunFxClient}).
 * The muzzle blast itself is CBC's blast wave (sound and screen shake that travel at the speed of sound).
 */
public final class RailgunFx {
    /** Players this close to the muzzle get the shot's effects. */
    static final double RANGE = 512;

    private RailgunFx() {
    }

    /**
     * @param entity id of the projectile entity (its flight is followed client side; may be gone if it hit at once)
     * @param mount  id of the cannon's contraption entity: the muzzle effects stay on the barrel when it moves (a ship)
     * @param speed  muzzle speed in blocks/tick
     */
    public record Shot(int entity, int mount, Vec3 muzzle, Vec3 direction, float speed, int rails) implements CustomPacketPayload {
        public static final Type<Shot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AshCC.MOD_ID, "railgun_shot"));
        private static final StreamCodec<ByteBuf, Vec3> VEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);
        public static final StreamCodec<ByteBuf, Shot> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Shot::entity, ByteBufCodecs.VAR_INT, Shot::mount, VEC, Shot::muzzle, VEC, Shot::direction,
            ByteBufCodecs.FLOAT, Shot::speed, ByteBufCodecs.VAR_INT, Shot::rails, Shot::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    static void register(IEventBus modBus) {
        modBus.addListener((RegisterPayloadHandlersEvent e) -> e.registrar("1").optional()
            .playToClient(Shot.TYPE, Shot.CODEC, RailgunFx::onShot));
        if (FMLEnvironment.dist.isClient()) RailgunFxClient.register();
    }

    private static void onShot(Shot shot, IPayloadContext context) {
        context.enqueueWork(() -> RailgunFxClient.onShot(shot));
    }

    /** Sends the shot to players in range and the muzzle blast wave (delayed boom and shake) as CBC's big cannons do. */
    static void send(ServerLevel level, int entity, int mount, Vec3 muzzle, Vec3 dir, double speed, int rails) {
        Shot shot = new Shot(entity, mount, muzzle, dir, (float) speed, rails);
        float power = (float) Math.min(1.0, speed / 40.0);
        float volume = 14.0f + 20.0f * power;
        var blast = new CannonBlastWaveEffectParticleData(4.0 + speed * 0.75,
            BuiltInRegistries.SOUND_EVENT.wrapAsHolder(CBCSoundEvents.FIRE_BIG_CANNON.getMainEvent()), SoundSource.BLOCKS,
            volume, 1.5f + 0.4f * power, 2.0f, (float) speed * 0.6f);
        var blastPacket = new ClientboundLevelParticlesPacket(blast, true, muzzle.x, muzzle.y, muzzle.z, 0, 0, 0, 1, 0);
        double blastRange = volume * 16.0 * 1.1;
        for (ServerPlayer player : level.players()) {
            double d2 = player.distanceToSqr(muzzle);
            if (d2 < RANGE * RANGE) player.connection.send(shot);
            if (d2 < blastRange * blastRange) player.connection.send(blastPacket);
        }
    }
}
