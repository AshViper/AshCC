package com.ashcc.iff;

import com.ashcc.AshCC;
import com.ashcc.radar.Contact;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Loaded server-side transponders, and tagging of radar contacts with the IFF of their ship. */
public final class Transponders {
    private static final Set<TransponderBlockEntity> LOADED = Collections.newSetFromMap(new WeakHashMap<>());

    private Transponders() {
    }

    static void add(TransponderBlockEntity be) {
        LOADED.add(be);
    }

    static void remove(TransponderBlockEntity be) {
        LOADED.remove(be);
    }

    /** Transmitting transponders of a level by ship. With several on one ship the lowest block position wins. */
    public static Map<UUID, TransponderBlockEntity> byShip(Level level) {
        Map<UUID, TransponderBlockEntity> out = new HashMap<>();
        if (!AshCC.sableLoaded) return out;
        for (TransponderBlockEntity be : LOADED) {
            if (be.getLevel() != level || be.isRemoved() || !be.transmitting()) continue;
            UUID ship = be.subLevel();
            out.merge(ship, be, (a, b) -> a.getBlockPos().compareTo(b.getBlockPos()) <= 0 ? a : b);
        }
        return out;
    }

    /** Adds {@code iff = {code, callsign}} to the sub-level contacts whose ship carries a transmitting transponder. */
    public static void annotate(Level level, Collection<Contact> contacts) {
        if (contacts.isEmpty()) return;
        Map<UUID, TransponderBlockEntity> ships = byShip(level);
        if (ships.isEmpty()) return;
        for (Contact c : contacts) {
            if (!Contact.SUB_LEVEL.equals(c.kind())) continue;
            TransponderBlockEntity be = ships.get(c.id());
            if (be == null) continue;
            Map<String, Object> iff = new HashMap<>();
            iff.put("code", be.code());
            iff.put("callsign", be.callsign());
            c.extra().put("iff", iff);
        }
    }
}
