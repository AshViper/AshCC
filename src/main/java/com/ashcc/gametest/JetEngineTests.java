package com.ashcc.gametest;

import com.ashcc.AshCC;
import com.ashcc.jet.JetEngineBlockEntity;
import com.ashcc.jet.JetEnginePeripheral;
import com.ashcc.jet.JetPartBlock;
import dan200.computercraft.api.lua.LuaException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;

/** Jet engine structure, start sequence, afterburner and flameout (without Sable: no ship to push). */
@GameTestHolder(AshCC.MOD_ID)
@PrefixGameTestTemplate(false)
public class JetEngineTests {
    /** Builds the sections from x = 1 eastwards (exhaust east) and returns the nozzle's engine. */
    static JetEngineBlockEntity build(GameTestHelper helper, List<Block> sections) {
        int x = 1;
        for (Block b : sections) {
            helper.setBlock(new BlockPos(x++, 2, 4), b.defaultBlockState().setValue(JetPartBlock.FACING, Direction.EAST));
        }
        return (JetEngineBlockEntity) helper.getBlockEntity(new BlockPos(x - 1, 2, 4));
    }

    static List<Block> fullEngine() {
        return List.of(AshCC.JET_INTAKE.get(), AshCC.JET_CORE.get(), AshCC.JET_AFTERBURNER_NOZZLE.get());
    }

    @GameTest(template = RadarTests.EMPTY)
    public static void structure(GameTestHelper helper) {
        JetEngineBlockEntity bad = build(helper, List.of(AshCC.JET_CORE.get(), AshCC.JET_INTAKE.get(), AshCC.JET_NOZZLE.get()));
        helper.runAfterDelay(3, () -> {
            Map<String, Object> s = bad.peripheral().getStructure();
            helper.assertTrue(Boolean.FALSE.equals(s.get("valid")) && "no_core".equals(s.get("error")), "bad order: " + s);
            JetEngineBlockEntity good = build(helper, fullEngine());
            helper.runAfterDelay(25, () -> {
                Map<String, Object> g = good.peripheral().getStructure();
                helper.assertTrue(Boolean.TRUE.equals(g.get("valid")), "good: " + g);
                helper.assertTrue(Boolean.TRUE.equals(g.get("afterburner")), "afterburner nozzle " + g);
                helper.assertBlockProperty(new BlockPos(2, 2, 4), JetPartBlock.FORMED, true);
                // The core is the engine as far as capabilities go
                helper.assertTrue(JetEngineBlockEntity.forPart(helper.getLevel(), helper.absolutePos(new BlockPos(2, 2, 4)),
                    helper.getBlockState(new BlockPos(2, 2, 4))) == good, "section delegates to the nozzle");
                helper.destroyBlock(new BlockPos(2, 2, 4));
                helper.runAfterDelay(3, () -> {
                    helper.assertTrue(!good.formed(), "breaking the core breaks the engine");
                    helper.assertBlockProperty(new BlockPos(1, 2, 4), JetPartBlock.FORMED, false);
                    helper.succeed();
                });
            });
        });
    }

