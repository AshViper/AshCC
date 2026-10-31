package com.ashcc.jet;

import com.ashcc.AshCCConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Which fluids burn in a jet engine and how much energy a mB holds (kerosene = 1), from the "fuels" config list. */
public final class JetFuels {
    private static List<? extends String> parsedFrom;
    private static final Map<Fluid, Double> CACHE = new HashMap<>();

    private JetFuels() {
    }

    /** Energy per mB, or 0 when the fluid is not a fuel. */
    public static synchronized double value(FluidStack stack) {
        if (stack.isEmpty()) return 0;
        List<? extends String> list = AshCCConfig.JET_FUELS.get();
        if (list != parsedFrom) {
            CACHE.clear();
            parsedFrom = list;
        }
        return CACHE.computeIfAbsent(stack.getFluid(), f -> lookup(stack, list));
    }

    public static boolean isFuel(FluidStack stack) {
        return value(stack) > 0;
    }

    private static double lookup(FluidStack stack, List<? extends String> list) {
        ResourceLocation id = BuiltInRegistries.FLUID.getKey(stack.getFluid());
        for (String entry : list) {
            int eq = entry.lastIndexOf('=');
            if (eq < 0) continue;
            String key = entry.substring(0, eq).trim();
            double v;
            try {
                v = Double.parseDouble(entry.substring(eq + 1).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            boolean match;
            if (key.startsWith("#")) {
                ResourceLocation tag = ResourceLocation.tryParse(key.substring(1));
                match = tag != null && stack.is(TagKey.create(Registries.FLUID, tag));
            } else {
                match = key.equals(id.toString());
            }
            if (match) return Math.max(0, v);
        }
        return 0.0;
    }
}
