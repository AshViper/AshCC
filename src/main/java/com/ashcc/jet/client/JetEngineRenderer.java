package com.ashcc.jet.client;

import com.ashcc.AshCC;
import com.ashcc.jet.JetEngineBlockEntity;
import com.ashcc.jet.JetPartBlock;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.joml.Matrix4f;

/**
 * Draws the exhaust as glowing geometry (no particles): the hot nozzle face, the faint dry exhaust, and the afterburner
 * plume made of nested additive shells with Mach diamonds (shock diamonds) inside. The plume swells and pinches
 * between the diamonds, flickers ring by ring, shifts from a translucent violet-blue at minimum reheat to a bright
 * orange torch at maximum, and flashes when the afterburner lights. Drawn in block coordinates, so on a Sable ship
 * it follows the ship's pose. Client only.
 */
public class JetEngineRenderer implements BlockEntityRenderer<JetEngineBlockEntity> {
    private static final int SEGMENTS = 18;
    private static final int RINGS = 28;
    private static final double NOZZLE_RADIUS = 0.34;

    public JetEngineRenderer(BlockEntityRendererProvider.Context context) {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener((EntityRenderersEvent.RegisterRenderers e) -> e.registerBlockEntityRenderer(AshCC.JET_ENGINE_BE.get(), JetEngineRenderer::new));
    }

    /** Profile of one shell: radius, colour and opacity at a fraction s (0 = nozzle, 1 = tip) of its length. */
    private interface Shell {
        double radius(double s, double x);

        void color(double s, float[] rgba);
    }

    @Override
    public void render(JetEngineBlockEntity be, float pt, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
        if (be.getLevel() == null || !be.getBlockState().hasProperty(JetPartBlock.FACING)) return;
        double n2 = be.clientN2(pt);
        double egt = be.clientEgt(pt);
        double ab = be.clientAb(pt);
        double flash = be.clientFlash(pt);
        Direction facing = be.getBlockState().getValue(JetPartBlock.FACING);
        Vec3 d = Vec3.atLowerCornerOf(facing.getNormal());
        Vec3 a = (Math.abs(d.y) < 0.9 ? d.cross(new Vec3(0, 1, 0)) : d.cross(new Vec3(1, 0, 0))).normalize();
        Vec3 b = d.cross(a).normalize();

        // Fan and turbine, only while the casing around them is complete
        if (be.getBlockState().getValue(JetPartBlock.FORMED)) {
            poseStack.pushPose();
            poseStack.translate(0.5, 0.5, 0.5);
            int fanLight = LevelRenderer.getLightColor(be.getLevel(), be.getBlockPos().relative(facing.getOpposite(), 2));
            JetRotors.draw(poseStack.last(), buffers, d, a, b, be.fanAngle(pt), be.turbineAngle(pt), egt, fanLight, light);
            poseStack.popPose();
        }

        if (egt < 150 && ab < 0.01 && n2 < 0.05) return;
        double n1 = JetEngineBlockEntity.n1(n2);
        float time = (be.getLevel().getGameTime() % 100000L) + pt;
        long seed = be.getBlockPos().asLong();
        // The variable nozzle opens up for reheat
        double exitR = NOZZLE_RADIUS * (1 + 0.18 * ab);

        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);
        Matrix4f m = poseStack.last().pose();
        VertexConsumer vc = buffers.getBuffer(JetRenderTypes.EXHAUST);
        Vec3 exit = d.scale(0.505);
        // A vectoring nozzle bends the plume: it leaves along the deflected direction
        Vec3 pd = be.clientVectoring() ? JetEngineBlockEntity.deflect(d, be.clientVectorPitch(pt), be.clientVectorYaw(pt)) : d;
        Vec3 pa = (Math.abs(pd.y) < 0.9 ? pd.cross(new Vec3(0, 1, 0)) : pd.cross(new Vec3(1, 0, 0))).normalize();
        Vec3 pb = pd.cross(pa).normalize();
        Vec3 pExit = pd.scale(0.505);

        // Glowing turbine face inside the nozzle: black body colour of the exhaust gas
        double heat = Mth.clamp((egt - 180) / 700, 0, 1);
        if (heat > 0 || ab > 0) {
            float[] c = blackBody(egt + 500 * ab);
            // Faint: the turbine behind it carries the glow itself (JetRotors)
            float alpha = (float) Math.min(0.6, heat * 0.3 + ab * 0.45);
            disc(vc, m, exit, d, a, b, exitR * 0.98, c[0], c[1], c[2], alpha);
        }

