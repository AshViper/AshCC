package com.ashcc.gametest;

import com.ashcc.AshCC;
import com.defpwoq.createarms.block.AbstractShipMissileBlockEntity;
import com.defpwoq.createarms.block.CicTerminalBlockEntity;
import com.defpwoq.createarms.block.CountermeasureDispenserBlockEntity;
import com.defpwoq.createarms.block.MunitionRackBlockEntity;
import com.defpwoq.createarms.block.VlsHatchBlockEntity;
import com.defpwoq.createarms.register.ModBlocks;
import com.defpwoq.createarms.register.ModItems;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.ObjectArguments;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** CC peripherals on createarms blocks (run only when createarms is in run-gt/mods). */
@GameTestHolder(AshCC.MOD_ID)
@PrefixGameTestTemplate(false)
public class CreateArmsTests {
    private static boolean skip(GameTestHelper helper) {
        if (AshCC.sableLoaded && ModList.get().isLoaded("createarms")) return false;
        helper.succeed();
        return true;
    }

    /** Rack fire at a point: the missile is a live sub-level we can read, retarget without resetting, and detonate. */
    @GameTest(template = "empty_17", timeoutTicks = 120, batch = "createarms_rack")
    public static void rackFiresAndSteers(GameTestHelper helper) {
        if (!skip(helper)) Impl.rack(helper);
    }

    /** VLS launch at a point, and the empty-cell / missing-structure errors. */
    @GameTest(template = "empty_17", timeoutTicks = 80, batch = "createarms_vls")
    public static void vlsLaunches(GameTestHelper helper) {
        if (!skip(helper)) Impl.vls(helper);
    }

    /** A ship id target: the VLS missile must follow that ship (createarms' VLS launch alone only keeps a point). */
    @GameTest(template = "empty_17", timeoutTicks = 60, batch = "createarms_ship")
    public static void vlsFollowsShip(GameTestHelper helper) {
        if (!skip(helper)) Impl.vlsShip(helper);
    }

    /** CIC queue launch: the missile appears after the hatch delay and is ours to steer. */
    @GameTest(template = "empty_17", timeoutTicks = 200, batch = "createarms_cic")
    public static void cicQueuesLaunch(GameTestHelper helper) {
        if (!skip(helper)) Impl.cic(helper);
    }

    @GameTest(template = RadarTests.EMPTY, batch = "createarms_cmds")
    public static void dispenserDeploys(GameTestHelper helper) {
        if (!skip(helper)) Impl.dispenser(helper);
    }

    private static final class Impl {
        static IPeripheral peripheral(GameTestHelper helper, BlockPos rel) {
            IPeripheral p = helper.getLevel().getCapability(PeripheralCapability.get(), helper.absolutePos(rel), Direction.UP);
            helper.assertTrue(p != null, "no peripheral at " + rel);
            return p;
        }

        static Object call(IPeripheral p, String method, Object... args) {
            try {
                Object[] r = CreateArmsTestAccess.call(p, method, new ObjectArguments(args));
                return r == null || r.length == 0 ? null : r[0];
            } catch (LuaException e) {
                throw new IllegalStateException(method + ": " + e.getMessage(), e);
            }
        }

        static Object[] callAll(IPeripheral p, String method, Object... args) {
            try {
                return CreateArmsTestAccess.call(p, method, new ObjectArguments(args));
            } catch (LuaException e) {
                throw new IllegalStateException(method + ": " + e.getMessage(), e);
            }
        }

        static Map<String, Object> point(Vec3 v) {
            return Map.of("x", v.x, "y", v.y, "z", v.z);
        }

