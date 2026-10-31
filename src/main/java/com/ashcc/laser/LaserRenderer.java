package com.ashcc.laser;

import com.ashcc.AshCC;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.joml.Matrix4f;

/**
 * Draws the beam in block coordinates. On a Sable sub-level the block entity is rendered with the ship's pose,
 * so a beam along the block-space aim lines up with the server's world-space trace. Client only.
 */
public class LaserRenderer implements BlockEntityRenderer<LaserBlockEntity> {
    public LaserRenderer(BlockEntityRendererProvider.Context context) {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener((EntityRenderersEvent.RegisterRenderers e) -> e.registerBlockEntityRenderer(AshCC.LASER_BE.get(), LaserRenderer::new));
    }

    @Override
    public void render(LaserBlockEntity be, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
        if (!be.firing() || be.beamLength() <= 0 || be.getLevel() == null) return;
        Vec3 d = LaserBlockEntity.blockDirection(be.yaw(), be.pitch());
        Vec3 a = (Math.abs(d.y) < 0.9 ? d.cross(new Vec3(0, 1, 0)) : d.cross(new Vec3(1, 0, 0))).normalize();
        Vec3 b = d.cross(a).normalize();
        double start = LaserBlockEntity.muzzleOffset(d) - 0.02;
        double end = be.beamLength();
        float t = be.getLevel().getGameTime() + partialTick;
        float pulse = 1 + 0.15f * (float) Math.sin(t * 0.9);

        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);
        Matrix4f m = poseStack.last().pose();
        VertexConsumer vc = buffers.getBuffer(RenderType.lightning());
        beam(vc, m, d, a, b, start, end, 0.11 * pulse, 1.0f, 0.12f, 0.08f, 0.45f);
        beam(vc, m, d, a, b, start, end, 0.04, 1.0f, 0.85f, 0.8f, 0.9f);
        poseStack.popPose();
    }

    /** Two crossed quads along the beam, each with both windings so they show from every side. */
    private static void beam(VertexConsumer vc, Matrix4f m, Vec3 d, Vec3 a, Vec3 b, double start, double end, double r,
                             float red, float green, float blue, float alpha) {
        Vec3 s = d.scale(start);
        Vec3 e = d.scale(end);
        for (Vec3 side : new Vec3[]{a.scale(r), b.scale(r)}) {
            quad(vc, m, s.subtract(side), s.add(side), e.add(side), e.subtract(side), red, green, blue, alpha);
            quad(vc, m, e.subtract(side), e.add(side), s.add(side), s.subtract(side), red, green, blue, alpha);
        }
    }

    private static void quad(VertexConsumer vc, Matrix4f m, Vec3 p1, Vec3 p2, Vec3 p3, Vec3 p4, float r, float g, float b, float a) {
        for (Vec3 p : new Vec3[]{p1, p2, p3, p4}) vc.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
    }

    @Override
    public boolean shouldRenderOffScreen(LaserBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 256;
    }

    @Override
    public AABB getRenderBoundingBox(LaserBlockEntity be) {
        return AABB.INFINITE;
    }
}
