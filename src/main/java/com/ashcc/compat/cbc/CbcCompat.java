package com.ashcc.compat.cbc;

import com.ashcc.AshCC;
import com.simibubi.create.api.contraption.ContraptionType;
import com.simibubi.create.api.registry.CreateRegistries;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import rbasamoyai.createbigcannons.cannon_control.cannon_types.CannonContraptionTypeRegistry;
import rbasamoyai.createbigcannons.cannon_control.cannon_types.ICannonContraptionType;
import rbasamoyai.createbigcannons.cannons.autocannon.AutocannonBlockEntity;
import rbasamoyai.createbigcannons.cannons.autocannon.AutocannonBlockItem;
import rbasamoyai.createbigcannons.cannons.autocannon.material.AutocannonMaterial;
import rbasamoyai.createbigcannons.cannons.autocannon.material.AutocannonMaterialProperties;
import rbasamoyai.createbigcannons.index.CBCBlockEntities;

/**
 * Create Big Cannons content: CC peripherals on CBC's cannon mounts, the rotary autocannon (a CBC autocannon with its
 * own breech, barrels and material), and the railgun and laser cannon (new cannon types for CBC's mounts). Only loaded when CBC is installed.
 */
public final class CbcCompat {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(AshCC.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AshCC.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AshCC.MOD_ID);
    private static final DeferredRegister<ContraptionType> CONTRAPTION_TYPES = DeferredRegister.create(CreateRegistries.CONTRAPTION_TYPE, AshCC.MOD_ID);

    /** Material of the rotary autocannon's breech and barrels; the defaults can be overridden by data/ashcc/autocannon_materials/rotary.json. */
    public static final AutocannonMaterial ROTARY = AutocannonMaterial.register(id("rotary"), AutocannonMaterialProperties.builder()
        .maxBarrelLength(4)
        .weight(2.0F)
        .baseSpread(3.0F)
        .spreadReductionPerBarrel(0.75F)
        .baseSpeed(4.0F)
        .speedIncreasePerBarrel(1.5F)
        .maxSpeedIncreases(3)
        .projectileLifetime(40)
        .baseRecoil(0.5F)
        .connectsInSurvival(true)
        .isWeldable(false)
        .weldDamage(0)
        .weldStressPenalty(0)
        .build());

    /** The railgun's cannon type: mounts list their pitch limits for it in data/createbigcannons/cannon_mounts/. */
    public static final ICannonContraptionType RAILGUN_TYPE = CannonContraptionTypeRegistry.register(id("railgun"), new ICannonContraptionType() {
        private final ResourceLocation id = id("railgun");

        @Override
        public ResourceLocation getId() {
            return id;
        }
    });

    /** The laser cannon's cannon type (pitch limits in data/createbigcannons/cannon_mounts/). */
    public static final ICannonContraptionType LASER_CANNON_TYPE = CannonContraptionTypeRegistry.register(id("laser_cannon"), new ICannonContraptionType() {
        private final ResourceLocation id = id("laser_cannon");

        @Override
        public ResourceLocation getId() {
            return id;
        }
    });

