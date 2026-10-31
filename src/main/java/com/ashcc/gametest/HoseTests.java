package com.ashcc.gametest;

import com.ashcc.AshCC;
import com.ashcc.hose.HoseData;
import com.ashcc.jet.JetEngineBlockEntity;
import com.ashcc.jet.JetFuels;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Fuel hoses, and Create Diesel Generators fuels in the jet engine. */
@GameTestHolder(AshCC.MOD_ID)
@PrefixGameTestTemplate(false)
public class HoseTests {
    @GameTest(template = RadarTests.EMPTY, timeoutTicks = 200)
    public static void hoseFillsEngine(GameTestHelper helper) {
        JetEngineBlockEntity e = JetEngineTests.build(helper, JetEngineTests.fullEngine());
        BlockPos cauldron = new BlockPos(2, 2, 7);
        helper.setBlock(cauldron, Blocks.LAVA_CAULDRON);
        // Stone under the hose's path: the hose is not a block, so it is no obstacle to building
        helper.setBlock(new BlockPos(2, 2, 6), Blocks.STONE);
        helper.runAfterDelay(25, () -> {
            var level = helper.getLevel();
            BlockPos src = helper.absolutePos(cauldron);
            BlockPos core = helper.absolutePos(new BlockPos(2, 2, 4));
            helper.assertTrue(HoseData.handler(level, src, Direction.UP) != null, "cauldron has a fluid handler");
            helper.assertTrue(HoseData.handler(level, core, Direction.UP) != null, "formed core has a fluid handler");
            HoseData.get(level).add(level, src, Direction.UP, core, Direction.UP);
            helper.runAfterDelay(20, () -> {
                helper.assertTrue(e.fuel().getAmount() == 1000 && e.fuel().getFluid() == Fluids.LAVA, "fuel through the hose " + e.fuel().getAmount());
                helper.assertBlockPresent(Blocks.CAULDRON, cauldron);
                // Taking the source away drops the hose
                helper.setBlock(cauldron, Blocks.AIR);
                helper.runAfterDelay(3, () -> {
                    helper.assertTrue(HoseData.get(level).hoses().stream().noneMatch(h -> h.touches(src)), "hose removed");
                    helper.assertTrue(!level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(src).inflate(6),
                        i -> i.getItem().is(AshCC.HOSE.get())).isEmpty(), "hose dropped");
                    helper.succeed();
                });
            });
        });
    }

    /** The pipe's route runs only along the axes, starts and ends on the clicked faces, and does not go back through them. */
    @GameTest(template = RadarTests.EMPTY)
    public static void pipeRoute(GameTestHelper helper) {
        BlockPos a = new BlockPos(0, 0, 0);
        BlockPos b = new BlockPos(5, 3, -4);
        for (Direction fa : Direction.values()) {
            for (Direction fb : Direction.values()) {
                var r = com.ashcc.hose.Hose.route(a, fa, b, fb);
                helper.assertTrue(r.get(0).equals(com.ashcc.hose.Hose.attach(a, fa)) && r.get(r.size() - 1).equals(com.ashcc.hose.Hose.attach(b, fb)),
                    "ends on the faces " + fa + " " + fb);
                for (int i = 1; i < r.size(); i++) {
                    var d = r.get(i).subtract(r.get(i - 1));
                    int moving = (Math.abs(d.x) > 1e-9 ? 1 : 0) + (Math.abs(d.y) > 1e-9 ? 1 : 0) + (Math.abs(d.z) > 1e-9 ? 1 : 0);
                    helper.assertTrue(moving == 1, "axis-aligned run " + fa + " " + fb + ": " + r);
                }
                helper.assertTrue(r.size() <= 7, "at most three bends between the stubs " + r);
            }
        }
        helper.succeed();
    }

    @GameTest(template = RadarTests.EMPTY, timeoutTicks = 200)
    public static void pipeFillsEngine(GameTestHelper helper) {
        JetEngineBlockEntity e = JetEngineTests.build(helper, JetEngineTests.fullEngine());
        BlockPos cauldron = new BlockPos(1, 2, 7);
        helper.setBlock(cauldron, Blocks.LAVA_CAULDRON);
        helper.runAfterDelay(25, () -> {
            var level = helper.getLevel();
            BlockPos src = helper.absolutePos(cauldron);
            BlockPos core = helper.absolutePos(new BlockPos(2, 2, 4));
            var pipe = HoseData.get(level).add(level, src, Direction.NORTH, core, Direction.SOUTH, true);
            // Blocks can stand where the pipe runs
            helper.setBlock(new BlockPos(1, 2, 6), Blocks.STONE);
            helper.runAfterDelay(20, () -> {
                helper.assertTrue(e.fuel().getAmount() == 1000, "fuel through the pipe " + e.fuel().getAmount());
                helper.assertTrue(HoseData.get(level).hoses().contains(pipe), "pipe still there with a block on its path");
                helper.assertTrue(HoseData.item(pipe).is(AshCC.PIPE.get()), "comes back as a pipe");
                helper.succeed();
            });
        });
    }

    @GameTest(template = RadarTests.EMPTY)
    public static void dieselGeneratorFuels(GameTestHelper helper) {
        if (!ModList.get().isLoaded("createdieselgenerators")) {
            helper.succeed();
            return;
        }
        Fluid diesel = BuiltInRegistries.FLUID.get(ResourceLocation.fromNamespaceAndPath("createdieselgenerators", "diesel"));
        Fluid bio = BuiltInRegistries.FLUID.get(ResourceLocation.fromNamespaceAndPath("createdieselgenerators", "biodiesel"));
        Fluid gas = BuiltInRegistries.FLUID.get(ResourceLocation.fromNamespaceAndPath("createdieselgenerators", "gasoline"));
        Fluid eth = BuiltInRegistries.FLUID.get(ResourceLocation.fromNamespaceAndPath("createdieselgenerators", "ethanol"));
        helper.assertTrue(JetFuels.value(new FluidStack(diesel, 1)) == 0.9, "diesel " + JetFuels.value(new FluidStack(diesel, 1)));
        helper.assertTrue(JetFuels.value(new FluidStack(bio, 1)) == 0.8, "biodiesel " + JetFuels.value(new FluidStack(bio, 1)));
        helper.assertTrue(JetFuels.value(new FluidStack(gas, 1)) == 0.9, "gasoline");
        helper.assertTrue(JetFuels.value(new FluidStack(eth, 1)) == 0.6, "ethanol");
        JetEngineBlockEntity e = JetEngineTests.build(helper, JetEngineTests.fullEngine());
        helper.assertTrue(e.fluidHandler().fill(new FluidStack(diesel, 1000), IFluidHandler.FluidAction.EXECUTE) == 1000, "engine takes diesel");
        helper.succeed();
    }
}
