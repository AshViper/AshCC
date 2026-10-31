package com.ashcc.monitor.client;

import com.ashcc.monitor.Canvas;
import com.ashcc.monitor.HdMonitorBlock;
import com.ashcc.monitor.HdMonitorBlockEntity;
import com.ashcc.monitor.MonitorGeometry;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Draws a whole HD monitor from its origin block as one textured quad over the front faces. On a Sable sub-level the
 * block entity is rendered with the ship's pose, so block coordinates are enough. Client only.
 */
public class HdMonitorRenderer implements BlockEntityRenderer<HdMonitorBlockEntity> {
    public HdMonitorRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(HdMonitorBlockEntity be, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
        if (!be.isOrigin() || be.getLevel() == null) return;
        MonitorClient.Screen screen = MonitorClient.screen(be.getBlockPos());
        if (screen == null) return;
        ResourceLocation tex = screen.texture();
        if (tex == null) return;

        MonitorGeometry g = be.geometry();
        boolean hologram = be.getBlockState().getValue(HdMonitorBlock.HOLOGRAM);
        int w = be.width(), h = be.height();
        double m = hologram ? 0 : MonitorGeometry.BEZEL;
        Vec3 r = MonitorGeometry.vec(g.right()), u = MonitorGeometry.vec(g.up()), f = MonitorGeometry.vec(g.facing());
        Vec3 corner = g.cornerLocal().add(f.scale(0.002));
        int lightValue = be.glow() ? LightTexture.FULL_BRIGHT
            : LevelRenderer.getLightColor(be.getLevel(), be.getBlockPos().relative(g.facing()));

        Matrix4f pose = poseStack.last().pose();
        VertexConsumer vc = buffers.getBuffer(RenderType.text(tex));
        Vec3 tl = point(corner, r, u, m, m), tr = point(corner, r, u, w - m, m);
        Vec3 br = point(corner, r, u, w - m, h - m), bl = point(corner, r, u, m, h - m);
        vertex(vc, pose, tl, 0, 0, lightValue);
        vertex(vc, pose, bl, 0, 1, lightValue);
        vertex(vc, pose, br, 1, 1, lightValue);
        vertex(vc, pose, tr, 1, 0, lightValue);

        // Blinking term cursor, drawn on top so it never touches the pixels
        Canvas c = screen.canvas;
        if (c.cursorVisible && (be.getLevel().getGameTime() / 8) % 2 == 0) {
            double sx = (w - 2 * m) / c.width, sy = (h - 2 * m) / c.height;
            Vec3 cc = corner.add(f.scale(0.001));
            double x0 = m + c.cursorX * sx, x1 = m + (c.cursorX + c.cursorW) * sx;
            double y0 = m + c.cursorY * sy, y1 = m + (c.cursorY + c.cursorH) * sy;
            VertexConsumer bg = buffers.getBuffer(RenderType.textBackground());
            int col = c.cursorColor;
            for (Vec3 p : new Vec3[]{point(cc, r, u, x0, y0), point(cc, r, u, x0, y1), point(cc, r, u, x1, y1), point(cc, r, u, x1, y0)}) {
                bg.addVertex(pose, (float) p.x, (float) p.y, (float) p.z)
                    .setColor((col >> 16) & 255, (col >> 8) & 255, col & 255, 255).setLight(lightValue);
            }
        }
    }

    /** Point dx blocks right and dy blocks down of the corner. */
    private static Vec3 point(Vec3 corner, Vec3 r, Vec3 u, double dx, double dy) {
        return corner.add(r.scale(dx)).add(u.scale(-dy));
    }

    private static void vertex(VertexConsumer vc, Matrix4f pose, Vec3 p, float u, float v, int light) {
        vc.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(255, 255, 255, 255).setUv(u, v).setLight(light);
    }

    @Override
    public boolean shouldRenderOffScreen(HdMonitorBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 128;
    }

    @Override
    public AABB getRenderBoundingBox(HdMonitorBlockEntity be) {
        // Like the laser: on a Sable ship the block sits in the plot grid, so plot-space bounds would cull it wrongly
        return AABB.INFINITE;
    }
}
