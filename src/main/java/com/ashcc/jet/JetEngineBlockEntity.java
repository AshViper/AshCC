package com.ashcc.jet;

import com.ashcc.AshCC;
import com.ashcc.AshCCConfig;
import com.ashcc.compat.SableCompat;
import dan200.computercraft.api.peripheral.IComputerAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * The engine, kept in the nozzle. A two-spool turbofan in miniature:
 * <ul>
 *   <li>start(): the starter turns the core (N2); at 18% the igniters light the fuel, the core accelerates on its own
 *   and the starter cuts out at 58%; idle is 62%. No light-off within 15 s (no fuel, blocked intake) aborts the start.</li>
 *   <li>Throttle 0..1 sets N2 between idle and its maximum; N2 follows with a lag that grows with the number of stages.
 *   The fan (N1) and thrust follow N2. Thrust drops with air density (height) and airspeed (ram drag).</li>
 *   <li>Afterburner (afterburner nozzle only): at full throttle and N2 &gt; 93%; extra fuel behind the turbine for +50% thrust.</li>
 *   <li>EGT rises with power; above the redline the engine takes damage, at 0 health it seizes (repair with iron ingots).</li>
 *   <li>Fuel starvation, a blocked or flooded intake, or too thin air put the flame out.</li>
 * </ul>
 * The engine is three blocks: intake, core and nozzle; an afterburner nozzle adds reheat. Positions are block coordinates (the ship's frame on a Sable ship).
 */
public class JetEngineBlockEntity extends BlockEntity {
    public enum EngineState { OFF, STARTING, RUNNING, FAILED }

    public static final double AMBIENT = 15;
    public static final double IDLE_N2 = 0.62;
    static final double LIGHT_OFF_N2 = 0.18;
    static final double STARTER_CUTOUT_N2 = 0.58;
    static final double STARTER_N2 = 0.30;
    private static final double DT = 0.05;
    private static final int LIGHT_OFF_TIMEOUT = 15 * 20;
    private static final int START_TIMEOUT = 40 * 20;
    private static final int LENGTH = 3;
    private static final double AB_BOOST = 0.5;

    private @Nullable JetEnginePeripheral peripheral;
    final Set<IComputerAccess> computers = new CopyOnWriteArraySet<>();

    // ---- Structure ----
    private boolean formed;
    private String structureError = "unchecked";
    private boolean afterburner;
    private boolean vectoring;
    private final List<BlockPos> members = new ArrayList<>();
    private @Nullable BlockPos intakePos;
    private int scanTimer;
    private boolean scanRequested = true;

    // ---- Controls ----
    private double throttleCmd;
    private double afterburnerCmd;
    private double vectorPitchCmd, vectorYawCmd;
    private double vectorPitch, vectorYaw;
    private boolean redstoneControl = true;
    private double throttleLimit = 1;
    private int redstoneInput;
    private boolean redstoneLatch;
    private int redstoneOffTicks;
    private int restartTicks;

    // ---- Engine state ----
    private EngineState state = EngineState.OFF;
    private boolean lit;
    private double n2;
    private double egt = AMBIENT;
    private double abLevel;
    private int abDelay;
    private double health = 1;
    private int startTicks;
    private double fuelDebt;
    private String lastReason = "";

    // Derived every tick, read by the physics step and Lua
    private volatile double thrust;
    private double fuelFlow;
    private double density = 1;
    private double airspeed;
    private boolean intakeClear = true;
    private boolean intakeFlooded;
    private int fodCooldown;
    private final Set<String> warnings = new LinkedHashSet<>();

    private final FluidTank tank = new FluidTank(AshCCConfig.JET_TANK_CAPACITY.get(), JetFuels::isFuel) {
        @Override
        protected void onContentsChanged() {
            setChanged();
        }
    };

    // ---- Client copy ----
    private int syncTimer;
    private double sentN2 = -1, sentEgt = -1, sentAb = -1, sentVecP, sentVecY;
    private boolean sentLit;
    private EngineState sentState = EngineState.OFF;
    private boolean sentFormed;
    double clientN2, clientEgt = AMBIENT, clientAb;
    double prevN2, prevEgt = AMBIENT, prevAb;
    double clientVecP, clientVecY, prevVecP, prevVecY;
    private double syncedVecP, syncedVecY;
    double clientFlash, prevFlash;
    /** Rotor angles (radians) of the fan and the HP turbine, advanced each client tick for the renderer. */
    float fanAngle, prevFanAngle, turbineAngle, prevTurbineAngle;
    private double syncedN2, syncedEgt = AMBIENT, syncedAb;
    boolean syncedLit;
    public @Nullable Object clientSound;

    public JetEngineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public JetEngineBlockEntity(BlockPos pos, BlockState state) {
        this(AshCC.JET_ENGINE_BE.get(), pos, state);
    }

    public JetEnginePeripheral peripheral() {
        if (peripheral == null) peripheral = new JetEnginePeripheral(this);
        return peripheral;
    }

    public IFluidHandler fluidHandler() {
        return tank;
    }

    /** The engine whose nozzle is found walking from {@code pos} along the exhaust direction, through sections facing that way. */
    public static @Nullable JetEngineBlockEntity find(Level level, BlockPos pos, Direction exhaust) {
        BlockPos p = pos;
        for (int i = 0; i < LENGTH; i++) {
            BlockState s = level.getBlockState(p);
            if (!(s.getBlock() instanceof JetPartBlock part) || s.getValue(JetPartBlock.FACING) != exhaust) return null;
            if (part.part().isNozzle()) return level.getBlockEntity(p) instanceof JetEngineBlockEntity e ? e : null;
            p = p.relative(exhaust);
        }
        return null;
    }

    public static @Nullable JetEngineBlockEntity find(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (!(s.getBlock() instanceof JetPartBlock)) return null;
        return find(level, pos, s.getValue(JetPartBlock.FACING));
    }

    /** Capabilities of a section: those of its engine while it is part of a complete one. */
    public static @Nullable JetEngineBlockEntity forPart(Level level, BlockPos pos, BlockState state) {
        if (!state.getValue(JetPartBlock.FORMED)) return null;
        JetEngineBlockEntity e = find(level, pos, state.getValue(JetPartBlock.FACING));
        return e != null && e.formed ? e : null;
    }

    // ---- Controls ----

    public Direction exhaust() {
        return getBlockState().getValue(JetPartBlock.FACING);
    }

    /** Begins the start sequence (also a windmill relight while the core still turns). False when it cannot start. */
    public boolean start() {
        if (!formed || state == EngineState.FAILED) return false;
        if (state == EngineState.STARTING || state == EngineState.RUNNING) return true;
        startTicks = 0;
        setState(EngineState.STARTING, "start");
        return true;
    }

    /** Fuel off: the flame goes out and the engine spools down. */
    public void shutdown() {
        if (state == EngineState.STARTING || state == EngineState.RUNNING) {
            lit = false;
            setState(EngineState.OFF, "shutdown");
        }
    }

    public void setThrottle(double t) {
        throttleCmd = Mth.clamp(t, 0, 1);
        redstoneControl = false;
        setChanged();
    }

    public void setAfterburner(double a) {
        afterburnerCmd = Mth.clamp(a, 0, 1);
        redstoneControl = false;
        setChanged();
    }

    public void setRedstoneControl(boolean enabled) {
        redstoneControl = enabled;
        redstoneLatch = false;
        setChanged();
    }

    /** Throttle from redstone: 1..13 = idle..military, 14 = half afterburner, 15 = full afterburner. */
    public double throttle() {
        return redstoneControl ? Mth.clamp((redstoneInput - 1) / 12.0, 0, 1) : throttleCmd;
    }

    public double afterburnerCommand() {
        if (!redstoneControl) return afterburnerCmd;
        return redstoneInput >= 15 ? 1 : redstoneInput == 14 ? 0.5 : 0;
    }

    /** The throttle the engine runs at: the command scaled by the limit (set with a wrench). Below 100% no afterburner. */
    public double effectiveThrottle() {
        return throttle() * throttleLimit;
    }

    public double throttleLimit() {
        return throttleLimit;
    }

    public void setThrottleLimit(double limit) {
        throttleLimit = Mth.clamp(Math.round(limit * 10) / 10.0, 0.1, 1);
        setChanged();
    }

    /** Wrench: 10% down or up, wrapping around between 10% and 100%. Returns the new limit. */
    public double cycleThrottleLimit(boolean down) {
        int steps = (int) Math.round(throttleLimit * 10) + (down ? -1 : 1);
        if (steps > 10) steps = 1;
        if (steps < 1) steps = 10;
        setThrottleLimit(steps / 10.0);
        if (level != null) level.playSound(null, worldPosition, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.5f, 0.6f + 0.08f * steps);
        return throttleLimit;
    }

    /** Iron ingot: +25% health. A seized engine can be started again once it is above half. */
    public boolean repair() {
        if (health >= 1) return false;
        health = Math.min(1, health + 0.25);
        if (state == EngineState.FAILED && health >= 0.5) setState(EngineState.OFF, "repaired");
        if (level != null) level.playSound(null, worldPosition, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.6f, 1.3f);
        setChanged();
        return true;
    }

    public void requestScan() {
        scanRequested = true;
    }

    // ---- Reads ----

    public boolean formed() { return formed; }
    public String structureError() { return structureError; }
    public boolean hasAfterburner() { return afterburner; }
    public int length() { return members.size(); }
    public EngineState state() { return state; }
    public boolean lit() { return lit; }
    public double n2() { return n2; }
    public double n1() { return n1(n2); }
    public double egt() { return egt; }
    public double afterburnerLevel() { return abLevel; }
    public double health() { return health; }
    public double thrust() { return thrust; }
    /** mB per second. */
    public double fuelFlow() { return fuelFlow * 20; }
    public double density() { return density; }
    public double airspeed() { return airspeed; }
    public boolean intakeClear() { return intakeClear && !intakeFlooded; }
    public boolean redstoneControl() { return redstoneControl; }
    public int redstoneInput() { return redstoneInput; }
    public String lastReason() { return lastReason; }
    public List<String> warnings() { return new ArrayList<>(warnings); }
    public FluidStack fuel() { return tank.getFluid(); }
    public int fuelCapacity() { return tank.getCapacity(); }

    public double pressureRatio() {
        return 15;
    }

    public double maxDryThrust() {
        return AshCCConfig.JET_DRY_THRUST.get();
    }

    public double maxWetThrust() {
        return maxDryThrust() * (afterburner ? 1 + AB_BOOST : 1);
    }

    /** Fan speed from core speed: about 25% at idle, 100% at 100% N2. */
    public static double n1(double n2) {
        return Math.pow(Mth.clamp((n2 - 0.45) / 0.55, 0, 1), 1.2);
    }

    /** Thrust axis without nozzle deflection: the ship is pushed against the exhaust. Block coordinates. */
    public Vec3 thrustAxis() {
        return Vec3.atLowerCornerOf(exhaust().getOpposite().getNormal());
    }

    public Vec3 localForce() {
        return exhaustDir().scale(-thrust);
    }

    // ---- Thrust vectoring ----

    public boolean hasVectoring() { return vectoring; }
    public double vectorPitch() { return vectorPitch; }
    public double vectorYaw() { return vectorYaw; }
    public double vectorPitchCommand() { return vectorPitchCmd; }
    public double vectorYawCommand() { return vectorYawCmd; }

    /** Commands the nozzle deflection in degrees (pitch: exhaust up +, yaw: exhaust right +). False without a vectoring nozzle. */
    public boolean setNozzleVector(double pitchDeg, double yawDeg) {
        if (!vectoring) return false;
        double max = AshCCConfig.JET_VECTOR_MAX_ANGLE.get();
        vectorPitchCmd = Mth.clamp(pitchDeg, -max, max);
        vectorYawCmd = Mth.clamp(yawDeg, -max, max);
        setChanged();
        return true;
    }

    /** Exhaust direction with the current vectoring deflection applied (block coordinates). */
    public Vec3 exhaustDir() {
        return deflect(Vec3.atLowerCornerOf(exhaust().getNormal()), vectorPitch, vectorYaw);
    }

    /** Deflects a unit exhaust direction: pitch around the lateral axis (exhaust up for +), then yaw around the up axis (exhaust right for +). */
    public static Vec3 deflect(Vec3 f, double pitchDeg, double yawDeg) {
        if (pitchDeg == 0 && yawDeg == 0) return f;
        Vec3 upRef = Math.abs(f.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 lateral = f.cross(upRef).normalize();
        Vec3 v = rotate(f, lateral, Math.toRadians(pitchDeg));
        v = rotate(v, upRef, Math.toRadians(yawDeg));
        return v.normalize();
    }

    private static Vec3 rotate(Vec3 v, Vec3 axis, double rad) {
        double c = Math.cos(rad), s = Math.sin(rad);
        return v.scale(c).add(axis.cross(v).scale(s)).add(axis.scale(axis.dot(v) * (1 - c)));
    }

    // ---- Structure ----

    private void scan() {
        Level level = this.level;
        if (level == null) return;
        Direction f = exhaust();
        BlockPos core = worldPosition.relative(f.getOpposite());
        BlockPos intake = core.relative(f.getOpposite());
        String error = null;
        if (!is(level, core, JetPart.CORE, f)) error = "no_core";
        else if (!is(level, intake, JetPart.INTAKE, f)) error = "no_intake";

        boolean ok = error == null;
        List<BlockPos> newMembers = ok ? List.of(worldPosition, core, intake) : List.of();
        Set<BlockPos> keep = new HashSet<>(newMembers);
        for (BlockPos old : members) if (!keep.contains(old)) setFormed(level, old, false);
        for (BlockPos m : newMembers) setFormed(level, m, true);
        members.clear();
        members.addAll(newMembers);

        boolean wasFormed = formed;
        formed = ok;
        structureError = ok ? "" : error;
        afterburner = ((JetPartBlock) getBlockState().getBlock()).part() == JetPart.AFTERBURNER_NOZZLE;
        vectoring = ((JetPartBlock) getBlockState().getBlock()).part() == JetPart.VECTORING_NOZZLE;
        intakePos = ok ? intake : null;
        if (wasFormed && !ok && state != EngineState.OFF && state != EngineState.FAILED) {
            lit = false;
            setState(EngineState.OFF, "structure");
        }
    }

    private static boolean is(Level level, BlockPos pos, JetPart part, Direction exhaust) {
        BlockState s = level.getBlockState(pos);
        return s.getBlock() instanceof JetPartBlock b && b.part() == part && s.getValue(JetPartBlock.FACING) == exhaust;
    }

    private void setFormed(Level level, BlockPos pos, boolean value) {
        BlockState s = level.getBlockState(pos);
        if (s.getBlock() instanceof JetPartBlock && s.getValue(JetPartBlock.FORMED) != value) {
            level.setBlock(pos, s.setValue(JetPartBlock.FORMED, value), Block.UPDATE_CLIENTS);
            level.invalidateCapabilities(pos);
        }
    }

    /** The nozzle is gone: release the sections. */
    void disassemble() {
        if (level == null) return;
        for (BlockPos m : members) if (!m.equals(worldPosition)) setFormed(level, m, false);
        members.clear();
        formed = false;
    }

    // ---- Tick ----

    public static void serverTick(Level level, BlockPos pos, BlockState blockState, JetEngineBlockEntity be) {
        if (be.scanRequested || --be.scanTimer <= 0) {
            be.scanRequested = false;
            be.scanTimer = 20;
            be.scan();
        }
        if (level.getGameTime() % 10 == 0 || be.scanTimer == 20) be.sampleEnvironment();
        be.readRedstone();
        be.step();
        if (be.formed && be.n2 > 0.2) be.airEffects();
        be.updateVisuals(blockState);
    }

    private void sampleEnvironment() {
        Level level = this.level;
        if (level == null) return;
        Vec3 center = Vec3.atCenterOf(worldPosition);
        Vec3 world = AshCC.sableLoaded ? SableCompat.worldCenter(level, worldPosition) : center;
        double scale = AshCCConfig.JET_DENSITY_SCALE_HEIGHT.get();
        density = Mth.clamp(Math.exp(-(world.y - 63) / scale), 0.02, 1.25);
        airspeed = AshCC.sableLoaded ? SableCompat.velocityAt(level, worldPosition).length() : 0;

        if (intakePos != null) {
            BlockPos front = intakePos.relative(exhaust().getOpposite());
            BlockState s = level.getBlockState(front);
            intakeFlooded = s.getFluidState().is(FluidTags.WATER) || s.getFluidState().is(FluidTags.LAVA);
            intakeClear = s.getCollisionShape(level, front).isEmpty();
        }
    }

    private void readRedstone() {
        if (!redstoneControl || level == null) return;
        int input = 0;
        for (BlockPos m : members) input = Math.max(input, level.getBestNeighborSignal(m));
        if (members.isEmpty()) input = level.getBestNeighborSignal(worldPosition);
        // A dropout keeps the last throttle until it counts as off
        if (input > 0 || redstoneOffTicks >= 4) redstoneInput = input;
        if (input > 0) {
            redstoneOffTicks = 0;
            // Start on the rising edge; while the signal stays on, try again every 3 s after a flameout or failed start
            if (state == EngineState.OFF && (!redstoneLatch || ++restartTicks >= 60)) {
                restartTicks = 0;
                start();
            }
            redstoneLatch = true;
        } else if (++redstoneOffTicks >= 5) {
            // Ignore short dropouts (clocks, flickering wiring): only a signal off for 5 ticks shuts the engine down
            redstoneLatch = false;
            restartTicks = 0;
            shutdown();
        }
    }

    /** Why the fire cannot burn, or null. */
    private @Nullable String combustionProblem() {
        if (intakeFlooded) return "water_ingestion";
        if (!intakeClear) return "intake_blocked";
        if (density < 0.08) return "air_too_thin";
        if (AshCCConfig.JET_REQUIRE_FUEL.get() && tank.isEmpty()) return "fuel_starvation";
        return null;
    }

    private void step() {
        double tau = AshCCConfig.JET_SPOOL_SECONDS.get();
        double maxN2 = 1.0;
        double throttle = effectiveThrottle();
        String problem = formed ? combustionProblem() : "structure";

        switch (state) {
            case OFF -> {
                lit = false;
                // A moving engine windmills in the airstream
                double windmill = Math.min(0.3, airspeed / 250);
                n2 = approach(n2, windmill, tau * 2.5);
            }
            case STARTING -> {
                startTicks++;
                if (!lit) n2 = approach(n2, STARTER_N2, tau * 2);
                if (!lit && n2 >= LIGHT_OFF_N2) {
                    if (problem == null) {
                        lit = true;
                        event("jet_engine_light_off");
                    } else if (startTicks > LIGHT_OFF_TIMEOUT) {
                        abort(problem.equals("fuel_starvation") ? "no_light_off" : problem);
                    }
                } else if (!lit && startTicks > LIGHT_OFF_TIMEOUT) {
                    abort("starter");
                }
                if (lit) {
                    if (problem != null) {
                        flameout(problem);
                    } else {
                        // Starter and burning fuel together accelerate the core to idle
                        n2 = approach(n2, IDLE_N2 + 0.02, tau * 1.6);
                        if (n2 >= STARTER_CUTOUT_N2) setState(EngineState.RUNNING, "idle");
                        else if (startTicks > START_TIMEOUT) abort("hung_start");
                    }
                }
            }
            case RUNNING -> {
                if (problem != null) {
                    flameout(problem);
                } else {
                    double target = IDLE_N2 + (maxN2 - IDLE_N2) * throttle;
                    n2 = approach(n2, target, target > n2 ? tau : tau * 0.8);
                }
            }
            case FAILED -> {
                lit = false;
                n2 = approach(n2, 0, 0.5);
            }
        }

        // Vectoring nozzle: the deflection follows its command at a fixed rate
        double vStep = 120 * DT;
        vectorPitch += Mth.clamp(vectorPitchCmd - vectorPitch, -vStep, vStep);
        vectorYaw += Mth.clamp(vectorYawCmd - vectorYaw, -vStep, vStep);

        // Afterburner: only at military power. Lights after a short delay, cuts at once.
        double abTarget = 0;
        if (state == EngineState.RUNNING && lit && afterburner && throttle >= 0.95 && n2 >= 0.93 * maxN2) {
            abTarget = afterburnerCommand();
        }
        if (abTarget > 0 && abLevel == 0 && abDelay < 6) {
            abDelay++;
        } else {
            if (abTarget == 0) abDelay = 0;
            double rate = abTarget > abLevel ? 1.5 * DT : 3.0 * DT;
            abLevel = abLevel + Mth.clamp(abTarget - abLevel, -rate, rate);
            if (abLevel < 1e-3 && abTarget == 0) abLevel = 0;
        }

        // Thrust
        double n1 = n1(n2);
        double ramDry = 1 - 0.45 * Math.min(1, airspeed / 300);
        double ramWet = 1 - 0.2 * Math.min(1, airspeed / 300);
        // Thrust counts from idle: an idling engine only holds itself turning and pushes nothing
        double idleN1 = n1(IDLE_N2);
        double power = Mth.clamp((n1 - idleN1) / (1 - idleN1), 0, 1);
        double dry = maxDryThrust() * power * power * density * ramDry;
        double wet = afterburner ? maxDryThrust() * AB_BOOST * abLevel * density * ramWet : 0;
        thrust = lit && formed ? dry + wet : 0;

        // Fuel
        fuelFlow = 0;
        if (lit) {
            double base = AshCCConfig.JET_FUEL_FLOW.get();
            double flow = base * (0.06 + 0.94 * n1 * n1) + base * AB_BOOST * abLevel * 3.0;
            if (AshCCConfig.JET_REQUIRE_FUEL.get()) {
                double value = JetFuels.value(tank.getFluid());
                flow = value > 0 ? flow / value : flow;
                fuelDebt += flow;
                int draw = (int) fuelDebt;
                if (draw > 0) {
                    int got = tank.drain(draw, IFluidHandler.FluidAction.EXECUTE).getAmount();
                    fuelDebt -= draw;
                    if (got < draw) fuelDebt = 0;
                }
            }
            fuelFlow = flow;
        }

        // Exhaust gas temperature
        double egtTarget = AMBIENT;
        if (lit) {
            egtTarget = 380 + 440 * n2 * n2 + 80 * (1 - Math.min(1, density));
            if (state == EngineState.STARTING) {
                // Light-off peak. The controller holds the fuel at the start schedule whatever the throttle says,
                // so an open throttle (or a strong redstone signal) cannot cause a hot start.
                egtTarget += 300 * Math.max(0, 0.5 - n2);
            }
        }
        egt = approach(egt, egtTarget, egtTarget > egt ? 1.2 : 6.0);

        double limit = AshCCConfig.JET_EGT_LIMIT.get();
        if (egt > limit && state != EngineState.FAILED) {
            health -= (egt - limit) / 100 * 0.02 * DT;
            if (health <= 0) fail("overtemp");
            setChanged();
        }
        if (fodCooldown > 0) fodCooldown--;
        updateWarnings(limit);
    }

    private void updateWarnings(double limit) {
        Set<String> now = new LinkedHashSet<>();
        if (!formed) now.add("structure");
        if (egt > limit) now.add("overtemp");
        else if (egt > limit - 50) now.add("egt_high");
        if (AshCCConfig.JET_REQUIRE_FUEL.get() && tank.getFluidAmount() < tank.getCapacity() * 0.1) now.add("low_fuel");
        if (formed && !intakeClear) now.add("intake_blocked");
        if (formed && intakeFlooded) now.add("intake_flooded");
        if (health < 0.5) now.add("damaged");
        if (density < 0.2) now.add("thin_air");
        if (afterburnerCommand() > 0 && !afterburner) now.add("no_afterburner");
        for (String w : now) if (!warnings.contains(w)) event("jet_engine_warning", w);
        warnings.clear();
        warnings.addAll(now);
    }

    private void flameout(String reason) {
        lit = false;
        event("jet_engine_flameout", reason);
        setState(EngineState.OFF, reason);
    }

    private void abort(String reason) {
        lit = false;
        setState(EngineState.OFF, reason);
    }

    private void fail(String reason) {
        health = 0;
        lit = false;
        setState(EngineState.FAILED, reason);
        if (level != null) level.playSound(null, worldPosition, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 1.5f, 0.6f);
    }

    private void setState(EngineState next, String reason) {
        if (state == next) return;
        state = next;
        lastReason = reason;
        event("jet_engine_state", next.name().toLowerCase(), reason);
        setChanged();
    }

    private void event(String name, Object... args) {
        for (IComputerAccess c : computers) {
            Object[] all = new Object[args.length + 1];
            all[0] = c.getAttachmentName();
            System.arraycopy(args, 0, all, 1, args.length);
            c.queueEvent(name, all);
        }
    }

    private static double approach(double current, double target, double tauSeconds) {
        double next = current + (target - current) * Math.min(1, DT / tauSeconds);
        return Math.abs(target - next) < 1e-4 ? target : next;
    }

    // ---- Intake suction and jet blast ----

    private void airEffects() {
        Level level = this.level;
        if (level == null || intakePos == null) return;
        double n1 = n1(n2);
        Direction ex = exhaust();
        Vec3 exLocal = Vec3.atLowerCornerOf(ex.getNormal());

        if (AshCCConfig.JET_INGESTION.get() && state == EngineState.RUNNING && n1 > 0.15) {
            Vec3 mouth = world(Vec3.atCenterOf(intakePos).subtract(exLocal.scale(0.55)));
            Vec3 in = dir(exLocal);
            double reach = 1.5 + 2.5 * n1;
            AABB box = new AABB(mouth, mouth).inflate(reach);
            for (Entity e : level.getEntities((Entity) null, box, e -> e.isAlive() && !e.isSpectator())) {
                if (e instanceof Player p && p.getAbilities().flying) continue;
                Vec3 to = mouth.subtract(e.position().add(0, e.getBbHeight() / 2, 0));
                double dist = to.length();
                // Only in front of the intake (a cone opening forwards)
                if (dist > reach || to.normalize().dot(in) < 0.3) continue;
                double pull = 0.06 * n1 * density * (1 - dist / reach);
                e.setDeltaMovement(e.getDeltaMovement().add(to.normalize().scale(pull)));
                e.hurtMarked = true;
                if (dist < 0.9) ingest(e, n1);
            }
        }

        if (AshCCConfig.JET_BLAST.get() && lit) {
            Vec3 blastLocal = exhaustDir();
            Vec3 exit = world(Vec3.atCenterOf(worldPosition).add(blastLocal.scale(0.5)));
            Vec3 out = dir(blastLocal);
            double len = 2 + 6 * n1 + 8 * abLevel;
            AABB box = new AABB(exit, exit.add(out.scale(len))).inflate(1.2);
            for (Entity e : level.getEntities((Entity) null, box, e -> e.isAlive() && !e.isSpectator())) {
                if (e instanceof Player p && p.getAbilities().flying) continue;
                Vec3 rel = e.position().add(0, e.getBbHeight() / 2, 0).subtract(exit);
                double along = rel.dot(out);
                if (along < 0 || along > len) continue;
                double radius = 0.5 + along * 0.12;
                if (rel.subtract(out.scale(along)).length() > radius + e.getBbWidth() / 2) continue;
                double falloff = 1 - along / len;
                e.setDeltaMovement(e.getDeltaMovement().add(out.scale((0.12 * n1 + 0.25 * abLevel) * falloff * density)));
                e.hurtMarked = true;
                if (along < len * (0.25 + 0.4 * abLevel) && (abLevel > 0.05 || n1 > 0.8)) {
                    e.igniteForSeconds(3);
                    e.hurt(level.damageSources().inFire(), (float) (1 + 3 * abLevel));
                }
            }
        }
    }

    private void ingest(Entity e, double n1) {
        if (level == null || fodCooldown > 0) return;
        fodCooldown = 10;
        if (e instanceof ItemEntity item) {
            item.discard();
            health -= 0.01;
        } else {
            e.hurt(level.damageSources().flyIntoWall(), (float) (4 + 8 * n1));
            health -= 0.05;
        }
        warnings.add("fod");
        event("jet_engine_warning", "fod");
        if (health <= 0) fail("fod");
        setChanged();
    }

    private Vec3 world(Vec3 blockPoint) {
        return AshCC.sableLoaded && level != null ? SableCompat.pointToWorld(level, worldPosition, blockPoint) : blockPoint;
    }

    private Vec3 dir(Vec3 blockDir) {
        return AshCC.sableLoaded && level != null ? SableCompat.dirToWorld(level, worldPosition, blockDir) : blockDir;
    }

    // ---- Visuals and client sync ----

    private void updateVisuals(BlockState blockState) {
        Level level = this.level;
        if (level == null) return;
        int glow = abLevel > 0.05 ? 2 : lit || egt > 250 ? 1 : 0;
        if (blockState.getValue(JetNozzleBlock.GLOW) != glow) {
            level.setBlock(worldPosition, blockState.setValue(JetNozzleBlock.GLOW, glow), Block.UPDATE_CLIENTS);
        }
        if (++syncTimer < 2) return;
        // Fast for the visuals; once a second anyway so the goggle readout (fuel, health, ...) stays current
        boolean changed = Math.abs(n2 - sentN2) > 0.004 || Math.abs(egt - sentEgt) > 4 || Math.abs(abLevel - sentAb) > 0.01
            || Math.abs(vectorPitch - sentVecP) > 0.05 || Math.abs(vectorYaw - sentVecY) > 0.05
            || lit != sentLit || state != sentState || formed != sentFormed || syncTimer >= 20;
        if (!changed) return;
        syncTimer = 0;
        sentN2 = n2;
        sentEgt = egt;
        sentAb = abLevel;
        sentVecP = vectorPitch;
        sentVecY = vectorYaw;
        sentLit = lit;
        sentState = state;
        sentFormed = formed;
        level.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_CLIENTS);
    }

    /** Client: smooth the synced values, play the sound. */
    public void clientTick() {
        prevN2 = clientN2;
        prevEgt = clientEgt;
        prevAb = clientAb;
        prevVecP = clientVecP;
        prevVecY = clientVecY;
        clientVecP += (syncedVecP - clientVecP) * 0.4;
        clientVecY += (syncedVecY - clientVecY) * 0.4;
        prevFlash = clientFlash;
        if (syncedAb > 0.02 && clientAb <= 0.02) clientFlash = 1;
        clientN2 += (syncedN2 - clientN2) * 0.35;
        clientEgt += (syncedEgt - clientEgt) * 0.35;
        clientAb += (syncedAb - clientAb) * 0.4;
        clientFlash *= 0.75;
        // The fan windmills with the core during the start, before N1 picks up. Capped below the strobe rate.
        prevFanAngle = fanAngle;
        prevTurbineAngle = turbineAngle;
        fanAngle = (float) ((fanAngle + 0.55 * Math.max(n1(clientN2), clientN2 * 0.45)) % (Math.PI * 2));
        turbineAngle = (float) ((turbineAngle + 0.8 * clientN2) % (Math.PI * 2));
        if (fanAngle < prevFanAngle) prevFanAngle -= (float) (Math.PI * 2);
        if (turbineAngle < prevTurbineAngle) prevTurbineAngle -= (float) (Math.PI * 2);
        com.ashcc.jet.client.JetEngineSound.tick(this);
    }

    public double clientVectorPitch(float pt) { return Mth.lerp(pt, prevVecP, clientVecP); }
    public double clientVectorYaw(float pt) { return Mth.lerp(pt, prevVecY, clientVecY); }
    public boolean clientVectoring() { return vectoring; }
    public double clientN2(float pt) { return Mth.lerp(pt, prevN2, clientN2); }
    public double clientEgt(float pt) { return Mth.lerp(pt, prevEgt, clientEgt); }
    public double clientAb(float pt) { return Mth.lerp(pt, prevAb, clientAb); }
    public double clientFlash(float pt) { return Mth.lerp(pt, prevFlash, clientFlash); }
    public float fanAngle(float pt) { return Mth.lerp(pt, prevFanAngle, fanAngle); }
    public float turbineAngle(float pt) { return Mth.lerp(pt, prevTurbineAngle, turbineAngle); }
    public boolean clientAfterburner() { return afterburner; }
    public boolean syncedLitClient() { return syncedLit; }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && level.isClientSide) com.ashcc.jet.client.JetEngineSound.stop(this);
    }

    private CompoundTag syncTag() {
        CompoundTag t = new CompoundTag();
        t.putDouble("N2", n2);
        t.putDouble("Egt", egt);
        t.putDouble("Ab", abLevel);
        t.putBoolean("Lit", lit);
        t.putBoolean("Formed", formed);
        t.putBoolean("Afterburner", afterburner);
        t.putBoolean("Vectoring", vectoring);
        t.putDouble("VecP", vectorPitch);
        t.putDouble("VecY", vectorYaw);
        t.putString("State", state.name());
        // Instrument readings for the goggles
        t.putDouble("Thrust", thrust);
        t.putDouble("Throttle", effectiveThrottle());
        t.putDouble("Limit", throttleLimit);
        t.putDouble("AbCmd", afterburnerCommand());
        t.putDouble("FuelFlow", fuelFlow);
        t.putDouble("Health", health);
        t.putBoolean("Redstone", redstoneControl);
        t.putInt("RedstoneInput", redstoneInput);
        t.putString("Error", structureError);
        t.putString("Reason", lastReason);
        t.putString("Warnings", String.join(",", warnings));
        if (level != null) t.put("Fuel", tank.getFluid().saveOptional(level.registryAccess()));
        return t;
    }

    private void readSync(CompoundTag t) {
        syncedN2 = t.getDouble("N2");
        syncedEgt = t.getDouble("Egt");
        syncedAb = t.getDouble("Ab");
        syncedLit = t.getBoolean("Lit");
        formed = t.getBoolean("Formed");
        afterburner = t.getBoolean("Afterburner");
        vectoring = t.getBoolean("Vectoring");
        syncedVecP = t.getDouble("VecP");
        syncedVecY = t.getDouble("VecY");
        state = parseState(t.getString("State"));
        n2 = syncedN2;
        egt = syncedEgt;
        abLevel = syncedAb;
        lit = syncedLit;
        thrust = t.getDouble("Thrust");
        clientThrottle = t.getDouble("Throttle");
        throttleLimit = t.contains("Limit") ? t.getDouble("Limit") : 1;
        clientAbCmd = t.getDouble("AbCmd");
        fuelFlow = t.getDouble("FuelFlow");
        health = t.getDouble("Health");
        redstoneControl = t.getBoolean("Redstone");
        redstoneInput = t.getInt("RedstoneInput");
        structureError = t.getString("Error");
        lastReason = t.getString("Reason");
        warnings.clear();
        for (String w : t.getString("Warnings").split(",")) if (!w.isEmpty()) warnings.add(w);
        if (level != null && t.contains("Fuel")) tank.setFluid(FluidStack.parseOptional(level.registryAccess(), t.getCompound("Fuel")));
    }

    /** Client copies of the commanded throttle and afterburner (the server works them out from redstone). */
    private double clientThrottle, clientAbCmd;

    public double displayedThrottle() {
        return level != null && level.isClientSide ? clientThrottle : effectiveThrottle();
    }

    public double displayedAfterburnerCommand() {
        return level != null && level.isClientSide ? clientAbCmd : afterburnerCommand();
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return syncTag();
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        readSync(tag);
        clientN2 = prevN2 = syncedN2;
        clientEgt = prevEgt = syncedEgt;
        clientAb = prevAb = syncedAb;
        clientVecP = prevVecP = syncedVecP;
        clientVecY = prevVecY = syncedVecY;
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider registries) {
        readSync(pkt.getTag());
    }

    private static EngineState parseState(String s) {
        try {
            return EngineState.valueOf(s);
        } catch (IllegalArgumentException e) {
            return EngineState.OFF;
        }
    }

    // ---- Save ----

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putDouble("Throttle", throttleCmd);
        tag.putDouble("ThrottleLimit", throttleLimit);
        tag.putDouble("Afterburner", afterburnerCmd);
        tag.putDouble("VectorPitchCmd", vectorPitchCmd);
        tag.putDouble("VectorYawCmd", vectorYawCmd);
        tag.putDouble("VectorPitch", vectorPitch);
        tag.putDouble("VectorYaw", vectorYaw);
        tag.putBoolean("RedstoneControl", redstoneControl);
        tag.putString("State", state.name());
        tag.putBoolean("Lit", lit);
        tag.putDouble("N2", n2);
        tag.putDouble("Egt", egt);
        tag.putDouble("Health", health);
        tag.put("Tank", tank.writeToNBT(registries, new CompoundTag()));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        throttleCmd = tag.getDouble("Throttle");
        throttleLimit = tag.contains("ThrottleLimit") ? Mth.clamp(tag.getDouble("ThrottleLimit"), 0.1, 1) : 1;
        afterburnerCmd = tag.getDouble("Afterburner");
        vectorPitchCmd = tag.getDouble("VectorPitchCmd");
        vectorYawCmd = tag.getDouble("VectorYawCmd");
        vectorPitch = tag.getDouble("VectorPitch");
        vectorYaw = tag.getDouble("VectorYaw");
        redstoneControl = !tag.contains("RedstoneControl") || tag.getBoolean("RedstoneControl");
        state = parseState(tag.getString("State"));
        lit = tag.getBoolean("Lit");
        n2 = tag.getDouble("N2");
        egt = tag.contains("Egt") ? tag.getDouble("Egt") : AMBIENT;
        health = tag.contains("Health") ? tag.getDouble("Health") : 1;
        if (tag.contains("Tank", Tag.TAG_COMPOUND)) tank.readFromNBT(registries, tag.getCompound("Tank"));
        // A running engine resumes after a reload as soon as the structure check confirms it
        redstoneLatch = state == EngineState.RUNNING || state == EngineState.STARTING;
    }
}