        static void rack(GameTestHelper helper) {
            BlockPos rackPos = new BlockPos(8, 8, 8);
            helper.setBlock(rackPos, ModBlocks.MUNITION_RACK.get());
            MunitionRackBlockEntity rack = (MunitionRackBlockEntity) helper.getBlockEntity(rackPos);
            rack.setStoredItem(new ItemStack(ModItems.AIM_9_SIDEWINDER_ITEM.get()));
            IPeripheral p = peripheral(helper, rackPos);
            helper.assertTrue(p.getType().equals("missile_rack"), "type " + p.getType());
            @SuppressWarnings("unchecked") Map<String, Object> loaded = (Map<String, Object>) call(p, "getMissile");
            helper.assertTrue(loaded != null && "aim_9_sidewinder".equals(loaded.get("name")) && Boolean.TRUE.equals(loaded.get("firable")), "getMissile " + loaded);

            Vec3 goal = Vec3.atCenterOf(helper.absolutePos(new BlockPos(8, 8, 8))).add(0, 60, 400);
            Object id = call(p, "fire", point(goal));
            helper.assertTrue(id instanceof String, "fire returned " + id);
            helper.assertTrue(rack.getStoredItem().isEmpty(), "rack still holds the missile");
            Object[] again = callAll(p, "fire");
            helper.assertTrue(again[0] == null && "empty".equals(again[1]), "second fire did not report empty");

            helper.runAfterDelay(20, () -> {
                AbstractShipMissileBlockEntity missile = CreateArmsTestAccess.shipMissile(helper.getLevel(), UUID.fromString((String) id));
                helper.assertTrue(missile != null && missile.isActive(), "no active missile sub-level");
                @SuppressWarnings("unchecked") Map<String, Object> info = (Map<String, Object>) call(p, "getMissileInfo", id);
                helper.assertTrue(info != null && "aim9_block".equals(info.get("name")) && "ir".equals(info.get("guidance")), "info " + info);
                @SuppressWarnings("unchecked") List<Object> mine = (List<Object>) call(p, "getMissiles");
                helper.assertTrue(mine.size() == 1, "getMissiles " + mine.size());

                int age = missile.getTicksAlive();
                Vec3 other = goal.add(300, 0, 0);
                helper.assertTrue(Boolean.TRUE.equals(call(p, "retarget", id, other.x, other.y, other.z)), "retarget refused");
                helper.assertTrue(missile.getTicksAlive() >= age, "retarget restarted the flight");
                @SuppressWarnings("unchecked") Map<String, Object> target = (Map<String, Object>) ((Map<String, Object>) call(p, "getMissileInfo", id)).get("target");
                helper.assertTrue(Math.abs(((Number) target.get("x")).doubleValue() - other.x) < 1e-6, "target not moved: " + target);

                // Another rack's computer cannot steer it
                helper.setBlock(new BlockPos(2, 8, 2), ModBlocks.MUNITION_RACK.get());
                IPeripheral stranger = peripheral(helper, new BlockPos(2, 8, 2));
                helper.assertTrue(call(stranger, "getMissileInfo", id) == null, "foreign rack sees our missile");
                helper.assertTrue(Boolean.FALSE.equals(call(stranger, "detonate", id)), "foreign rack detonated it");

                helper.assertTrue(Boolean.TRUE.equals(call(p, "detonate", id)), "detonate refused");
                helper.succeedWhen(() -> helper.assertTrue(CreateArmsTestAccess.shipMissile(helper.getLevel(), UUID.fromString((String) id)) == null,
                    "missile still there"));
            });
        }

        static BlockPos buildVls(GameTestHelper helper, int x, int z) {
            for (int y = 1; y <= 6; y++) helper.setBlock(new BlockPos(x, y, z), ModBlocks.VLS_CELL.get());
            BlockPos hatch = new BlockPos(x, 7, z);
            helper.setBlock(hatch, ModBlocks.VLS_HATCH.get());
            return hatch;
        }

        static void vls(GameTestHelper helper) {
            BlockPos hatch = buildVls(helper, 8, 8);
            VlsHatchBlockEntity vls = (VlsHatchBlockEntity) helper.getBlockEntity(hatch);
            IPeripheral p = peripheral(helper, hatch);
            helper.assertTrue(p.getType().equals("vls"), "type " + p.getType());
            Object[] empty = callAll(p, "launch");
            helper.assertTrue(empty[0] == null && "empty".equals(empty[1]), "empty cell launched");
            vls.setStoredItem(new ItemStack(ModItems.PATRIOT_ITEM.get()));
            helper.assertTrue(Boolean.TRUE.equals(call(p, "isReady")), "not ready");

            Vec3 goal = Vec3.atCenterOf(helper.absolutePos(hatch)).add(200, 80, 0);
            Object id = call(p, "launch", point(goal));
            helper.assertTrue(id instanceof String, "launch returned " + id);
            helper.assertTrue(vls.getStoredItem().isEmpty(), "cell still loaded");

            // A hatch without its cells
            helper.setBlock(new BlockPos(3, 7, 3), ModBlocks.VLS_HATCH.get());
            ((VlsHatchBlockEntity) helper.getBlockEntity(new BlockPos(3, 7, 3))).setStoredItem(new ItemStack(ModItems.PATRIOT_ITEM.get()));
            Object[] noCell = callAll(peripheral(helper, new BlockPos(3, 7, 3)), "launch");
            helper.assertTrue(noCell[0] == null && "no cell".equals(noCell[1]), "hatch without cells launched");

            helper.runAfterDelay(10, () -> {
                AbstractShipMissileBlockEntity missile = CreateArmsTestAccess.shipMissile(helper.getLevel(), UUID.fromString((String) id));
                helper.assertTrue(missile != null && missile.isActive(), "no active VLS missile");
                helper.assertTrue(missile.getPersistentData().getBoolean("IsVlsLaunch"), "not marked as a VLS launch");
                helper.assertTrue(call(p, "getMissileInfo", id) != null, "VLS cannot see its missile");
                missile.explode();
                helper.succeed();
            });
        }

