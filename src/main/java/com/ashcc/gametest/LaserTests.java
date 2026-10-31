package com.ashcc.gametest;

import com.ashcc.AshCC;
import com.ashcc.laser.LaserBlock;
import com.ashcc.laser.LaserBlockEntity;
import com.ashcc.laser.LaserPeripheral;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.lua.ObjectArguments;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;

/** Laser behaviour that does not need Sable. Ship tests are in {@link SableLaserTests}. */
@GameTestHolder(AshCC.MOD_ID)
@PrefixGameTestTemplate(false)
public class LaserTests {
    /** Places a laser already turned to yaw/pitch (yaw 0 = north, 90 = east; pitch 90 = up). */
    static LaserBlockEntity placeLaser(GameTestHelper helper, BlockPos pos, double yaw, double pitch) {
        helper.setBlock(pos, AshCC.LASER.get().defaultBlockState());
        LaserBlockEntity laser = (LaserBlockEntity) helper.getBlockEntity(pos);
        laser.aimNow(yaw, pitch);
        return laser;
    }

    static Object[] values(MethodResult r) {
        return r.getResult();
    }

    /** Stone (hardness 1.5) burns through in 3 ticks at the default 0.5 per tick; the laser reports the hit first. */
    @GameTest(template = RadarTests.EMPTY)
    public static void burnsThroughBlock(GameTestHelper helper) throws LuaException {
        LaserBlockEntity laser = placeLaser(helper, new BlockPos(1, 2, 4), 90, 0);
        helper.setBlock(new BlockPos(6, 2, 4), Blocks.STONE);
        LaserPeripheral p = laser.peripheral();

        Map<String, Object> trace = p.trace();
        helper.assertTrue("block".equals(trace.get("type")) && "minecraft:stone".equals(trace.get("block")), "trace " + trace);
        helper.assertTrue(Math.abs(RadarTests.num(trace, "x") - helper.absoluteVec(new Vec3(6, 2.5, 4.5)).x) < 1e-6, "trace ends on the stone face " + trace);
        helper.assertTrue(Boolean.TRUE.equals(values(p.fire(new ObjectArguments()))[0]), "fire() refused");

        helper.runAfterDelay(1, () -> {
            helper.assertTrue(p.isFiring(), "not firing");
            helper.assertBlockProperty(new BlockPos(1, 2, 4), LaserBlock.ACTIVE, true);
            Map<String, Object> hit = p.getHit();
            helper.assertTrue(hit != null && "block".equals(hit.get("type")), "getHit " + hit);
        });
        helper.runAfterDelay(6, () -> {
            helper.assertBlockPresent(Blocks.AIR, new BlockPos(6, 2, 4));
            helper.assertTrue(laser.brokenCount() == 1, "broken " + laser.brokenCount());
            p.stop();
            helper.runAfterDelay(2, () -> {
                helper.assertTrue(!p.isFiring() && p.getHit() == null, "still firing after stop()");
                helper.assertBlockProperty(new BlockPos(1, 2, 4), LaserBlock.ACTIVE, false);
                helper.succeed();
            });
        });
    }

