package com.ashcc.hose;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/** The start a hose item remembers between the first and the second click. */
public record HoseStart(BlockPos pos, Direction face, ResourceLocation dimension) {
    public static final Codec<HoseStart> CODEC = RecordCodecBuilder.create(i -> i.group(
        BlockPos.CODEC.fieldOf("pos").forGetter(HoseStart::pos),
        Direction.CODEC.fieldOf("face").forGetter(HoseStart::face),
        ResourceLocation.CODEC.fieldOf("dimension").forGetter(HoseStart::dimension)
    ).apply(i, HoseStart::new));
    public static final StreamCodec<ByteBuf, HoseStart> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, HoseStart::pos, Direction.STREAM_CODEC, HoseStart::face, ResourceLocation.STREAM_CODEC, HoseStart::dimension,
        HoseStart::new);
}
