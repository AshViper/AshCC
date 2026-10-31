package com.ashcc.link;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

/**
 * A peripheral position registered with a link card: block, the face it was clicked on (the capability side)
 * and the peripheral type seen at that time (used to name the link). On a Sable ship the position is the block's
 * plot position, so links keep working while the ship moves.
 */
public record LinkTarget(GlobalPos pos, Direction side, String type) {
    public static final Codec<LinkTarget> CODEC = RecordCodecBuilder.create(i -> i.group(
        GlobalPos.CODEC.fieldOf("pos").forGetter(LinkTarget::pos),
        Direction.CODEC.fieldOf("side").forGetter(LinkTarget::side),
        Codec.STRING.fieldOf("type").forGetter(LinkTarget::type)
    ).apply(i, LinkTarget::new));

    public static final StreamCodec<ByteBuf, LinkTarget> STREAM_CODEC = StreamCodec.composite(
        GlobalPos.STREAM_CODEC, LinkTarget::pos,
        Direction.STREAM_CODEC, LinkTarget::side,
        ByteBufCodecs.STRING_UTF8, LinkTarget::type,
        LinkTarget::new);

    public static final Codec<List<LinkTarget>> LIST_CODEC = CODEC.listOf();
    public static final StreamCodec<ByteBuf, List<LinkTarget>> LIST_STREAM_CODEC = STREAM_CODEC.apply(ByteBufCodecs.list());

    /** Same block (the side may differ). */
    public boolean sameBlock(LinkTarget other) {
        return pos.equals(other.pos);
    }
}
