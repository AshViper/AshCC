package com.ashcc.gametest;

import com.ashcc.AshCC;
import com.ashcc.iff.TransponderBlockEntity;
import com.ashcc.iff.TransponderPeripheral;
import com.ashcc.iff.Transponders;
import com.ashcc.radar.RadarBlockEntity;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.ObjectArguments;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;

@GameTestHolder(AshCC.MOD_ID)
@PrefixGameTestTemplate(false)
public class IffTests {

    static TransponderBlockEntity placeTransponder(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, AshCC.TRANSPONDER.get());
        return (TransponderBlockEntity) helper.getBlockEntity(pos);
    }

    /** Off a ship it never transmits; codes are length-checked. */
    @GameTest(template = RadarTests.EMPTY)
    public static void groundTransponderIsSilent(GameTestHelper helper) throws LuaException {
        TransponderPeripheral p = placeTransponder(helper, new BlockPos(4, 1, 4)).peripheral();
        helper.assertTrue(p.setCode(" BLUE ").equals("BLUE"), "code not trimmed");
        helper.assertTrue(p.isEnabled() && !p.isTransmitting() && p.getSubLevel() == null, "ground transponder transmits");
        try {
            p.setCode("x".repeat(TransponderBlockEntity.MAX_LENGTH + 1));
            helper.fail("long code accepted");
        } catch (LuaException expected) {
            // ok
        }
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.REDSTONE_BLOCK);
        helper.assertTrue(p.isSilenced(), "redstone does not silence it");
        helper.succeed();
    }

    @GameTest(template = "empty_17", timeoutTicks = 60, batch = "iff")
    public static void radarReadsShipIff(GameTestHelper helper) {
        if (!AshCC.sableLoaded) {
            helper.succeed();
            return;
        }
        Impl.run(helper);
    }

    private static final class Impl {
        static void run(GameTestHelper helper) {
            RadarBlockEntity radar = RadarTests.placeRadar(helper, new BlockPos(8, 1, 8), Direction.NORTH);

            // Ship A: iron block with a transponder on top, coded before assembly (the setting must travel with it)
            helper.setBlock(new BlockPos(4, 8, 4), Blocks.IRON_BLOCK);
            TransponderBlockEntity before = placeTransponder(helper, new BlockPos(4, 9, 4));
            before.setCode("BLUE");
            before.setCallsign("Alpha");
            BlockPos a1 = helper.absolutePos(new BlockPos(4, 8, 4));
            BlockPos a2 = helper.absolutePos(new BlockPos(4, 9, 4));
            ServerSubLevel shipA = SubLevelAssemblyHelper.assembleBlocks(helper.getLevel(), a1, List.of(a1, a2), new BoundingBox3i(a1, a2));
            // Ship B: no transponder
            helper.setBlock(new BlockPos(12, 8, 12), Blocks.IRON_BLOCK);
            BlockPos b = helper.absolutePos(new BlockPos(12, 8, 12));
            ServerSubLevel shipB = SubLevelAssemblyHelper.assembleBlocks(helper.getLevel(), b, List.of(b), new BoundingBox3i(b, b));
            String idA = shipA.getUniqueId().toString();
            String idB = shipB.getUniqueId().toString();

            helper.runAfterDelay(3, () -> {
                try {
                    Map<String, Object> a = find(radar, idA);
                    helper.assertTrue(a != null && a.get("iff") instanceof Map<?, ?>, "ship A has no iff: " + a);
                    Map<?, ?> iff = (Map<?, ?>) a.get("iff");
                    helper.assertTrue("BLUE".equals(iff.get("code")) && "Alpha".equals(iff.get("callsign")), "iff " + iff);
                    Map<String, Object> bt = find(radar, idB);
                    helper.assertTrue(bt != null && !bt.containsKey("iff"), "ship B has an iff: " + bt);
                    Map<String, Object> got = radar.peripheral().getContact(idA);
                    helper.assertTrue(got != null && got.get("iff") != null, "getContact has no iff");

                    TransponderBlockEntity onBoard = Transponders.byShip(helper.getLevel()).get(shipA.getUniqueId());
                    helper.assertTrue(onBoard != null, "transponder not registered on the ship");
                    TransponderPeripheral p = onBoard.peripheral();
                    helper.assertTrue(p.isTransmitting() && idA.equals(p.getSubLevel()), "on-board peripheral state");
                    p.setEnabled(false);
                    helper.assertTrue(!find(radar, idA).containsKey("iff"), "disabled transponder still seen");
                    helper.assertTrue(Transponders.byShip(helper.getLevel()).isEmpty(), "disabled transponder listed");
                    p.setEnabled(true);
                    p.setCode("");
                    helper.assertTrue(!find(radar, idA).containsKey("iff"), "empty code still seen");
                    p.setCode("RED");
                    helper.assertTrue("RED".equals(((Map<?, ?>) find(radar, idA).get("iff")).get("code")), "code change not seen");
                    helper.succeed();
                } catch (LuaException e) {
                    helper.fail(e.getMessage());
                }
            });
        }

        static Map<String, Object> find(RadarBlockEntity radar, String id) throws LuaException {
            for (Map<String, Object> s : radar.peripheral().scanSubLevels(new ObjectArguments(64.0))) {
                if (s.get("id").equals(id)) return s;
            }
            return null;
        }
    }
}
