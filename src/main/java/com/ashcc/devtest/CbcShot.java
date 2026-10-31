package com.ashcc.devtest;

import com.ashcc.AshCC;
import com.ashcc.antigrav.AntigravBlockEntity;
import com.ashcc.antigrav.ShipState;
import com.ashcc.compat.cbc.CannonMountPeripheral;
import com.ashcc.compat.cbc.CbcCompat;
import com.ashcc.compat.cbc.RailgunBlock;
import dan200.computercraft.api.lua.ObjectArguments;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Dev-only (excluded from the jar). {@code gradlew runTestClient -PcbcShot -Pworld=ashcc_cbc}:
 * three CBC cannon mounts on the ground (railgun, rotary autocannon, CBC steel autocannon) are assembled, aimed and
 * fired at a low wall from Java through the cannon_mount peripheral; then a target ship hovers with an anti-gravity
 * core and two computers run the real {@code lua/cbc_turret.lua} with the railgun and the rotary autocannon.
 * Logs everything as "CbcShot" and takes screenshots into run-test/screenshots, then quits.
 */
@EventBusSubscriber(modid = AshCC.MOD_ID, value = Dist.CLIENT)
public final class CbcShot {
    private static final boolean ENABLED = System.getProperty("ashcc.cbcshot") != null;
    /** -PcbcShot=fx: only the railgun's shot effects, filmed from the side, behind the gun and downrange. */
    private static final boolean FX = "fx".equals(System.getProperty("ashcc.cbcshot"));
    /** -PcbcShot=power: a Crafts & Additions creative generator next to the mount must charge the railgun on its own. */
    private static final boolean POWER = "power".equals(System.getProperty("ashcc.cbcshot"));
    /** -PcbcShot=laser: a 4-lens laser cannon on a creative generator sweeps its beam across a wall. */
    private static final boolean LASER = "laser".equals(System.getProperty("ashcc.cbcshot"));
    /** -PcbcShot=ship: the same laser cannon on a Sable ship. */
    private static final boolean SHIP = "ship".equals(System.getProperty("ashcc.cbcshot"));
    private static BlockPos shipMount;
    private static final BlockPos RAIL = new BlockPos(0, -60, 0);
    private static final BlockPos ROTARY = new BlockPos(8, -60, 0);
    private static final BlockPos AUTO = new BlockPos(16, -60, 0);
    private static final BlockPos CORE = new BlockPos(8, -40, -50);
    private static int tick;
    private static Object ship;

