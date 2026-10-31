package com.ashcc.gametest;

import com.ashcc.AshCC;
import com.ashcc.decoy.DecoyEntity;
import com.ashcc.decoy.DecoyLauncherBlock;
import com.ashcc.decoy.DecoyLauncherBlockEntity;
import com.ashcc.decoy.DecoyLauncherPeripheral;
import com.ashcc.radar.RadarBlockEntity;
import com.ashcc.seeker.SeekerBlock;
import com.ashcc.seeker.SeekerBlockEntity;
import com.ashcc.seeker.SeekerPeripheral;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.lua.ObjectArguments;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;

@GameTestHolder(AshCC.MOD_ID)
@PrefixGameTestTemplate(false)
public class DecoyTests {

    static DecoyLauncherBlockEntity placeLauncher(GameTestHelper helper, BlockPos pos, Direction facing) {
        helper.setBlock(pos, AshCC.DECOY_LAUNCHER.get().defaultBlockState().setValue(DecoyLauncherBlock.FACING, facing));
        return (DecoyLauncherBlockEntity) helper.getBlockEntity(pos);
    }

    static DecoyEntity spawn(GameTestHelper helper, Vec3 worldPos, byte kind, int lifetime) {
        DecoyEntity e = new DecoyEntity(AshCC.DECOY.get(), helper.getLevel());
        e.setup(kind, lifetime, Map.of());
        e.setPos(worldPos);
        helper.getLevel().addFreshEntity(e);
        return e;
    }

    static List<DecoyEntity> decoys(GameTestHelper helper) {
        AABB box = new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(64);
        return helper.getLevel().getEntities(AshCC.DECOY.get(), box, e -> e.isAlive());
    }

    private static Object[] result(MethodResult r) {
        return r.getResult();
    }

    /** Ammo goes in by hopper (item handler) only, fire() uses it, then cooldown and empty. */
    @GameTest(template = RadarTests.EMPTY, batch = "decoy_launcher")
    public static void launcherAmmoAndCooldown(GameTestHelper helper) throws LuaException {
        DecoyLauncherBlockEntity be = placeLauncher(helper, new BlockPos(4, 1, 4), Direction.UP);
        DecoyLauncherPeripheral p = be.peripheral();
        ItemStack rest = be.itemHandler().insertItem(0, new ItemStack(AshCC.FLARE.get(), 3), false);
        helper.assertTrue(rest.isEmpty(), "flares not accepted");
        helper.assertTrue(!be.itemHandler().insertItem(0, new ItemStack(AshCC.CHAFF.get(), 2), false).isEmpty(), "chaff accepted in the flare slot");
        helper.assertTrue(be.itemHandler().insertItem(1, new ItemStack(AshCC.CHAFF.get(), 2), false).isEmpty(), "chaff not accepted");
        helper.assertTrue(be.itemHandler().extractItem(0, 64, false).isEmpty(), "ammo could be extracted");
        helper.assertTrue(((Number) p.getAmmo().get("flare")).intValue() == 3 && ((Number) p.getAmmo().get("chaff")).intValue() == 2, "ammo " + p.getAmmo());

        Object[] r = result(p.fire(new ObjectArguments("flare", 5.0)));
        helper.assertTrue(((Number) r[0]).intValue() == 3, "fired " + r[0] + " of 3 loaded");
        helper.assertTrue(decoys(helper).size() == 3, "decoys in world: " + decoys(helper).size());
        helper.assertTrue(decoys(helper).stream().allMatch(DecoyEntity::isFlare), "fired chaff");
        helper.assertTrue(decoys(helper).stream().allMatch(e -> e.getDeltaMovement().y > 0.5), "flares not launched upwards");
        r = result(p.fire(new ObjectArguments("chaff")));
        helper.assertTrue(((Number) r[0]).intValue() == 0 && "cooldown".equals(r[1]), "no cooldown: " + List.of(r));
        helper.assertTrue(!p.isReady() && p.getCooldown() > 0, "isReady during cooldown");
        try {
            p.fire(new ObjectArguments("smoke"));
            helper.fail("unknown kind accepted");
        } catch (LuaException expected) {
            // ok
        }
        helper.runAfterDelay(11, () -> {
            try {
                Object[] r2 = result(p.fire(new ObjectArguments("flare")));
                helper.assertTrue(((Number) r2[0]).intValue() == 0 && "empty".equals(r2[1]), "fired with no flares: " + List.of(r2));
                Object[] r3 = result(p.fire(new ObjectArguments("chaff", 1.0)));
                helper.assertTrue(((Number) r3[0]).intValue() == 1, "chaff not fired");
                helper.assertTrue(((Number) p.getAmmo().get("chaff")).intValue() == 1, "chaff ammo " + p.getAmmo());
                helper.succeed();
            } catch (LuaException e) {
                helper.fail(e.getMessage());
            }
        });
    }

