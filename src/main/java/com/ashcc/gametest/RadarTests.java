package com.ashcc.gametest;

import com.ashcc.AshCC;
import com.ashcc.radar.Intercept;
import com.ashcc.radar.RadarBlock;
import com.ashcc.radar.RadarBlockEntity;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.ObjectArguments;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;

/** Run with {@code gradlew runGameTestServer}. Sable tests live in {@link SableRadarTests}. */
@GameTestHolder(AshCC.MOD_ID)
@PrefixGameTestTemplate(false)
public class RadarTests {
    static final String EMPTY = "empty_9x6x9";
    static final BlockPos RADAR = new BlockPos(4, 1, 4);

    static RadarBlockEntity placeRadar(GameTestHelper helper, BlockPos pos, Direction facing) {
        helper.setBlock(pos, AshCC.RADAR.get().defaultBlockState().setValue(RadarBlock.FACING, facing));
        return (RadarBlockEntity) helper.getBlockEntity(pos);
    }

    static ArmorStand stand(GameTestHelper helper, double x, double y, double z) {
        ArmorStand stand = helper.spawn(EntityType.ARMOR_STAND, new Vec3(x, y, z));
        stand.setNoGravity(true);
        return stand;
    }

    static double num(Map<?, ?> t, String key) {
        return ((Number) t.get(key)).doubleValue();
    }

    @GameTest(template = EMPTY)
    public static void detectsEntitiesWithBearing(GameTestHelper helper) throws LuaException {
        RadarBlockEntity radar = placeRadar(helper, RADAR, Direction.NORTH);
        ArmorStand ahead = stand(helper, 4.5, 1, 0.5);  // north = forward
        ArmorStand right = stand(helper, 8.5, 1, 4.5);  // east = right when facing north

        List<Map<String, Object>> found = radar.peripheral().scanEntities(new ObjectArguments(16.0, Map.of("type", "minecraft:armor_stand")));
        helper.assertTrue(found.size() == 2, "Expected 2 armor stands, got " + found.size());
        for (Map<String, Object> c : found) {
            double az = num(c, "azimuth");
            if (c.get("id").equals(ahead.getStringUUID())) {
                helper.assertTrue(Math.abs(az) < 1, "ahead azimuth " + az);
                helper.assertTrue(num((Map<?, ?>) c.get("local"), "z") > 3, "ahead local z " + c.get("local"));
            } else if (c.get("id").equals(right.getStringUUID())) {
                helper.assertTrue(Math.abs(az - 90) < 1, "right azimuth " + az);
            } else {
                helper.fail("unexpected contact " + c);
            }
        }

        // Range limit and getContact
        helper.assertTrue(radar.peripheral().scanEntities(new ObjectArguments(2.0)).isEmpty(), "range 2 should see nothing");
        helper.assertTrue(radar.peripheral().getContact(ahead.getStringUUID()) != null, "getContact(ahead)");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void interceptLeadsMovingTarget(GameTestHelper helper) {
        Vec3 d = new Vec3(10, 0, 0);
        Vec3 v = new Vec3(0, 0, 5);
        Intercept.Solution s = Intercept.solve(d, v, 10, 0);
        helper.assertTrue(s != null, "no solution");
        Vec3 predicted = d.add(v.scale(s.time()));
        helper.assertTrue(predicted.distanceTo(s.aim()) < 1e-6, "aim must be predicted point");
        helper.assertTrue(Math.abs(s.aim().length() - 10 * s.time()) < 1e-6, "projectile must arrive at the same time");
        helper.assertTrue(Intercept.solve(d, new Vec3(20, 0, 0), 10, 0) == null, "a faster receding target is unreachable");

        Intercept.Solution g = Intercept.solve(d, Vec3.ZERO, 20, 20);
        helper.assertTrue(g != null && g.aim().y > 0, "gravity must raise the aim point");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void terrainMapFollowsHeading(GameTestHelper helper) throws LuaException {
        // The test area is roofed with barriers, which would be the surface everywhere
        for (int x = -1; x <= 9; x++) for (int z = -1; z <= 9; z++) helper.setBlock(new BlockPos(x, 7, z), net.minecraft.world.level.block.Blocks.AIR);
        for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(4, y, 1), net.minecraft.world.level.block.Blocks.STONE);
        helper.setBlock(new BlockPos(7, 1, 4), net.minecraft.world.level.block.Blocks.WATER);
        int top = helper.absolutePos(new BlockPos(4, 4, 1)).getY();

        // Facing north: the pillar is straight ahead (top rows, middle columns)
        RadarBlockEntity radar = placeRadar(helper, RADAR, Direction.NORTH);
        int[] at = highest(helper, radar.peripheral().scanTerrain(new ObjectArguments(4.0, 8)), top);
        helper.assertTrue(at[0] == 1 && (at[1] == 4 || at[1] == 5), "north: pillar at row/col " + at[0] + "/" + at[1]);

        // Facing east: the pillar (north) is on the left; north-up keeps it at the top
        radar = placeRadar(helper, RADAR, Direction.EAST);
        at = highest(helper, radar.peripheral().scanTerrain(new ObjectArguments(4.0, 8)), top);
        helper.assertTrue(at[1] == 1 && (at[0] == 4 || at[0] == 5), "east: pillar at row/col " + at[0] + "/" + at[1]);
        Map<String, Object> north = radar.peripheral().scanTerrain(new ObjectArguments(4.0, 8, true));
        at = highest(helper, north, top);
        helper.assertTrue(at[0] == 1, "north-up: pillar at row " + at[0]);
        boolean water = false;
        for (Object row : (List<?>) north.get("kind")) water |= ((List<?>) row).contains("water");
        helper.assertTrue(water, "water cell expected");
        helper.succeed();
    }

    /** 1-based row/column of the cell with the given surface height. */
    static int[] highest(GameTestHelper helper, Map<String, Object> terrain, int top) {
        List<?> rows = (List<?>) terrain.get("height");
        for (int r = 0; r < rows.size(); r++) {
            List<?> row = (List<?>) rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                if (row.get(c) instanceof Number n && n.intValue() == top) return new int[]{r + 1, c + 1};
            }
        }
        helper.fail("pillar not found in " + rows);
        return new int[2];
    }
}
