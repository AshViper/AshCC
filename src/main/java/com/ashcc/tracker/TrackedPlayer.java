package com.ashcc.tracker;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

/** The player a head tracker is bound to. The name is only for display (it is refreshed when the player is online). */
public record TrackedPlayer(UUID id, String name) {
    public static final Codec<TrackedPlayer> CODEC = RecordCodecBuilder.create(i -> i.group(
        UUIDUtil.CODEC.fieldOf("id").forGetter(TrackedPlayer::id),
        Codec.STRING.fieldOf("name").forGetter(TrackedPlayer::name)
    ).apply(i, TrackedPlayer::new));

    public static final StreamCodec<ByteBuf, TrackedPlayer> STREAM_CODEC = StreamCodec.composite(
        UUIDUtil.STREAM_CODEC, TrackedPlayer::id,
        ByteBufCodecs.STRING_UTF8, TrackedPlayer::name,
        TrackedPlayer::new);
}
