package com.ashcc.connector.client;

import com.ashcc.AshCC;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Client only. */
public final class ConnectorClient {
    private ConnectorClient() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener((EntityRenderersEvent.RegisterRenderers e) -> e.registerEntityRenderer(AshCC.CONNECTOR.get(), ConnectorRenderer::new));
    }
}