    /** A vectoring nozzle deflects the exhaust (pitch + tilts it up); commands clamp to the configured maximum. */
    @GameTest(template = RadarTests.EMPTY, timeoutTicks = 200)
    public static void vectoringNozzle(GameTestHelper helper) {
        JetEngineBlockEntity e = build(helper, List.of(AshCC.JET_INTAKE.get(), AshCC.JET_CORE.get(), AshCC.JET_VECTORING_NOZZLE.get()));
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(e.formed(), "engine formed");
            helper.assertTrue(e.hasVectoring(), "vectoring nozzle");
            helper.assertTrue(e.hasAfterburner(), "vectoring nozzle has an afterburner");
            helper.assertTrue(Boolean.TRUE.equals(e.peripheral().getStructure().get("vectoring")), "structure reports vectoring");
            helper.assertTrue(Boolean.TRUE.equals(e.peripheral().getStructure().get("afterburner")), "structure reports afterburner");
            helper.assertTrue(e.setNozzleVector(45, 0), "set vector");
            helper.assertTrue(e.vectorPitchCommand() == 15, "clamped to max: " + e.vectorPitchCommand());
            helper.runAfterDelay(10, () -> {
                Vec3 dir = e.exhaustDir();
                helper.assertTrue(dir.y > 0.2 && dir.y < 0.32, "exhaust tilts up: " + dir);
                helper.assertTrue(dir.x > 0.93, "mostly forward: " + dir);
                helper.succeed();
            });
        });
    }

    /** A 50% throttle limit holds full throttle at half power and keeps the afterburner out. */
    @GameTest(template = RadarTests.EMPTY, timeoutTicks = 700)
    public static void throttleLimit(GameTestHelper helper) {
        JetEngineBlockEntity e = build(helper, fullEngine());
        e.fluidHandler().fill(new FluidStack(Fluids.LAVA, 16000), IFluidHandler.FluidAction.EXECUTE);
        e.setThrottleLimit(0.5);
        e.setThrottle(1);
        e.setAfterburner(1);
        helper.runAfterDelay(2, e::start);
        helper.runAfterDelay(600, () -> {
            double expected = JetEngineBlockEntity.IDLE_N2 + (1 - JetEngineBlockEntity.IDLE_N2) * 0.5;
            helper.assertTrue(Math.abs(e.n2() - expected) < 0.01, "n2 at half power " + e.n2() + " expected " + expected);
            helper.assertTrue(e.afterburnerLevel() == 0, "no afterburner under a limit");
            helper.assertTrue(e.thrust() < e.maxDryThrust() * 0.5, "thrust " + e.thrust());
            helper.succeed();
        });
    }

    /** Full redstone from the start: no hot start, dropouts ignored, restart after a flameout while the signal stays on. */
    @GameTest(template = RadarTests.EMPTY, timeoutTicks = 1600)
    public static void redstoneControl(GameTestHelper helper) {
        JetEngineBlockEntity e = build(helper, fullEngine());
        e.fluidHandler().fill(new FluidStack(Fluids.LAVA, 16000), IFluidHandler.FluidAction.EXECUTE);
        BlockPos power = new BlockPos(2, 3, 4);
        helper.setBlock(power, Blocks.REDSTONE_BLOCK);
        helper.runAfterDelay(500, () -> {
            helper.assertTrue(e.state() == JetEngineBlockEntity.EngineState.RUNNING, "running on redstone " + e.peripheral().getStatus());
            helper.assertTrue(e.health() > 0.999, "no hot start damage " + e.health());
            helper.assertTrue(e.afterburnerLevel() > 0.9, "signal 15 = full afterburner " + e.peripheral().getStatus());
            // A 2-tick dropout must not shut it down
            helper.setBlock(power, Blocks.AIR);
            helper.runAfterDelay(2, () -> {
                helper.setBlock(power, Blocks.REDSTONE_BLOCK);
                helper.runAfterDelay(5, () -> {
                    helper.assertTrue(e.state() == JetEngineBlockEntity.EngineState.RUNNING && e.lit(), "survives dropout " + e.peripheral().getStatus());
                    // Flameout from a blocked intake, then it restarts by itself once the intake is clear
                    helper.setBlock(new BlockPos(0, 2, 4), Blocks.STONE);
                    helper.runAfterDelay(25, () -> {
                        helper.assertTrue(!e.lit(), "flameout");
                        helper.setBlock(new BlockPos(0, 2, 4), Blocks.AIR);
                        helper.runAfterDelay(400, () -> {
                            helper.assertTrue(e.state() == JetEngineBlockEntity.EngineState.RUNNING, "restarted " + e.peripheral().getStatus());
                            helper.setBlock(power, Blocks.AIR);
                            helper.runAfterDelay(10, () -> {
                                helper.assertTrue(e.state() == JetEngineBlockEntity.EngineState.OFF, "signal off shuts down");
                                helper.succeed();
                            });
                        });
                    });
                });
            });
        });
    }

    @GameTest(template = RadarTests.EMPTY, timeoutTicks = 1400)
    public static void startAfterburnerFlameout(GameTestHelper helper) throws LuaException {
        JetEngineBlockEntity e = build(helper, fullEngine());
        JetEnginePeripheral p = e.peripheral();
        int filled = e.fluidHandler().fill(new FluidStack(Fluids.LAVA, 4000), IFluidHandler.FluidAction.EXECUTE);
        helper.assertTrue(filled == 4000, "lava is a fuel");
        helper.assertTrue(e.fluidHandler().fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.SIMULATE) == 0, "water is not");
        p.setThrottle(0);
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(p.start(), "start");
            helper.runAfterDelay(20, () -> helper.assertTrue("starting".equals(p.getStatus().get("state")), "starting " + p.getStatus()));
        });
        // Idle within the start timeout
        helper.runAfterDelay(500, () -> {
            Map<String, Object> s = p.getStatus();
            helper.assertTrue("running".equals(s.get("state")), "running at idle " + s);
            double idleThrust = e.thrust();
            helper.assertTrue(idleThrust == 0 && e.lit() && e.n2() > 0.6, "idle: running but no thrust " + s);
            try {
                p.setThrottle(1);
                p.setAfterburner(1);
            } catch (LuaException ex) {
                throw new RuntimeException(ex);
            }
            helper.runAfterDelay(300, () -> {
                helper.assertTrue(e.afterburnerLevel() > 0.9, "afterburner lit " + p.getStatus());
                helper.assertTrue(e.thrust() > e.maxDryThrust() * 1.2, "wet thrust " + e.thrust() + " dry max " + e.maxDryThrust());
                helper.assertBlockProperty(new BlockPos(3, 2, 4), com.ashcc.jet.JetNozzleBlock.GLOW, 2);
                // Block the intake: flameout
                helper.setBlock(new BlockPos(0, 2, 4), Blocks.STONE);
                helper.runAfterDelay(25, () -> {
                    Map<String, Object> s2 = p.getStatus();
                    helper.assertTrue("off".equals(s2.get("state")) && !e.lit() && e.thrust() == 0, "flameout " + s2);
                    helper.assertTrue("intake_blocked".equals(s2.get("reason")), "reason " + s2);
                    helper.succeed();
                });
            });
        });
    }
}