        boolean lit = be.syncedLitClient();
        if (lit && n1 > 0.02) dryExhaust(vc, m, pExit, pd, pa, pb, exitR, n1, ab, time, seed);
        if (ab > 0.005) afterburner(vc, m, pExit, pd, pa, pb, exitR, ab, be.clientAfterburner() ? 2 : 1, time, seed);
        if (flash > 0.02) {
            // Light-off of the reheat: a short bright ball of flame at the nozzle
            double fl = flash;
            tube(vc, m, pExit, pd, pa, pb, 0.9 + 0.6 * (1 - fl), 10, 12, new Shell() {
                public double radius(double s, double x) {
                    return exitR * (1.2 + 1.4 * fl) * Math.sqrt(Math.max(0, 1 - s * s)) + 0.02;
                }

                public void color(double s, float[] o) {
                    set(o, 1f, 0.75f, 0.4f, (float) (0.55 * fl * (1 - s)));
                }
            }, time, seed);
        }
        poseStack.popPose();
    }

    /** Hot, mostly transparent jet of the dry engine: a short bluish haze with an orange tint near military power. */
    private static void dryExhaust(VertexConsumer vc, Matrix4f m, Vec3 exit, Vec3 d, Vec3 a, Vec3 b, double exitR,
                                   double n1, double ab, float time, long seed) {
        double fade = 1 - Mth.clamp(ab * 3, 0, 1);
        if (fade <= 0) return;
        double len = 0.8 + 2.2 * n1;
        tube(vc, m, exit, d, a, b, len, RINGS / 2, SEGMENTS, new Shell() {
            public double radius(double s, double x) {
                return exitR * (0.95 + 0.9 * s);
            }

            public void color(double s, float[] o) {
                set(o, 0.55f, 0.62f, 0.95f, (float) (0.10 * n1 * fade * Math.pow(1 - s, 1.5)));
            }
        }, time, seed);
        double hot = Math.pow(n1, 3) * fade;
        if (hot > 0.02) {
            tube(vc, m, exit, d, a, b, 0.4 + 0.6 * n1, 8, SEGMENTS, new Shell() {
                public double radius(double s, double x) {
                    return exitR * 0.85 * (1 - 0.6 * s);
                }

                public void color(double s, float[] o) {
                    set(o, 1f, 0.45f, 0.2f, (float) (0.22 * hot * (1 - s)));
                }
            }, time, seed);
        }
    }

    private static void afterburner(VertexConsumer vc, Matrix4f m, Vec3 exit, Vec3 d, Vec3 a, Vec3 b, double exitR,
                                    double ab, int stages, float time, long seed) {
        double size = 1 + 0.15 * Math.max(0, stages - 1);
        double len = (2.6 + 4.6 * ab) * size * (1 + 0.04 * Math.sin(time * 2.3 + seed));
        // Mach diamonds: more and further apart at a higher pressure ratio
        double spacing = (0.42 + 0.22 * ab) * size;
        int diamonds = 2 + (int) Math.round(3 * ab) + (stages > 1 ? 1 : 0);
        double first = 0.32 * size + spacing * 0.5;
        // Colour: violet-blue translucent at minimum reheat, orange torch at maximum
        float or = (float) Mth.lerp(ab, 0.75, 1.0), og = (float) Mth.lerp(ab, 0.35, 0.52), ob = (float) Mth.lerp(ab, 0.85, 0.16);

        // Outer envelope, pinched at each diamond
        tube(vc, m, exit, d, a, b, len, RINGS, SEGMENTS, new Shell() {
            public double radius(double s, double x) {
                double taper = Math.sqrt(Math.max(0, 1 - Math.pow(s, 2.4)));
                return exitR * (1.05 + 0.45 * s) * taper * pinch(x, first, spacing, 0.10);
            }

            public void color(double s, float[] o) {
                set(o, or, og, ob, (float) (0.45 * Math.sqrt(ab) * Math.pow(1 - s, 1.1)));
            }
        }, time, seed);
        // Yellow flame body
        tube(vc, m, exit, d, a, b, len * 0.78, RINGS, SEGMENTS, new Shell() {
            public double radius(double s, double x) {
                double taper = Math.sqrt(Math.max(0, 1 - Math.pow(s, 2.0)));
                return exitR * 0.78 * (1 + 0.2 * s) * taper * pinch(x, first, spacing, 0.16);
            }

            public void color(double s, float[] o) {
                set(o, 1f, (float) Mth.lerp(ab, 0.55, 0.78), (float) Mth.lerp(ab, 0.75, 0.32), (float) (0.5 * Math.sqrt(ab) * (1 - s)));
            }
        }, time, seed);
        // Blue-white core: the supersonic jet before it mixes with the air
        tube(vc, m, exit, d, a, b, first + spacing * (diamonds - 1) + 0.2, RINGS, SEGMENTS, new Shell() {
            public double radius(double s, double x) {
                return exitR * 0.5 * (1 - 0.55 * s) * pinch(x, first, spacing, 0.25);
            }

            public void color(double s, float[] o) {
                set(o, 0.7f, 0.82f, 1f, (float) (0.55 * ab * (1 - s * 0.7)));
            }
        }, time, seed);

        // The diamonds: bright double cones on the axis, fading downstream
        for (int k = 0; k < diamonds; k++) {
            double x = first + spacing * k;
            if (x > len * 0.85) break;
            double fadeK = 1 - (double) k / (diamonds + 1);
            double flick = 0.85 + 0.15 * noise(time * 1.7 + k * 3.1, seed);
            double half = spacing * (0.30 - 0.03 * k);
            double r = exitR * (0.42 - 0.04 * k) * (0.6 + 0.4 * ab);
            float alpha = (float) (Math.min(1, 1.1 * ab) * fadeK * flick);
            bicone(vc, m, d.scale(0.505 + x), d, a, b, half, r, 1f, (float) Mth.lerp(fadeK, 0.6, 0.95),
                (float) Mth.lerp(fadeK, 0.35, 0.85), alpha);
            // Soft glow around each diamond
            bicone(vc, m, d.scale(0.505 + x), d, a, b, half * 1.6, r * 1.9, 1f, 0.7f, 0.35f, alpha * 0.25f);
        }
    }

    /** Radius factor that narrows the jet at each shock (where the diamonds are) and widens it between them. */
    private static double pinch(double x, double first, double spacing, double depth) {
        if (x < first - spacing) return 1;
        return 1 - depth * 0.5 * (1 + Math.cos(2 * Math.PI * (x - first) / spacing));
    }

    /** A tube of {@code rings} x {@code segments} quads along d, shaped and coloured by the shell, flickering per ring. */
    private static void tube(VertexConsumer vc, Matrix4f m, Vec3 exit, Vec3 d, Vec3 a, Vec3 b, double len, int rings, int segments,
                             Shell shell, float time, long seed) {
        float[] c0 = new float[4];
        float[] c1 = new float[4];
        double[] cos = new double[segments + 1];
        double[] sin = new double[segments + 1];
        for (int j = 0; j <= segments; j++) {
            double ang = 2 * Math.PI * j / segments;
            cos[j] = Math.cos(ang);
            sin[j] = Math.sin(ang);
        }
        double prevX = 0;
        double prevR = shell.radius(0, 0) * wobble(0, time, seed);
        shell.color(0, c0);
        float prevFlick = 1;
        for (int i = 1; i <= rings; i++) {
            double s = (double) i / rings;
            double x = s * len;
            double r = shell.radius(s, x) * wobble(i, time, seed);
            shell.color(s, c1);
            float flick = (float) (0.8 + 0.2 * noise(time * 1.3 + i * 0.77, seed));
            for (int j = 0; j < segments; j++) {
                Vec3 p00 = exit.add(d.scale(prevX)).add(a.scale(cos[j] * prevR)).add(b.scale(sin[j] * prevR));
                Vec3 p01 = exit.add(d.scale(prevX)).add(a.scale(cos[j + 1] * prevR)).add(b.scale(sin[j + 1] * prevR));
                Vec3 p11 = exit.add(d.scale(x)).add(a.scale(cos[j + 1] * r)).add(b.scale(sin[j + 1] * r));
                Vec3 p10 = exit.add(d.scale(x)).add(a.scale(cos[j] * r)).add(b.scale(sin[j] * r));
                vertex(vc, m, p00, c0, prevFlick);
                vertex(vc, m, p01, c0, prevFlick);
                vertex(vc, m, p11, c1, flick);
                vertex(vc, m, p10, c1, flick);
            }
            prevX = x;
            prevR = r;
            prevFlick = flick;
            System.arraycopy(c1, 0, c0, 0, 4);
        }
    }

    private static void bicone(VertexConsumer vc, Matrix4f m, Vec3 center, Vec3 d, Vec3 a, Vec3 b, double half, double r,
                               float red, float green, float blue, float alpha) {
        float[] tip = {red, green, blue, 0};
        float[] mid = {red, green, blue, alpha};
        Vec3 front = center.subtract(d.scale(half));
        Vec3 back = center.add(d.scale(half));
        int seg = 12;
        for (int j = 0; j < seg; j++) {
            double a0 = 2 * Math.PI * j / seg;
            double a1 = 2 * Math.PI * (j + 1) / seg;
            Vec3 q0 = center.add(a.scale(Math.cos(a0) * r)).add(b.scale(Math.sin(a0) * r));
            Vec3 q1 = center.add(a.scale(Math.cos(a1) * r)).add(b.scale(Math.sin(a1) * r));
            for (Vec3 apex : new Vec3[]{front, back}) {
                vertex(vc, m, apex, tip, 1);
                vertex(vc, m, apex, tip, 1);
                vertex(vc, m, q1, mid, 1);
                vertex(vc, m, q0, mid, 1);
            }
        }
    }

    private static void disc(VertexConsumer vc, Matrix4f m, Vec3 center, Vec3 d, Vec3 a, Vec3 b, double r,
                             float red, float green, float blue, float alpha) {
        float[] mid = {Math.min(1, red * 1.2f), Math.min(1, green * 1.3f), Math.min(1, blue * 1.3f), alpha};
        float[] rim = {red, green, blue, alpha * 0.5f};
        int seg = 16;
        for (int j = 0; j < seg; j++) {
            double a0 = 2 * Math.PI * j / seg;
            double a1 = 2 * Math.PI * (j + 1) / seg;
            Vec3 q0 = center.add(a.scale(Math.cos(a0) * r)).add(b.scale(Math.sin(a0) * r));
            Vec3 q1 = center.add(a.scale(Math.cos(a1) * r)).add(b.scale(Math.sin(a1) * r));
            vertex(vc, m, center, mid, 1);
            vertex(vc, m, center, mid, 1);
            vertex(vc, m, q1, rim, 1);
            vertex(vc, m, q0, rim, 1);
        }
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, Vec3 p, float[] c, float flick) {
        vc.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(c[0], c[1], c[2], Mth.clamp(c[3] * flick, 0, 1));
    }

    private static void set(float[] o, float r, float g, float b, float a) {
        o[0] = r;
        o[1] = g;
        o[2] = b;
        o[3] = a;
    }

    private static double wobble(int ring, float time, long seed) {
        return 1 + 0.05 * noise(time * 0.9 + ring * 1.37, seed);
    }

    /** Cheap smooth noise in -1..1 from a few incommensurate sines. */
    private static double noise(double t, long seed) {
        double s = (seed & 1023) * 0.37;
        return 0.5 * Math.sin(t * 3.1 + s) + 0.3 * Math.sin(t * 7.3 + s * 1.7) + 0.2 * Math.sin(t * 13.9 + s * 2.3);
    }

    /** Approximate colour of glowing metal / gas at a temperature in degrees C. */
    static float[] blackBody(double c) {
        double t = Mth.clamp((c - 400) / 1200, 0, 1);
        float r = (float) Mth.clamp(0.35 + t * 2.2, 0, 1);
        float g = (float) Mth.clamp(-0.1 + t * 1.3, 0, 1);
        float b = (float) Mth.clamp(-0.6 + t * 1.2, 0, 1);
        return new float[]{r, g, b};
    }

    @Override
    public boolean shouldRenderOffScreen(JetEngineBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 192;
    }

    @Override
    public AABB getRenderBoundingBox(JetEngineBlockEntity be) {
        // Infinite like the laser: on a Sable ship the block sits in the far-away plot grid, so a real box gets culled
        return AABB.INFINITE;
    }

    /** Additive, double sided, no depth writes: overlapping shells add up to a glow instead of hiding each other. */
    static final class JetRenderTypes extends RenderType {
        static final RenderType EXHAUST = create("ashcc_jet_exhaust", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 1 << 16,
            false, true, CompositeState.builder()
                .setShaderState(RENDERTYPE_LIGHTNING_SHADER)
                .setWriteMaskState(COLOR_WRITE)
                .setTransparencyState(LIGHTNING_TRANSPARENCY)
                .setCullState(NO_CULL)
                .setOutputState(TRANSLUCENT_TARGET)
                .createCompositeState(false));

        private JetRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int size, boolean crumbling, boolean sort,
                               Runnable setup, Runnable clear) {
            super(name, format, mode, size, crumbling, sort, setup, clear);
        }
    }
}
