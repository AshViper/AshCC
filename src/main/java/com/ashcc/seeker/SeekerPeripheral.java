package com.ashcc.seeker;

import com.ashcc.AshCC;
import com.ashcc.AshCCConfig;
import com.ashcc.compat.SableCompat;
import com.ashcc.decoy.DecoyEntity;
import com.ashcc.decoy.Decoys;
import com.ashcc.iff.Transponders;
import com.ashcc.radar.Contact;
import com.ashcc.radar.RadarFrame;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lua API of the seeker (peripheral type "seeker"): one function,
 * {@code seek()}, {@code seek(angle)} or {@code seek(angle, distance)}.
 * Returns the Sable sub-level closest to the line of sight inside the cone, or nil.
 * Burning flares are seen as ships too, and win over a real ship slightly closer to the centre.
 */
public class SeekerPeripheral implements IPeripheral {
    private final SeekerBlockEntity seeker;

    SeekerPeripheral(SeekerBlockEntity seeker) {
        this.seeker = seeker;
    }

    @Override
    public String getType() {
        return "seeker";
    }

    @Override
    public Object getTarget() {
        return seeker;
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof SeekerPeripheral o && o.seeker == seeker;
    }

    /**
     * seek([angle], [distance]).
     * angle: half-angle of the cone from the line of sight in degrees (default seeker.defaultAngle).
     * distance: maximum distance to the target's centre of mass (default seeker.defaultDistance).
     * Result: the radar's sub-level table plus offAxis (degrees from the line of sight).
     */
    @LuaFunction(mainThread = true)
    public final @Nullable Map<String, Object> seek(IArguments args) throws LuaException {
        double angle = args.optFiniteDouble(0).orElse(AshCCConfig.SEEKER_DEFAULT_ANGLE.get());
        double distance = args.optFiniteDouble(1).orElse(AshCCConfig.SEEKER_DEFAULT_DISTANCE.get());
        if (angle < 0 || angle > 180) throw new LuaException("angle must be between 0 and 180");
        if (distance < 0) throw new LuaException("distance must not be negative");
        Level level = seeker.getLevel();
        if (level == null) return null;

        RadarFrame f = seeker.frame();
        List<Contact> candidates = new ArrayList<>();
        if (AshCC.sableLoaded) {
            candidates.addAll(SableCompat.scan(level, f.origin(), distance, f.subLevel()));
            Transponders.annotate(level, candidates);
        }
        Map<Contact, Double> heat = new IdentityHashMap<>();
        double bias = AshCCConfig.SEEKER_FLARE_BIAS.get();
        for (DecoyEntity flare : level.getEntities(AshCC.DECOY.get(), new AABB(f.origin(), f.origin()).inflate(distance), e -> e.isAlive() && e.isFlare())) {
            Contact c = Decoys.contact(flare, f.origin());
            candidates.add(c);
            heat.put(c, flare.heat());
        }

        Contact best = null;
        double bestScore = Double.MAX_VALUE;
        double bestOff = 0;
        double bestLen = 0;
        for (Contact c : candidates) {
            Vec3 d = c.position().subtract(f.origin());
            double len = d.length();
            double cos = len < 1e-9 ? 1 : d.dot(f.forward()) / len;
            double off = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
            if (len > distance || off > angle) continue;
            // Closest to the centre wins, a burning flare counts as up to flareBias degrees closer; on a tie the nearer one
            double score = off - bias * heat.getOrDefault(c, 0.0);
            if (best == null || score < bestScore - 1e-7 || (score < bestScore + 1e-7 && len < bestLen)) {
                best = c;
                bestScore = score;
                bestOff = off;
                bestLen = len;
            }
        }
        if (best == null) return null;
        Map<String, Object> t = best.toLua(f);
        t.put("offAxis", bestOff);
        return t;
    }
}