    /** aimAt turns the beam onto any block around it, behind included; setAim wraps yaw and clamps pitch. */
    @GameTest(template = RadarTests.EMPTY)
    public static void aimsAtPoints(GameTestHelper helper) throws LuaException {
        BlockPos lp = new BlockPos(4, 2, 7);
        LaserBlockEntity laser = placeLaser(helper, lp, 0, 0);
        LaserPeripheral p = laser.peripheral();
        // Yaw 0 = north, positive towards east: a block 3 east and 4 north is at yaw ~37
        BlockPos target = new BlockPos(7, 2, 3);
        helper.setBlock(target, Blocks.OBSIDIAN);
        Vec3 c = helper.absoluteVec(Vec3.atCenterOf(target));
        Object[] r = values(p.aimAt(c.x, c.y, c.z));
        helper.assertTrue(Boolean.TRUE.equals(r[0]), "aimAt refused");
        double yaw = (Double) r[1];
        helper.assertTrue(yaw > 30 && yaw < 45 && Math.abs((Double) r[2]) < 1, "aim " + yaw + ", " + r[2]);

        // Straight south (behind the old "front") and straight down are reachable too
        Vec3 behind = helper.absoluteVec(new Vec3(4.5, 2.5, 8.5));
        Object[] back = values(p.aimAt(behind.x, behind.y, behind.z));
        helper.assertTrue(Boolean.TRUE.equals(back[0]) && Math.abs(Math.abs((Double) back[1]) - 180) < 1e-6 && Math.abs((Double) back[2]) < 1e-6,
            "aim south " + back[1] + ", " + back[2]);
        Vec3 below = helper.absoluteVec(new Vec3(4.5, 0.5, 7.5));
        Object[] down = values(p.aimAt(below.x, below.y, below.z));
        helper.assertTrue(Math.abs((Double) down[2] + 90) < 1e-6, "aim down " + down[2]);
        Object[] wrapped = values(p.setAim(190, 100));
        helper.assertTrue(Math.abs((Double) wrapped[0] + 170) < 1e-9 && (Double) wrapped[1] == 90, "setAim wrap/clamp " + wrapped[0] + ", " + wrapped[1]);

        p.aimAt(c.x, c.y, c.z);
        helper.runAfterDelay(10, () -> {
            try {
                helper.assertTrue(p.isAimed(), "aim not reached");
                Map<String, Object> t = p.trace();
                helper.assertTrue("minecraft:obsidian".equals(t.get("block")), "trace after aimAt " + t);
                Map<String, Object> dir = p.getDirection();
                helper.assertTrue(RadarTests.num(dir, "x") > 0.5 && RadarTests.num(dir, "z") < -0.5, "direction " + dir);
                helper.succeed();
            } catch (LuaException e) {
                helper.fail(e.getMessage());
            }
        });
    }

    /** Entities in the beam are hurt, set on fire and stop the beam before the block behind them. */
    @GameTest(template = RadarTests.EMPTY)
    public static void hurtsEntities(GameTestHelper helper) throws LuaException {
        LaserBlockEntity laser = placeLaser(helper, new BlockPos(1, 2, 4), 90, 0);
        helper.setBlock(new BlockPos(7, 2, 4), Blocks.OBSIDIAN);
        Pig pig = helper.spawnWithNoFreeWill(EntityType.PIG, new Vec3(4.5, 2, 4.5));
        pig.setNoGravity(true);
        LaserPeripheral p = laser.peripheral();
        Map<String, Object> t = p.trace();
        helper.assertTrue("entity".equals(t.get("type")), "trace should stop at the pig: " + t);
        p.fire(new ObjectArguments(1.0));
        helper.runAfterDelay(3, () -> {
            helper.assertTrue(pig.getHealth() < pig.getMaxHealth(), "pig not hurt: " + pig.getHealth());
            helper.assertTrue(pig.isOnFire(), "pig not burning");
            helper.assertBlockPresent(Blocks.OBSIDIAN, new BlockPos(7, 2, 4));
            // fire(1) stops by itself after 20 ticks
            helper.runAfterDelay(20, () -> {
                helper.assertTrue(!p.isFiring(), "fire(1) still firing");
                helper.succeed();
            });
        });
    }

    /** Full heat stops the beam until it has cooled down; redstone fires it again afterwards. */
    @GameTest(template = RadarTests.EMPTY, timeoutTicks = 300)
    public static void overheatsAndCools(GameTestHelper helper) {
        BlockPos lp = new BlockPos(1, 2, 4);
        LaserBlockEntity laser = placeLaser(helper, lp, 90, 0);
        LaserPeripheral p = laser.peripheral();
        laser.setHeat(0.995);
        helper.setBlock(lp.north(), Blocks.REDSTONE_BLOCK);
        helper.runAfterDelay(3, () -> {
            helper.assertTrue(p.getRedstoneInput() == 15, "redstone " + p.getRedstoneInput());
            helper.assertTrue(p.isOverheated() && !p.isFiring(), "should have overheated, heat " + p.getHeat());
            helper.assertTrue(p.getHeat() < 1, "not cooling");
        });
        // Default cooldown is 5 s from full heat
        helper.runAfterDelay(110, () -> {
            helper.assertTrue(!p.isOverheated() && p.isFiring(), "should fire again after cooling, heat " + p.getHeat());
            p.stop();
            helper.succeed();
        });
    }
}
