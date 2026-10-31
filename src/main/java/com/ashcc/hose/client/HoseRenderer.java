package com.ashcc.hose.client;

import com.ashcc.AshCC;
import com.ashcc.hose.Hose;
import com.ashcc.compat.SableClientCompat;
import com.ashcc.hose.HoseStart;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * Draws the hoses as rubber tubes: a cubic curve that leaves each face straight out, sags under its own weight and
 * ends in a brass collar. Ends on a Sable ship move with the ship. While a hose item holds a start, a preview runs
 * from it to the block face under the crosshair. Client only.
 */
public final class HoseRenderer {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(AshCC.MOD_ID, "textures/entity/hose.png");
    private static final int SIDES = 8;
    private static final double RADIUS = 0.085;
    private static volatile List<Hose> hoses = List.of();

    private HoseRenderer() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(HoseRenderer::render);
    }

    public static void set(List<Hose> list) {
        hoses = List.copyOf(list);
    }

    private static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return;
        HoseStart preview = previewStart(mc);
        boolean previewPipe = preview != null && previewIsPipe(mc);
        if (hoses.isEmpty() && preview == null) return;

        Vec3 cam = event.getCamera().getPosition();
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        PoseStack.Pose last = pose.last();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        VertexConsumer pipes = buffers.getBuffer(PipeRenderer.TYPE);
        for (Hose h : hoses) {
            Vec3 a = end(level, h.from(), h.fromFace(), pt);
            Vec3 b = end(level, h.to(), h.toFace(), pt);
            if (a.distanceToSqr(cam) > 160 * 160 && b.distanceToSqr(cam) > 160 * 160) continue;
            if (h.pipe()) {
                PipeRenderer.draw(pipes, last, level, h.from(), h.route(), h.fromFace(), h.toFace(), pt, 1f);
            } else {
                draw(vc, last, level, a, normal(level, h.from(), h.fromFace(), pt), b, normal(level, h.to(), h.toFace(), pt), 1f);
            }
        }
        if (preview != null && previewPipe) {
            // A pipe's preview only shows where it would run to a block face under the crosshair
            if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(preview.pos())) {
                PipeRenderer.draw(pipes, last, level, preview.pos(),
                    Hose.route(preview.pos(), preview.face(), hit.getBlockPos(), hit.getDirection()), preview.face(), hit.getDirection(), pt, 0.55f);
            }
        } else if (preview != null) {
            Vec3 a = end(level, preview.pos(), preview.face(), pt);
            Vec3 na = normal(level, preview.pos(), preview.face(), pt);
            Vec3 b;
            Vec3 nb;
            if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
                b = end(level, hit.getBlockPos(), hit.getDirection(), pt);
                nb = normal(level, hit.getBlockPos(), hit.getDirection(), pt);
            } else {
                b = mc.player.getEyePosition().add(mc.player.getLookAngle().scale(3));
                nb = mc.player.getLookAngle().reverse();
            }
            draw(vc, last, level, a, na, b, nb, 0.6f);
        }
        pose.popPose();
        buffers.endBatch(RenderType.entityCutoutNoCull(TEXTURE));
        buffers.endBatch(PipeRenderer.TYPE);
    }

    private static boolean previewIsPipe(Minecraft mc) {
        ItemStack s = mc.player.getMainHandItem();
        if (!(s.getItem() instanceof com.ashcc.hose.HoseItem)) s = mc.player.getOffhandItem();
        return s.getItem() instanceof com.ashcc.hose.HoseItem item && item.isPipe();
    }

    private static HoseStart previewStart(Minecraft mc) {
        for (ItemStack s : new ItemStack[]{mc.player.getMainHandItem(), mc.player.getOffhandItem()}) {
            if (s.getItem() instanceof com.ashcc.hose.HoseItem) {
                HoseStart st = s.get(AshCC.HOSE_START.get());
                if (st != null && mc.level != null && st.dimension().equals(mc.level.dimension().location())) return st;
            }
        }
        return null;
    }

    /** Hose end where its block is drawn this frame (a ship's render pose, not its once-a-tick logical pose). */
    private static Vec3 end(ClientLevel level, BlockPos pos, Direction face, float pt) {
        Vec3 p = Hose.attach(pos, face);
        return AshCC.sableLoaded ? SableClientCompat.pointToWorld(level, pos, p, pt) : p;
    }

    private static Vec3 normal(ClientLevel level, BlockPos pos, Direction face, float pt) {
        Vec3 n = Vec3.atLowerCornerOf(face.getNormal());
        return AshCC.sableLoaded ? SableClientCompat.dirToWorld(level, pos, n, pt) : n;
    }

    /** Point on the hose at t: a cubic Bezier leaving both faces straight out, plus a sag that is deepest mid-way. */
    private static Vec3 point(Vec3 a, Vec3 na, Vec3 b, Vec3 nb, double t, double reach, double sag) {
        Vec3 p1 = a.add(na.scale(reach));
        Vec3 p2 = b.add(nb.scale(reach));
        double u = 1 - t;
        Vec3 p = a.scale(u * u * u).add(p1.scale(3 * u * u * t)).add(p2.scale(3 * u * t * t)).add(b.scale(t * t * t));
        return p.add(0, -sag * 4 * t * u, 0);
    }

    private static void draw(VertexConsumer vc, PoseStack.Pose pose, ClientLevel level, Vec3 a, Vec3 na, Vec3 b, Vec3 nb, float shade) {
        double len = a.distanceTo(b);
        double reach = Math.min(0.6, len * 0.35);
        double sag = Math.min(2.5, 0.05 + len * 0.07);
        int segments = Math.max(8, Math.min(96, (int) (len * 5)));
        Vec3[] pts = new Vec3[segments + 1];
        for (int i = 0; i <= segments; i++) pts[i] = point(a, na, b, nb, (double) i / segments, reach, sag);

        // Tube, its cross-section carried along the curve (parallel transport) so it does not twist
        Vec3 tangent = pts[1].subtract(pts[0]).normalize();
        Vec3 side = perpendicular(tangent);
        Vec3[] prevRing = ring(pts[0], tangent, side, RADIUS);
        double v = 0;
        for (int i = 1; i <= segments; i++) {
            Vec3 t = pts[Math.min(segments, i + 1)].subtract(pts[i - 1]).normalize();
            side = t.cross(side.cross(t)).normalize();
            if (side.lengthSqr() < 1e-6) side = perpendicular(t);
            Vec3[] ring = ring(pts[i], t, side, RADIUS);
            double v2 = v + pts[i].distanceTo(pts[i - 1]) * 2;
            int l0 = light(level, pts[i - 1]);
            int l1 = light(level, pts[i]);
            for (int j = 0; j < SIDES; j++) {
                int k = (j + 1) % SIDES;
                float u0 = (float) j / SIDES * 0.5f;
                float u1 = (float) (j + 1) / SIDES * 0.5f;
                quad(vc, pose, prevRing[j], prevRing[k], ring[k], ring[j], pts[i - 1], pts[i], u0, u1, (float) v, (float) v2, l0, l1, shade);
            }
            prevRing = ring;
            v = v2;
        }

        // Brass collars at both ends
        collar(vc, pose, level, a, na, shade);
        collar(vc, pose, level, b, nb, shade);
    }

    private static void collar(VertexConsumer vc, PoseStack.Pose pose, ClientLevel level, Vec3 at, Vec3 n, float shade) {
        Vec3 side = perpendicular(n);
        Vec3 c0 = at.subtract(n.scale(0.01));
        Vec3 c1 = at.add(n.scale(0.14));
        Vec3[] r0 = ring(c0, n, side, RADIUS * 1.55);
        Vec3[] r1 = ring(c1, n, side, RADIUS * 1.55);
        int l = light(level, at.add(n.scale(0.2)));
        for (int j = 0; j < SIDES; j++) {
            int k = (j + 1) % SIDES;
            float u0 = 0.5f + (float) j / SIDES * 0.5f;
            float u1 = 0.5f + (float) (j + 1) / SIDES * 0.5f;
            quad(vc, pose, r0[j], r0[k], r1[k], r1[j], c0, c1, u0, u1, 0f, 0.5f, l, l, shade);
            // Flat ring facing out of the block
            quad(vc, pose, r1[j], r1[k], c1.add(r1[k].subtract(c1).scale(0.55)), c1.add(r1[j].subtract(c1).scale(0.55)),
                c1, c1, u0, u1, 0.5f, 0.6f, l, l, shade);
        }
    }

    private static Vec3 perpendicular(Vec3 t) {
        Vec3 up = Math.abs(t.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        return t.cross(up).normalize();
    }

    private static Vec3[] ring(Vec3 c, Vec3 t, Vec3 side, double r) {
        Vec3 up = t.cross(side).normalize();
        Vec3[] out = new Vec3[SIDES];
        for (int j = 0; j < SIDES; j++) {
            double ang = 2 * Math.PI * j / SIDES;
            out[j] = c.add(side.scale(Math.cos(ang) * r)).add(up.scale(Math.sin(ang) * r));
        }
        return out;
    }

    private static int light(ClientLevel level, Vec3 p) {
        return LevelRenderer.getLightColor(level, BlockPos.containing(p));
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose pose, Vec3 p00, Vec3 p01, Vec3 p11, Vec3 p10, Vec3 c0, Vec3 c1,
                             float u0, float u1, float v0, float v1, int l0, int l1, float shade) {
        vertex(vc, pose, p00, p00.subtract(c0), u0, v0, l0, shade);
        vertex(vc, pose, p01, p01.subtract(c0), u1, v0, l0, shade);
        vertex(vc, pose, p11, p11.subtract(c1), u1, v1, l1, shade);
        vertex(vc, pose, p10, p10.subtract(c1), u0, v1, l1, shade);
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, Vec3 p, Vec3 n, float u, float v, int light, float shade) {
        Matrix4f m = pose.pose();
        Vector3f nn = new Vector3f((float) n.x, (float) n.y, (float) n.z);
        if (nn.lengthSquared() > 1e-8) nn.normalize();
        vc.addVertex(m, (float) p.x, (float) p.y, (float) p.z)
            .setColor(shade, shade, shade, 1f)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(pose, nn.x, nn.y, nn.z);
    }
}
