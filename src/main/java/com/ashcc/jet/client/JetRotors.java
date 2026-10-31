package com.ashcc.jet.client;

import com.ashcc.AshCC;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The moving parts seen through the open ends of the casing: the fan with its spinner behind the inlet lip, and the
 * turbine inside the nozzle, glowing with the exhaust temperature. Twisted blades, drawn relative to the nozzle's
 * centre with the exhaust direction d and the cross axes a, b. Client only.
 */
final class JetRotors {
    private static final ResourceLocation FAN = ResourceLocation.fromNamespaceAndPath(AshCC.MOD_ID, "block/jet_fan_blade");
    private static final ResourceLocation TURBINE = ResourceLocation.fromNamespaceAndPath(AshCC.MOD_ID, "block/jet_turbine_blade");
    private static final ResourceLocation HUB = ResourceLocation.fromNamespaceAndPath(AshCC.MOD_ID, "block/jet_interior");
    /** Tip radius: just inside the 10 px bore of the casing. */
    private static final double TIP = 0.30;

    private JetRotors() {
    }

    static void draw(PoseStack.Pose pose, MultiBufferSource buffers, Vec3 d, Vec3 a, Vec3 b, float fanAngle, float turbineAngle,
                     double egt, int fanLight, int light) {
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS));
        TextureAtlasSprite hub = sprite(HUB);

        // Fan: two blocks upstream, 10 px into the intake (models put the exhaust end at z = 0)
        Vec3 fan = d.scale(-2 - 2 / 16.0);
        rotor(vc, pose, fan, d, a, b, fanAngle, 14, 0.075, TIP, 0.12, 0.3, 0.75, sprite(FAN), 1, 1, 1, fanLight, d.reverse());
        cone(vc, pose, fan, d.reverse(), a, b, fanAngle, 0.15, 0.08, hub, 0.85f, 0.87f, 0.9f, fanLight);

        // Turbine: 11 px into the nozzle, in front of the rear bulkhead. Hot blades glow, so they ignore the block light.
        Vec3 turbine = d.scale(-3 / 16.0);
        double heat = Mth.clamp((egt - 300) / 600, 0, 1);
        float[] glow = JetEngineRenderer.blackBody(egt);
        float r = (float) Mth.lerp(heat, 1, Math.max(glow[0], 0.6)), g = (float) Mth.lerp(heat, 1, glow[1] * 0.8 + 0.2),
            bl = (float) Mth.lerp(heat, 1, glow[2] * 0.6 + 0.1);
        int tl = heat > 0.15 ? LightTexture.FULL_BRIGHT : light;
        rotor(vc, pose, turbine, d, a, b, -turbineAngle, 26, 0.10, TIP - 0.01, 0.07, -0.5, -0.7, sprite(TURBINE), r, g, bl, tl, d);
        cone(vc, pose, turbine, d, a, b, -turbineAngle, 0.06, 0.10, hub, r * 0.7f, g * 0.7f, bl * 0.7f, tl);
    }

    /**
     * n twisted blades from radius r0 to r1, pitch going from p0 at the root to p1 at the tip (radians from the disc plane).
     * All blades are shaded as if facing out of the open end (view), else the far side of the disc goes black.
     */
    private static void rotor(VertexConsumer vc, PoseStack.Pose pose, Vec3 c, Vec3 d, Vec3 a, Vec3 b, float angle, int n,
                              double r0, double r1, double chord, double p0, double p1, TextureAtlasSprite s,
                              float red, float green, float blue, int light, Vec3 view) {
        int spans = 3;
        double h = chord / 2;
        for (int k = 0; k < n; k++) {
            double th = angle + 2 * Math.PI * k / n;
            Vec3 u = a.scale(Math.cos(th)).add(b.scale(Math.sin(th)));
            Vec3 v = a.scale(-Math.sin(th)).add(b.scale(Math.cos(th)));
            for (int i = 0; i < spans; i++) {
                double f0 = (double) i / spans, f1 = (double) (i + 1) / spans;
                double ra = Mth.lerp(f0, r0, r1), rb = Mth.lerp(f1, r0, r1);
                double pa = Mth.lerp(f0, p0, p1), pb = Mth.lerp(f1, p0, p1);
                Vec3 la = edge(c, u, v, d, ra, h, pa), ta = edge(c, u, v, d, ra, -h, pa);
                Vec3 lb = edge(c, u, v, d, rb, h, pb), tb = edge(c, u, v, d, rb, -h, pb);
                // A slight outward tilt varies the shading round the disc, so the turning shows
                Vec3 nrm = view.add(u.scale(0.3)).normalize();
                float v0 = (float) f0, v1 = (float) f1;
                vertex(vc, pose, la, s, 0, v0, red, green, blue, light, nrm);
                vertex(vc, pose, lb, s, 0, v1, red, green, blue, light, nrm);
                vertex(vc, pose, tb, s, 1, v1, red, green, blue, light, nrm);
                vertex(vc, pose, ta, s, 1, v0, red, green, blue, light, nrm);
            }
        }
    }

    /** Point on a blade at radius r, offset t along the chord, which is turned by the pitch out of the disc plane. */
    private static Vec3 edge(Vec3 c, Vec3 u, Vec3 v, Vec3 d, double r, double t, double pitch) {
        return c.add(u.scale(r)).add(v.scale(t * Math.cos(pitch))).add(d.scale(t * Math.sin(pitch)));
    }

    /** Spinner or tail cone: base of radius rr at c, apex len along axis. Turns with the rotor so its texture shows the speed. */
    private static void cone(VertexConsumer vc, PoseStack.Pose pose, Vec3 c, Vec3 axis, Vec3 a, Vec3 b, float angle, double len,
                             double rr, TextureAtlasSprite s, float red, float green, float blue, int light) {
        int seg = 12;
        Vec3 apex = c.add(axis.scale(len));
        for (int j = 0; j < seg; j++) {
            double a0 = angle + 2 * Math.PI * j / seg, a1 = angle + 2 * Math.PI * (j + 1) / seg;
            Vec3 q0 = c.add(a.scale(Math.cos(a0) * rr)).add(b.scale(Math.sin(a0) * rr));
            Vec3 q1 = c.add(a.scale(Math.cos(a1) * rr)).add(b.scale(Math.sin(a1) * rr));
            Vec3 nrm = q0.add(q1).scale(0.5).subtract(c).normalize().add(axis.scale(rr / len)).normalize();
            // Alternate light and dark panels so the turning is visible
            float k = j % 2 == 0 ? 1f : 0.6f;
            vertex(vc, pose, apex, s, 0.5f, 0, red * k, green * k, blue * k, light, nrm);
            vertex(vc, pose, apex, s, 0.5f, 0, red * k, green * k, blue * k, light, nrm);
            vertex(vc, pose, q1, s, 1, 1, red * k, green * k, blue * k, light, nrm);
            vertex(vc, pose, q0, s, 0, 1, red * k, green * k, blue * k, light, nrm);
        }
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, Vec3 p, TextureAtlasSprite s, float u, float v,
                               float red, float green, float blue, int light, Vec3 n) {
        Matrix4f m = pose.pose();
        vc.addVertex(m, (float) p.x, (float) p.y, (float) p.z)
            .setColor(red, green, blue, 1f)
            .setUv(Mth.lerp(u, s.getU0(), s.getU1()), Mth.lerp(v, s.getV0(), s.getV1()))
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
    }

    private static TextureAtlasSprite sprite(ResourceLocation id) {
        return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(id);
    }
}
