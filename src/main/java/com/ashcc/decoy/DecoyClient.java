package com.ashcc.decoy;

import com.ashcc.AshCC;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Decoys are drawn with particles only. Client only. */
public final class DecoyClient {
    private DecoyClient() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener((EntityRenderersEvent.RegisterRenderers e) -> {
            e.registerEntityRenderer(AshCC.DECOY.get(), NoopRenderer::new);
        });
    }
}