    /** Chaff is a ship on the radar; flares are invisible to it; neither is an entity contact. */
    @GameTest(template = RadarTests.EMPTY, batch = "decoy_radar")
    public static void chaffOnRadar(GameTestHelper helper) throws LuaException {
        RadarBlockEntity radar = RadarTests.placeRadar(helper, new BlockPos(0, 1, 0), Direction.NORTH);
        Vec3 c = Vec3.atCenterOf(helper.absolutePos(new BlockPos(4, 3, 4)));
        DecoyEntity chaff = spawn(helper, c, DecoyEntity.CHAFF, 200);
        DecoyEntity flare = spawn(helper, c.add(2, 0, 0), DecoyEntity.FLARE, 200);
        helper.runAfterDelay(2, () -> {
            try {
                List<Map<String, Object>> subs = radar.peripheral().scanSubLevels(new ObjectArguments(64.0));
                Map<String, Object> echo = subs.stream().filter(s -> s.get("id").equals(chaff.getStringUUID())).findFirst().orElse(null);
                helper.assertTrue(echo != null, "chaff not on the radar: " + subs);
                helper.assertTrue("sublevel".equals(echo.get("kind")) && echo.get("bounds") != null && echo.get("size") != null, "echo " + echo);
                helper.assertTrue(subs.stream().noneMatch(s -> s.get("id").equals(flare.getStringUUID())), "flare on the radar");
                List<Map<String, Object>> ents = radar.peripheral().scanEntities(new ObjectArguments(64.0));
                helper.assertTrue(ents.stream().noneMatch(e -> e.get("type").equals("ashcc:decoy")), "decoy listed as an entity");
                helper.assertTrue(radar.peripheral().getContact(chaff.getStringUUID()) != null, "getContact(chaff) is nil");
                helper.assertTrue(radar.peripheral().getContact(flare.getStringUUID()) == null, "getContact(flare) found it");
                helper.assertTrue(radar.peripheral().intercept(chaff.getStringUUID(), 50, java.util.Optional.empty()) != null, "no intercept on chaff");
                helper.succeed();
            } catch (LuaException e) {
                helper.fail(e.getMessage());
            }
        });
    }

    /** Seekers see flares (not chaff) as ships, and a flare burns out after its lifetime. */
    @GameTest(template = RadarTests.EMPTY, batch = "decoy_seeker")
    public static void flareLuresSeeker(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 3, 4);
        helper.setBlock(pos, AshCC.SEEKER.get().defaultBlockState().setValue(SeekerBlock.FACING, Direction.EAST));
        SeekerPeripheral p = ((SeekerBlockEntity) helper.getBlockEntity(pos)).peripheral();
        Vec3 o = Vec3.atCenterOf(helper.absolutePos(pos));
        DecoyEntity flare = spawn(helper, o.add(6, 0, 0.63), DecoyEntity.FLARE, 200);   // ~6 deg off
        spawn(helper, o.add(6, 0, 0), DecoyEntity.CHAFF, 200);                            // on axis, but not hot
        DecoyEntity shortFlare = spawn(helper, o.add(-3, 0, 0), DecoyEntity.FLARE, 4);  // behind, burns out
        helper.runAfterDelay(2, () -> {
            try {
                Map<String, Object> t = p.seek(new ObjectArguments(45.0));
                helper.assertTrue(t != null && t.get("id").equals(flare.getStringUUID()), "seek(45) should see the flare: " + t);
                double off = RadarTests.num(t, "offAxis");
                helper.assertTrue(off > 4 && off < 8, "offAxis " + off);
                helper.assertTrue(p.seek(new ObjectArguments(3.0)) == null, "seek(3) should find nothing (chaff is not hot)");
            } catch (LuaException e) {
                helper.fail(e.getMessage());
            }
        });
        // A new entity may start ticking a little late on a busy server: wait for it to burn out instead of checking one tick
        helper.runAfterDelay(3, () -> helper.succeedWhen(() -> helper.assertTrue(shortFlare.isRemoved(), "flare did not burn out")));
    }

    /** A fresh flare a few degrees off wins over a ship on the line of sight; without it the ship is found again. */
    @GameTest(template = "empty_17", timeoutTicks = 60, batch = "decoy_sable")
    public static void flareBeatsShip(GameTestHelper helper) {
        if (!AshCC.sableLoaded) {
            helper.succeed();
            return;
        }
        Impl.run(helper);
    }

    private static final class Impl {
        static void run(GameTestHelper helper) {
            BlockPos pos = new BlockPos(1, 8, 8);
            helper.setBlock(pos, AshCC.SEEKER.get().defaultBlockState().setValue(SeekerBlock.FACING, Direction.EAST));
            SeekerPeripheral p = ((SeekerBlockEntity) helper.getBlockEntity(pos)).peripheral();
            BlockPos rel = new BlockPos(9, 8, 8);
            helper.setBlock(rel, Blocks.IRON_BLOCK);
            BlockPos abs = helper.absolutePos(rel);
            ServerSubLevel ship = SubLevelAssemblyHelper.assembleBlocks(helper.getLevel(), abs, List.of(abs), new BoundingBox3i(abs, abs));
            Vec3 o = Vec3.atCenterOf(helper.absolutePos(pos));
            helper.runAfterDelay(3, () -> {
                try {
                    DecoyEntity flare = spawn(helper, o.add(8, 0, 0.84), DecoyEntity.FLARE, 200); // ~6 deg off
                    Map<String, Object> t = p.seek(new ObjectArguments(45.0));
                    helper.assertTrue(t != null && t.get("id").equals(flare.getStringUUID()), "flare should win: " + t);
                    flare.discard();
                    Map<String, Object> s = p.seek(new ObjectArguments(45.0));
                    helper.assertTrue(s != null && s.get("id").equals(ship.getUniqueId().toString()), "ship should be found again: " + s);
                    TestShips.remove(helper, ship);
                    helper.succeed();
                } catch (LuaException e) {
                    helper.fail(e.getMessage());
                }
            });
        }
    }
}
