package com.ashcc.compat.simulated;

import com.ashcc.AshCC;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Content that needs Create Simulated (Create Aeronautics) and Sable. Only loaded when Simulated is installed. */
public final class SimulatedCompat {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(AshCC.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AshCC.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AshCC.MOD_ID);

    public static final DeferredBlock<ServoBearingBlock> SERVO_BEARING = BLOCKS.register("servo_bearing", () -> new ServoBearingBlock(
        BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f, 6.0f).sound(SoundType.METAL).noOcclusion()));
    public static final DeferredItem<BlockItem> SERVO_BEARING_ITEM = ITEMS.registerSimpleBlockItem(SERVO_BEARING, new Item.Properties());
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ServoBearingBlockEntity>> SERVO_BEARING_BE = BLOCK_ENTITIES.register("servo_bearing",
        () -> BlockEntityType.Builder.of(ServoBearingBlockEntity::new, SERVO_BEARING.get()).build(null));

    /** The turntable half that lives on the rotating sub-level. No item: it drops the servo bearing. */
    public static final DeferredBlock<ServoBearingPlateBlock> SERVO_BEARING_PLATE = BLOCKS.register("servo_bearing_plate", () -> new ServoBearingPlateBlock(
        BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f, 6.0f).sound(SoundType.METAL).noOcclusion()));
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ServoBearingPlateBlockEntity>> SERVO_BEARING_PLATE_BE = BLOCK_ENTITIES.register("servo_bearing_plate",
        () -> BlockEntityType.Builder.of(ServoBearingPlateBlockEntity::new, SERVO_BEARING_PLATE.get()).build(null));

    private SimulatedCompat() {
    }

    public static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(SimulatedCompat::registerCapabilities);
        modBus.addListener(SimulatedCompat::buildCreativeTabs);
    }

    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(PeripheralCapability.get(), SERVO_BEARING_BE.get(), (be, side) -> be.peripheral());
    }

    private static void buildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == AshCC.TAB.getKey()) event.accept(SERVO_BEARING_ITEM);
    }
}
