package com.ashcc.compat.cbc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import rbasamoyai.createbigcannons.effects.particles.explosions.CannonBlastWaveEffectParticleData;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Client half of {@link RailgunFx}: a fading ionised trail behind the projectile, Mach rings along it, shock rings at the
 * muzzle and the impact, a sonic crack that reaches the player at the speed of sound (CBC's blast wave particle, which
 * also shakes the screen) and dust kicked up by the shock wave under low passes.
 */
final class RailgunFxClient {
    /** Ticks a trail point glows and fades. */
    private static final int TRAIL_LIFE = 50;
    /** Blocks between trail points and between Mach rings. */
    private static final double POINT_STEP = 1.5;
    private static final double MACH_RING_STEP = 8.0;
    /** The crack is heard when the shell passes this close. */
    private static final double CRACK_RANGE = 96.0;
    /** Speed of sound in blocks/tick (343 m/s at one metre per block). Slower shells make no crack, Mach rings or dust. */
    static final double MACH_1 = 343.0 / 20.0;
    /** Dust is kicked up when the shell passes this low over a block. */
    private static final double DUST_HEIGHT = 7.0;

    private record Point(Vec3 pos, long born) {
    }

    /**
     * A shock ring. With {@code mount} >= 0 it stays on that cannon's barrel, {@code along} blocks past the muzzle
     * (rings at the muzzle of a gun on a moving ship); otherwise it stays where it was made, in the air.
     */
    private record Ring(Vec3 center, Vec3 normal, long born, int life, double radius, float r, float g, float b, float alpha, double width,
                        int mount, double along) {
        Ring(Vec3 center, Vec3 normal, long born, int life, double radius, float r, float g, float b, float alpha, double width) {
            this(center, normal, born, life, radius, r, g, b, alpha, width, -1, 0);
        }
    }

    private static final class Trail {
        final int entity;
        final List<Point> points = new ArrayList<>();
        final double speed;
        final Vec3 muzzle;
        final Vec3 dir0;
        /** Furthest distance flown along the shot direction; the client's copy can step back a little when it is corrected. */
        double progress;
        Vec3 last;
        Vec3 dir;
        boolean alive = true;
        boolean cracked;
        double sinceRing;
        double sinceDust;
        long lastSeen;

        Trail(int entity, Vec3 muzzle, Vec3 dir, double speed, long now) {
            this.entity = entity;
            this.last = muzzle;
            this.muzzle = muzzle;
            this.dir0 = dir;
            this.dir = dir;
            this.speed = speed;
            this.lastSeen = now;
            points.add(new Point(muzzle, now));
        }
    }

    /** Dev scenes (devtest/CbcShot) log the cracks. */
    private static final boolean DEV = System.getProperty("ashcc.cbcshot") != null;
    private static final List<Trail> TRAILS = new ArrayList<>();
    private static final List<Ring> RINGS = new ArrayList<>();
    private static final RandomSource RANDOM = RandomSource.create();

