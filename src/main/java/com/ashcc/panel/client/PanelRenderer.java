package com.ashcc.panel.client;

import com.ashcc.monitor.client.MonitorClient;
import com.ashcc.panel.ControlPanelBlockEntity;
import com.ashcc.panel.PanelControl;
import com.ashcc.panel.PanelFrame;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Draws the panel plate (tilted if set; edges shared with a matching neighbour panel have no frame, so rows of panels read
 * as one console) and its controls as small scaled blocks. Screens and HUDs show their part of the panel's canvas.
 * Works in the panel's frame: x = right, y = out of the panel, z = down the grid (row 0 at -z), origin at the surface centre.
 */
public class PanelRenderer implements BlockEntityRenderer<ControlPanelBlockEntity> {
    private static final BlockState IRON = Blocks.IRON_BLOCK.defaultBlockState();
    private static final BlockState BLACK = Blocks.BLACK_CONCRETE.defaultBlockState();
    private static final BlockState DARK = Blocks.GRAY_CONCRETE.defaultBlockState();
    private static final BlockState FRAME = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
    private static final BlockState WHITE = Blocks.WHITE_CONCRETE.defaultBlockState();
    private static final BlockState RED = Blocks.RED_CONCRETE.defaultBlockState();
    private static final BlockState GLASS = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState();
    private static final double RIM = 1 / 32.0;

    private final BlockRenderDispatcher blocks;

    public PanelRenderer(BlockEntityRendererProvider.Context ctx) {
        this.blocks = ctx.getBlockRenderDispatcher();
    }

    @Override
    public void render(ControlPanelBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        PanelFrame f = be.frame();
        pose.pushPose();
        if (be.tilt() > 0) foot(be, f, pose, buffers, light);
        Vec3 o = f.surfaceCentre();
        pose.translate(o.x, o.y, o.z);
        Vec3 back = f.up().scale(-1);
        Matrix3f m = new Matrix3f(
            (float) f.right().x, (float) f.right().y, (float) f.right().z,
            (float) f.normal().x, (float) f.normal().y, (float) f.normal().z,
            (float) back.x, (float) back.y, (float) back.z);
        pose.mulPose(new Quaternionf().setFromNormalized(m));
        plate(be, f, pose, buffers, light);
        double cell = 1.0 / be.grid();
        for (PanelControl c : be.controls()) {
            double cx = -0.5 + (c.col + c.width / 2.0) * cell, cz = -0.5 + (c.row + c.height / 2.0) * cell;
            double s = Math.min(c.width, c.height) * cell;
            pose.pushPose();
            pose.translate(cx, 0, cz);
            draw(be, c, s, c.width * cell, c.height * cell, pose, buffers, light);
            pose.popPose();
        }
        pose.popPose();
    }

    /** Plate under the surface, with a light rim on the edges that do not meet a matching panel. */
    private void plate(ControlPanelBlockEntity be, PanelFrame f, PoseStack pose, MultiBufferSource buffers, int light) {
        double t = PanelFrame.THICKNESS;
        box(pose, buffers, light, BLACK, 0, -t, 0, 1, t, 1);
        boolean left = !joins(be, f.right().scale(-1)), right = !joins(be, f.right());
        boolean top = be.tilt() > 0 || !joins(be, f.up()), bottom = be.tilt() > 0 || !joins(be, f.up().scale(-1));
        double h = 0.012;
        if (left) box(pose, buffers, light, FRAME, -0.5 + RIM / 2, 0, 0, RIM, h, 1);
        if (right) box(pose, buffers, light, FRAME, 0.5 - RIM / 2, 0, 0, RIM, h, 1);
        if (top) box(pose, buffers, light, FRAME, 0, 0, -0.5 + RIM / 2, 1, h, RIM);
        if (bottom) box(pose, buffers, light, FRAME, 0, 0, 0.5 - RIM / 2, 1, h, RIM);
    }

