package com.ashcc.link;

import dan200.computercraft.api.filesystem.Mount;
import dan200.computercraft.api.filesystem.WritableMount;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.ILuaContext;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.NotAttachedException;
import dan200.computercraft.api.peripheral.WorkMonitor;
import dan200.computercraft.core.computer.GuardedLuaContext;
import dan200.computercraft.core.methods.PeripheralMethod;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One linked peripheral as seen by one computer. Mirrors CC's wired-modem RemotePeripheralWrapper:
 * the peripheral is attached with this object as its computer, so its events carry the link name.
 */
final class RemotePeripheral implements IComputerAccess, GuardedLuaContext.Guard {
    private final LinkHubPeripheral hub;
    final IPeripheral peripheral;
    private final IComputerAccess computer;
    private final String name;
    private final Map<String, PeripheralMethod> methods;
    private final Set<String> mounts = new HashSet<>();
    private volatile boolean attached;
    private @Nullable GuardedLuaContext context;

    RemotePeripheral(LinkHubPeripheral hub, IPeripheral peripheral, IComputerAccess computer, String name, Map<String, PeripheralMethod> methods) {
        this.hub = hub;
        this.peripheral = peripheral;
        this.computer = computer;
        this.name = name;
        this.methods = methods;
    }

    void attach() {
        attached = true;
        peripheral.attach(this);
        computer.queueEvent("peripheral", name);
    }

    void detach() {
        peripheral.detach(this);
        attached = false;
        synchronized (this) {
            for (String mount : mounts) computer.unmount(mount);
            mounts.clear();
        }
        computer.queueEvent("peripheral_detach", name);
    }

    String type() {
        return peripheral.getType();
    }

    Set<String> additionalTypes() {
        return peripheral.getAdditionalTypes();
    }

    Collection<String> methodNames() {
        return methods.keySet();
    }

    MethodResult call(ILuaContext ctx, String method, IArguments args) throws LuaException {
        PeripheralMethod m = methods.get(method);
        if (m == null) throw new LuaException("No such method " + method);
        GuardedLuaContext guarded = context;
        if (guarded == null || !guarded.wraps(ctx)) context = guarded = new GuardedLuaContext(ctx, this);
        return m.apply(peripheral, guarded, this, args);
    }

    @Override
    public boolean checkValid() {
        return attached;
    }

    // ---- IComputerAccess, forwarded to the real computer ----

    @Override
    public synchronized @Nullable String mount(String desiredLocation, Mount mount, String driveName) {
        if (!attached) throw new NotAttachedException();
        String location = computer.mount(desiredLocation, mount, driveName);
        if (location != null) mounts.add(location);
        return location;
    }

    @Override
    public synchronized @Nullable String mountWritable(String desiredLocation, WritableMount mount, String driveName) {
        if (!attached) throw new NotAttachedException();
        String location = computer.mountWritable(desiredLocation, mount, driveName);
        if (location != null) mounts.add(location);
        return location;
    }

    @Override
    public synchronized void unmount(@Nullable String location) {
        if (!attached) throw new NotAttachedException();
        computer.unmount(location);
        mounts.remove(location);
    }

    @Override
    public int getID() {
        if (!attached) throw new NotAttachedException();
        return computer.getID();
    }

    @Override
    public void queueEvent(String event, @Nullable Object... arguments) {
        if (!attached) throw new NotAttachedException();
        computer.queueEvent(event, arguments);
    }

    @Override
    public WorkMonitor getMainThreadMonitor() {
        if (!attached) throw new NotAttachedException();
        return computer.getMainThreadMonitor();
    }

    @Override
    public String getAttachmentName() {
        if (!attached) throw new NotAttachedException();
        return name;
    }

    @Override
    public Map<String, IPeripheral> getAvailablePeripherals() {
        if (!attached) throw new NotAttachedException();
        return hub.peripheralsFor(computer);
    }

    @Override
    public @Nullable IPeripheral getAvailablePeripheral(String name) {
        if (!attached) throw new NotAttachedException();
        return hub.peripheralsFor(computer).get(name);
    }
}
