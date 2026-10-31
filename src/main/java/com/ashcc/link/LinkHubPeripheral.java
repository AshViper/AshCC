package com.ashcc.link;

import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.ILuaContext;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.core.methods.PeripheralMethod;
import dan200.computercraft.shared.computer.core.ServerContext;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Peripheral of the link hub. Its additional type "peripheral_hub" makes CC's peripheral API treat it like a wired
 * modem: linked peripherals show up in peripheral.getNames() / wrap() / find() under their link names.
 */
public class LinkHubPeripheral implements IPeripheral {
    private final LinkHubBlockEntity hub;
    /** Linked peripherals that are currently loaded, by link name. Written on the server thread. */
    private final Map<String, IPeripheral> available = new HashMap<>();
    private final Map<IComputerAccess, ConcurrentMap<String, RemotePeripheral>> wrappers = new HashMap<>();

    LinkHubPeripheral(LinkHubBlockEntity hub) {
        this.hub = hub;
    }

    @Override
    public String getType() {
        return "link_hub";
    }

    @Override
    public Set<String> getAdditionalTypes() {
        return Set.of("peripheral_hub");
    }

    @Override
    public Object getTarget() {
        return hub;
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof LinkHubPeripheral o && o.hub == hub;
    }

    // ---- Computers ----

    @Override
    public synchronized void attach(IComputerAccess computer) {
        ConcurrentMap<String, RemotePeripheral> map = new ConcurrentHashMap<>();
        wrappers.put(computer, map);
        available.forEach((name, p) -> attachTo(computer, map, name, p));
    }

    @Override
    public synchronized void detach(IComputerAccess computer) {
        ConcurrentMap<String, RemotePeripheral> map = wrappers.remove(computer);
        if (map == null) return;
        map.values().forEach(RemotePeripheral::detach);
        map.clear();
    }

    // ---- Linked peripherals (server thread) ----

    synchronized void attachRemote(String name, IPeripheral peripheral) {
        if (available.get(name) instanceof IPeripheral old && old.equals(peripheral)) return;
        detachRemote(name);
        available.put(name, peripheral);
        wrappers.forEach((computer, map) -> attachTo(computer, map, name, peripheral));
    }

    synchronized void detachRemote(String name) {
        if (available.remove(name) == null) return;
        for (ConcurrentMap<String, RemotePeripheral> map : wrappers.values()) {
            RemotePeripheral w = map.remove(name);
            if (w != null) w.detach();
        }
    }

    synchronized boolean isAvailable(String name) {
        return available.containsKey(name);
    }

    private void attachTo(IComputerAccess computer, ConcurrentMap<String, RemotePeripheral> map, String name, IPeripheral peripheral) {
        MinecraftServer server = hub.getLevel() == null ? null : hub.getLevel().getServer();
        if (server == null) return;
        Map<String, PeripheralMethod> methods = ServerContext.get(server).peripheralMethods().getSelfMethods(peripheral);
        RemotePeripheral w = new RemotePeripheral(this, peripheral, computer, name, methods);
        map.put(name, w);
        w.attach();
    }

    synchronized Map<String, IPeripheral> peripheralsFor(IComputerAccess computer) {
        ConcurrentMap<String, RemotePeripheral> map = wrappers.get(computer);
        Map<String, IPeripheral> out = new HashMap<>();
        if (map != null) map.forEach((name, w) -> out.put(name, w.peripheral));
        return out;
    }

    private @Nullable RemotePeripheral wrapper(IComputerAccess computer, String name) {
        ConcurrentMap<String, RemotePeripheral> map;
        synchronized (this) {
            map = wrappers.get(computer);
        }
        return map == null ? null : map.get(name);
    }

    // ---- peripheral_hub protocol (used by CC's peripheral API) ----

    @LuaFunction
    public final List<String> getNamesRemote(IComputerAccess computer) {
        ConcurrentMap<String, RemotePeripheral> map;
        synchronized (this) {
            map = wrappers.get(computer);
        }
        return map == null ? List.of() : new ArrayList<>(map.keySet());
    }

    @LuaFunction
    public final boolean isPresentRemote(IComputerAccess computer, String name) {
        return wrapper(computer, name) != null;
    }

    @LuaFunction
    public final Object @Nullable [] getTypeRemote(IComputerAccess computer, String name) {
        RemotePeripheral w = wrapper(computer, name);
        if (w == null) return null;
        List<Object> types = new ArrayList<>();
        types.add(w.type());
        types.addAll(w.additionalTypes());
        return types.toArray();
    }

    @LuaFunction
    public final Object @Nullable [] hasTypeRemote(IComputerAccess computer, String name, String type) {
        RemotePeripheral w = wrapper(computer, name);
        if (w == null) return null;
        return new Object[]{ w.type().equals(type) || w.additionalTypes().contains(type) };
    }

    @LuaFunction
    public final Object @Nullable [] getMethodsRemote(IComputerAccess computer, String name) {
        RemotePeripheral w = wrapper(computer, name);
        return w == null ? null : new Object[]{ new ArrayList<>(w.methodNames()) };
    }

    @LuaFunction
    public final MethodResult callRemote(IComputerAccess computer, ILuaContext context, IArguments args) throws LuaException {
        String name = args.getString(0);
        String method = args.getString(1);
        RemotePeripheral w = wrapper(computer, name);
        if (w == null) throw new LuaException("No peripheral: " + name);
        return w.call(context, method, args.drop(2));
    }

    // ---- Link management ----

    /** { {name, type, dimension, x, y, z, side, connected}, ... } */
    @LuaFunction(mainThread = true)
    public final List<Map<String, Object>> getLinks() {
        return hub.linksInfo();
    }

    @LuaFunction(mainThread = true)
    public final boolean removeLink(String name) {
        return hub.removeLink(name);
    }

    @LuaFunction(mainThread = true)
    public final boolean renameLink(String name, String newName) throws LuaException {
        if (newName.isEmpty() || newName.contains(" ")) throw new LuaException("Invalid name: " + newName);
        return hub.renameLink(name, newName);
    }
}