        static void vlsShip(GameTestHelper helper) {
            helper.setBlock(new BlockPos(14, 12, 14), net.minecraft.world.level.block.Blocks.IRON_BLOCK);
            BlockPos shipBlock = helper.absolutePos(new BlockPos(14, 12, 14));
            var ship = dev.ryanhcode.sable.api.SubLevelAssemblyHelper.assembleBlocks(helper.getLevel(), shipBlock, List.of(shipBlock),
                new dev.ryanhcode.sable.companion.math.BoundingBox3i(shipBlock, shipBlock));
            String shipId = ship.getUniqueId().toString();
            BlockPos hatch = buildVls(helper, 4, 4);
            ((VlsHatchBlockEntity) helper.getBlockEntity(hatch)).setStoredItem(new ItemStack(ModItems.PATRIOT_ITEM.get()));
            IPeripheral p = peripheral(helper, hatch);
            Object id = call(p, "launch", shipId);
            helper.assertTrue(id instanceof String, "launch returned " + id);
            helper.runAfterDelay(5, () -> {
                AbstractShipMissileBlockEntity missile = CreateArmsTestAccess.shipMissile(helper.getLevel(), UUID.fromString((String) id));
                helper.assertTrue(missile != null, "no missile");
                helper.assertTrue(ship.getUniqueId().equals(missile.getTargetEntityUUID()), "missile does not follow the ship: " + missile.getTargetEntityUUID());
                @SuppressWarnings("unchecked") Map<String, Object> info = (Map<String, Object>) call(p, "getMissileInfo", id);
                helper.assertTrue(shipId.equals(info.get("targetId")), "targetId " + info.get("targetId"));
                missile.explode();
                TestShips.remove(helper, ship);
                helper.succeed();
            });
        }

        static void cic(GameTestHelper helper) {
            BlockPos hatch = buildVls(helper, 8, 8);
            VlsHatchBlockEntity vls = (VlsHatchBlockEntity) helper.getBlockEntity(hatch);
            vls.setStoredItem(new ItemStack(ModItems.PATRIOT_ITEM.get()));
            BlockPos cicPos = new BlockPos(3, 1, 3);
            helper.setBlock(cicPos, ModBlocks.CIC_TERMINAL.get());
            CicTerminalBlockEntity cic = (CicTerminalBlockEntity) helper.getBlockEntity(cicPos);
            cic.addLinkedVls(helper.absolutePos(hatch));
            IPeripheral p = peripheral(helper, cicPos);
            helper.assertTrue(p.getType().equals("cic_terminal"), "type " + p.getType());
            @SuppressWarnings("unchecked") List<Map<String, Object>> cells = (List<Map<String, Object>>) call(p, "getLinkedVls");
            helper.assertTrue(cells.size() == 1 && Boolean.TRUE.equals(cells.get(0).get("ready")), "cells " + cells);

            Vec3 goal = Vec3.atCenterOf(helper.absolutePos(hatch)).add(0, 80, 200);
            Object queued = call(p, "launch", point(goal), 1);
            helper.assertTrue(queued instanceof Number n && n.intValue() == 1, "queued " + queued);
            helper.succeedWhen(() -> {
                @SuppressWarnings("unchecked") List<Map<String, Object>> mine = (List<Map<String, Object>>) call(p, "getMissiles");
                helper.assertTrue(mine.size() == 1, "CIC missiles: " + mine.size());
                helper.assertTrue(CreateArmsTestAccess.isOwned(helper.getLevel(), UUID.fromString((String) mine.get(0).get("id"))), "not tagged");
                AbstractShipMissileBlockEntity missile = CreateArmsTestAccess.shipMissile(helper.getLevel(), UUID.fromString((String) mine.get(0).get("id")));
                if (missile != null) missile.explode();
            });
        }

        static void dispenser(GameTestHelper helper) {
            BlockPos pos = new BlockPos(4, 1, 4);
            helper.setBlock(pos, ModBlocks.COUNTERMEASURE_DISPENSER.get());
            CountermeasureDispenserBlockEntity d = (CountermeasureDispenserBlockEntity) helper.getBlockEntity(pos);
            d.addCountermeasure(new ItemStack(ModItems.FLARE_ITEM.get(), 3));
            IPeripheral p = peripheral(helper, pos);
            helper.assertTrue(((Number) call(p, "getCount")).intValue() == 3, "count");
            callAll(p, "setMode", "flare");
            helper.assertTrue(Boolean.TRUE.equals(call(p, "deploy")), "deploy failed");
            helper.assertTrue(Boolean.FALSE.equals(call(p, "deploy")), "deployed during cooldown");
            helper.assertTrue(((Number) call(p, "getCount")).intValue() == 2, "count after deploy");
            helper.succeed();
        }
    }
}
