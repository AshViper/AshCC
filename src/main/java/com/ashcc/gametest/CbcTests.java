package com.ashcc.gametest;

import com.ashcc.AshCC;
import com.ashcc.compat.cbc.CannonBallistics;
import com.ashcc.compat.cbc.CannonMountPeripheral;
import com.ashcc.compat.cbc.CbcCompat;
import com.ashcc.compat.cbc.LineCannonBlock;
import com.ashcc.compat.cbc.RailgunBlock;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.lua.ObjectArguments;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlock;
import rbasamoyai.createbigcannons.cannons.autocannon.AutocannonBaseBlock;
import rbasamoyai.createbigcannons.cannons.autocannon.AutocannonBlock;
import rbasamoyai.createbigcannons.index.CBCBlocks;
import rbasamoyai.createbigcannons.munitions.autocannon.AbstractAutocannonProjectile;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;
import rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock;
import net.minecraft.world.item.BlockItem;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** CC cannon mounts, the rotary autocannon and the railgun (run only when Create Big Cannons is in run-gt/mods). */
@GameTestHolder(AshCC.MOD_ID)
@PrefixGameTestTemplate(false)
public class CbcTests {
    private static boolean skip(GameTestHelper helper) {
        if (ModList.get().isLoaded("createbigcannons")) return false;
        helper.succeed();
        return true;
    }

    /** Railgun: assembles on a mount, charges through it, turns with the AshCC angle convention and fires a CBC shot. */
    @GameTest(template = "empty_17", timeoutTicks = 200, batch = "cbc_railgun")
    public static void railgunAimsAndFires(GameTestHelper helper) {
        if (!skip(helper)) Impl.railgun(helper);
    }

    /** The aim solver behind aimAt: a real CBC shot fired at the solved pitch passes through the target point. */
    @GameTest(template = "empty_17", timeoutTicks = 200, batch = "cbc_ballistics")
    public static void aimSolverMatchesCbcFlight(GameTestHelper helper) {
        if (!skip(helper)) Impl.ballistics(helper);
    }

    /** Assembly errors are reported to Lua. */
    @GameTest(template = "empty_17", batch = "cbc_errors")
    public static void railgunAssemblyErrors(GameTestHelper helper) {
        if (!skip(helper)) Impl.errors(helper);
    }

    /** Laser cannon: fires under setFiring while it has energy, burns through a block ahead, heats up, stops when released. */
    @GameTest(template = "empty_17", timeoutTicks = 120, batch = "cbc_laser")
    public static void laserCannonBurnsThrough(GameTestHelper helper) {
        if (!skip(helper)) Impl.laser(helper);
    }

    /** Rotary autocannon: spins up under setFiring, fires many rounds, spins down when released. */
    @GameTest(template = "empty_17", timeoutTicks = 260, batch = "cbc_rotary")
    public static void rotaryAutocannonSpinsAndFires(GameTestHelper helper) {
        if (!skip(helper)) Impl.rotary(helper);
    }

    private static final class Impl {
        static final BlockPos MOUNT = new BlockPos(8, 1, 8);