    private CbcShot() {
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!ENABLED || mc.player == null || mc.getSingleplayerServer() == null) return;
        MinecraftServer server = mc.getSingleplayerServer();
        mc.getToasts().clear();
        int t = ++tick;
        if (FX) {
            fxTick(mc, server, t);
            return;
        }
        if (POWER) {
            powerTick(mc, server, t);
            return;
        }
        if (SHIP) {
            shipTick(mc, server, t);
            return;
        }
        if (LASER) {
            laserTick(mc, server, t);
            return;
        }
        switch (t) {
            case 40 -> {
                cmd(server, "clear @p");
                cmd(server, "time set noon");
                cmd(server, "gamerule doDaylightCycle false");
                cmd(server, "kill @e[type=!player]");
                cmd(server, "fill -20 -60 -70 36 -20 20 air");
                cmd(server, "fill -12 -60 -36 28 -53 -36 stone");
                server.execute(() -> build(server.overworld()));
            }
            case 60 -> {
                mc.player.getAbilities().flying = true;
                cmd(server, "tp @p 8 -52 12 facing 8 -58 -10");
            }
            case 70 -> server.execute(() -> {
                assemble(server.overworld());
                log(server, "assembled");
            });
            case 90 -> shot(mc, "cbc_1_assembled");
            case 95 -> server.execute(() -> {
                aim(server.overworld(), RAIL, 0, 4);
                aim(server.overworld(), ROTARY, -15, 8);
                aim(server.overworld(), AUTO, 15, 8);
            });
            case 130 -> {
                server.execute(() -> log(server, "aimed"));
                shot(mc, "cbc_2_aimed");
            }
            case 135 -> server.execute(() -> {
                fire(server.overworld(), RAIL);
                trigger(server.overworld(), ROTARY, true);
                trigger(server.overworld(), AUTO, true);
            });
            case 150 -> shot(mc, "cbc_3_firing");
            case 180, 225 -> server.execute(() -> fire(server.overworld(), RAIL));
            case 195 -> server.execute(() -> {
                log(server, "firing");
                trigger(server.overworld(), ROTARY, false);
                trigger(server.overworld(), AUTO, false);
            });
            case 240 -> {
                server.execute(() -> {
                    log(server, "after");
                    wall(server.overworld());
                });
                shot(mc, "cbc_4_wall");
            }
            case 250 -> server.execute(() -> {
                ship = Target.build(server.overworld());
                turret(server, RAIL.west());
                turret(server, ROTARY.west());
            });
            case 270 -> cmd(server, "tp @p 24 -44 14 facing 8 -48 -40");
            default -> {
            }
        }
        if (t > 250 && t % 20 == 0) server.execute(() -> {
            ServerLevel level = server.overworld();
            charge(level, RAIL, 20000);
            load(level, RAIL, "createbigcannons:ap_shot", 8);
            load(level, ROTARY, "createbigcannons:machine_gun_round", 32);
        });
        if (t >= 300 && t <= 600 && t % 60 == 0) {
            int at = t;
            server.execute(() -> {
                log(server, "turret t=" + at);
                screen(server, RAIL.west(), "railgun computer");
                screen(server, ROTARY.west(), "rotary computer");
                Target.log(server.overworld(), ship);
            });
            shot(mc, "cbc_5_turret_" + t);
        }
        if (t == 610) server.execute(() -> {
            turretLog(server, RAIL.west());
            turretLog(server, ROTARY.west());
        });
        if (t == 620) mc.stop();
    }

    // ---- Laser cannon on a ship ----

    private static void shipTick(Minecraft mc, MinecraftServer server, int t) {
        switch (t) {
            case 40 -> {
                cmd(server, "clear @p");
                cmd(server, "time set noon");
                cmd(server, "kill @e[type=!player]");
                cmd(server, "fill -30 -60 -60 30 -30 20 air");
                cmd(server, "fill -20 -60 -32 20 -45 -32 stone");
                server.execute(() -> ship = ShipScene.build(server.overworld()));
            }
            case 70 -> server.execute(() -> {
                ServerLevel level = server.overworld();
                shipMount = ShipScene.mount(level, ship);
                AshCC.LOGGER.info("CbcShot ship mount in plot: {}", shipMount);
                if (shipMount == null) return;
                try {
                    AshCC.LOGGER.info("CbcShot ship assemble: {}", java.util.Arrays.toString(p(level, shipMount).assemble().getResult()));
                    p(level, shipMount).setAimSpeed(20);
                    p(level, shipMount).setAim(-20, 2);
                } catch (Exception e) {
                    AshCC.LOGGER.error("CbcShot ship setup failed", e);
                }
            });
            case 80 -> {
                mc.player.getAbilities().flying = true;
                cmd(server, "tp @p 10 -52 8 facing 0 -55 -20");
            }
            case 100 -> server.execute(() -> {
                try {
                    charge(server.overworld(), shipMount, 200000);
                    p(server.overworld(), shipMount).setFiring(new ObjectArguments(true));
                    p(server.overworld(), shipMount).setAim(20, 2);
                } catch (Exception e) {
                    AshCC.LOGGER.error("CbcShot ship fire failed", e);
                }
            });
            case 110, 150, 190 -> {
                server.execute(() -> {
                    try {
                        AshCC.LOGGER.info("CbcShot ship t={}: {} muzzle {}", t, p(server.overworld(), shipMount).getCannon(), p(server.overworld(), shipMount).getMuzzle());
                    } catch (Exception e) {
                        AshCC.LOGGER.error("CbcShot ship read failed", e);
                    }
                });
                shotNow(mc, "cbc_ship_laser_" + t);
            }
            case 200 -> mc.stop();
            default -> {
                // Turn the ship slowly while it fires: the beam must stay on the barrel
                if (t > 115 && t < 200 && ship != null) {
                    double deg = (t - 115) * 1.0;
                    server.execute(() -> ShipScene.turn(ship, deg));
                }
            }
        }
    }

    /** Sable types stay in here. */
    private static final class ShipScene {
        static Object build(ServerLevel level) {
            // 3x7 iron deck resting on the ground with a cannon mount in the middle and the laser cannon on top
            java.util.List<BlockPos> blocks = new java.util.ArrayList<>();
            for (int x = -1; x <= 1; x++) for (int z = -3; z <= 3; z++) {
                BlockPos p = new BlockPos(x, -60, z);
                level.setBlockAndUpdate(p, Blocks.IRON_BLOCK.defaultBlockState());
                blocks.add(p);
            }
            BlockPos m = new BlockPos(0, -59, 0);
            level.setBlockAndUpdate(m, CbcShot.mount());
            blocks.add(m);
            level.setBlockAndUpdate(m.above(2).south(), CbcCompat.LASER_EMITTER.get().defaultBlockState().setValue(RailgunBlock.FACING, Direction.NORTH));
            blocks.add(m.above(2).south());
            for (int i = 0; i < 4; i++) {
                level.setBlockAndUpdate(m.above(2).north(i), CbcCompat.LASER_LENS.get().defaultBlockState().setValue(RailgunBlock.FACING, Direction.NORTH));
                blocks.add(m.above(2).north(i));
            }
            var sub = dev.ryanhcode.sable.api.SubLevelAssemblyHelper.assembleBlocks(level, m, blocks,
                new dev.ryanhcode.sable.companion.math.BoundingBox3i(new BlockPos(-1, -60, -3), new BlockPos(1, -57, 3)));
            AshCC.LOGGER.info("CbcShot ship: {}", sub == null ? null : sub.getUniqueId());
            return sub;
        }

        /** Lifts the ship a little and turns it to {@code degrees} about Y (one step of a slow turn while firing). */
        static void turn(Object ship, double degrees) {
            if (!(ship instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel sub)) return;
            var handle = dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(sub);
            if (handle == null) return;
            var pos = new org.joml.Vector3d(sub.logicalPose().position());
            pos.y = Math.max(pos.y, -56.5);
            handle.teleport(pos, new org.joml.Quaterniond().rotateY(Math.toRadians(degrees)));
        }

        static BlockPos mount(ServerLevel level, Object ship) {
            if (!(ship instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel sub)) return null;
            var plot = sub.getPlot();
            for (int cx = plot.getChunkMin().x; cx <= plot.getChunkMax().x; cx++) {
                for (int cz = plot.getChunkMin().z; cz <= plot.getChunkMax().z; cz++) {
                    for (BlockEntity be : level.getChunk(cx, cz).getBlockEntities().values()) {
                        if (be instanceof rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity) return be.getBlockPos();
                    }
                }
            }
            return null;
        }
    }

    // ---- Laser cannon scene ----

    private static void laserTick(Minecraft mc, MinecraftServer server, int t) {
        switch (t) {
            case 40 -> {
                cmd(server, "clear @p");
                cmd(server, "time set noon");
                cmd(server, "kill @e[type=!player]");
                cmd(server, "fill -30 -60 -60 30 -30 20 air");
                cmd(server, "fill -20 -60 -32 20 -50 -32 stone");
                server.execute(() -> {
                    ServerLevel level = server.overworld();
                    level.setBlockAndUpdate(RAIL, mount());
                    level.setBlockAndUpdate(RAIL.above(2).south(), CbcCompat.LASER_EMITTER.get().defaultBlockState().setValue(RailgunBlock.FACING, Direction.NORTH));
                    for (int i = 0; i < 4; i++) {
                        level.setBlockAndUpdate(RAIL.above(2).north(i), CbcCompat.LASER_LENS.get().defaultBlockState().setValue(RailgunBlock.FACING, Direction.NORTH));
                    }
                    level.setBlockAndUpdate(RAIL.east(), BuiltInRegistries.BLOCK.get(ResourceLocation.parse("createaddition:creative_energy")).defaultBlockState());
                });
            }
            case 50 -> server.execute(() -> {
                ServerLevel level = server.overworld();
                try {
                    AshCC.LOGGER.info("CbcShot laser assemble: {}", java.util.Arrays.toString(p(level, RAIL).assemble().getResult()));
                    p(level, RAIL).setAimSpeed(20);
                    p(level, RAIL).setAim(-25, 3);
                } catch (Exception e) {
                    AshCC.LOGGER.error("CbcShot laser setup failed", e);
                }
            });
            case 60 -> {
                mc.player.getAbilities().flying = true;
                cmd(server, "tp @p 10 -55 8 facing 0 -57 -20");
            }
            case 90 -> server.execute(() -> {
                ServerLevel level = server.overworld();
                try {
                    p(level, RAIL).setFiring(new ObjectArguments(true));
                    p(level, RAIL).setAim(25, 3);
                } catch (Exception e) {
                    AshCC.LOGGER.error("CbcShot laser fire failed", e);
                }
            });
            case 100, 140, 180 -> {
                server.execute(() -> log(server, "laser t=" + t));
                shotNow(mc, "cbc_laser_" + t);
            }
            case 190 -> cmd(server, "tp @p 14 -54 -20 facing 0 -57 -32");
            case 200, 230 -> shotNow(mc, "cbc_laser_wall_" + t);
            case 240 -> server.execute(() -> {
                try {
                    p(server.overworld(), RAIL).setFiring(new ObjectArguments(false));
                } catch (Exception ignored) {
                }
                log(server, "laser off");
            });
            case 250 -> shotNow(mc, "cbc_laser_off");
            case 260 -> mc.stop();
            default -> {
            }
        }
    }

    // ---- Power scene ----

    private static void powerTick(Minecraft mc, MinecraftServer server, int t) {
        switch (t) {
            case 40 -> {
                cmd(server, "clear @p");
                cmd(server, "time set noon");
                cmd(server, "kill @e[type=!player]");
                cmd(server, "fill -20 -60 -40 20 -40 20 air");
                server.execute(() -> {
                    ServerLevel level = server.overworld();
                    level.setBlockAndUpdate(RAIL, mount());
                    level.setBlockAndUpdate(RAIL.above(2).south(), CbcCompat.RAILGUN_BREECH.get().defaultBlockState().setValue(RailgunBlock.FACING, Direction.NORTH));
                    for (int i = 0; i < 3; i++) {
                        level.setBlockAndUpdate(RAIL.above(2).north(i), CbcCompat.RAILGUN_RAIL.get().defaultBlockState().setValue(RailgunBlock.FACING, Direction.NORTH));
                    }
                    // Unassembled breech beside its own generator too: it can be charged before assembly
                    level.setBlockAndUpdate(new BlockPos(6, -60, 0), CbcCompat.RAILGUN_BREECH.get().defaultBlockState());
                });
            }
            case 50 -> server.execute(() -> {
                ServerLevel level = server.overworld();
                try {
                    AshCC.LOGGER.info("CbcShot power assemble: {}", java.util.Arrays.toString(p(level, RAIL).assemble().getResult()));
                    load(level, RAIL, "createbigcannons:ap_shot", 4);
                    AshCC.LOGGER.info("CbcShot power before: {}", p(level, RAIL).getCannon());
                } catch (Exception e) {
                    AshCC.LOGGER.error("CbcShot power setup failed", e);
                }
                var gen = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("createaddition:creative_energy")).defaultBlockState();
                AshCC.LOGGER.info("CbcShot power generator block: {}", gen);
                level.setBlockAndUpdate(RAIL.east(), gen);
                level.setBlockAndUpdate(new BlockPos(7, -60, 0), gen);
            });
            case 60 -> {
                mc.player.getAbilities().flying = true;
                cmd(server, "tp @p 4 -56 6 facing 1 -59 0");
            }
            case 70, 90, 130 -> server.execute(() -> {
                ServerLevel level = server.overworld();
                try {
                    var e = level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, new BlockPos(6, -60, 0), Direction.UP);
                    AshCC.LOGGER.info("CbcShot power t={}: mounted {} / loose breech energy {}", t, p(level, RAIL).getCannon(),
                        e == null ? null : e.getEnergyStored());
                } catch (Exception ex) {
                    AshCC.LOGGER.error("CbcShot power read failed", ex);
                }
            });
            case 135 -> server.execute(() -> fire(server.overworld(), RAIL));
            case 140 -> shot(mc, "cbc_power");
            case 160 -> mc.stop();
            default -> {
            }
        }
    }

    // ---- Effects scene ----

    /** Railgun with 6 rails (supersonic with the default speeds) firing almost level, 3.5 blocks over the ground. */
    private static void fxTick(Minecraft mc, MinecraftServer server, int t) {
        switch (t) {
            case 40 -> {
                cmd(server, "clear @p");
                cmd(server, "time set noon");
                cmd(server, "gamerule doDaylightCycle false");
                cmd(server, "kill @e[type=!player]");
                cmd(server, "fill -20 -60 -200 20 -20 20 air");
                server.execute(() -> {
                    ServerLevel level = server.overworld();
                    level.setBlockAndUpdate(RAIL, mount());
                    level.setBlockAndUpdate(RAIL.above(2).south(), CbcCompat.RAILGUN_BREECH.get().defaultBlockState().setValue(RailgunBlock.FACING, Direction.NORTH));
                    for (int i = 0; i < 6; i++) {
                        level.setBlockAndUpdate(RAIL.above(2).north(i), CbcCompat.RAILGUN_RAIL.get().defaultBlockState().setValue(RailgunBlock.FACING, Direction.NORTH));
                    }
                });
            }
            case 60 -> {
                mc.player.getAbilities().flying = true;
                server.execute(() -> {
                    ServerLevel level = server.overworld();
                    try {
                        AshCC.LOGGER.info("CbcShot fx assemble: {}", java.util.Arrays.toString(p(level, RAIL).assemble().getResult()));
                        p(level, RAIL).setAimSpeed(180);
                        p(level, RAIL).setAim(0, 0.5);
                        AshCC.LOGGER.info("CbcShot fx ballistics: {}", p(level, RAIL).getBallistics());
                    } catch (Exception e) {
                        AshCC.LOGGER.error("CbcShot fx setup failed", e);
                    }
                });
            }
            // 1: from the side, 12 blocks east of the line of fire
            case 80 -> cmd(server, "tp @p 12 -56 -14 facing 0 -57.5 -14");
            case 100 -> fxFire(server);
            case 102, 105, 112, 130 -> shotNow(mc, "cbc_fx_1_side_" + (t - 100));
            // 2: just behind and above the gun
            case 140 -> cmd(server, "tp @p 2.5 -54 5 facing 0 -57.5 -40");
            case 160 -> fxFire(server);
            case 161, 163, 170, 190 -> shotNow(mc, "cbc_fx_2_behind_" + (t - 160));
            // 3: downrange, 4 blocks beside the line of fire, looking back at the gun (the shell passes us: crack + shake)
            case 200 -> cmd(server, "tp @p 4 -58 -50 facing 0 -57.5 0");
            case 220 -> fxFire(server);
            case 221, 223, 226, 235, 250 -> shotNow(mc, "cbc_fx_3_downrange_" + (t - 220));
            case 270 -> mc.stop();
            default -> {
            }
        }
    }

    private static void fxFire(MinecraftServer server) {
        server.execute(() -> {
            ServerLevel level = server.overworld();
            charge(level, RAIL, 40000);
            load(level, RAIL, "createbigcannons:ap_shot", 2);
            fire(level, RAIL);
        });
    }

    private static void shotNow(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), msg -> AshCC.LOGGER.info("CbcShot: {}", msg.getString()));
    }

    // ---- Scene ----

    private static BlockState mount() {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse("createbigcannons:cannon_mount")).defaultBlockState()
            .setValue(rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlock.HORIZONTAL_FACING, Direction.NORTH)
            .setValue(rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlock.VERTICAL_DIRECTION, Direction.DOWN);
    }

    private static BlockState block(String id, Direction facing) {
        BlockState s = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)).defaultBlockState();
        return s.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, facing);
    }

    private static void build(ServerLevel level) {
        // Railgun: breech south of the mount column, 4 rails to the north
        level.setBlockAndUpdate(RAIL, mount());
        level.setBlockAndUpdate(RAIL.above(2).south(), CbcCompat.RAILGUN_BREECH.get().defaultBlockState().setValue(RailgunBlock.FACING, Direction.NORTH));
        for (int i = 0; i < 4; i++) {
            level.setBlockAndUpdate(RAIL.above(2).north(i), CbcCompat.RAILGUN_RAIL.get().defaultBlockState().setValue(RailgunBlock.FACING, Direction.NORTH));
        }
        // Rotary autocannon: breech south, 2 barrels
        level.setBlockAndUpdate(ROTARY, mount());
        level.setBlockAndUpdate(ROTARY.above(2).south(), block("ashcc:rotary_autocannon_breech", Direction.NORTH));
        for (int i = 0; i < 2; i++) level.setBlockAndUpdate(ROTARY.above(2).north(i), block("ashcc:rotary_autocannon_barrel", Direction.NORTH));
        // CBC's own steel autocannon: breech, recoil-free 3 barrels
        level.setBlockAndUpdate(AUTO, mount());
        level.setBlockAndUpdate(AUTO.above(2).south(), block("createbigcannons:steel_autocannon_breech", Direction.NORTH));
        for (int i = 0; i < 3; i++) level.setBlockAndUpdate(AUTO.above(2).north(i), block("createbigcannons:steel_autocannon_barrel", Direction.NORTH));
        for (BlockPos m : new BlockPos[]{ROTARY, AUTO}) {
            for (int dz = -3; dz <= 1; dz++) {
                rbasamoyai.createbigcannons.cannons.autocannon.AutocannonBlock.onPlace(level, m.above(2).offset(0, 0, dz));
            }
        }
    }

    private static CannonMountPeripheral p(ServerLevel level, BlockPos pos) {
        return (CannonMountPeripheral) level.getCapability(PeripheralCapability.get(), pos, Direction.UP);
    }

    private static void assemble(ServerLevel level) {
        for (BlockPos m : new BlockPos[]{RAIL, ROTARY, AUTO}) {
            try {
                Object[] r = p(level, m).assemble().getResult();
                AshCC.LOGGER.info("CbcShot assemble {}: {}", m, java.util.Arrays.toString(r));
                p(level, m).setAimSpeed(90);
            } catch (Exception e) {
                AshCC.LOGGER.error("CbcShot assemble {} failed", m, e);
            }
        }
        charge(level, RAIL, 100000);
        load(level, RAIL, "createbigcannons:ap_shot", 8);
        load(level, ROTARY, "createbigcannons:machine_gun_round", 32);
        // CBC autocannon ammo is a cartridge holding a round (the round item alone is a crafting part)
        ItemStack cartridge = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("createbigcannons:autocannon_cartridge")), 5);
        rbasamoyai.createbigcannons.munitions.autocannon.AutocannonCartridgeItem.writeProjectile(
            new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("createbigcannons:ap_autocannon_round"))), cartridge);
        load(level, AUTO, cartridge);
    }

    private static void load(ServerLevel level, BlockPos m, ItemStack stack) {
        IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, m, Direction.EAST);
        if (h == null) return;
        ItemStack left = stack;
        for (int s = 0; s < h.getSlots() && !left.isEmpty(); s++) left = h.insertItem(s, left, false);
    }

    private static void charge(ServerLevel level, BlockPos m, int fe) {
        IEnergyStorage e = level.getCapability(Capabilities.EnergyStorage.BLOCK, m, Direction.EAST);
        if (e == null) return;
        int got = 0;
        for (int i = 0; i < 40 && got < fe; i++) {
            int r = e.receiveEnergy(fe - got, false);
            if (r == 0) break;
            got += r;
        }
    }

    private static void load(ServerLevel level, BlockPos m, String id, int count) {
        IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, m, Direction.EAST);
        if (h == null) return;
        ItemStack left = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)), count);
        for (int s = 0; s < h.getSlots() && !left.isEmpty(); s++) left = h.insertItem(s, left, false);
    }

    private static void aim(ServerLevel level, BlockPos m, double yaw, double pitch) {
        try {
            AshCC.LOGGER.info("CbcShot setAim {}: {}", m, java.util.Arrays.toString(p(level, m).setAim(yaw, pitch).getResult()));
        } catch (Exception e) {
            AshCC.LOGGER.error("CbcShot setAim {} failed", m, e);
        }
    }

    private static void fire(ServerLevel level, BlockPos m) {
        try {
            AshCC.LOGGER.info("CbcShot fire {}: {}", m, java.util.Arrays.toString(p(level, m).fire().getResult()));
        } catch (Exception e) {
            AshCC.LOGGER.error("CbcShot fire {} failed", m, e);
        }
    }

    private static void trigger(ServerLevel level, BlockPos m, boolean on) {
        try {
            p(level, m).setFiring(new ObjectArguments(on));
        } catch (Exception e) {
            AshCC.LOGGER.error("CbcShot setFiring {} failed", m, e);
        }
    }

    private static void log(MinecraftServer server, String when) {
        ServerLevel level = server.overworld();
        for (BlockPos m : new BlockPos[]{RAIL, ROTARY, AUTO}) {
            try {
                CannonMountPeripheral p = p(level, m);
                AshCC.LOGGER.info("CbcShot {} {}: cannon={} ammo={} ballistics={} muzzle={}", when, m, p.getCannon(), p.getAmmo(),
                    p.getBallistics(), p.isAssembled() ? p.getMuzzle() : null);
            } catch (Exception e) {
                AshCC.LOGGER.info("CbcShot {} {}: {}", when, m, e.getMessage());
            }
        }
    }

    /** How many wall blocks the shots knocked out. */
    private static void wall(ServerLevel level) {
        int holes = 0;
        for (int x = -12; x <= 28; x++) for (int y = -60; y <= -53; y++) {
            if (level.getBlockState(new BlockPos(x, y, -36)).isAir()) holes++;
        }
        AshCC.LOGGER.info("CbcShot wall: {} of {} blocks gone", holes, 41 * 8);
    }

    // ---- Turret phase (real CC) ----

    private static void turret(MinecraftServer server, BlockPos computerPos) {
        ServerLevel level = server.overworld();
        level.setBlockAndUpdate(computerPos, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("computercraft:computer_advanced")).defaultBlockState());
        level.setBlockAndUpdate(computerPos.above(), AshCC.RADAR.get().defaultBlockState());
        if (!(level.getBlockEntity(computerPos) instanceof dan200.computercraft.shared.computer.blocks.AbstractComputerBlockEntity c)) {
            AshCC.LOGGER.error("CbcShot: no computer at {}", computerPos);
            return;
        }
        var computer = c.createServerComputer();
        Path dir = server.getWorldPath(LevelResource.ROOT).resolve("computercraft/computer/" + computer.getID());
        try {
            Files.createDirectories(dir);
            Files.copy(Path.of(System.getProperty("user.dir")).getParent().resolve("lua/cbc_turret.lua"), dir.resolve("cbc_turret.lua"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(dir.resolve("startup.lua"), "shell.run(\"cbc_turret 256\")\n");
        } catch (java.io.IOException e) {
            AshCC.LOGGER.error("CbcShot: could not write the computer's files", e);
            return;
        }
        if (computer.isOn()) computer.reboot();
        else computer.turnOn();
        AshCC.LOGGER.info("CbcShot: computer {} at {} runs cbc_turret", computer.getID(), computerPos);
    }

    private static void turretLog(MinecraftServer server, BlockPos computerPos) {
        if (!(server.overworld().getBlockEntity(computerPos) instanceof dan200.computercraft.shared.computer.blocks.AbstractComputerBlockEntity c)) return;
        Path p = server.getWorldPath(LevelResource.ROOT).resolve("computercraft/computer/" + c.createServerComputer().getID() + "/cbc_turret.log");
        try {
            AshCC.LOGGER.info("CbcShot turret log of {}:\n{}", computerPos, Files.exists(p) ? Files.readString(p) : "(none)");
        } catch (java.io.IOException e) {
            AshCC.LOGGER.error("CbcShot: could not read {}", p, e);
        }
    }

    private static void screen(MinecraftServer server, BlockPos computerPos, String name) {
        if (!(server.overworld().getBlockEntity(computerPos) instanceof dan200.computercraft.shared.computer.blocks.AbstractComputerBlockEntity c)) return;
        var computer = c.createServerComputer();
        var term = computer.getTerminalState().create();
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < term.getHeight(); y++) {
            String line = term.getLine(y).toString().stripTrailing();
            if (!line.isEmpty()) sb.append("\n    | ").append(line);
        }
        AshCC.LOGGER.info("CbcShot {} screen (on={}):{}", name, computer.isOn(), sb);
    }

    private static void cmd(MinecraftServer server, String command) {
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command));
    }

    private static void shot(Minecraft mc, String name) {
        new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            mc.execute(() -> Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), msg -> AshCC.LOGGER.info("CbcShot: {}", msg.getString())));
        }).start();
    }

    /** Sable types stay in here. */
    private static final class Target {
        static Object build(ServerLevel level) {
            // 5x5 iron deck with an anti-gravity core in the middle: weightless and holding still by default
            java.util.List<BlockPos> blocks = new java.util.ArrayList<>();
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                BlockPos p = CORE.offset(x, 0, z);
                level.setBlockAndUpdate(p, x == 0 && z == 0 ? AshCC.ANTIGRAV.get().defaultBlockState() : Blocks.IRON_BLOCK.defaultBlockState());
                blocks.add(p);
            }
            var sub = dev.ryanhcode.sable.api.SubLevelAssemblyHelper.assembleBlocks(level, CORE, blocks,
                new dev.ryanhcode.sable.companion.math.BoundingBox3i(CORE.offset(-2, 0, -2), CORE.offset(2, 0, 2)));
            AshCC.LOGGER.info("CbcShot: target ship {}", sub == null ? null : sub.getUniqueId());
            if (core(level, sub) instanceof AntigravBlockEntity c) c.setEnabled(true);
            return sub;
        }

        static AntigravBlockEntity core(ServerLevel level, Object ship) {
            if (!(ship instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel sub)) return null;
            var plot = sub.getPlot();
            for (int cx = plot.getChunkMin().x; cx <= plot.getChunkMax().x; cx++) {
                for (int cz = plot.getChunkMin().z; cz <= plot.getChunkMax().z; cz++) {
                    for (BlockEntity be : level.getChunk(cx, cz).getBlockEntities().values()) {
                        if (be instanceof AntigravBlockEntity c) return c;
                    }
                }
            }
            return null;
        }

        static void log(ServerLevel level, Object ship) {
            if (!(ship instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel sub)) return;
            AntigravBlockEntity c = core(level, ship);
            ShipState s = c == null ? null : c.shipState();
            Vec3 p = s == null ? null : s.position();
            AshCC.LOGGER.info("CbcShot target: removed={} mass={} pos={}", sub.isRemoved(), sub.getMassTracker() == null ? null : sub.getMassTracker().getMass(),
                p == null ? null : String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", p.x, p.y, p.z));
        }
    }
}
