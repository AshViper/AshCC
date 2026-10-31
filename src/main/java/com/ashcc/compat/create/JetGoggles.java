package com.ashcc.compat.create;

import com.ashcc.AshCCConfig;
import com.ashcc.jet.JetEngineBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/** The jet engine's instruments in the Create engineer's goggles overlay. Only loaded with Create. */
final class JetGoggles {
    private static final String K = "ashcc.goggles.jet.";

    private JetGoggles() {
    }

    static boolean addTooltip(JetEngineBlockEntity e, List<Component> tooltip) {
        tooltip.add(Component.literal("    ").append(Component.translatable(K + (e.hasAfterburner() ? "title_ab" : "title"))));
        if (!e.formed()) {
            line(tooltip, Component.translatable(K + "incomplete", Component.translatable(K + "error." + e.structureError())), ChatFormatting.RED);
            return true;
        }

        ChatFormatting stateColor = switch (e.state()) {
            case RUNNING -> ChatFormatting.GREEN;
            case STARTING -> ChatFormatting.YELLOW;
            case FAILED -> ChatFormatting.RED;
            case OFF -> ChatFormatting.GRAY;
        };
        MutableComponent st = Component.translatable(K + "state." + e.state().name().toLowerCase(Locale.ROOT)).withStyle(stateColor);
        String reason = e.lastReason();
        if ((e.state() == JetEngineBlockEntity.EngineState.OFF || e.state() == JetEngineBlockEntity.EngineState.FAILED)
            && !reason.isEmpty() && !reason.equals("shutdown")) {
            st.append(Component.literal(" (").append(Component.translatable(K + "reason." + reason)).append(")").withStyle(ChatFormatting.GRAY));
        }
        line(tooltip, label("state").append(st), null);

        line(tooltip, label("speed").append(value(String.format("N1 %.0f%%  N2 %.0f%%", e.n1() * 100, e.n2() * 100))), null);

        double limit = AshCCConfig.JET_EGT_LIMIT.get();
        ChatFormatting egtColor = e.egt() > limit ? ChatFormatting.RED : e.egt() > limit - 50 ? ChatFormatting.GOLD : ChatFormatting.AQUA;
        line(tooltip, label("egt").append(Component.literal(String.format("%.0f / %.0f °C", e.egt(), limit)).withStyle(egtColor)), null);

        line(tooltip, label("thrust").append(value(String.format("%,.0f", e.thrust()))), null);

        MutableComponent thr = value(String.format("%.0f%%", e.displayedThrottle() * 100));
        if (e.hasAfterburner()) {
            thr.append(Component.literal("  AB ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.format("%.0f%%", e.afterburnerLevel() * 100))
                    .withStyle(e.afterburnerLevel() > 0.01 ? ChatFormatting.GOLD : ChatFormatting.AQUA));
        }
        if (e.redstoneControl()) thr.append(Component.translatable(K + "redstone", e.redstoneInput()).withStyle(ChatFormatting.DARK_RED));
        line(tooltip, label("throttle").append(thr), null);
        if (e.throttleLimit() < 1) {
            line(tooltip, label("limit").append(Component.literal(String.format("%.0f%%", e.throttleLimit() * 100)).withStyle(ChatFormatting.GOLD)), null);
        }

        if (AshCCConfig.JET_REQUIRE_FUEL.get()) {
            FluidStack fuel = e.fuel();
            MutableComponent f = fuel.isEmpty()
                ? Component.translatable(K + "empty").withStyle(ChatFormatting.RED)
                : fuel.getHoverName().copy().withStyle(ChatFormatting.GRAY)
                    .append(value(String.format(" %,d / %,d mB", fuel.getAmount(), e.fuelCapacity())));
            line(tooltip, label("fuel").append(f), null);
            if (e.lit()) line(tooltip, label("flow").append(value(String.format("%.1f mB/s", e.fuelFlow()))), null);
        }

        double h = e.health();
        ChatFormatting hc = h > 0.75 ? ChatFormatting.GREEN : h > 0.4 ? ChatFormatting.GOLD : ChatFormatting.RED;
        line(tooltip, label("health").append(Component.literal(String.format("%.0f%%", h * 100)).withStyle(hc)), null);

        for (String w : e.warnings()) {
            line(tooltip, Component.literal("⚠ ").append(Component.translatable(K + "warning." + w)), ChatFormatting.GOLD);
        }
        return true;
    }

    private static MutableComponent label(String key) {
        return Component.translatable(K + key).append(": ").withStyle(ChatFormatting.GRAY);
    }

    private static MutableComponent value(String s) {
        return Component.literal(s).withStyle(ChatFormatting.AQUA);
    }

    private static void line(List<Component> tooltip, Component c, @Nullable ChatFormatting color) {
        MutableComponent m = Component.literal("     ").append(c);
        tooltip.add(color == null ? m : m.withStyle(color));
    }
}
