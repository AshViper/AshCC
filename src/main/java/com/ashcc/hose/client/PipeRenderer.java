package com.ashcc.hose.client;

import com.ashcc.AshCC;
import com.ashcc.compat.SableClientCompat;
import com.ashcc.hose.Hose;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Draws rigid pipes: square metal ducts along the path of {@link Hose#route}, with a collar at each bend and a flange
 * at each end. The path is in block coordinates and every corner is moved out with the ship's render pose, so a pipe on
 * a Sable ship stays on it. Pipes are not blocks and are not hidden by them, only drawn: they can run through walls.
 */
final class PipeRenderer {
    static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(AshCC.MOD_ID, "textures/entity/fluid_pipe.png");
    static final RenderType TYPE = RenderType.entityCutoutNoCull(TEXTURE);
    private static final double HALF = 0.14;
    private static final double JOINT = 0.17;
    private static final double FLANGE = 0.22;

    private PipeRenderer() {
    }

    /** Points of {@code route} are in the block frame of {@code frame} (the ship it is on, or the world). */
    static void draw(VertexConsumer vc, PoseStack.Pose pose, ClientLevel level, BlockPos frame, List<Vec3> route,
                     Direction fromFace, Direction toFace, float pt, float shade) {
        for (int i = 1; i < route.size(); i++) {
            Vec3 a = route.get(i - 1);
            Vec3 b = route.get(i);
            Vec3 d = b.subtract(a);
            if (d.lengthSqr() < 1e-8) continue;
            Direction.Axis axis = Direction.getNearest(d.x, d.y, d.z).getAxis();
            // Each run is extended by its half width so the bends are filled
            Vec3 lo = new Vec3(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z));
            Vec3 hi = new Vec3(Math.max(a.x, b.x), Math.max(a.y, b.y), Math.max(a.z, b.z));
            // Runs end flush with the block faces at the two ends; elsewhere they overlap into the bend
            double extA = i == 1 ? 0 : HALF;
            double extB = i == route.size() - 1 ? 0 : HALF;
            boolean aIsLo = a.get(axis) < b.get(axis);
            box(vc, pose, level, frame, inflate(lo, hi, axis, HALF, aIsLo ? extA : extB, aIsLo ? extB : extA), axis, pt, shade, 0f, d.length() * 2);
            // Collar at the bend
            if (i < route.size() - 1) {
                Vec3 c = b;
                box(vc, pose, level, frame, new Vec3[]{c.subtract(JOINT, JOINT, JOINT), c.add(JOINT, JOINT, JOINT)}, axis, pt, shade, 0.5f, 1);
            }
        }
        flange(vc, pose, level, frame, route.get(0), fromFace, pt, shade);
        flange(vc, pose, level, frame, route.get(route.size() - 1), toFace, pt, shade);
    }

    /** Box around the run lo..hi: {@code half} across, extended by extLo / extHi along the run's axis. */
    private static Vec3[] inflate(Vec3 lo, Vec3 hi, Direction.Axis axis, double half, double extLo, double extHi) {
        double[] l = {lo.x - half, lo.y - half, lo.z - half};
        double[] h = {hi.x + half, hi.y + half, hi.z + half};
        int k = axis.ordinal();
        l[k] = lo.get(axis) - extLo;
        h[k] = hi.get(axis) + extHi;
        return new Vec3[]{new Vec3(l[0], l[1], l[2]), new Vec3(h[0], h[1], h[2])};
    }

    private static void flange(VertexConsumer vc, PoseStack.Pose pose, ClientLevel level, BlockPos frame, Vec3 at, Direction face,
                               float pt, float shade) {
        Vec3 n = Vec3.atLowerCornerOf(face.getNormal());
        Vec3 c = at.add(n.scale(0.04));
        Vec3 half = new Vec3(FLANGE, FLANGE, FLANGE).subtract(Math.abs(n.x) * (FLANGE - 0.04), Math.abs(n.y) * (FLANGE - 0.04), Math.abs(n.z) * (FLANGE - 0.04));
        box(vc, pose, level, frame, new Vec3[]{c.subtract(half), c.add(half)}, face.getAxis(), pt, shade, 0.5f, 1);
    }

    /** All six faces of an axis-aligned box in block coordinates. u0 picks the texture half (0 duct, 0.5 fittings). */
    private static void box(VertexConsumer vc, PoseStack.Pose pose, ClientLevel level, BlockPos frame, Vec3[] box, Direction.Axis along,
                            float pt, float shade, float u0, double vLen) {
        Vec3 lo = box[0];
        Vec3 hi = box[1];
        int light = LevelRenderer.getLightColor(level, BlockPos.containing(world(level, frame, lo.add(hi).scale(0.5), pt)));
        for (Direction f : Direction.values()) {
            Vec3 n = Vec3.atLowerCornerOf(f.getNormal());
            Vec3[] q = face(lo, hi, f);
            Vec3 wn = dir(level, frame, n, pt);
            // Along the run the texture repeats with the length; across it spans one tile
            float v1 = f.getAxis() == along ? 0.5f : (float) vLen;
            float[][] uv = {{u0, 0}, {u0 + 0.5f, 0}, {u0 + 0.5f, v1}, {u0, v1}};
            for (int k = 0; k < 4; k++) {
                Vec3 p = world(level, frame, q[k], pt);
                vc.addVertex(pose.pose(), (float) p.x, (float) p.y, (float) p.z)
                    .setColor(shade, shade, shade, 1f)
                    .setUv(uv[k][0], uv[k][1])
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(light)
                    .setNormal(pose, (float) wn.x, (float) wn.y, (float) wn.z);
            }
        }
    }

    /** The four corners of one face, counter-clockwise seen from outside. */
    private static Vec3[] face(Vec3 l, Vec3 h, Direction f) {
        return switch (f) {
            case DOWN -> new Vec3[]{new Vec3(l.x, l.y, l.z), new Vec3(h.x, l.y, l.z), new Vec3(h.x, l.y, h.z), new Vec3(l.x, l.y, h.z)};
            case UP -> new Vec3[]{new Vec3(l.x, h.y, l.z), new Vec3(l.x, h.y, h.z), new Vec3(h.x, h.y, h.z), new Vec3(h.x, h.y, l.z)};
            case NORTH -> new Vec3[]{new Vec3(l.x, l.y, l.z), new Vec3(l.x, h.y, l.z), new Vec3(h.x, h.y, l.z), new Vec3(h.x, l.y, l.z)};
            case SOUTH -> new Vec3[]{new Vec3(l.x, l.y, h.z), new Vec3(h.x, l.y, h.z), new Vec3(h.x, h.y, h.z), new Vec3(l.x, h.y, h.z)};
            case WEST -> new Vec3[]{new Vec3(l.x, l.y, l.z), new Vec3(l.x, l.y, h.z), new Vec3(l.x, h.y, h.z), new Vec3(l.x, h.y, l.z)};
            case EAST -> new Vec3[]{new Vec3(h.x, l.y, l.z), new Vec3(h.x, h.y, l.z), new Vec3(h.x, h.y, h.z), new Vec3(h.x, l.y, h.z)};
        };
    }

    private static Vec3 world(ClientLevel level, BlockPos frame, Vec3 p, float pt) {
        return AshCC.sableLoaded ? SableClientCompat.pointToWorld(level, frame, p, pt) : p;
    }

    private static Vec3 dir(ClientLevel level, BlockPos frame, Vec3 n, float pt) {
        return AshCC.sableLoaded ? SableClientCompat.dirToWorld(level, frame, n, pt) : n;
    }
}