        static CannonMountPeripheral mount(GameTestHelper helper) {
            helper.setBlock(MOUNT, CBCBlocks.CANNON_MOUNT.get().defaultBlockState()
                .setValue(CannonMountBlock.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(CannonMountBlock.VERTICAL_DIRECTION, Direction.DOWN));
            IPeripheral p = helper.getLevel().getCapability(PeripheralCapability.get(), helper.absolutePos(MOUNT), Direction.UP);
            helper.assertTrue(p instanceof CannonMountPeripheral, "no cannon_mount peripheral: " + p);
            return (CannonMountPeripheral) p;
        }

        /** Muzzle speed of the 3-rail railgun the tests build (blocks/tick). */
        static double railSpeed() {
            return Math.min(com.ashcc.AshCCConfig.RAILGUN_BASE_SPEED.get() + 2 * com.ashcc.AshCCConfig.RAILGUN_SPEED_PER_RAIL.get(),
                com.ashcc.AshCCConfig.RAILGUN_MAX_SPEED.get());
        }

        static BlockState rail(boolean breech, Direction facing) {
            return (breech ? CbcCompat.RAILGUN_BREECH : CbcCompat.RAILGUN_RAIL).get().defaultBlockState().setValue(RailgunBlock.FACING, facing);
        }

        /** Breech at z=9 facing north, rails at z=8..6: the cannon block above the mount is the first rail. */
        static void buildRailgun(GameTestHelper helper) {
            helper.setBlock(new BlockPos(8, 3, 9), rail(true, Direction.NORTH));
            for (int z = 6; z <= 8; z++) helper.setBlock(new BlockPos(8, 3, z), rail(false, Direction.NORTH));
        }

        static ItemStack item(String id, int count) {
            return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)), count);
        }

        static Object[] r(MethodResult m) {
            return m.getResult();
        }

        static void charge(GameTestHelper helper, int fe) {
            IEnergyStorage e = helper.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, helper.absolutePos(MOUNT), Direction.EAST);
            helper.assertTrue(e != null && e.canReceive(), "mount takes no energy");
            int got = 0;
            for (int i = 0; i < 100 && got < fe; i++) got += e.receiveEnergy(fe - got, false);
            helper.assertTrue(got >= fe, "charged only " + got);
        }

        static void load(GameTestHelper helper, ItemStack stack) {
            IItemHandler h = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(MOUNT), Direction.EAST);
            helper.assertTrue(h != null, "mount has no item handler");
            ItemStack left = stack;
            for (int s = 0; s < h.getSlots() && !left.isEmpty(); s++) left = h.insertItem(s, left, false);
            helper.assertTrue(left.getCount() < stack.getCount(), "nothing loaded: " + stack);
        }

        static List<AbstractBigCannonProjectile> shells(GameTestHelper helper) {
            AABB box = new AABB(helper.absolutePos(MOUNT)).inflate(128);
            return helper.getLevel().getEntitiesOfClass(AbstractBigCannonProjectile.class, box);
        }

        static void railgun(GameTestHelper helper) {
            CannonMountPeripheral p = mount(helper);
            buildRailgun(helper);
            try {
                Object[] a = r(p.assemble());
                helper.assertTrue(Boolean.TRUE.equals(a[0]), "assemble: " + Arrays.toString(a));
                Map<String, Object> c = p.getCannon();
                helper.assertTrue(c != null && "railgun".equals(c.get("type")) && Integer.valueOf(3).equals(c.get("rails")), "cannon " + c);
                charge(helper, 40000);
                load(helper, item("createbigcannons:ap_shot", 2));
                Map<String, Object> ammo = p.getAmmo();
                helper.assertTrue(ammo != null && Integer.valueOf(2).equals(ammo.get("count")), "ammo " + ammo);
                Map<String, Object> b = p.getBallistics();
                helper.assertTrue(b != null && Math.abs((Double) b.get("speed") - railSpeed() * 20) < 1e-6, "ballistics " + b);
                p.setAimSpeed(180);
                Object[] aim = r(p.setAim(30, 10));
                helper.assertTrue(Math.abs((Double) aim[0] - 30) < 1e-6 && Math.abs((Double) aim[1] - 10) < 1e-6, "setAim " + Arrays.toString(aim));
            } catch (LuaException e) {
                helper.fail(e.getMessage());
            }
            helper.runAfterDelay(10, () -> {
                try {
                    helper.assertTrue(p.isAimed(), "not aimed: " + Arrays.toString(r(p.getAim())));
                    // yaw 30 = 30 degrees east of north, pitch 10 up
                    @SuppressWarnings("unchecked")
                    Map<String, Object> d = (Map<String, Object>) p.getMuzzle().get("direction");
                    Vec3 dir = new Vec3((Double) d.get("x"), (Double) d.get("y"), (Double) d.get("z"));
                    double y = Math.toRadians(30), pi = Math.toRadians(10);
                    Vec3 want = new Vec3(Math.sin(y) * Math.cos(pi), Math.sin(pi), -Math.cos(y) * Math.cos(pi));
                    helper.assertTrue(dir.distanceTo(want) < 1e-3, "barrel direction " + dir + " want " + want);
                    Object[] f = r(p.fire());
                    helper.assertTrue(Boolean.TRUE.equals(f[0]), "fire: " + Arrays.toString(f));
                    Object[] again = r(p.fire());
                    helper.assertTrue(Boolean.FALSE.equals(again[0]) && "cooling down".equals(again[1]), "second fire: " + Arrays.toString(again));
                    Map<String, Object> c = p.getCannon();
                    helper.assertTrue(Integer.valueOf(20000).equals(c.get("energy")), "energy after shot " + c);
                    List<AbstractBigCannonProjectile> s = shells(helper);
                    helper.assertTrue(s.size() == 1, "shells " + s.size());
                    Vec3 v = s.getFirst().getDeltaMovement();
                    helper.assertTrue(Math.abs(v.length() - railSpeed()) < 0.5 && v.normalize().distanceTo(want) < 0.05, "shell velocity " + v);
                } catch (LuaException e) {
                    helper.fail(e.getMessage());
                }
                helper.succeed();
            });
        }

        /**
         * The solver's flight model against a real CBC AP shot, shot above the test's barrier box (shots inside it stop at
         * the barriers 8 blocks out): slow enough that gravity and drag bend it a lot over 14 blocks.
         */
        static void ballistics(GameTestHelper helper) {
            ItemStack round = item("createbigcannons:ap_shot", 1);
            AbstractBigCannonProjectile shell = ((ProjectileBlock<?>) ((BlockItem) round.getItem()).getBlock()).getProjectile(helper.getLevel(), round);
            double speed = 1.5;
            CannonBallistics.Shot shot = CannonBallistics.railgunShot(helper.getLevel(), shell, speed, 0, "");
            helper.assertTrue(shot.gravity() < 0 && shot.drag() > 0, "ballistics " + shot);
            Vec3 start = Vec3.atCenterOf(helper.absolutePos(new BlockPos(8, 40, 15)));
            Vec3 goal = start.add(0, -10, -14);
            double[] sol = CannonBallistics.solvePitch(shot, 14, -10, false);
            helper.assertTrue(sol != null, "no solution");
            double pr = Math.toRadians(sol[0]);
            Vec3 dir = new Vec3(0, Math.sin(pr), -Math.cos(pr));
            shell.setPos(start);
            shell.shoot(dir.x, dir.y, dir.z, (float) speed, 0.0f);
            helper.getLevel().addFreshEntity(shell);

            double[] best = {Double.MAX_VALUE};
            Vec3[] last = {start};
            helper.onEachTick(() -> {
                if (shell.isRemoved()) return;
                // closest approach on the segment flown this tick
                Vec3 a = last[0], b = shell.position(), ab = b.subtract(a);
                double t = ab.lengthSqr() < 1e-9 ? 0 : Math.max(0, Math.min(1, goal.subtract(a).dot(ab) / ab.lengthSqr()));
                best[0] = Math.min(best[0], a.add(ab.scale(t)).distanceTo(goal));
                last[0] = b;
            });
            int ticks = (int) Math.ceil(sol[1]) + 5;
            helper.runAfterDelay(ticks, () -> {
                helper.assertTrue(best[0] < 0.3, "missed by " + best[0] + " (pitch " + sol[0] + ", " + sol[1] + " ticks)");
                shell.discard();
                helper.succeed();
            });
        }

        static void errors(GameTestHelper helper) {
            CannonMountPeripheral p = mount(helper);
            // Breech facing away from its rail (towards the north, with the rail south of it)
            helper.setBlock(new BlockPos(8, 3, 9), rail(false, Direction.NORTH));
            helper.setBlock(new BlockPos(8, 3, 8), rail(true, Direction.NORTH));
            try {
                Object[] a = r(p.assemble());
                helper.assertTrue(Boolean.FALSE.equals(a[0]) && a[1] instanceof String s && s.contains("breech"), "wrong breech: " + Arrays.toString(a));
                helper.assertTrue(!p.isAssembled(), "assembled anyway");
                // Rails only
                helper.setBlock(new BlockPos(8, 3, 8), rail(false, Direction.NORTH));
                Object[] b = r(p.assemble());
                helper.assertTrue(Boolean.FALSE.equals(b[0]), "no breech: " + Arrays.toString(b));
            } catch (LuaException e) {
                helper.fail(e.getMessage());
            }
            helper.succeed();
        }

        static void laser(GameTestHelper helper) {
            CannonMountPeripheral p = mount(helper);
            // Emitter at z=9 facing north, 3 lenses at z=8..6; an iron block 3 blocks ahead of the front lens
            helper.setBlock(new BlockPos(8, 3, 9), CbcCompat.LASER_EMITTER.get().defaultBlockState().setValue(LineCannonBlock.FACING, Direction.NORTH));
            for (int z = 6; z <= 8; z++) {
                helper.setBlock(new BlockPos(8, 3, z), CbcCompat.LASER_LENS.get().defaultBlockState().setValue(LineCannonBlock.FACING, Direction.NORTH));
            }
            BlockPos target = new BlockPos(8, 3, 2);
            helper.setBlock(target, net.minecraft.world.level.block.Blocks.IRON_BLOCK);
            try {
                Object[] a = r(p.assemble());
                helper.assertTrue(Boolean.TRUE.equals(a[0]), "assemble: " + Arrays.toString(a));
                Map<String, Object> c = p.getCannon();
                helper.assertTrue(c != null && "laser_cannon".equals(c.get("type")) && Integer.valueOf(3).equals(c.get("lenses")), "cannon " + c);
                // No energy yet: the trigger is held but nothing fires
                p.setFiring(new ObjectArguments(true));
            } catch (LuaException e) {
                helper.fail(e.getMessage());
            }
            helper.runAfterDelay(3, () -> {
                try {
                    helper.assertTrue(!p.isFiring(), "fires without energy");
                    helper.assertBlockPresent(net.minecraft.world.level.block.Blocks.IRON_BLOCK, target);
                } catch (LuaException e) {
                    helper.fail(e.getMessage());
                }
                charge(helper, 50000);
            });
            helper.runAfterDelay(10, () -> {
                try {
                    Map<String, Object> c = p.getCannon();
                    helper.assertTrue(p.isFiring(), "not firing with energy: " + c);
                    helper.assertBlockNotPresent(net.minecraft.world.level.block.Blocks.IRON_BLOCK, target);
                    double beam = ((Number) c.get("beamLength")).doubleValue();
                    helper.assertTrue(beam > 3.0, "beam stops at " + beam + " (should pass the burnt block)");
                    helper.assertTrue(((Number) c.get("energy")).intValue() < 50000 && ((Number) c.get("heat")).doubleValue() > 0, "energy/heat " + c);
                    p.setFiring(new ObjectArguments(false));
                } catch (LuaException e) {
                    helper.fail(e.getMessage());
                }
            });
            helper.runAfterDelay(14, () -> {
                try {
                    helper.assertTrue(!p.isFiring(), "still firing after release");
                } catch (LuaException e) {
                    helper.fail(e.getMessage());
                }
                helper.succeed();
            });
        }

        static void rotary(GameTestHelper helper) {
            CannonMountPeripheral p = mount(helper);
            // Breech at z=9 facing north (towards the muzzle), barrels at z=8..7
            helper.setBlock(new BlockPos(8, 3, 9), CbcCompat.ROTARY_BREECH.get().defaultBlockState().setValue(AutocannonBaseBlock.FACING, Direction.NORTH));
            for (int z = 7; z <= 8; z++) {
                helper.setBlock(new BlockPos(8, 3, z), CbcCompat.ROTARY_BARREL.get().defaultBlockState().setValue(AutocannonBaseBlock.FACING, Direction.NORTH));
            }
            for (int z = 7; z <= 9; z++) AutocannonBlock.onPlace(helper.getLevel(), helper.absolutePos(new BlockPos(8, 3, z)));
            int[] start = new int[1];
            try {
                Object[] a = r(p.assemble());
                helper.assertTrue(Boolean.TRUE.equals(a[0]), "assemble: " + Arrays.toString(a));
                Map<String, Object> c = p.getCannon();
                helper.assertTrue(c != null && "rotary_autocannon".equals(c.get("type")), "cannon " + c);
                load(helper, item("createbigcannons:machine_gun_round", 32));
                start[0] = ((Number) p.getAmmo().get("rounds")).intValue();
                helper.assertTrue(start[0] == 32, "loaded " + start[0]);
                Map<String, Object> b = p.getBallistics();
                helper.assertTrue(b != null && Math.abs((Double) b.get("speed") - (4 + 1.5 * 2) * 20) < 1e-6, "ballistics " + b);
                p.setFiring(new ObjectArguments(true));
            } catch (LuaException e) {
                helper.fail(e.getMessage());
            }
            helper.runAfterDelay(40, () -> {
                try {
                    Map<String, Object> c = p.getCannon();
                    int left = ((Number) p.getAmmo().get("rounds")).intValue();
                    helper.assertTrue(((Number) c.get("spin")).doubleValue() > 0.99, "spin " + c.get("spin"));
                    // Spun up within 1.5 s, then about one round per tick: well over CBC's fastest 20/s ramp
                    helper.assertTrue(start[0] - left >= 15, "fired only " + (start[0] - left));
                    int flying = helper.getLevel().getEntitiesOfClass(AbstractAutocannonProjectile.class,
                        new AABB(helper.absolutePos(MOUNT)).inflate(128)).size();
                    helper.assertTrue(flying > 0, "no rounds in flight");
                    p.setFiring(new ObjectArguments(false));
                } catch (LuaException e) {
                    helper.fail(e.getMessage());
                }
                helper.runAfterDelay(25, () -> {
                    try {
                        Map<String, Object> c = p.getCannon();
                        double spin = ((Number) c.get("spin")).doubleValue();
                        helper.assertTrue(!p.isFiring() && spin < 0.7 && spin > 0, "after release: firing " + p.isFiring() + " spin " + spin);
                    } catch (LuaException e) {
                        helper.fail(e.getMessage());
                    }
                    helper.succeed();
                });
            });
        }
    }
}
