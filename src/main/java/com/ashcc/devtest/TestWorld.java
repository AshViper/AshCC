package com.ashcc.devtest;

import com.ashcc.AshCC;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.Set;

/**
 * Dev-only (excluded from the jar). With {@code -Dashcc.testworld=<name>} the client skips the title screen:
 * it opens the save if it exists, otherwise creates a superflat creative world with cheats and sets it up for testing.
 */
@EventBusSubscriber(modid = AshCC.MOD_ID, value = Dist.CLIENT)
public final class TestWorld {
    private static final String WORLD = System.getProperty("ashcc.testworld");
    private static boolean opened;
    private static boolean created;

    private TestWorld() {
    }

    @SubscribeEvent
    public static void onTitle(ScreenEvent.Init.Post event) {
        if (WORLD == null || opened || !(event.getScreen() instanceof TitleScreen title)) return;
        opened = true;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getLevelSource().levelExists(WORLD)) {
            AshCC.LOGGER.info("TestWorld: opening {}", WORLD);
            mc.createWorldOpenFlows().openWorld(WORLD, () -> mc.setScreen(title));
            return;
        }
        AshCC.LOGGER.info("TestWorld: creating {}", WORLD);
        created = true;
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(0, false, false),
            registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
            title);
    }

    /** First join of a freshly created world: daytime, clear weather and the test items. */
    @SubscribeEvent
    public static void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        MinecraftServer server = event.getEntity().getServer();
        if (server == null) return;
        giveMissing(server, event.getEntity());
        if (!created) return;
        created = false;
        String[] commands = {
            "time set noon",
            "weather clear",
            "give @p ashcc:radar 4",
            "give @p ashcc:seeker 4",
            "give @p ashcc:thruster 16",
            "give @p ashcc:link_hub 2",
            "give @p ashcc:link_card 2",
            "give @p ashcc:flap_bearing 8",
            "give @p ashcc:quad_flap_bearing 4",
            "give @p ashcc:laser 4",
            "give @p computercraft:computer_advanced 2",
            "give @p computercraft:monitor_advanced 8",
            "give @p computercraft:wired_modem_full 8",
            "give @p computercraft:cable 64",
        };
        for (String cmd : commands) {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd);
        }
    }

    /** Items added after the test world was made: given on every join to a player who has none of them. */
    private static final String[][] LATER_KIT = {
        {"ashcc:transponder", "4"},
        {"ashcc:decoy_launcher", "4"},
        {"ashcc:flare", "64"},
        {"ashcc:chaff", "64"},
    };

    private static void giveMissing(MinecraftServer server, Player player) {
        for (String[] kit : LATER_KIT) {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(kit[0]));
            if (player.getInventory().hasAnyOf(Set.of(item))) continue;
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                "give " + player.getGameProfile().getName() + " " + kit[0] + " " + kit[1]);
        }
        // Name tags for setting IFF codes by hand
        if (!player.getInventory().hasAnyOf(Set.of(Items.NAME_TAG))) {
            for (String code : new String[]{"BLUE", "RED"}) {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                    "give " + player.getGameProfile().getName() + " name_tag[custom_name='\"" + code + "\"'] 1");
            }
        }
    }
}