    /** A neighbour panel the same way round, tilt and grid size: no frame between them. */
    private static boolean joins(ControlPanelBlockEntity be, Vec3 dir) {
        if (be.getLevel() == null) return false;
        BlockPos n = be.getBlockPos().offset((int) Math.round(dir.x), (int) Math.round(dir.y), (int) Math.round(dir.z));
        return be.getLevel().getBlockEntity(n) instanceof ControlPanelBlockEntity o && o.getBlockState().equals(be.getBlockState())
            && o.tilt() == be.tilt() && o.grid() == be.grid();
    }

    /** A tilted panel stands on a thin base with a support under its raised edge. */
    private void foot(ControlPanelBlockEntity be, PanelFrame f, PoseStack pose, MultiBufferSource buffers, int light) {
        Direction base = f.attached();
        Vec3 n = Vec3.atLowerCornerOf(base.getOpposite().getNormal());
        Vec3 c = new Vec3(0.5, 0.5, 0.5).add(n.scale(1 / 32.0 - 0.5));
        // Base slab against the attached side
        Vec3 size = new Vec3(n.x != 0 ? 1 / 16.0 : 1, n.y != 0 ? 1 / 16.0 : 1, n.z != 0 ? 1 / 16.0 : 1);
        pose.pushPose();
        pose.translate(c.x - size.x / 2, c.y - size.y / 2, c.z - size.z / 2);
        pose.scale((float) size.x, (float) size.y, (float) size.z);
        blocks.renderSingleBlock(DARK, pose, buffers, light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
        // Support from the base to under the far edge
        Vec3 top = f.point(0.5, 0.92).subtract(f.normal().scale(PanelFrame.THICKNESS));
        double height = top.subtract(c).dot(n);
        if (height > 0.05) {
            Vec3 foot = top.subtract(n.scale(height));
            Vec3 s = new Vec3(n.x != 0 ? height : 0.6, n.y != 0 ? height : 0.6, n.z != 0 ? height : 0.6);
            Vec3 thin = f.up().scale(0.06);
            s = new Vec3(Math.abs(thin.x) > 0.01 ? 0.06 : s.x, Math.abs(thin.y) > 0.01 ? 0.06 : s.y, Math.abs(thin.z) > 0.01 ? 0.06 : s.z);
            Vec3 mid = foot.add(n.scale(height / 2));
            pose.pushPose();
            pose.translate(mid.x - s.x / 2, mid.y - s.y / 2, mid.z - s.z / 2);
            pose.scale((float) s.x, (float) s.y, (float) s.z);
            blocks.renderSingleBlock(DARK, pose, buffers, light, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
    }

    private void draw(ControlPanelBlockEntity be, PanelControl c, double s, double w, double h, PoseStack pose, MultiBufferSource buffers, int light) {
        BlockState color = concrete(c.color);
        switch (c.type) {
            case BUTTON -> {
                box(pose, buffers, light, DARK, 0, 0, 0, s * 0.75, 0.02, s * 0.75);
                box(pose, buffers, light, color, 0, 0.02, 0, s * 0.5, c.on ? 0.015 : 0.045, s * 0.5);
            }
            case TOGGLE -> {
                box(pose, buffers, light, BLACK, 0, 0, 0, s * 0.45, 0.03, s * 0.55);
                pose.pushPose();
                pose.translate(0, 0.03, 0);
                // On: the lever leans up the grid (-z)
                pose.mulPose(Axis.XP.rotationDegrees(c.on ? -28 : 28));
                box(pose, buffers, light, IRON, 0, 0, 0, 0.025, s * 0.55, 0.025);
                box(pose, buffers, light, color, 0, s * 0.55, 0, 0.055, 0.055, 0.055);
                pose.popPose();
            }
            case KNOB -> {
                box(pose, buffers, light, BLACK, 0, 0, 0, s * 0.8, 0.01, s * 0.8);
                pose.pushPose();
                pose.mulPose(Axis.YP.rotationDegrees((float) (135 - 270 * c.value)));
                box(pose, buffers, light, color, 0, 0.01, 0, s * 0.5, 0.06, s * 0.5);
                box(pose, buffers, light, WHITE, 0, 0.07, -s * 0.15, 0.02, 0.005, s * 0.22);
                pose.popPose();
            }
            case SLIDER -> {
                boolean alongZ = h >= w;
                double len = (alongZ ? h : w) * 0.8;
                box(pose, buffers, light, BLACK, 0, 0, 0, alongZ ? 0.03 : len, 0.01, alongZ ? len : 0.03);
                double p = (c.value - 0.5) * len * 0.9;
                // Full at the top of the grid (-z) or at the right (+x)
                double hx = alongZ ? 0 : p, hz = alongZ ? -p : 0;
                double hw = Math.min(alongZ ? w : h, 0.25) * 0.6;
                box(pose, buffers, light, color, hx, 0.01, hz, alongZ ? hw : 0.05, 0.05, alongZ ? 0.05 : hw);
            }
            case THROTTLE -> {
                // Lever in a slot along the grid: forward (-z) is more power. Grip with two thumb buttons
                double len = h * 0.8;
                box(pose, buffers, light, BLACK, 0, 0, 0, Math.min(w * 0.5, 0.08), 0.01, len);
                double z = -(c.value - 0.5) * len * 0.85;
                pose.pushPose();
                pose.translate(0, 0.01, z);
                double gw = Math.min(w * 0.7, 0.18);
                box(pose, buffers, light, IRON, 0, 0, 0, 0.03, 0.08, 0.03);
                box(pose, buffers, light, color, 0, 0.08, 0, gw, 0.07, 0.07);
                box(pose, buffers, light, (c.buttons & PanelControl.THUMB1) != 0 ? RED : WHITE, -gw * 0.25, 0.15, 0, 0.025, 0.012, 0.025);
                box(pose, buffers, light, (c.buttons & PanelControl.THUMB2) != 0 ? RED : DARK, gw * 0.25, 0.15, 0, 0.025, 0.012, 0.025);
                pose.popPose();
            }
            case STICK -> {
                box(pose, buffers, light, BLACK, 0, 0, 0, s * 0.6, 0.03, s * 0.6);
                pose.pushPose();
                pose.translate(0, 0.03, 0);
                // Forward (y+) leans up the grid (-z), right (x+) leans to +x
                pose.mulPose(Axis.XP.rotationDegrees((float) (-25 * c.y)));
                pose.mulPose(Axis.ZP.rotationDegrees((float) (-25 * c.x)));
                double len = s * 0.7;
                box(pose, buffers, light, IRON, 0, 0, 0, 0.035, len, 0.035);
                box(pose, buffers, light, color, 0, len, 0, 0.07, 0.12, 0.07);
                box(pose, buffers, light, (c.buttons & PanelControl.TRIGGER) != 0 ? RED : DARK, 0, len + 0.05, -0.04, 0.025, 0.025, 0.02);
                // Hat on top, leaning the way it is pushed
                int b = c.buttons;
                double hx = ((b & PanelControl.HAT_RIGHT) != 0 ? 0.008 : 0) - ((b & PanelControl.HAT_LEFT) != 0 ? 0.008 : 0);
                double hz = ((b & PanelControl.HAT_DOWN) != 0 ? 0.008 : 0) - ((b & PanelControl.HAT_UP) != 0 ? 0.008 : 0);
                box(pose, buffers, light, (b & (PanelControl.HAT_UP | PanelControl.HAT_DOWN | PanelControl.HAT_LEFT | PanelControl.HAT_RIGHT)) != 0 ? RED : WHITE,
                    hx, len + 0.12, hz, 0.025, 0.012, 0.025);
                box(pose, buffers, light, (b & PanelControl.BUTTON2) != 0 ? RED : WHITE, 0.025, len + 0.12, 0.02, 0.015, 0.01, 0.015);
                pose.popPose();
            }
            case SCREEN -> screen(be, c, w, h, pose, buffers, light);
            case HUD -> hud(be, c, w, h, pose, buffers, light);
        }
    }

    /** MFD: bezel and the control's rectangle of the canvas, flush with the panel. */
    private void screen(ControlPanelBlockEntity be, PanelControl c, double w, double h, PoseStack pose, MultiBufferSource buffers, int light) {
        box(pose, buffers, light, DARK, 0, 0, 0, w - 0.01, 0.012, h - 0.01);
        double m = Math.min(w, h) * 0.06;
        canvasQuad(be, c, pose, buffers, light, -w / 2 + m, w / 2 - m, -h / 2 + m, h / 2 - m, 0.0125, false);
    }

    /** HUD: a combiner glass standing up from the control's near edge, facing the pilot (+z), showing the canvas. */
    private void hud(ControlPanelBlockEntity be, PanelControl c, double w, double h, PoseStack pose, MultiBufferSource buffers, int light) {
        double z = h / 2 - 0.03;
        box(pose, buffers, light, BLACK, 0, 0, z, w * 0.9, 0.03, 0.05);
        pose.pushPose();
        pose.translate(0, 0.03, z);
        // Leans back a little, like a real combiner glass
        pose.mulPose(Axis.XP.rotationDegrees(-12));
        double gh = h * 0.95;
        box(pose, buffers, light, GLASS, 0, 0, 0, w * 0.9, gh, 0.004);
        // The canvas on the glass: in the y-z plane of the pane, so turn the frame to draw it like a flat screen
        pose.translate(0, gh / 2, 0.003);
        pose.mulPose(Axis.XP.rotationDegrees(90));
        canvasQuad(be, c, pose, buffers, light, -w * 0.45, w * 0.45, -gh / 2, gh / 2, 0, true);
        pose.popPose();
    }

    /** The control's rectangle of the panel canvas on the quad x0..x1, z0..z1 at height y (facing +y). */
    private void canvasQuad(ControlPanelBlockEntity be, PanelControl c, PoseStack pose, MultiBufferSource buffers, int light,
                            double x0, double x1, double z0, double z1, double y, boolean bothSides) {
        MonitorClient.Screen screen = MonitorClient.screen(be.getBlockPos());
        if (screen == null) return;
        ResourceLocation tex = screen.texture();
        if (tex == null) return;
        int[] r = be.pixels(c);
        float u0 = r[0] / (float) ControlPanelBlockEntity.CANVAS, v0 = r[1] / (float) ControlPanelBlockEntity.CANVAS;
        float u1 = (r[0] + r[2]) / (float) ControlPanelBlockEntity.CANVAS, v1 = (r[1] + r[3]) / (float) ControlPanelBlockEntity.CANVAS;
        int l = be.glow() ? LightTexture.FULL_BRIGHT : light;
        Matrix4f m = pose.last().pose();
        VertexConsumer vc = buffers.getBuffer(RenderType.text(tex));
        vertex(vc, m, x0, y, z0, u0, v0, l);
        vertex(vc, m, x0, y, z1, u0, v1, l);
        vertex(vc, m, x1, y, z1, u1, v1, l);
        vertex(vc, m, x1, y, z0, u1, v0, l);
        if (bothSides) {
            vertex(vc, m, x1, y, z0, u1, v0, l);
            vertex(vc, m, x1, y, z1, u1, v1, l);
            vertex(vc, m, x0, y, z1, u0, v1, l);
            vertex(vc, m, x0, y, z0, u0, v0, l);
        }
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, double x, double y, double z, float u, float v, int light) {
        vc.addVertex(m, (float) x, (float) y, (float) z).setColor(255, 255, 255, 255).setUv(u, v).setLight(light);
    }

    /** A block scaled to sx x sy x sz, centred on (x, z) and standing on y. */
    private void box(PoseStack pose, MultiBufferSource buffers, int light, BlockState state, double x, double y, double z, double sx, double sy, double sz) {
        pose.pushPose();
        pose.translate(x - sx / 2, y, z - sz / 2);
        pose.scale((float) sx, (float) sy, (float) sz);
        blocks.renderSingleBlock(state, pose, buffers, light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    private static BlockState concrete(DyeColor color) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(color.getSerializedName() + "_concrete")).defaultBlockState();
    }

    @Override
    public boolean shouldRenderOffScreen(ControlPanelBlockEntity be) {
        return true;
    }

    @Override
    public AABB getRenderBoundingBox(ControlPanelBlockEntity be) {
        // On a Sable ship the block's box is in plot coordinates; an infinite box keeps it from being culled
        return AABB.INFINITE;
    }
}
