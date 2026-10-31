package com.ashcc;

import com.ashcc.antigrav.AntigravBlock;
import com.ashcc.antigrav.AntigravBlockEntity;
import com.ashcc.compat.SableAntigravBlockEntity;
import com.ashcc.compat.SableThrusterBlockEntity;
import com.ashcc.compat.create.CreateCompat;
import com.ashcc.compat.create.GoggleJetEngineBlockEntity;
import com.ashcc.compat.create.GoggleJetPartBlock;
import com.ashcc.compat.create.SableGoggleJetEngineBlockEntity;
import com.ashcc.compat.cbc.CbcCompat;
import com.ashcc.compat.createarms.CreateArmsCompat;
import com.ashcc.compat.simulated.SimulatedCompat;
import com.ashcc.decoy.DecoyClient;
import com.ashcc.panel.ControlPanelBlock;
import com.ashcc.panel.ControlPanelBlockEntity;
import com.ashcc.panel.PanelNetwork;
import com.ashcc.panel.client.PanelClient;
import com.ashcc.decoy.DecoyEntity;
import com.ashcc.decoy.DecoyLauncherBlock;
import com.ashcc.decoy.DecoyLauncherBlockEntity;
import com.ashcc.compat.SableJetEngineBlockEntity;
import com.ashcc.jet.JetEngineBlockEntity;
import com.ashcc.jet.JetNozzleBlock;
import com.ashcc.jet.JetPart;
import com.ashcc.jet.JetPartBlock;
import com.ashcc.jet.client.JetEngineRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import com.ashcc.hose.HoseItem;
import com.ashcc.hose.HoseNetwork;
import com.ashcc.hose.HoseStart;
import com.ashcc.hose.client.HoseRenderer;
import com.ashcc.iff.TransponderBlock;
import com.ashcc.imu.ComputerImuApi;
import com.ashcc.iff.TransponderBlockEntity;
import com.ashcc.laser.LaserBlock;
import com.ashcc.laser.LaserBlockEntity;
import com.ashcc.laser.LaserRenderer;
import com.ashcc.link.LinkCardItem;
import com.ashcc.link.LinkHubBlock;
import com.ashcc.link.LinkHubBlockEntity;
import com.ashcc.link.LinkTarget;
import com.ashcc.monitor.HdMonitorBlock;
import com.ashcc.monitor.HdMonitorBlockEntity;
import com.ashcc.monitor.MonitorNetwork;
import com.ashcc.monitor.client.MonitorClient;
import com.ashcc.radar.RadarBlock;
import com.ashcc.radar.RadarBlockEntity;
import com.ashcc.seeker.SeekerBlock;
import com.ashcc.seeker.SeekerBlockEntity;
import com.ashcc.thruster.ThrusterBlock;
import com.ashcc.thruster.ThrusterBlockEntity;
import com.ashcc.tracker.HeadTrackerItem;
import com.ashcc.tracker.TrackedPlayer;
import com.mojang.logging.LogUtils;
import dan200.computercraft.api.ComputerCraftAPI;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

import java.util.List;

