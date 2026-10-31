package com.ashcc;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class AshCCConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue RADAR_DEFAULT_RANGE;
    public static final ModConfigSpec.IntValue RADAR_MAX_RESULTS;
    public static final ModConfigSpec.IntValue RADAR_MIN_WATCH_INTERVAL;
    public static final ModConfigSpec.IntValue RADAR_TERRAIN_MAX_SIZE;
    public static final ModConfigSpec.DoubleValue THRUSTER_MAX_THRUST;
    public static final ModConfigSpec.DoubleValue THRUSTER_MAX_GIMBAL;
    public static final ModConfigSpec.DoubleValue THRUSTER_THROTTLE_RATE;
    public static final ModConfigSpec.DoubleValue THRUSTER_GIMBAL_RATE;
    public static final ModConfigSpec.DoubleValue JET_DRY_THRUST;
    public static final ModConfigSpec.DoubleValue JET_FUEL_FLOW;
    public static final ModConfigSpec.IntValue JET_TANK_CAPACITY;
    public static final ModConfigSpec.BooleanValue JET_REQUIRE_FUEL;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> JET_FUELS;
    public static final ModConfigSpec.DoubleValue JET_EGT_LIMIT;
    public static final ModConfigSpec.DoubleValue JET_SPOOL_SECONDS;
    public static final ModConfigSpec.DoubleValue JET_DENSITY_SCALE_HEIGHT;
    public static final ModConfigSpec.BooleanValue JET_INGESTION;
    public static final ModConfigSpec.BooleanValue JET_BLAST;
    public static final ModConfigSpec.DoubleValue PANEL_STICK_RATE;
    public static final ModConfigSpec.DoubleValue PANEL_SLIDER_RATE;
    public static final ModConfigSpec.IntValue HOSE_RATE;
    public static final ModConfigSpec.IntValue HOSE_MAX_LENGTH;
    public static final ModConfigSpec.IntValue PIPE_RATE;
    public static final ModConfigSpec.IntValue PIPE_MAX_LENGTH;
    public static final ModConfigSpec.DoubleValue LINK_RANGE;
    public static final ModConfigSpec.BooleanValue LINK_INTERDIMENSIONAL;
    public static final ModConfigSpec.IntValue LINK_MAX;
    public static final ModConfigSpec.DoubleValue SEEKER_DEFAULT_ANGLE;
    public static final ModConfigSpec.DoubleValue SEEKER_DEFAULT_DISTANCE;
    public static final ModConfigSpec.DoubleValue SEEKER_FLARE_BIAS;
    public static final ModConfigSpec.DoubleValue IMU_GRAVITY;
    public static final ModConfigSpec.IntValue MONITOR_MAX_WIDTH;
    public static final ModConfigSpec.IntValue MONITOR_MAX_HEIGHT;
    public static final ModConfigSpec.DoubleValue MONITOR_SYNC_RANGE;
    public static final ModConfigSpec.IntValue MONITOR_MAX_FRAME_BYTES;
    public static final ModConfigSpec.IntValue DECOY_CAPACITY;
    public static final ModConfigSpec.IntValue DECOY_MAX_SALVO;
    public static final ModConfigSpec.BooleanValue DECOY_REQUIRE_AMMO;
    public static final ModConfigSpec.DoubleValue DECOY_COOLDOWN_SECONDS;
    public static final ModConfigSpec.DoubleValue DECOY_SPREAD;
    public static final ModConfigSpec.DoubleValue DECOY_FLARE_SPEED;
    public static final ModConfigSpec.DoubleValue DECOY_FLARE_SECONDS;
    public static final ModConfigSpec.DoubleValue DECOY_FLARE_GRAVITY;
    public static final ModConfigSpec.DoubleValue DECOY_FLARE_DRAG;
    public static final ModConfigSpec.DoubleValue DECOY_CHAFF_SPEED;
    public static final ModConfigSpec.DoubleValue DECOY_CHAFF_SECONDS;
    public static final ModConfigSpec.DoubleValue DECOY_CHAFF_GRAVITY;
    public static final ModConfigSpec.DoubleValue DECOY_CHAFF_DRAG;
    public static final ModConfigSpec.DoubleValue FLAP_DEFAULT_SPEED;
    public static final ModConfigSpec.DoubleValue FLAP_MAX_SPEED;
    public static final ModConfigSpec.DoubleValue FLAP_MAX_ANGLE;
    public static final ModConfigSpec.DoubleValue FLAP_REDSTONE_ANGLE;
    public static final ModConfigSpec.DoubleValue SERVO_DEFAULT_SPEED;
    public static final ModConfigSpec.DoubleValue SERVO_MAX_SPEED;
    public static final ModConfigSpec.DoubleValue SERVO_STIFFNESS;
    public static final ModConfigSpec.DoubleValue SERVO_DAMPING;
    public static final ModConfigSpec.DoubleValue SERVO_FRICTION;
    public static final ModConfigSpec.DoubleValue ANTIGRAV_MAX_SPEED;
    public static final ModConfigSpec.DoubleValue ANTIGRAV_MAX_ACCELERATION;
    public static final ModConfigSpec.DoubleValue ANTIGRAV_MAX_ANGULAR_SPEED;
    public static final ModConfigSpec.DoubleValue ANTIGRAV_MAX_ANGULAR_ACCELERATION;
    public static final ModConfigSpec.DoubleValue ANTIGRAV_SPEED_CAP;
    public static final ModConfigSpec.DoubleValue ANTIGRAV_ACCELERATION_CAP;
    public static final ModConfigSpec.DoubleValue ANTIGRAV_ANGULAR_SPEED_CAP;
    public static final ModConfigSpec.DoubleValue ANTIGRAV_ANGULAR_ACCELERATION_CAP;
    public static final ModConfigSpec.DoubleValue CANNON_AIM_SPEED;
    public static final ModConfigSpec.DoubleValue CANNON_MAX_AIM_SPEED;
    public static final ModConfigSpec.DoubleValue ROTARY_MAX_RPM;
    public static final ModConfigSpec.DoubleValue ROTARY_SPIN_UP_SECONDS;
    public static final ModConfigSpec.DoubleValue ROTARY_SPIN_DOWN_SECONDS;
    public static final ModConfigSpec.DoubleValue ROTARY_MIN_SPIN;
    public static final ModConfigSpec.DoubleValue RAILGUN_BASE_SPEED;
    public static final ModConfigSpec.DoubleValue RAILGUN_SPEED_PER_RAIL;
    public static final ModConfigSpec.DoubleValue RAILGUN_MAX_SPEED;
    public static final ModConfigSpec.IntValue RAILGUN_MAX_RAILS;
    public static final ModConfigSpec.IntValue RAILGUN_ENERGY_PER_SHOT;
    public static final ModConfigSpec.IntValue RAILGUN_ENERGY_CAPACITY;
    public static final ModConfigSpec.IntValue RAILGUN_MAX_RECEIVE;
    public static final ModConfigSpec.DoubleValue RAILGUN_COOLDOWN_SECONDS;
    public static final ModConfigSpec.IntValue RAILGUN_AMMO_CAPACITY;
    public static final ModConfigSpec.DoubleValue RAILGUN_SPREAD;
    public static final ModConfigSpec.DoubleValue RAILGUN_RECOIL;
    public static final ModConfigSpec.IntValue LASER_CANNON_MAX_LENSES;
    public static final ModConfigSpec.DoubleValue LASER_CANNON_BASE_RANGE;
    public static final ModConfigSpec.DoubleValue LASER_CANNON_RANGE_PER_LENS;
    public static final ModConfigSpec.DoubleValue LASER_CANNON_BASE_BLOCK_POWER;
    public static final ModConfigSpec.DoubleValue LASER_CANNON_BLOCK_POWER_PER_LENS;
    public static final ModConfigSpec.DoubleValue LASER_CANNON_BASE_DAMAGE;
    public static final ModConfigSpec.DoubleValue LASER_CANNON_DAMAGE_PER_LENS;
    public static final ModConfigSpec.IntValue LASER_CANNON_IGNITE_SECONDS;
    public static final ModConfigSpec.BooleanValue LASER_CANNON_BREAK_BLOCKS;
    public static final ModConfigSpec.BooleanValue LASER_CANNON_DROP_BLOCKS;
    public static final ModConfigSpec.BooleanValue LASER_CANNON_DAMAGE_OWN_SUB_LEVEL;
    public static final ModConfigSpec.DoubleValue LASER_CANNON_IMPACT_FORCE;
    public static final ModConfigSpec.BooleanValue LASER_CANNON_RECOIL;
    public static final ModConfigSpec.IntValue LASER_CANNON_ENERGY_PER_TICK;
    public static final ModConfigSpec.IntValue LASER_CANNON_ENERGY_CAPACITY;
    public static final ModConfigSpec.IntValue LASER_CANNON_MAX_RECEIVE;
    public static final ModConfigSpec.DoubleValue LASER_CANNON_MAX_FIRE_SECONDS;
    public static final ModConfigSpec.DoubleValue LASER_CANNON_COOLDOWN_SECONDS;

    public static final ModConfigSpec.DoubleValue LASER_RANGE;
    public static final ModConfigSpec.DoubleValue LASER_BLOCK_POWER;
    public static final ModConfigSpec.BooleanValue LASER_BREAK_BLOCKS;
    public static final ModConfigSpec.BooleanValue LASER_DROP_BLOCKS;
    public static final ModConfigSpec.BooleanValue LASER_DAMAGE_OWN_SUB_LEVEL;
    public static final ModConfigSpec.DoubleValue LASER_ENTITY_DAMAGE;
    public static final ModConfigSpec.IntValue LASER_IGNITE_SECONDS;
    public static final ModConfigSpec.DoubleValue LASER_IMPACT_FORCE;
    public static final ModConfigSpec.BooleanValue LASER_RECOIL;
    public static final ModConfigSpec.DoubleValue LASER_AIM_SPEED;
    public static final ModConfigSpec.DoubleValue LASER_MAX_FIRE_SECONDS;
    public static final ModConfigSpec.DoubleValue LASER_COOLDOWN_SECONDS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("radar");
        RADAR_DEFAULT_RANGE = b.comment("Scan radius of a newly placed radar (blocks)").defineInRange("defaultRange", 2048, 1, Integer.MAX_VALUE);
        RADAR_MAX_RESULTS = b.comment("Maximum number of contacts returned by one scan").defineInRange("maxResults", 256, 1, 4096);
        RADAR_MIN_WATCH_INTERVAL = b.comment("Minimum interval in ticks between automatic watch scans").defineInRange("minWatchInterval", 5, 1, 1200);
        RADAR_TERRAIN_MAX_SIZE = b.comment("Maximum cells per side of a terrain scan (scanTerrain)").defineInRange("terrainMaxSize", 128, 1, 512);
        b.pop();
        b.push("thruster");
        THRUSTER_MAX_THRUST = b.comment("Force at full throttle (mass x blocks/s^2; Sable gravity is about 9.8 blocks/s^2)")
            .defineInRange("maxThrust", 2000.0, 0.0, 1.0e9);
        THRUSTER_MAX_GIMBAL = b.comment("Maximum gimbal angle per axis in degrees").defineInRange("maxGimbal", 30.0, 0.0, 80.0);
        THRUSTER_THROTTLE_RATE = b.comment("Fraction of the throttle change applied per tick (1 = instant)").defineInRange("throttleRate", 1.0, 0.01, 1.0);
        THRUSTER_GIMBAL_RATE = b.comment("Fraction of the gimbal change applied per tick (1 = instant)").defineInRange("gimbalRate", 1.0, 0.01, 1.0);
        b.pop();
        b.push("jetEngine");
        JET_DRY_THRUST = b.comment("Military (full dry) thrust at sea level (mass x blocks/s^2); the afterburner adds half of it.",
                "Scaled to Create Propulsion on Sable: its thruster gives 533, a 3x3x3 thruster 800")
            .defineInRange("dryThrust", 1000.0, 0.0, 1.0e9);
        JET_FUEL_FLOW = b.comment("Fuel flow at military power (mB/tick, for a fuel of value 1); full afterburner adds 1.5x of it")
            .defineInRange("fuelFlow", 1.0, 0.0, 1000.0);
        JET_TANK_CAPACITY = b.comment("Fuel the engine itself holds (mB)").defineInRange("tankCapacity", 16000, 1, Integer.MAX_VALUE);
        JET_REQUIRE_FUEL = b.comment("false: engines run without fuel").define("requireFuel", true);
        JET_FUELS = b.comment("Accepted fuels: \"fluid id=value\" or \"#fluid tag=value\". Value is energy per mB (kerosene = 1). The first match wins, so list specific tags first")
            .defineListAllowEmpty("fuels", java.util.List.of(
                "#c:kerosene=1.0", "#c:fuels/kerosene=1.0", "#c:biodiesel=0.8", "#c:fuels/biodiesel=0.8",
                "#c:diesel=0.9", "#c:fuels/diesel=0.9", "#c:gasoline=0.9", "#c:fuels/gasoline=0.9", "#c:ethanol=0.6",
                "#c:fuels/ethanol=0.6", "#c:fuel=0.8", "#c:plantoil=0.5", "#c:oil=0.4", "minecraft:lava=0.3"),
                () -> "#c:kerosene=1.0", o -> o instanceof String s && s.contains("="));
        JET_EGT_LIMIT = b.comment("Exhaust gas temperature redline in degrees C; above it the engine takes damage")
            .defineInRange("egtLimit", 900.0, 100.0, 5000.0);
        JET_SPOOL_SECONDS = b.comment("Time constant of the core spool (seconds)")
            .defineInRange("spoolSeconds", 1.6, 0.05, 60.0);
        JET_DENSITY_SCALE_HEIGHT = b.comment("Height above sea level (Y 63) where air density and thrust drop to 1/e (blocks)")
            .defineInRange("densityScaleHeight", 800.0, 1.0, 1.0e6);
        JET_INGESTION = b.comment("Running intakes suck in and hurt entities in front of them (and are damaged by it)").define("ingestion", true);
        JET_BLAST = b.comment("Exhaust pushes entities behind the nozzle, and the afterburner sets them on fire").define("jetBlast", true);
        b.pop();
        b.push("controlPanel");
        PANEL_STICK_RATE = b.comment("How fast a panel stick follows the keys and springs back (full deflection per second)").defineInRange("stickRate", 4.0, 0.1, 100.0);
        PANEL_SLIDER_RATE = b.comment("How fast a panel slider moves while W/S is held (full travel per second)").defineInRange("sliderRate", 0.5, 0.01, 100.0);
        b.pop();
        b.push("hose");
        HOSE_RATE = b.comment("Fluid a hose moves per tick (mB)").defineInRange("rate", 100, 1, 1000000);
        HOSE_MAX_LENGTH = b.comment("Longest hose (blocks); a hose stretched to 1.5x this comes off").defineInRange("maxLength", 32, 2, 256);
        PIPE_RATE = b.comment("Fluid a rigid pipe moves per tick (mB)").defineInRange("pipeRate", 250, 1, 1000000);
        PIPE_MAX_LENGTH = b.comment("Longest rigid pipe, measured along its path (blocks)").defineInRange("pipeMaxLength", 64, 2, 512);
        b.pop();
        b.push("link");
        LINK_RANGE = b.comment("Maximum distance between a link hub and a linked peripheral in blocks (0 = unlimited)").defineInRange("range", 0.0, 0.0, 1.0e9);
        LINK_INTERDIMENSIONAL = b.comment("Allow linking peripherals in other dimensions").define("interdimensional", true);
        LINK_MAX = b.comment("Maximum number of links per hub").defineInRange("maxLinks", 64, 1, 1024);
        b.pop();
        b.push("seeker");
        SEEKER_DEFAULT_ANGLE = b.comment("Cone half-angle used by seek() without arguments (degrees from the line of sight)").defineInRange("defaultAngle", 15.0, 0.0, 180.0);
        SEEKER_DEFAULT_DISTANCE = b.comment("Distance used by seek() without a distance argument (blocks)").defineInRange("defaultDistance", 512.0, 0.0, 1.0e9);
        SEEKER_FLARE_BIAS = b.comment("A fresh flare beats a ship that is up to this many degrees closer to the line of sight (fades as it burns out)")
            .defineInRange("flareBias", 10.0, 0.0, 180.0);
        b.pop();
        b.push("hdMonitor");
        MONITOR_MAX_WIDTH = b.comment("Widest HD monitor in blocks (128 pixels each)").defineInRange("maxWidth", 16, 1, 32);
        MONITOR_MAX_HEIGHT = b.comment("Tallest HD monitor in blocks (128 pixels each)").defineInRange("maxHeight", 12, 1, 32);
        MONITOR_SYNC_RANGE = b.comment("Players within this many blocks receive the monitor's picture").defineInRange("syncRange", 96.0, 8.0, 1024.0);
        MONITOR_MAX_FRAME_BYTES = b.comment("Most bytes of drawing collected before present() when auto-present is off")
            .defineInRange("maxFrameBytes", 16 << 20, 1 << 20, 256 << 20);
        b.pop();
        b.push("imu");
        IMU_GRAVITY = b.comment("Gravity the imu API's accelerometer assumes (blocks/s^2; Sable uses about 9.8)").defineInRange("gravity", 9.8, 0.0, 1000.0);
        b.pop();
        b.push("decoyLauncher");
        DECOY_CAPACITY = b.comment("Flares and chaff a launcher holds (each)").defineInRange("capacity", 64, 1, 4096);
        DECOY_MAX_SALVO = b.comment("Most decoys one fire() call launches").defineInRange("maxSalvo", 8, 1, 64);
        DECOY_REQUIRE_AMMO = b.comment("Whether firing uses up loaded flare / chaff items").define("requireAmmo", true);
        DECOY_COOLDOWN_SECONDS = b.comment("Seconds between salvos").defineInRange("cooldownSeconds", 0.5, 0.05, 600.0);
        DECOY_SPREAD = b.comment("Half-angle of the launch cone in degrees").defineInRange("spread", 15.0, 0.0, 90.0);
        DECOY_FLARE_SPEED = b.comment("Flare launch speed relative to the launcher (blocks/s)").defineInRange("flareSpeed", 25.0, 0.0, 500.0);
        DECOY_FLARE_SECONDS = b.comment("Seconds a flare burns").defineInRange("flareSeconds", 6.0, 0.05, 600.0);
        DECOY_FLARE_GRAVITY = b.comment("Flare gravity (blocks/s^2)").defineInRange("flareGravity", 4.0, 0.0, 100.0);
        DECOY_FLARE_DRAG = b.comment("Fraction of a flare's speed kept per tick").defineInRange("flareDrag", 0.96, 0.0, 1.0);
        DECOY_CHAFF_SPEED = b.comment("Chaff launch speed relative to the launcher (blocks/s)").defineInRange("chaffSpeed", 15.0, 0.0, 500.0);
        DECOY_CHAFF_SECONDS = b.comment("Seconds a chaff cloud lasts").defineInRange("chaffSeconds", 8.0, 0.05, 600.0);
        DECOY_CHAFF_GRAVITY = b.comment("Chaff gravity (blocks/s^2)").defineInRange("chaffGravity", 0.5, 0.0, 100.0);
        DECOY_CHAFF_DRAG = b.comment("Fraction of a chaff cloud's speed kept per tick").defineInRange("chaffDrag", 0.85, 0.0, 1.0);
        b.pop();
        b.push("flapBearing");
        FLAP_DEFAULT_SPEED = b.comment("Servo speed of a newly placed flap bearing (degrees per second)").defineInRange("defaultSpeed", 90.0, 1.0, 7200.0);
        FLAP_MAX_SPEED = b.comment("Maximum servo speed settable from Lua (degrees per second)").defineInRange("maxSpeed", 720.0, 1.0, 7200.0);
        FLAP_MAX_ANGLE = b.comment("Target angles are clamped to +-this many degrees").defineInRange("maxAngle", 180.0, 1.0, 180.0);
        FLAP_REDSTONE_ANGLE = b.comment("Angle a redstone flap bearing turns to at signal strength 15 (degrees, linear in the signal)")
            .defineInRange("redstoneAngle", 45.0, 1.0, 180.0);
        b.pop();
        b.push("servoBearing");
        SERVO_DEFAULT_SPEED = b.comment("Servo speed of a newly placed servo bearing (degrees per second)").defineInRange("defaultSpeed", 90.0, 1.0, 7200.0);
        SERVO_MAX_SPEED = b.comment("Maximum servo speed settable from Lua (degrees per second)").defineInRange("maxSpeed", 720.0, 1.0, 7200.0);
        SERVO_STIFFNESS = b.comment("Joint motor stiffness while locked, per unit of inertia (Simulated's swivel bearing uses 1600)")
            .defineInRange("stiffness", 1600.0, 0.0, 1.0e9);
        SERVO_DAMPING = b.comment("Joint motor damping while locked, per unit of inertia (swivel bearing: 40)").defineInRange("damping", 40.0, 0.0, 1.0e9);
        SERVO_FRICTION = b.comment("Joint friction while unlocked (swivel bearing: 0.3)").defineInRange("friction", 0.3, 0.0, 1.0e9);
        b.pop();
        b.push("laser");
        LASER_RANGE = b.comment("Beam length in blocks").defineInRange("range", 256.0, 1.0, 4096.0);
        LASER_BLOCK_POWER = b.comment("Block hardness burnt through per tick (stone 1.5, iron block 5, obsidian 50)").defineInRange("blockPower", 0.5, 0.0, 1.0e6);
        LASER_BREAK_BLOCKS = b.comment("Whether the beam destroys blocks").define("breakBlocks", true);
        LASER_DROP_BLOCKS = b.comment("Whether destroyed blocks drop as items (never for blocks on Sable sub-levels)").define("dropBlocks", false);
        LASER_DAMAGE_OWN_SUB_LEVEL = b.comment("Whether the beam damages blocks of the sub-level the laser is mounted on").define("damageOwnSubLevel", false);
        LASER_ENTITY_DAMAGE = b.comment("Damage per hit to entities in the beam (entities can be hurt about twice a second)").defineInRange("entityDamage", 4.0, 0.0, 1.0e6);
        LASER_IGNITE_SECONDS = b.comment("Seconds entities in the beam are set on fire (0 = never)").defineInRange("igniteSeconds", 3, 0, 600);
        LASER_IMPACT_FORCE = b.comment("Force on a Sable sub-level hit by the beam (mass x blocks/s^2)").defineInRange("impactForce", 100.0, 0.0, 1.0e9);
        LASER_RECOIL = b.comment("Push the laser's own sub-level back with the same force while firing").define("recoil", true);
        LASER_AIM_SPEED = b.comment("Aim slew speed in degrees per second").defineInRange("aimSpeed", 180.0, 1.0, 7200.0);
        LASER_MAX_FIRE_SECONDS = b.comment("Seconds of continuous fire until the laser overheats (0 = never overheats)").defineInRange("maxFireSeconds", 10.0, 0.0, 3600.0);
        LASER_COOLDOWN_SECONDS = b.comment("Seconds to cool down from fully hot to cold").defineInRange("cooldownSeconds", 5.0, 0.05, 3600.0);
        b.pop();
        b.push("antigravity");
        ANTIGRAV_MAX_SPEED = b.comment("Speed limit of a newly placed anti-gravity core (blocks/s)").defineInRange("maxSpeed", 20.0, 0.0, 1.0e4);
        ANTIGRAV_MAX_ACCELERATION = b.comment("Acceleration limit of a newly placed core (blocks/s^2, on top of cancelling gravity)")
            .defineInRange("maxAcceleration", 20.0, 0.0, 1.0e4);
        ANTIGRAV_MAX_ANGULAR_SPEED = b.comment("Turn rate limit of a newly placed core (degrees/s)").defineInRange("maxAngularSpeed", 45.0, 0.0, 1.0e5);
        ANTIGRAV_MAX_ANGULAR_ACCELERATION = b.comment("Angular acceleration limit of a newly placed core (degrees/s^2)")
            .defineInRange("maxAngularAcceleration", 90.0, 0.0, 1.0e6);
        ANTIGRAV_SPEED_CAP = b.comment("Highest speed limit settable from Lua (blocks/s)").defineInRange("speedCap", 100.0, 0.0, 1.0e4);
        ANTIGRAV_ACCELERATION_CAP = b.comment("Highest acceleration limit settable from Lua (blocks/s^2)").defineInRange("accelerationCap", 100.0, 0.0, 1.0e4);
        ANTIGRAV_ANGULAR_SPEED_CAP = b.comment("Highest turn rate limit settable from Lua (degrees/s)").defineInRange("angularSpeedCap", 360.0, 0.0, 1.0e5);
        ANTIGRAV_ANGULAR_ACCELERATION_CAP = b.comment("Highest angular acceleration limit settable from Lua (degrees/s^2)")
            .defineInRange("angularAccelerationCap", 720.0, 0.0, 1.0e6);
        b.pop();
        b.push("cannonMount");
        CANNON_AIM_SPEED = b.comment("Turn rate of a Create Big Cannons cannon mount aimed from Lua (degrees per second)").defineInRange("aimSpeed", 45.0, 1.0, 7200.0);
        CANNON_MAX_AIM_SPEED = b.comment("Highest turn rate settable with setAimSpeed (degrees per second)").defineInRange("maxAimSpeed", 180.0, 1.0, 7200.0);
        b.pop();
        b.push("rotaryAutocannon");
        ROTARY_MAX_RPM = b.comment("Rounds per minute at full spin and fire rate 15 (1200 = one per tick, at most 2400)").defineInRange("maxRpm", 1200.0, 1.0, 2400.0);
        ROTARY_SPIN_UP_SECONDS = b.comment("Seconds from standstill to full spin while the trigger is held").defineInRange("spinUpSeconds", 1.5, 0.05, 60.0);
        ROTARY_SPIN_DOWN_SECONDS = b.comment("Seconds from full spin to standstill after the trigger is released").defineInRange("spinDownSeconds", 2.5, 0.05, 60.0);
        ROTARY_MIN_SPIN = b.comment("Fraction of full spin needed before the first round fires").defineInRange("minSpin", 0.25, 0.0, 1.0);
        b.pop();
        b.push("railgun");
        RAILGUN_BASE_SPEED = b.comment("Muzzle speed with one rail (blocks per tick; a big cannon gets about 1 per powder charge; about 17 is the speed of sound)").defineInRange("baseSpeed", 12.0, 0.1, 1000.0);
        RAILGUN_SPEED_PER_RAIL = b.comment("Muzzle speed added by each rail after the first (blocks per tick)").defineInRange("speedPerRail", 3.0, 0.0, 1000.0);
        RAILGUN_MAX_SPEED = b.comment("Highest muzzle speed (blocks per tick)").defineInRange("maxSpeed", 60.0, 0.1, 1000.0);
        RAILGUN_MAX_RAILS = b.comment("Most rails in one railgun").defineInRange("maxRails", 15, 1, 64);
        RAILGUN_ENERGY_PER_SHOT = b.comment("Forge Energy used by one shot (0 = no power needed)").defineInRange("energyPerShot", 20000, 0, Integer.MAX_VALUE);
        RAILGUN_ENERGY_CAPACITY = b.comment("Forge Energy a railgun breech stores").defineInRange("energyCapacity", 100000, 1, Integer.MAX_VALUE);
        RAILGUN_MAX_RECEIVE = b.comment("Forge Energy a railgun accepts per tick").defineInRange("maxReceive", 5000, 1, Integer.MAX_VALUE);
        RAILGUN_COOLDOWN_SECONDS = b.comment("Seconds between shots").defineInRange("cooldownSeconds", 2.0, 0.05, 600.0);
        RAILGUN_AMMO_CAPACITY = b.comment("Rounds of one kind a railgun breech holds").defineInRange("ammoCapacity", 8, 1, 64);
        RAILGUN_SPREAD = b.comment("Random spread of a shot (same unit as Create Big Cannons, which uses about 1 for a short cannon)").defineInRange("spread", 0.05, 0.0, 10.0);
        RAILGUN_RECOIL = b.comment("Recoil of a shot (Create Big Cannons scale; a 4-charge cannon is about 4)").defineInRange("recoil", 4.0, 0.0, 1000.0);
        b.pop();
        b.push("laserCannon");
        LASER_CANNON_MAX_LENSES = b.comment("Most lenses in one laser cannon").defineInRange("maxLenses", 8, 1, 64);
        LASER_CANNON_BASE_RANGE = b.comment("Beam length with one lens (blocks)").defineInRange("baseRange", 96.0, 1.0, 4096.0);
        LASER_CANNON_RANGE_PER_LENS = b.comment("Beam length added by each lens after the first (blocks)").defineInRange("rangePerLens", 48.0, 0.0, 4096.0);
        LASER_CANNON_BASE_BLOCK_POWER = b.comment("Block hardness burnt through per tick with one lens (the laser block has 0.5)").defineInRange("baseBlockPower", 1.5, 0.0, 1.0e6);
        LASER_CANNON_BLOCK_POWER_PER_LENS = b.comment("Block power added by each lens after the first").defineInRange("blockPowerPerLens", 1.0, 0.0, 1.0e6);
        LASER_CANNON_BASE_DAMAGE = b.comment("Damage per hit to entities with one lens").defineInRange("baseEntityDamage", 6.0, 0.0, 1.0e6);
        LASER_CANNON_DAMAGE_PER_LENS = b.comment("Entity damage added by each lens after the first").defineInRange("entityDamagePerLens", 2.0, 0.0, 1.0e6);
        LASER_CANNON_IGNITE_SECONDS = b.comment("Seconds entities in the beam are set on fire (0 = never)").defineInRange("igniteSeconds", 5, 0, 600);
        LASER_CANNON_BREAK_BLOCKS = b.comment("Whether the beam destroys blocks").define("breakBlocks", true);
        LASER_CANNON_DROP_BLOCKS = b.comment("Whether destroyed blocks drop as items (never for blocks on Sable sub-levels)").define("dropBlocks", false);
        LASER_CANNON_DAMAGE_OWN_SUB_LEVEL = b.comment("Whether the beam damages blocks of the sub-level the cannon is mounted on").define("damageOwnSubLevel", false);
        LASER_CANNON_IMPACT_FORCE = b.comment("Force on a Sable sub-level hit by the beam (mass x blocks/s^2)").defineInRange("impactForce", 300.0, 0.0, 1.0e9);
        LASER_CANNON_RECOIL = b.comment("Push the cannon's own sub-level back with the same force while firing").define("recoil", true);
        LASER_CANNON_ENERGY_PER_TICK = b.comment("Forge Energy used per tick of firing (0 = no power needed)").defineInRange("energyPerTick", 400, 0, Integer.MAX_VALUE);
        LASER_CANNON_ENERGY_CAPACITY = b.comment("Forge Energy an emitter stores").defineInRange("energyCapacity", 200000, 1, Integer.MAX_VALUE);
        LASER_CANNON_MAX_RECEIVE = b.comment("Forge Energy an emitter accepts per tick").defineInRange("maxReceive", 10000, 1, Integer.MAX_VALUE);
        LASER_CANNON_MAX_FIRE_SECONDS = b.comment("Seconds of continuous fire until it overheats (0 = never)").defineInRange("maxFireSeconds", 10.0, 0.0, 3600.0);
        LASER_CANNON_COOLDOWN_SECONDS = b.comment("Seconds to cool down from fully hot to cold").defineInRange("cooldownSeconds", 5.0, 0.05, 3600.0);
        b.pop();
        SPEC = b.build();
    }

    private AshCCConfig() {
    }
}
