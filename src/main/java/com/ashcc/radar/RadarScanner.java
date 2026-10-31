package com.ashcc.radar;

import com.ashcc.AshCC;
import com.ashcc.compat.SableCompat;
import com.ashcc.decoy.DecoyEntity;
import com.ashcc.decoy.Decoys;
import com.ashcc.iff.Transponders;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public final class RadarScanner {
    private RadarScanner() {
    }

    public static List<Contact> scanEntities(ServerLevel level, RadarFrame frame, double range, Predicate<Entity> filter, int limit) {
        Vec3 o = frame.origin();
        double r2 = range * range;
        List<Contact> out = new ArrayList<>();
        // Decoys only show up as echoes (chaff in scanSubLevels, flares on seekers)
        for (Entity e : level.getEntities((Entity) null, new AABB(o, o).inflate(range), e -> e.isAlive() && !e.isSpectator() && !(e instanceof DecoyEntity))) {
            if (e.getBoundingBox().getCenter().distanceToSqr(o) > r2 || !filter.test(e)) continue;
            out.add(entityContact(e, o));
        }
        return sortAndLimit(out, limit);
    }

    /** Sable sub-levels (tagged with their IFF) plus chaff echoes, which look the same. */
    public static List<Contact> scanSubLevels(ServerLevel level, RadarFrame frame, double range, int limit) {
        List<Contact> out = new ArrayList<>();
        if (AshCC.sableLoaded) {
            out.addAll(SableCompat.scan(level, frame.origin(), range, frame.subLevel()));
            Transponders.annotate(level, out);
        }
        out.addAll(Decoys.echoes(level, frame.origin(), range, DecoyEntity.CHAFF));
        return sortAndLimit(out, limit);
    }

    /** Looks up one object by UUID (entity first, then sub-level). Returns null when out of range. */
    public static @Nullable Contact find(ServerLevel level, RadarFrame frame, double range, UUID id) {
        Entity e = level.getEntity(id);
        if (e instanceof DecoyEntity decoy) {
            // A flare is invisible to the radar
            if (!decoy.isAlive() || decoy.isFlare()) return null;
            Contact c = Decoys.contact(decoy, frame.origin());
            return c.distance() <= range ? c : null;
        }
        if (e != null && e.isAlive()) {
            Contact c = entityContact(e, frame.origin());
            return c.distance() <= range ? c : null;
        }
        if (!AshCC.sableLoaded) return null;
        Contact c = SableCompat.find(level, frame.origin(), range, id);
        if (c != null) Transponders.annotate(level, List.of(c));
        return c;
    }

    private static List<Contact> sortAndLimit(List<Contact> list, int limit) {
        list.sort(Comparator.comparingDouble(Contact::distance));
        return list.size() > limit ? new ArrayList<>(list.subList(0, limit)) : list;
    }

    private static Contact entityContact(Entity e, Vec3 origin) {
        Vec3 pos = e.getBoundingBox().getCenter();
        // Position change over the current tick; works for players too, whose deltaMovement is not kept on the server
        Vec3 vel = new Vec3(e.getX() - e.xo, e.getY() - e.yo, e.getZ() - e.zo).scale(20);
        Map<String, Object> extra = new HashMap<>();
        extra.put("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
        extra.put("name", e.getName().getString());
        extra.put("isPlayer", e instanceof Player);
        extra.put("isLiving", e instanceof LivingEntity);
        extra.put("yaw", (double) e.getYRot());
        extra.put("pitch", (double) e.getXRot());
        extra.put("feet", LuaUtil.vec(e.position()));
        if (e instanceof LivingEntity living) {
            extra.put("health", (double) living.getHealth());
            extra.put("maxHealth", (double) living.getMaxHealth());
        }
        if (e.getVehicle() != null) extra.put("vehicle", e.getVehicle().getStringUUID());
        if (AshCC.sableLoaded) {
            UUID sub = SableCompat.entitySubLevel(e);
            if (sub != null) extra.put("subLevel", sub.toString());
        }
        return new Contact(Contact.ENTITY, e.getUUID(), pos, vel, pos.distanceTo(origin), extra);
    }

    /**
     * Builds an entity predicate from a Lua filter table:
     * {@code type} (id or list of ids), {@code player} (bool), {@code living} (bool), {@code name} (substring, case-insensitive).
     */
    public static Predicate<Entity> filter(@Nullable Map<?, ?> t) {
        if (t == null) return e -> true;
        Predicate<Entity> p = e -> true;
        Object type = t.get("type");
        if (type instanceof String s) {
            p = p.and(e -> typeId(e).equals(s));
        } else if (type instanceof Map<?, ?> list) {
            Set<Object> ids = Set.copyOf(list.values());
            p = p.and(e -> ids.contains(typeId(e)));
        } else if (type instanceof List<?> list) {
            Set<Object> ids = Set.copyOf(list);
            p = p.and(e -> ids.contains(typeId(e)));
        }
        if (t.get("player") instanceof Boolean b) p = p.and(e -> (e instanceof Player) == b);
        if (t.get("living") instanceof Boolean b) p = p.and(e -> (e instanceof LivingEntity) == b);
        if (t.get("name") instanceof String s) {
            String needle = s.toLowerCase();
            p = p.and(e -> e.getName().getString().toLowerCase().contains(needle));
        }
        return p;
    }

    private static String typeId(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }
}
