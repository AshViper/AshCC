package com.ashcc.connector.client;

import com.ashcc.AshCC;
import com.ashcc.connector.ConnectorEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/** A 1 x 1/16 plate on the face, tilted as set with the wrench. Hidden once a block sits on it. */
public class ConnectorRenderer extends EntityRenderer<ConnectorEntity> {
    private static final ResourceLocation FRONT = ResourceLocation.fromNamespaceAndPath(AshCC.MOD_ID, "textures/entity/offset_connector.png");
    private static final ResourceLocation SIDE = ResourceLocation.fromNamespaceAndPath(AshCC.MOD_ID, "textures/entity/offset_connector_side.png");
    private static final float T = 1 / 16f;

    public ConnectorRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    public void render(ConnectorEntity e, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        if (e.child() == null) {
            pose.pushPose();
            // The parent ship's orientation, then the same tilt the welded blocks get (about the anchor, in the parent's frame)
            if (AshCC.sableLoaded) {
                org.joml.Quaterniond q = com.ashcc.compat.SableCompat.orientation(e.level(), e.parentPos());
                if (q != null) pose.mulPose(new org.joml.Quaternionf(q));
            }
            pose.mulPose(Axis.YP.rotationDegrees(-e.yawDeg()));
            pose.mulPose(Axis.XP.rotationDegrees(e.pitchDeg()));
            pose.mulPose(Axis.ZP.rotationDegrees(e.rollDeg()));
            pose.mulPose(e.face().getRotation());
            PoseStack.Pose p = pose.last();
            VertexConsumer front = buffers.getBuffer(RenderType.entityCutout(FRONT));
            quad(front, p, light, 0, 1, 0, -.5f, T, -.5f, -.5f, T, .5f, .5f, T, .5f, .5f, T, -.5f);
            quad(front, p, light, 0, -1, 0, -.5f, 0, .5f, -.5f, 0, -.5f, .5f, 0, -.5f, .5f, 0, .5f);
            VertexConsumer side = buffers.getBuffer(RenderType.entityCutout(SIDE));
            quad(side, p, light, 0, 0, -1, .5f, 0, -.5f, -.5f, 0, -.5f, -.5f, T, -.5f, .5f, T, -.5f);
            quad(side, p, light, 0, 0, 1, -.5f, 0, .5f, .5f, 0, .5f, .5f, T, .5f, -.5f, T, .5f);
            quad(side, p, light, -1, 0, 0, -.5f, 0, -.5f, -.5f, 0, .5f, -.5f, T, .5f, -.5f, T, -.5f);
            quad(side, p, light, 1, 0, 0, .5f, 0, .5f, .5f, 0, -.5f, .5f, T, -.5f, .5f, T, .5f);
            pose.popPose();
        }
        super.render(e, yaw, partialTick, pose, buffers, light);
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose p, int light, float nx, float ny, float nz, float... v) {
        float[][] uv = {{0, 1}, {0, 0}, {1, 0}, {1, 1}};
        for (int i = 0; i < 4; i++) {
            vc.addVertex(p, v[i * 3], v[i * 3 + 1], v[i * 3 + 2]).setColor(-1).setUv(uv[i][0], uv[i][1])
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, nx, ny, nz);
        }
    }

    @Override
    public ResourceLocation getTextureLocation(ConnectorEntity e) {
        return FRONT;
    }
}
