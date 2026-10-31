package com.ashcc.connector.client;

import com.ashcc.connector.ConnectorEntity;
import com.ashcc.connector.ConnectorNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** Wrench screen: yaw / pitch / roll of the connector plate (and what is welded on it) against the block it sticks to, in 5 degree steps. */
public class ConnectorScreen extends Screen {
    private static final int STEP = 5;
    private final int entity;
    private final float[] angles;

    private ConnectorScreen(int entity, float yaw, float pitch, float roll) {
        super(Component.translatable("screen.ashcc.offset_connector"));
        this.entity = entity;
        this.angles = new float[]{yaw, pitch, roll};
    }

    public static void open(ConnectorEntity plate) {
        Minecraft.getInstance().setScreen(new ConnectorScreen(plate.getId(), plate.yawDeg(), plate.pitchDeg(), plate.rollDeg()));
    }

    @Override
    protected void init() {
        int x = width / 2 - 100;
        int y = height / 2 - 50;
        String[] keys = {"yaw", "pitch", "roll"};
        for (int i = 0; i < 3; i++) addRenderableWidget(new AngleSlider(x, y + i * 24, i, keys[i]));
        addRenderableWidget(Button.builder(Component.translatable("screen.ashcc.offset_connector.reset"), b -> {
            angles[0] = angles[1] = angles[2] = 0;
            send();
            rebuildWidgets();
        }).bounds(x, y + 76, 98, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(x + 102, y + 76, 98, 20).build());
    }

    private void send() {
        ConnectorNetwork.send(entity, angles[0], angles[1], angles[2]);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, height / 2 - 70, 0xFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private class AngleSlider extends AbstractSliderButton {
        private final int index;
        private final String key;

        AngleSlider(int x, int y, int index, String key) {
            super(x, y, 200, 20, Component.empty(), (angles[index] + 180) / 360.0);
            this.index = index;
            this.key = key;
            updateMessage();
        }

        private int degrees() {
            return Math.round((float) (value * 360 - 180) / STEP) * STEP;
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("screen.ashcc.offset_connector." + key, degrees()));
        }

        @Override
        protected void applyValue() {
            float d = degrees();
            if (d == angles[index]) return;
            angles[index] = Mth.wrapDegrees(d);
            send();
        }
    }
}