    private RailgunFxClient() {
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> tick());
        NeoForge.EVENT_BUS.addListener(RailgunFxClient::render);
    }

    private static long now(ClientLevel level) {
        return level.getGameTime();
    }

    static void onShot(RailgunFx.Shot shot) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        long now = now(level);
        Vec3 m = shot.muzzle();
        Vec3 d = shot.direction().normalize();
        double power = Math.min(1.0, shot.speed() / 40.0);
        TRAILS.add(new Trail(shot.entity(), m, d, shot.speed(), now));

        // Shock rings: a fast bright one at the muzzle and a slower, wider one that the blast pushes out
        int mount = shot.mount();
        RINGS.add(new Ring(m, d, now, 6, 3.0 + 4.0 * power, 0.75f, 0.9f, 1.0f, 0.9f, 0.6, mount, 0.0));
        RINGS.add(new Ring(m.add(d.scale(1.0)), d, now, 14, 7.0 + 8.0 * power, 1.0f, 1.0f, 1.0f, 0.35f, 1.4, mount, 1.0));
        RINGS.add(new Ring(m.subtract(d.scale(0.5)), d, now, 10, 2.5 + 2.0 * power, 0.5f, 0.75f, 1.0f, 0.5f, 0.4, mount, -0.5));

        // Flash, sparks thrown forward, and a vapour disc perpendicular to the barrel
        level.addParticle(ParticleTypes.FLASH, true, m.x, m.y, m.z, 0, 0, 0);
        Vec3 a = perpendicular(d);
        Vec3 b = d.cross(a);
        for (int i = 0; i < 40; i++) {
            Vec3 v = d.scale(0.6 + RANDOM.nextDouble() * 1.4).add(a.scale(RANDOM.nextGaussian() * 0.15)).add(b.scale(RANDOM.nextGaussian() * 0.15));
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, true, m.x, m.y, m.z, v.x, v.y, v.z);
        }
        for (int i = 0; i < 18; i++) {
            Vec3 v = d.scale(0.8 + RANDOM.nextDouble()).add(a.scale(RANDOM.nextGaussian() * 0.08)).add(b.scale(RANDOM.nextGaussian() * 0.08));
            level.addParticle(ParticleTypes.END_ROD, true, m.x, m.y, m.z, v.x, v.y, v.z);
        }
        for (int i = 0; i < 36; i++) {
            double ang = i * Math.PI * 2 / 36;
            Vec3 radial = a.scale(Math.cos(ang)).add(b.scale(Math.sin(ang)));
            Vec3 v = radial.scale(0.35 + 0.25 * power).add(d.scale(0.05));
            level.addParticle(ParticleTypes.CLOUD, true, m.x, m.y, m.z, v.x, v.y, v.z);
        }
    }

    private static Vec3 perpendicular(Vec3 d) {
        return (Math.abs(d.y) < 0.9 ? d.cross(new Vec3(0, 1, 0)) : d.cross(new Vec3(1, 0, 0))).normalize();
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            TRAILS.clear();
            RINGS.clear();
            return;
        }
        long now = now(level);
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        for (Iterator<Trail> it = TRAILS.iterator(); it.hasNext(); ) {
            Trail t = it.next();
            if (t.alive) follow(level, t, now, cam);
            t.points.removeIf(p -> now - p.born() > TRAIL_LIFE);
            if (!t.alive && t.points.isEmpty()) it.remove();
        }
        RINGS.removeIf(r -> now - r.born() > r.life());
    }

    private static void follow(ClientLevel level, Trail t, long now, Vec3 cam) {
        Entity e = level.getEntity(t.entity);
        if (e == null || e.isRemoved()) {
            // Gone: it hit something (or left the tracking range) where we last saw it
            if (now - t.lastSeen > 2 || e != null) {
                t.alive = false;
                impact(level, t, now);
            }
            return;
        }
        t.lastSeen = now;
        Vec3 pos = e.position();
        double sNew = pos.subtract(t.muzzle).dot(t.dir0);
        if (sNew <= t.progress) return;
        double sOld = t.progress;
        t.progress = sNew;
        Vec3 seg = pos.subtract(t.last);
        double len = seg.length();
        if (len < 1e-4) return;
        Vec3 d = seg.scale(1 / len);
        t.dir = d;

        // Trail points and Mach rings along the segment flown this tick
        for (double s = POINT_STEP; s < len; s += POINT_STEP) t.points.add(new Point(t.last.add(d.scale(s)), now));
        t.points.add(new Point(pos, now));
        if (t.speed >= MACH_1) t.sinceRing += len;
        while (t.sinceRing >= MACH_RING_STEP) {
            t.sinceRing -= MACH_RING_STEP;
            Vec3 c = pos.subtract(d.scale(t.sinceRing));
            RINGS.add(new Ring(c, d, now, 8, 1.2 + 0.05 * t.speed, 0.85f, 0.95f, 1.0f, 0.4f, 0.25));
        }

        // Sonic crack from the point where the shell passes the player (only supersonic shells; one heard from
        // behind the gun is just the muzzle blast)
        if (!t.cracked) {
            double sCam = cam.subtract(t.muzzle).dot(t.dir0);
            if (sCam <= 0 || t.speed < MACH_1) {
                t.cracked = true;
            } else if (sNew >= sCam) {
                t.cracked = true;
                Vec3 at = t.last.lerp(pos, Mth.clamp((sCam - sOld) / (sNew - sOld), 0, 1));
                double dist = at.distanceTo(cam);
                if (dist < CRACK_RANGE) crack(at, dist, t.speed);
            }
        }

        // Dust thrown up by the shock wave under low passes
        if (t.speed >= MACH_1) t.sinceDust += len;
        for (double s = 0; s < len && t.sinceDust >= 2.0; s += 2.0) {
            t.sinceDust -= 2.0;
            dust(level, t.last.add(d.scale(s)), d);
        }
        t.last = pos;
    }

    private static void crack(Vec3 at, double dist, double speed) {
        Minecraft mc = Minecraft.getInstance();
        if (DEV) com.ashcc.AshCC.LOGGER.info("RailgunFx: sonic crack from {} ({} blocks away)", at, String.format(java.util.Locale.ROOT, "%.1f", dist));
        float closeness = (float) (1.0 - dist / CRACK_RANGE);
        var data = new CannonBlastWaveEffectParticleData(6.0 + speed * 0.3,
            BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST), SoundSource.BLOCKS,
            6.0f, 0.55f + 0.2f * closeness, 1.0f, (float) speed * 0.25f);
        mc.particleEngine.createParticle(data, at.x, at.y, at.z, 0, 0, 0);
        var whip = new CannonBlastWaveEffectParticleData(0.0,
            BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.TRIDENT_RIPTIDE_1.value()), SoundSource.BLOCKS,
            3.0f, 1.8f, 1.0f, 0.0f);
        mc.particleEngine.createParticle(whip, at.x, at.y, at.z, 0, 0, 0);
    }

    private static void dust(ClientLevel level, Vec3 p, Vec3 d) {
        BlockHitResult hit = level.clip(new ClipContext(p, p.add(0, -DUST_HEIGHT, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY,
            net.minecraft.world.phys.shapes.CollisionContext.empty()));
        if (hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        double height = p.y - hit.getLocation().y;
        int count = (int) Math.ceil(6 * (1 - height / DUST_HEIGHT));
        Vec3 ground = hit.getLocation();
        Vec3 side = new Vec3(-d.z, 0, d.x);
        if (side.lengthSqr() < 1e-6) return;
        side = side.normalize();
        var dustType = state.getFluidState().isEmpty() ? new BlockParticleOption(ParticleTypes.BLOCK, state) : ParticleTypes.SPLASH;
        for (int i = 0; i < count; i++) {
            double sgn = RANDOM.nextBoolean() ? 1 : -1;
            Vec3 v = side.scale(sgn * (0.2 + RANDOM.nextDouble() * 0.3)).add(0, 0.2 + RANDOM.nextDouble() * 0.3, 0);
            level.addParticle(dustType, ground.x, ground.y + 0.1, ground.z, v.x, v.y, v.z);
            if (i % 2 == 0) level.addParticle(ParticleTypes.POOF, ground.x, ground.y + 0.2, ground.z, v.x * 0.5, v.y * 0.3, v.z * 0.5);
        }
    }

    private static void impact(ClientLevel level, Trail t, long now) {
        Vec3 p = t.last;
        level.addParticle(ParticleTypes.FLASH, true, p.x, p.y, p.z, 0, 0, 0);
        RINGS.add(new Ring(p, t.dir, now, 10, 4.0, 1.0f, 0.85f, 0.6f, 0.7f, 0.5));
        for (int i = 0; i < 24; i++) {
            Vec3 v = new Vec3(RANDOM.nextGaussian(), RANDOM.nextGaussian(), RANDOM.nextGaussian()).normalize().scale(0.4 + RANDOM.nextDouble() * 0.6);
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, true, p.x, p.y, p.z, v.x, v.y, v.z);
        }
    }

    // ---- Rendering ----

    private static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || (TRAILS.isEmpty() && RINGS.isEmpty())) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double time = now(level) + partial;

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = pose.last().pose();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderType.lightning());

        for (Trail t : TRAILS) {
            List<Point> pts = new ArrayList<>(t.points);
            if (t.alive) {
                Entity e = level.getEntity(t.entity);
                if (e != null && !e.isRemoved()) pts.add(new Point(e.getPosition(partial), (long) Math.ceil(time)));
            }
            for (int i = 1; i < pts.size(); i++) {
                Point p0 = pts.get(i - 1);
                Point p1 = pts.get(i);
                double age0 = Math.max(0, time - p0.born());
                double age1 = Math.max(0, time - p1.born());
                // Outer glow spreads as the ionised air cools; the core is a thin hot line that fades first
                ribbon(vc, m, cam, p0.pos(), p1.pos(), 0.25 + age0 * 0.02, 0.25 + age1 * 0.02,
                    0.35f, 0.6f, 1.0f, fade(age0, TRAIL_LIFE) * 0.35f, fade(age1, TRAIL_LIFE) * 0.35f);
                ribbon(vc, m, cam, p0.pos(), p1.pos(), 0.05, 0.05,
                    0.9f, 0.95f, 1.0f, fade(age0, TRAIL_LIFE * 0.4) * 0.9f, fade(age1, TRAIL_LIFE * 0.4) * 0.9f);
            }
        }
        for (Ring r : RINGS) {
            double f = Mth.clamp((time - r.born()) / r.life(), 0, 1);
            double eased = 1 - (1 - f) * (1 - f);
            Vec3 center = r.center();
            Vec3 normal = r.normal();
            if (r.mount() >= 0) {
                // Follow the barrel this frame (a gun on a moving ship); where it was fired if the mount is gone
                Vec3[] g = CannonFxGeometry.muzzle(level, r.mount(), partial);
                if (g != null) {
                    center = g[0].add(g[1].scale(r.along()));
                    normal = g[1];
                }
            }
            ring(vc, m, center, normal, r.radius() * (0.15 + 0.85 * eased), r.width() * (1 - 0.5 * f),
                r.r(), r.g(), r.b(), (float) (r.alpha() * (1 - f) * (1 - f)));
        }
        buffers.endBatch(RenderType.lightning());
        pose.popPose();
    }

    private static float fade(double age, double life) {
        double f = Mth.clamp(1 - age / life, 0, 1);
        return (float) (f * f);
    }

    /** One camera-facing quad from a to b. */
    private static void ribbon(VertexConsumer vc, Matrix4f m, Vec3 cam, Vec3 a, Vec3 b, double wa, double wb,
                               float r, float g, float bl, float alphaA, float alphaB) {
        if (alphaA <= 0.002f && alphaB <= 0.002f) return;
        Vec3 d = b.subtract(a);
        Vec3 side = d.cross(a.subtract(cam));
        if (side.lengthSqr() < 1e-9) return;
        side = side.normalize();
        Vec3 sa = side.scale(wa);
        Vec3 sb = side.scale(wb);
        vertex(vc, m, a.subtract(sa), r, g, bl, alphaA);
        vertex(vc, m, a.add(sa), r, g, bl, alphaA);
        vertex(vc, m, b.add(sb), r, g, bl, alphaB);
        vertex(vc, m, b.subtract(sb), r, g, bl, alphaB);
        vertex(vc, m, b.subtract(sb), r, g, bl, alphaB);
        vertex(vc, m, b.add(sb), r, g, bl, alphaB);
        vertex(vc, m, a.add(sa), r, g, bl, alphaA);
        vertex(vc, m, a.subtract(sa), r, g, bl, alphaA);
    }

    /** A flat annulus around {@code normal}, visible from both sides. */
    private static void ring(VertexConsumer vc, Matrix4f m, Vec3 c, Vec3 normal, double radius, double width,
                             float r, float g, float b, float alpha) {
        if (alpha <= 0.002f || radius <= 0) return;
        Vec3 u = perpendicular(normal);
        Vec3 v = normal.cross(u).normalize();
        double inner = Math.max(0, radius - width);
        int n = 40;
        for (int i = 0; i < n; i++) {
            double a0 = i * Math.PI * 2 / n;
            double a1 = (i + 1) * Math.PI * 2 / n;
            Vec3 d0 = u.scale(Math.cos(a0)).add(v.scale(Math.sin(a0)));
            Vec3 d1 = u.scale(Math.cos(a1)).add(v.scale(Math.sin(a1)));
            Vec3 o0 = c.add(d0.scale(radius)), o1 = c.add(d1.scale(radius));
            Vec3 i0 = c.add(d0.scale(inner)), i1 = c.add(d1.scale(inner));
            // the inner edge is transparent so the ring looks like a soft pressure front
            vertex(vc, m, i0, r, g, b, 0);
            vertex(vc, m, o0, r, g, b, alpha);
            vertex(vc, m, o1, r, g, b, alpha);
            vertex(vc, m, i1, r, g, b, 0);
            vertex(vc, m, i1, r, g, b, 0);
            vertex(vc, m, o1, r, g, b, alpha);
            vertex(vc, m, o0, r, g, b, alpha);
            vertex(vc, m, i0, r, g, b, 0);
        }
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, Vec3 p, float r, float g, float b, float a) {
        vc.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
    }
}
