package com.ashcc.compat.create;

import com.ashcc.AshCC;
import com.simibubi.create.content.contraptions.bearing.BearingRenderer;
import com.simibubi.create.content.contraptions.bearing.BearingVisual;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Content that needs Create. Only loaded when Create is installed. */
public final class CreateCompat {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(AshCC.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AshCC.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AshCC.MOD_ID);

    public static final DeferredBlock<FlapBearingBlock> FLAP_BEARING = BLOCKS.register("flap_bearing", () -> new FlapBearingBlock(
        BlockBehaviour.Properties.of().mapColor(MapColor.PODZOL).strength(3.0f).sound(SoundType.WOOD).noOcclusion()));
    public static final DeferredItem<BlockItem> FLAP_BEARING_ITEM = ITEMS.registerSimpleBlockItem(FLAP_BEARING, new Item.Properties());
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FlapBearingBlockEntity>> FLAP_BEARING_BE = BLOCK_ENTITIES.register("flap_bearing",
        () -> BlockEntityType.Builder.<FlapBearingBlockEntity>of((pos, state) -> new FlapBearingBlockEntity(CreateCompat.FLAP_BEARING_BE.get(), pos, state),
            FLAP_BEARING.get()).build(null));

    public static final DeferredBlock<RedstoneFlapBearingBlock> REDSTONE_FLAP_BEARING = BLOCKS.register("redstone_flap_bearing", () -> new RedstoneFlapBearingBlock(
        BlockBehaviour.Properties.of().mapColor(MapColor.PODZOL).strength(3.0f).sound(SoundType.WOOD).noOcclusion()));
    public static final DeferredItem<BlockItem> REDSTONE_FLAP_BEARING_ITEM = ITEMS.registerSimpleBlockItem(REDSTONE_FLAP_BEARING, new Item.Properties());
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RedstoneFlapBearingBlockEntity>> REDSTONE_FLAP_BEARING_BE = BLOCK_ENTITIES.register("redstone_flap_bearing",
        () -> BlockEntityType.Builder.<RedstoneFlapBearingBlockEntity>of((pos, state) -> new RedstoneFlapBearingBlockEntity(CreateCompat.REDSTONE_FLAP_BEARING_BE.get(), pos, state),
            REDSTONE_FLAP_BEARING.get()).build(null));

    public static final DeferredBlock<QuadFlapBearingBlock> QUAD_FLAP_BEARING = BLOCKS.register("quad_flap_bearing", () -> new QuadFlapBearingBlock(
        BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f, 6.0f).sound(SoundType.METAL)));
    public static final DeferredItem<BlockItem> QUAD_FLAP_BEARING_ITEM = ITEMS.registerSimpleBlockItem(QUAD_FLAP_BEARING, new Item.Properties());
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<QuadFlapBearingBlockEntity>> QUAD_FLAP_BEARING_BE = BLOCK_ENTITIES.register("quad_flap_bearing",
        () -> BlockEntityType.Builder.of(QuadFlapBearingBlockEntity::new, QUAD_FLAP_BEARING.get()).build(null));

    private CreateCompat() {
    }

    public static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(CreateCompat::registerCapabilities);
        modBus.addListener(CreateCompat::buildCreativeTabs);
        if (FMLEnvironment.dist.isClient()) Client.init(modBus);
    }

    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(PeripheralCapability.get(), FLAP_BEARING_BE.get(), (be, side) -> be.peripheral());
        event.registerBlockEntity(PeripheralCapability.get(), QUAD_FLAP_BEARING_BE.get(), (be, side) -> be.peripheral());
    }

    private static void buildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == AshCC.TAB.getKey()) {
            event.accept(FLAP_BEARING_ITEM);
            event.accept(REDSTONE_FLAP_BEARING_ITEM);
            event.accept(QUAD_FLAP_BEARING_ITEM);
        }
    }

    /** Same renderer and Flywheel visual as Create's mechanical bearing. */
    private static final class Client {
        static void init(IEventBus modBus) {
            modBus.addListener((EntityRenderersEvent.RegisterRenderers e) -> {
                e.registerBlockEntityRenderer(FLAP_BEARING_BE.get(), BearingRenderer::new);
                e.registerBlockEntityRenderer(REDSTONE_FLAP_BEARING_BE.get(), BearingRenderer::new);
            });
            modBus.addListener((FMLClientSetupEvent e) -> e.enqueueWork(() -> {
                SimpleBlockEntityVisualizer.builder(FLAP_BEARING_BE.get())
                    .factory(BearingVisual::new)
                    .skipVanillaRender(be -> true)
                    .apply();
                SimpleBlockEntityVisualizer.builder(REDSTONE_FLAP_BEARING_BE.get())
                    .factory(BearingVisual::new)
                    .skipVanillaRender(be -> true)
                    .apply();
            }));
        }
    }
}
