package com.ashcc.panel;

import com.ashcc.monitor.HdMonitorPeripheral;
import com.ashcc.monitor.MonitorScreen;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lua API of the control panel (peripheral type "control_panel", also an "hd_monitor"): channel values 0..15, the controls with
 * their state, and every HD monitor drawing function on the panel's 256x256 canvas (screen and HUD controls show their part).
 * Event: control_panel(name, channel, value) whenever a channel changes.
 */
public class ControlPanelPeripheral extends HdMonitorPeripheral {
    private final ControlPanelBlockEntity panel;

    ControlPanelPeripheral(ControlPanelBlockEntity panel, MonitorScreen screen) {
        super(screen);
        this.panel = panel;
    }

    @Override
    public String getType() {
        return "control_panel";
    }

    @Override
    public Set<String> getAdditionalTypes() {
        return Set.of("hd_monitor");
    }

    @Override
    public Object getTarget() {
        return panel;
    }

    @Override
    public void attach(IComputerAccess computer) {
        super.attach(computer);
        panel.computers.add(computer);
    }

    @Override
    public void detach(IComputerAccess computer) {
        super.detach(computer);
        panel.computers.remove(computer);
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof ControlPanelPeripheral o && o.panel == panel && o.screen == screen;
    }

    /** Value 0..15 of one channel (1..64). */
    @LuaFunction(mainThread = true)
    public final int getChannel(int channel) throws LuaException {
        if (channel < 1 || channel > PanelControl.MAX_CHANNEL) throw new LuaException("channel must be 1.." + PanelControl.MAX_CHANNEL);
        return panel.channel(channel);
    }

    /** All channels: {[1] = value, ..., [64] = value}. */
    @LuaFunction(mainThread = true)
    public final Map<Integer, Integer> getChannels() {
        Map<Integer, Integer> m = new HashMap<>();
        for (int ch = 1; ch <= PanelControl.MAX_CHANNEL; ch++) m.put(ch, panel.channel(ch));
        return m;
    }

    /**
     * The controls: type, col, row (1-based, row 1 at the top), width, height, channel (first), channels (how many), color,
     * state (on, value, x, y, buttons, held), and for screens and HUDs their rectangle on the canvas (px, py, pw, ph, 0-based pixels).
     */
    @LuaFunction(mainThread = true)
    public final List<Map<String, Object>> getControls() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<PanelControl> controls = panel.controls();
        for (int i = 0; i < controls.size(); i++) {
            PanelControl c = controls.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", c.type.id());
            m.put("col", c.col + 1);
            m.put("row", c.row + 1);
            m.put("width", c.width);
            m.put("height", c.height);
            m.put("channel", c.channel);
            m.put("channels", c.type.channels);
            m.put("color", c.color.getSerializedName());
            m.put("on", c.on);
            m.put("value", c.value);
            m.put("x", c.x);
            m.put("y", c.y);
            m.put("buttons", c.buttons);
            m.put("held", panel.held(i));
            if (c.type.display()) {
                int[] r = panel.pixels(c);
                m.put("px", r[0]);
                m.put("py", r[1]);
                m.put("pw", r[2]);
                m.put("ph", r[3]);
                m.put("mode", c.mode == PanelControl.MODE_INSTRUMENTS ? "instruments" : "canvas");
            }
            out.add(m);
        }
        return out;
    }

    /** Grid size (4 or 8) and tilt in degrees. */
    @LuaFunction(mainThread = true)
    public final Object[] getLayout() {
        return new Object[]{panel.grid(), panel.tilt()};
    }
}