    private static BlockBehaviour.Properties metal() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5.0f, 6.0f).sound(SoundType.NETHERITE_BLOCK)
            .requiresCorrectToolForDrops().noOcclusion();
    }

    public static final DeferredBlock<RotaryBreechBlock> ROTARY_BREECH = BLOCKS.register("rotary_autocannon_breech",
        () -> new RotaryBreechBlock(metal(), ROTARY));
    public static final DeferredItem<BlockItem> ROTARY_BREECH_ITEM = ITEMS.register("rotary_autocannon_breech",
        () -> new AutocannonBlockItem<>(ROTARY_BREECH.get(), new Item.Properties()));
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RotaryBreechBlockEntity>> ROTARY_BREECH_BE = BLOCK_ENTITIES.register("rotary_autocannon_breech",
        () -> BlockEntityType.Builder.<RotaryBreechBlockEntity>of((pos, state) -> new RotaryBreechBlockEntity(CbcCompat.ROTARY_BREECH_BE.get(), pos, state),
            ROTARY_BREECH.get()).build(null));

    public static final DeferredBlock<RotaryBarrelBlock> ROTARY_BARREL = BLOCKS.register("rotary_autocannon_barrel",
        () -> new RotaryBarrelBlock(metal(), ROTARY));
    public static final DeferredItem<BlockItem> ROTARY_BARREL_ITEM = ITEMS.register("rotary_autocannon_barrel",
        () -> new AutocannonBlockItem<>(ROTARY_BARREL.get(), new Item.Properties()));
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AutocannonBlockEntity>> ROTARY_BARREL_BE = BLOCK_ENTITIES.register("rotary_autocannon_barrel",
        () -> BlockEntityType.Builder.<AutocannonBlockEntity>of((pos, state) -> new AutocannonBlockEntity(CbcCompat.ROTARY_BARREL_BE.get(), pos, state),
            ROTARY_BARREL.get()).build(null));

    public static final DeferredBlock<RailgunBreechBlock> RAILGUN_BREECH = BLOCKS.register("railgun_breech", () -> new RailgunBreechBlock(metal()));
    public static final DeferredItem<BlockItem> RAILGUN_BREECH_ITEM = ITEMS.registerSimpleBlockItem(RAILGUN_BREECH, new Item.Properties());
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RailgunBreechBlockEntity>> RAILGUN_BREECH_BE = BLOCK_ENTITIES.register("railgun_breech",
        () -> BlockEntityType.Builder.<RailgunBreechBlockEntity>of((pos, state) -> new RailgunBreechBlockEntity(CbcCompat.RAILGUN_BREECH_BE.get(), pos, state),
            RAILGUN_BREECH.get()).build(null));

    public static final DeferredBlock<RailgunRailBlock> RAILGUN_RAIL = BLOCKS.register("railgun_rail", () -> new RailgunRailBlock(metal()));
    public static final DeferredItem<BlockItem> RAILGUN_RAIL_ITEM = ITEMS.registerSimpleBlockItem(RAILGUN_RAIL, new Item.Properties());

    public static final DeferredBlock<LaserEmitterBlock> LASER_EMITTER = BLOCKS.register("laser_cannon_emitter", () -> new LaserEmitterBlock(metal()));
    public static final DeferredItem<BlockItem> LASER_EMITTER_ITEM = ITEMS.registerSimpleBlockItem(LASER_EMITTER, new Item.Properties());
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LaserEmitterBlockEntity>> LASER_EMITTER_BE = BLOCK_ENTITIES.register("laser_cannon_emitter",
        () -> BlockEntityType.Builder.<LaserEmitterBlockEntity>of((pos, state) -> new LaserEmitterBlockEntity(CbcCompat.LASER_EMITTER_BE.get(), pos, state),
            LASER_EMITTER.get()).build(null));

    public static final DeferredBlock<LaserLensBlock> LASER_LENS = BLOCKS.register("laser_cannon_lens", () -> new LaserLensBlock(metal().lightLevel(s -> 4)));
    public static final DeferredItem<BlockItem> LASER_LENS_ITEM = ITEMS.registerSimpleBlockItem(LASER_LENS, new Item.Properties());

    public static final DeferredHolder<ContraptionType, ContraptionType> MOUNTED_LASER = CONTRAPTION_TYPES.register("mounted_laser_cannon",
        () -> new ContraptionType(MountedLaserContraption::new));

    public static final DeferredHolder<ContraptionType, ContraptionType> MOUNTED_RAILGUN = CONTRAPTION_TYPES.register("mounted_railgun",
        () -> new ContraptionType(MountedRailgunContraption::new));

    private CbcCompat() {
    }

    /** For the control panel's weapons page: is this block entity one of CBC's cannon mounts? */
    public static boolean isCannonMount(net.minecraft.world.level.block.entity.BlockEntity be) {
        return be instanceof rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity
            || be instanceof rbasamoyai.createbigcannons.cannon_control.fixed_cannon_mount.FixedCannonMountBlockEntity;
    }

    /**
     * For the control panel's weapons page: null when the block entity is no cannon mount, "none" when no cannon is
     * assembled on it, otherwise the mounted cannon's kind ("railgun", "laser_cannon", "big_cannon", ...).
     */
    public static @org.jetbrains.annotations.Nullable String cannonStatus(net.minecraft.world.level.block.entity.BlockEntity be) {
        if (!isCannonMount(be)) return null;
        var cannon = Mounts.cannon(be);
        return cannon == null ? "none" : Mounts.typeName(cannon);
    }

    static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(AshCC.MOD_ID, path);
    }

    public static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        CONTRAPTION_TYPES.register(modBus);
        modBus.addListener(CbcCompat::registerCapabilities);
        modBus.addListener(CbcCompat::buildCreativeTabs);
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> MountControl.tickAll());
        RailgunFx.register(modBus);
        LaserCannonFx.register(modBus);
    }

    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(PeripheralCapability.get(), CBCBlockEntities.CANNON_MOUNT.get(), (be, side) -> new CannonMountPeripheral(be));
        event.registerBlockEntity(PeripheralCapability.get(), CBCBlockEntities.FIXED_CANNON_MOUNT.get(), (be, side) -> new CannonMountPeripheral(be));
        // A railgun or laser cannon is charged through the mount it sits on, or through its back block before assembly
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, CBCBlockEntities.CANNON_MOUNT.get(), (be, side) -> MountedRailgunContraption.energyOf(be));
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, CBCBlockEntities.FIXED_CANNON_MOUNT.get(), (be, side) -> MountedRailgunContraption.energyOf(be));
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, RAILGUN_BREECH_BE.get(), (be, side) -> be.energy());
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, RAILGUN_BREECH_BE.get(), (be, side) -> be.items());
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, LASER_EMITTER_BE.get(), (be, side) -> be.energy());
    }

    private static void buildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == AshCC.TAB.getKey()) {
            event.accept(ROTARY_BREECH_ITEM);
            event.accept(ROTARY_BARREL_ITEM);
            event.accept(RAILGUN_BREECH_ITEM);
            event.accept(RAILGUN_RAIL_ITEM);
            event.accept(LASER_EMITTER_ITEM);
            event.accept(LASER_LENS_ITEM);
        }
    }
}