@Mod(AshCC.MOD_ID)
public final class AshCC {
    public static final String MOD_ID = "ashcc";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** Sable classes may only be touched when this is true. */
    public static boolean sableLoaded;
    /** Create is installed: the jet engine is readable with the engineer's goggles. */
    public static boolean createLoaded;
    /** Drive By Wire: the control panel is a cable source. */
    public static boolean driveByWireLoaded;

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);

    public static final DeferredBlock<RadarBlock> RADAR = BLOCKS.register("radar", () -> new RadarBlock(
        BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f, 6.0f).sound(SoundType.METAL).requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> RADAR_ITEM = ITEMS.registerSimpleBlockItem(RADAR, new Item.Properties());
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RadarBlockEntity>> RADAR_BE = BLOCK_ENTITIES.register("radar",
        () -> BlockEntityType.Builder.of(RadarBlockEntity::new, RADAR.get()).build(null));

    public static final DeferredBlock<ThrusterBlock> THRUSTER = BLOCKS.register("thruster", () -> new ThrusterBlock(
        BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f, 6.0f).sound(SoundType.METAL).requiresCorrectToolForDrops()
            .lightLevel(state -> state.getValue(ThrusterBlock.ACTIVE) ? 10 : 0)));
    public static final DeferredItem<BlockItem> THRUSTER_ITEM = ITEMS.registerSimpleBlockItem(THRUSTER, new Item.Properties());
    /** With Sable the thruster's block entity is a Sable actor that pushes the ship. */
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ThrusterBlockEntity>> THRUSTER_BE = BLOCK_ENTITIES.register("thruster",
        () -> BlockEntityType.Builder.<ThrusterBlockEntity>of(
            (pos, state) -> sableLoaded ? SableThrusterBlockEntity.create(pos, state) : new ThrusterBlockEntity(pos, state),
            THRUSTER.get()).build(null));

    public static final DeferredBlock<AntigravBlock> ANTIGRAV = BLOCKS.register("antigravity_core", () -> new AntigravBlock(
        BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(3.5f, 6.0f).sound(SoundType.METAL).requiresCorrectToolForDrops()
            .lightLevel(state -> state.getValue(AntigravBlock.ACTIVE) ? 7 : 0)));
    public static final DeferredItem<BlockItem> ANTIGRAV_ITEM = ITEMS.registerSimpleBlockItem(ANTIGRAV, new Item.Properties());
    /** With Sable the core's block entity is a Sable actor that cancels gravity and steers the ship. */
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AntigravBlockEntity>> ANTIGRAV_BE = BLOCK_ENTITIES.register("antigravity_core",
        () -> BlockEntityType.Builder.<AntigravBlockEntity>of(
            (pos, state) -> sableLoaded ? SableAntigravBlockEntity.create(pos, state) : new AntigravBlockEntity(pos, state),
            ANTIGRAV.get()).build(null));

    public static final DeferredBlock<HdMonitorBlock> HD_MONITOR = BLOCKS.register("hd_monitor", () -> new HdMonitorBlock(
        BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(2.0f, 6.0f).sound(SoundType.METAL).requiresCorrectToolForDrops()
            .noOcclusion().isViewBlocking((state, level, pos) -> false)));
    public static final DeferredItem<BlockItem> HD_MONITOR_ITEM = ITEMS.registerSimpleBlockItem(HD_MONITOR, new Item.Properties());
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<HdMonitorBlockEntity>> HD_MONITOR_BE = BLOCK_ENTITIES.register("hd_monitor",
        () -> BlockEntityType.Builder.of(HdMonitorBlockEntity::new, HD_MONITOR.get()).build(null));

    public static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MOD_ID);
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<List<LinkTarget>>> LINK_TARGETS = COMPONENTS.registerComponentType("link_targets",
        b -> b.persistent(LinkTarget.LIST_CODEC).networkSynchronized(LinkTarget.LIST_STREAM_CODEC));

    public static final DeferredBlock<LinkHubBlock> LINK_HUB = BLOCKS.register("link_hub", () -> new LinkHubBlock(
        BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f, 6.0f).sound(SoundType.METAL).requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> LINK_HUB_ITEM = ITEMS.registerSimpleBlockItem(LINK_HUB, new Item.Properties());
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LinkHubBlockEntity>> LINK_HUB_BE = BLOCK_ENTITIES.register("link_hub",
        () -> BlockEntityType.Builder.of(LinkHubBlockEntity::new, LINK_HUB.get()).build(null));
    public static final DeferredItem<com.ashcc.connector.ConnectorItem> CONNECTOR_ITEM = ITEMS.register("offset_connector",
        () -> new com.ashcc.connector.ConnectorItem(new Item.Properties()));
    public static final DeferredHolder<EntityType<?>, EntityType<com.ashcc.connector.ConnectorEntity>> CONNECTOR = ENTITIES.register("offset_connector",
        () -> EntityType.Builder.<com.ashcc.connector.ConnectorEntity>of(com.ashcc.connector.ConnectorEntity::new, MobCategory.MISC).sized(1f, 0.0625f)
            .clientTrackingRange(10).updateInterval(Integer.MAX_VALUE).build("offset_connector"));
    public static final DeferredItem<LinkCardItem> LINK_CARD = ITEMS.register("link_card", () -> new LinkCardItem(new Item.Properties()));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<TrackedPlayer>> TRACKED_PLAYER = COMPONENTS.registerComponentType("tracked_player",
        b -> b.persistent(TrackedPlayer.CODEC).networkSynchronized(TrackedPlayer.STREAM_CODEC));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<HoseStart>> HOSE_START = COMPONENTS.registerComponentType("hose_start",
        b -> b.persistent(HoseStart.CODEC).networkSynchronized(HoseStart.STREAM_CODEC));
    public static final DeferredItem<HoseItem> HOSE = ITEMS.register("hose", () -> new HoseItem(new Item.Properties().stacksTo(64)));
    public static final DeferredItem<HoseItem> PIPE = ITEMS.register("fluid_pipe", () -> new HoseItem(new Item.Properties().stacksTo(64), true));

    public static final DeferredItem<HeadTrackerItem> HEAD_TRACKER = ITEMS.register("head_tracker", () -> new HeadTrackerItem(new Item.Properties()));

    public static final DeferredBlock<SeekerBlock> SEEKER = BLOCKS.register("seeker", () -> new SeekerBlock(
        BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f, 6.0f).sound(SoundType.METAL).requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> SEEKER_ITEM = ITEMS.registerSimpleBlockItem(SEEKER, new Item.Properties());
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SeekerBlockEntity>> SEEKER_BE = BLOCK_ENTITIES.register("seeker",
        () -> BlockEntityType.Builder.of(SeekerBlockEntity::new, SEEKER.get()).build(null));

    public static final DeferredBlock<LaserBlock> LASER = BLOCKS.register("laser", () -> new LaserBlock(
        BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f, 6.0f).sound(SoundType.METAL).requiresCorrectToolForDrops()
            .lightLevel(state -> state.getValue(LaserBlock.ACTIVE) ? 12 : 0)));
    public static final DeferredItem<BlockItem> LASER_ITEM = ITEMS.registerSimpleBlockItem(LASER, new Item.Properties());
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LaserBlockEntity>> LASER_BE = BLOCK_ENTITIES.register("laser",
        () -> BlockEntityType.Builder.of(LaserBlockEntity::new, LASER.get()).build(null));

    public static final DeferredBlock<TransponderBlock> TRANSPONDER = BLOCKS.register("transponder", () -> new TransponderBlock(
        BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f, 6.0f).sound(SoundType.METAL).requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> TRANSPONDER_ITEM = ITEMS.registerSimpleBlockItem(TRANSPONDER, new Item.Properties());
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TransponderBlockEntity>> TRANSPONDER_BE = BLOCK_ENTITIES.register("transponder",
        () -> BlockEntityType.Builder.of(TransponderBlockEntity::new, TRANSPONDER.get()).build(null));

    public static final DeferredBlock<DecoyLauncherBlock> DECOY_LAUNCHER = BLOCKS.register("decoy_launcher", () -> new DecoyLauncherBlock(
        BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f, 6.0f).sound(SoundType.METAL).requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> DECOY_LAUNCHER_ITEM = ITEMS.registerSimpleBlockItem(DECOY_LAUNCHER, new Item.Properties());
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DecoyLauncherBlockEntity>> DECOY_LAUNCHER_BE = BLOCK_ENTITIES.register("decoy_launcher",
        () -> BlockEntityType.Builder.of(DecoyLauncherBlockEntity::new, DECOY_LAUNCHER.get()).build(null));
    public static final DeferredItem<Item> FLARE = ITEMS.registerSimpleItem("flare");
    public static final DeferredItem<Item> CHAFF = ITEMS.registerSimpleItem("chaff");
    public static final DeferredHolder<EntityType<?>, EntityType<DecoyEntity>> DECOY = ENTITIES.register("decoy",
        () -> EntityType.Builder.<DecoyEntity>of(DecoyEntity::new, MobCategory.MISC).sized(0.25f, 0.25f).clientTrackingRange(16).updateInterval(2)
            .noSave().build("decoy"));

    // Control panel: buttons, toggles, knobs, sliders and sticks set up on a 4x4 grid; a cable source with Drive By Wire
    public static final DeferredBlock<ControlPanelBlock> CONTROL_PANEL = BLOCKS.register("control_panel", () -> {
        BlockBehaviour.Properties props = BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).strength(1.5f, 6.0f).sound(SoundType.METAL).noOcclusion();
        return driveByWireLoaded ? new com.ashcc.compat.drivebywire.WiredControlPanelBlock(props) : new ControlPanelBlock(props);
    });
    public static final DeferredItem<BlockItem> CONTROL_PANEL_ITEM = ITEMS.registerSimpleBlockItem(CONTROL_PANEL, new Item.Properties());
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ControlPanelBlockEntity>> CONTROL_PANEL_BE = BLOCK_ENTITIES.register("control_panel",
        () -> BlockEntityType.Builder.of(ControlPanelBlockEntity::new, CONTROL_PANEL.get()).build(null));

    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, MOD_ID);
    public static final DeferredHolder<SoundEvent, SoundEvent> JET_CORE_SOUND = SOUNDS.register("jet_engine.core",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MOD_ID, "jet_engine.core")));
    public static final DeferredHolder<SoundEvent, SoundEvent> JET_AB_SOUND = SOUNDS.register("jet_engine.afterburner",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MOD_ID, "jet_engine.afterburner")));

    // Jet engine: intake, core, nozzle in a line. The nozzle holds the engine.
    public static final DeferredBlock<JetPartBlock> JET_INTAKE = jetPart(JetPart.INTAKE);
    public static final DeferredBlock<JetPartBlock> JET_CORE = jetPart(JetPart.CORE);
    public static final DeferredBlock<JetNozzleBlock> JET_NOZZLE = jetNozzle(JetPart.NOZZLE);
    public static final DeferredBlock<JetNozzleBlock> JET_AFTERBURNER_NOZZLE = jetNozzle(JetPart.AFTERBURNER_NOZZLE);
    public static final List<DeferredItem<BlockItem>> JET_ITEMS = List.of(
        ITEMS.registerSimpleBlockItem(JET_INTAKE), ITEMS.registerSimpleBlockItem(JET_CORE),
        ITEMS.registerSimpleBlockItem(JET_NOZZLE), ITEMS.registerSimpleBlockItem(JET_AFTERBURNER_NOZZLE));
    /** With Sable the nozzle's block entity is a Sable actor that pushes the ship. */
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<JetEngineBlockEntity>> JET_ENGINE_BE = BLOCK_ENTITIES.register("jet_engine",
        () -> BlockEntityType.Builder.<JetEngineBlockEntity>of(
            (pos, state) -> createLoaded
                ? (sableLoaded ? new SableGoggleJetEngineBlockEntity(pos, state) : new GoggleJetEngineBlockEntity(pos, state))
                : (sableLoaded ? SableJetEngineBlockEntity.create(pos, state) : new JetEngineBlockEntity(pos, state)),
            JET_NOZZLE.get(), JET_AFTERBURNER_NOZZLE.get()).build(null));

    private static DeferredBlock<JetPartBlock> jetPart(JetPart part) {
        return BLOCKS.register("jet_" + part.partName(), () -> {
            BlockBehaviour.Properties props = BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5.0f, 8.0f)
                .sound(SoundType.NETHERITE_BLOCK).requiresCorrectToolForDrops().noOcclusion();
            return createLoaded ? new GoggleJetPartBlock(part, props) : new JetPartBlock(part, props);
        });
    }

    private static DeferredBlock<JetNozzleBlock> jetNozzle(JetPart part) {
        return BLOCKS.register("jet_" + part.partName(), () -> new JetNozzleBlock(part,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5.0f, 8.0f).sound(SoundType.NETHERITE_BLOCK).requiresCorrectToolForDrops()
                .noOcclusion().lightLevel(state -> state.getValue(JetNozzleBlock.GLOW) * 7)));
    }

    /** AshCC's own creative tab; the compat classes add their items to it when their mod is loaded. */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
        .title(Component.translatable("itemGroup.ashcc"))
        .icon(() -> RADAR_ITEM.get().getDefaultInstance())
        .displayItems((params, output) -> {
            output.accept(RADAR_ITEM);
            output.accept(CONNECTOR_ITEM);
            output.accept(SEEKER_ITEM);
            output.accept(LASER_ITEM);
            output.accept(TRANSPONDER_ITEM);
            output.accept(DECOY_LAUNCHER_ITEM);
            output.accept(FLARE);
            output.accept(CHAFF);
            output.accept(THRUSTER_ITEM);
            output.accept(ANTIGRAV_ITEM);
            output.accept(CONTROL_PANEL_ITEM);
            JET_ITEMS.forEach(output::accept);
            output.accept(HOSE);
            output.accept(PIPE);
            output.accept(LINK_HUB_ITEM);
            output.accept(LINK_CARD);
            output.accept(HEAD_TRACKER);
            output.accept(HD_MONITOR_ITEM);
        })
        .build());

    public AshCC(IEventBus modBus, ModContainer container) {
        sableLoaded = ModList.get().isLoaded("sable");
        createLoaded = ModList.get().isLoaded("create");
        driveByWireLoaded = ModList.get().isLoaded("drivebywire");
        LOGGER.info("AshCC: Sable integration {}", sableLoaded ? "enabled" : "disabled");

        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        ENTITIES.register(modBus);
        COMPONENTS.register(modBus);
        TABS.register(modBus);
        SOUNDS.register(modBus);
        if (ModList.get().isLoaded("create")) CreateCompat.init(modBus);
        if (sableLoaded && ModList.get().isLoaded("simulated")) SimulatedCompat.init(modBus);
        if (sableLoaded && ModList.get().isLoaded("createarms")) CreateArmsCompat.init(modBus);
        if (ModList.get().isLoaded("createbigcannons")) CbcCompat.init(modBus);
        container.registerConfig(ModConfig.Type.SERVER, AshCCConfig.SPEC);
        // Every computer gets an "imu" global for its own motion, no block needed
        ComputerCraftAPI.registerAPIFactory(ComputerImuApi::new);
        if (FMLEnvironment.dist.isClient()) {
            LaserRenderer.register(modBus);
            JetEngineRenderer.register(modBus);
            HoseRenderer.register();
            DecoyClient.register(modBus);
            PanelClient.register(modBus);
            MonitorClient.register(modBus);
            com.ashcc.connector.client.ConnectorClient.register(modBus);
        }
        MonitorNetwork.register(modBus);
        HoseNetwork.register(modBus);
        PanelNetwork.register(modBus);
        com.ashcc.connector.ConnectorNetwork.register(modBus);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(JetPartBlock::onRightClickBlock);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(com.ashcc.connector.ConnectorEntity::onRightClickBlock);

        modBus.addListener(this::registerCapabilities);
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(PeripheralCapability.get(), RADAR_BE.get(), (be, side) -> be.peripheral());
        event.registerBlockEntity(PeripheralCapability.get(), THRUSTER_BE.get(), (be, side) -> be.peripheral());
        event.registerBlockEntity(PeripheralCapability.get(), ANTIGRAV_BE.get(), (be, side) -> be.peripheral());
        event.registerBlockEntity(PeripheralCapability.get(), HD_MONITOR_BE.get(), (be, side) -> be.peripheral());
        event.registerBlockEntity(PeripheralCapability.get(), LINK_HUB_BE.get(), (be, side) -> be.peripheral());
        event.registerBlockEntity(PeripheralCapability.get(), SEEKER_BE.get(), (be, side) -> be.peripheral());
        event.registerBlockEntity(PeripheralCapability.get(), LASER_BE.get(), (be, side) -> be.peripheral());
        event.registerBlockEntity(PeripheralCapability.get(), TRANSPONDER_BE.get(), (be, side) -> be.peripheral());
        event.registerBlockEntity(PeripheralCapability.get(), DECOY_LAUNCHER_BE.get(), (be, side) -> be.peripheral());
        event.registerBlockEntity(PeripheralCapability.get(), JET_ENGINE_BE.get(), (be, side) -> be.peripheral());
        event.registerBlockEntity(PeripheralCapability.get(), CONTROL_PANEL_BE.get(), (be, side) -> be.peripheral());
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, JET_ENGINE_BE.get(), (be, side) -> be.fluidHandler());
        Block[] sections = {JET_INTAKE.get(), JET_CORE.get()};
        event.registerBlock(PeripheralCapability.get(), (level, pos, state, be, side) -> {
            JetEngineBlockEntity e = JetEngineBlockEntity.forPart(level, pos, state);
            return e == null ? null : e.peripheral();
        }, sections);
        event.registerBlock(Capabilities.FluidHandler.BLOCK, (level, pos, state, be, side) -> {
            JetEngineBlockEntity e = JetEngineBlockEntity.forPart(level, pos, state);
            return e == null ? null : e.fluidHandler();
        }, sections);
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, DECOY_LAUNCHER_BE.get(), (be, side) -> be.itemHandler());
    }
}
