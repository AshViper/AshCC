package com.ashcc.gametest;

import com.defpwoq.createarms.block.AbstractShipMissileBlockEntity;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

/** Calls peripheral methods by their Lua name, roughly like CC does, and finds createarms missiles. */
final class CreateArmsTestAccess {
    private CreateArmsTestAccess() {
    }

    static Object[] call(IPeripheral p, String name, IArguments args) throws LuaException {
        for (Method m : p.getClass().getMethods()) {
            if (!m.getName().equals(name) || !m.isAnnotationPresent(LuaFunction.class)) continue;
            m.setAccessible(true);
            Class<?>[] types = m.getParameterTypes();
            Object[] values = new Object[types.length];
            if (types.length == 1 && types[0] == IArguments.class) {
                values[0] = args;
            } else {
                for (int i = 0; i < types.length; i++) {
                    if (types[i] == String.class) values[i] = args.getString(i);
                    else if (types[i] == int.class) values[i] = args.getInt(i);
                    else if (types[i] == double.class) values[i] = args.getDouble(i);
                    else if (types[i] == boolean.class) values[i] = args.getBoolean(i);
                    else throw new IllegalArgumentException("unsupported parameter " + types[i]);
                }
            }
            try {
                Object r = m.invoke(p, values);
                if (r instanceof MethodResult result) return result.getResult();
                return m.getReturnType() == void.class ? new Object[0] : new Object[]{r};
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof LuaException lua) throw lua;
                throw new IllegalStateException(e.getCause());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalArgumentException("no Lua method " + name + " on " + p.getType());
    }

    static @Nullable AbstractShipMissileBlockEntity shipMissile(ServerLevel level, UUID id) {
        if (SubLevelContainer.getContainer(level).getSubLevel(id) instanceof ServerSubLevel sub && !sub.isRemoved()
            && sub.getLevel().getBlockEntity(sub.getPlot().getCenterBlock()) instanceof AbstractShipMissileBlockEntity missile) {
            return missile;
        }
        return null;
    }

    static boolean isOwned(ServerLevel level, UUID id) {
        AbstractShipMissileBlockEntity missile = shipMissile(level, id);
        return missile != null && !missile.getPersistentData().getString("ashcc:launcher").isEmpty();
    }
}
