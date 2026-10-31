package com.ashcc.compat.cbc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Client half of {@link LaserCannonFx}: a thick pulsing beam from the front lens along the barrel, a glow at the muzzle
 * and sparks, embers and smoke where it hits.
 */
final class LaserCannonFxClient {
    /** A beam not refreshed for this many ticks is dropped (the server refreshes once a second while firing). */
    private static final int STALE = 60;

    private record Beam(float length, long updated) {
    }

    private static final Map<Integer, Beam> BEAMS = new HashMap<>();
    private static final RandomSource RANDOM = RandomSource.create();

    private LaserCannonFxClient() {
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> tick());
        NeoForge.EVENT_BUS.addListener(LaserCannonFxClient::render);
    }

    static void onBeam(LaserCannonFx.Beam beam) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        if (beam.on()) BEAMS.put(beam.entity(), new Beam(beam.length(), level.getGameTime()));
        else BEAMS.remove(beam.entity());
    }

    /** Muzzle and direction of a laser cannon contraption at the given partial tick, or null when it is gone. */
    private static Vec3 @Nullable [] geometry(ClientLevel level, int id, float partial) {
        Entity e = level.getEntity(id);
        if (!(e instanceof PitchOrientedContraptionEntity poce) || !(poce.getContraption() instanceof MountedLaserContraption)) return null;
        // On a Sable ship: moved out with the ship's render pose, so the beam stays on the barrel as the ship moves
        return CannonFxGeometry.muzzle(level, id, partial);
    }

    private static void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            BEAMS.clear();
            return;
        }
        long now = level.getGameTime();
        for (Iterator<Map.Entry<Integer, Beam>> it = BEAMS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Beam> en = it.next();
            Vec3[] g = geometry(level, en.getKey(), 1.0f);
            if (g == null || now - en.getValue().updated() > STALE) {
                it.remove();
                continue;
            }
            Vec3 back = g[0].add(g[1].scale(Math.max(0, en.getValue().length() - 0.1)));
            for (int i = 0; i < 3; i++) {
                Vec3 v = new Vec3(RANDOM.nextGaussian(), RANDOM.nextGaussian(), RANDOM.nextGaussian()).scale(0.15).subtract(g[1].scale(0.1));
                level.addParticle(ParticleTypes.ELECTRIC_SPARK, back.x, back.y, back.z, v.x, v.y, v.z);
            }
            level.addParticle(ParticleTypes.LAVA, back.x, back.y, back.z, 0, 0, 0);
            if (now % 3 == 0) level.addParticle(ParticleTypes.LARGE_SMOKE, back.x, back.y, back.z, 0, 0.05, 0);
            level.addParticle(ParticleTypes.END_ROD, g[0].x, g[0].y, g[0].z,
                g[1].x * 0.2 + RANDOM.nextGaussian() * 0.02, g[1].y * 0.2 + RANDOM.nextGaussian() * 0.02, g[1].z * 0.2 + RANDOM.nextGaussian() * 0.02);
        }
    }

    private static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || BEAMS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        Vec3 cam = event.getCamera().getPosition();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double time = level.getGameTime() + partial;

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = pose.last().pose();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderType.lightning());
        for (Map.Entry<Integer, Beam> en : BEAMS.entrySet()) {
            Vec3[] g = geometry(level, en.getKey(), partial);
            if (g == null) continue;
            Vec3 from = g[0];
            Vec3 end = from.add(g[1].scale(en.getValue().length()));
            float pulse = 1 + 0.12f * (float) Math.sin(time * 1.3) + 0.05f * (float) Math.sin(time * 4.1);
            // Wide soft glow, a hot body and a white core
            beam(vc, m, cam, from, end, 0.55 * pulse, 0.25f, 0.45f, 1.0f, 0.18f);
            beam(vc, m, cam, from, end, 0.22 * pulse, 0.45f, 0.75f, 1.0f, 0.55f);
            beam(vc, m, cam, from, end, 0.07, 0.95f, 0.98f, 1.0f, 0.95f);
            // Glow at the muzzle and where it hits
            flare(vc, m, cam, from, 0.6 * pulse, 0.5f, 0.75f, 1.0f, 0.5f);
            flare(vc, m, cam, end, 1.1 * pulse, 1.0f, 0.75f, 0.45f, 0.6f);
        }
        buffers.endBatch(RenderType.lightning());
        pose.popPose();
    }

    /** A camera-facing band from a to b, opaque along its centre line and clear at both edges. */
    private static void beam(VertexConsumer vc, Matrix4f m, Vec3 cam, Vec3 a, Vec3 b, double width, float r, float g, float bl, float alpha) {
        Vec3 side = b.subtract(a).cross(a.lerp(b, 0.5).subtract(cam));
        if (side.lengthSqr() < 1e-9) return;
        side = side.normalize().scale(width);
        strip(vc, m, a, b, a.add(side), b.add(side), r, g, bl, alpha);
        strip(vc, m, a, b, a.subtract(side), b.subtract(side), r, g, bl, alpha);
    }

    private static void strip(VertexConsumer vc, Matrix4f m, Vec3 a, Vec3 b, Vec3 ae, Vec3 be, float r, float g, float bl, float alpha) {
        v(vc, m, a, r, g, bl, alpha);
        v(vc, m, ae, r, g, bl, 0);
        v(vc, m, be, r, g, bl, 0);
        v(vc, m, b, r, g, bl, alpha);
        v(vc, m, b, r, g, bl, alpha);
        v(vc, m, be, r, g, bl, 0);
        v(vc, m, ae, r, g, bl, 0);
        v(vc, m, a, r, g, bl, alpha);
    }

    /** A camera-facing glowing disc. */
    private static void flare(VertexConsumer vc, Matrix4f m, Vec3 cam, Vec3 c, double radius, float r, float g, float b, float alpha) {
        Vec3 toCam = cam.subtract(c).normalize();
        Vec3 u = (Math.abs(toCam.y) < 0.9 ? toCam.cross(new Vec3(0, 1, 0)) : toCam.cross(new Vec3(1, 0, 0))).normalize();
        Vec3 w = toCam.cross(u).normalize();
        int n = 16;
        for (int i = 0; i < n; i++) {
            double a0 = i * Math.PI * 2 / n, a1 = (i + 1) * Math.PI * 2 / n;
            Vec3 p0 = c.add(u.scale(Math.cos(a0) * radius)).add(w.scale(Math.sin(a0) * radius));
            Vec3 p1 = c.add(u.scale(Math.cos(a1) * radius)).add(w.scale(Math.sin(a1) * radius));
            v(vc, m, c, r, g, b, alpha);
            v(vc, m, p0, r, g, b, 0);
            v(vc, m, p1, r, g, b, 0);
            v(vc, m, c, r, g, b, alpha);
        }
    }

    private static void v(VertexConsumer vc, Matrix4f m, Vec3 p, float r, float g, float b, float a) {
        vc.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
    }
}
