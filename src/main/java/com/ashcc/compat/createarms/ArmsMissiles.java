package com.ashcc.compat.createarms;

import com.ashcc.AshCC;
import com.defpwoq.createarms.block.AbstractShipMissileBlockEntity;
import com.defpwoq.createarms.block.Agm114FourRackBlockEntity;
import com.defpwoq.createarms.block.DualRackBlockEntity;
import com.defpwoq.createarms.block.Gbu39RackBlockEntity;
import com.defpwoq.createarms.block.MunitionRackBlock;
import com.defpwoq.createarms.block.MunitionRackBlockEntity;
import com.defpwoq.createarms.block.SidewinderRackBlockEntity;
import com.defpwoq.createarms.block.SidewinderRightRackBlockEntity;
import com.defpwoq.createarms.block.VlsHatchBlock;
import com.defpwoq.createarms.block.VlsHatchBlockEntity;
import com.defpwoq.createarms.entity.GuidedMissileEntity;
import com.defpwoq.createarms.entity.TorpedoEntity;
import com.defpwoq.createarms.missile.MissileProperties;
import com.defpwoq.createarms.network.ModPackets;
import com.defpwoq.createarms.register.ModBlocks;
import com.defpwoq.createarms.register.ModEntities;
import com.defpwoq.createarms.register.ModSounds;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Launching createarms munitions without a player, and finding / steering them in flight.
 * Ship missiles are one-block Sable sub-levels whose block entity (at the plot centre) does the guidance; torpedoes are
 * entities. Launches made through AshCC tag the munition with the launcher ({@link #OWNER}) so only that launcher's
 * computer can steer it.
 */
final class ArmsMissiles {
    static final String OWNER = "ashcc:launcher";

    /** Rack item -> missile block, as createarms' own rack launch maps them. Items not listed cannot be fired by CC. */
    private static final Map<String, Supplier<Block>> RACK_MISSILES = new HashMap<>();

    static {
        RACK_MISSILES.put("gbu_38_jdam", ModBlocks.JDAM_BLOCK::get);
        RACK_MISSILES.put("jdam_er", ModBlocks.JDAM_ER_BLOCK::get);
        RACK_MISSILES.put("gbu_39", ModBlocks.GBU39_BLOCK::get);
        RACK_MISSILES.put("dumb_bomb", ModBlocks.DUMB_BOMB_BLOCK::get);
        RACK_MISSILES.put("agm_65e_maverick", ModBlocks.MAVERICK_BLOCK::get);
        RACK_MISSILES.put("aim_120c_amraam", ModBlocks.AMRAAM_BLOCK::get);
        RACK_MISSILES.put("r77m", ModBlocks.R77M_BLOCK::get);
        RACK_MISSILES.put("ship_missile", ModBlocks.SHIP_MISSILE::get);
        RACK_MISSILES.put("lrasm", ModBlocks.LRASM_BLOCK::get);
        RACK_MISSILES.put("aim_9_sidewinder", ModBlocks.AIM9_BLOCK::get);
        RACK_MISSILES.put("r73", ModBlocks.R73_BLOCK::get);
        RACK_MISSILES.put("agm114k", ModBlocks.AGM114K_BLOCK::get);
        RACK_MISSILES.put("kinzhal", ModBlocks.KINZHAL_BLOCK::get);
        RACK_MISSILES.put("kinzhal_block", ModBlocks.KINZHAL_BLOCK::get);
        RACK_MISSILES.put("agm114l", ModBlocks.AGM114L_BLOCK::get);
        RACK_MISSILES.put("stinger", ModBlocks.STINGER::get);
        RACK_MISSILES.put("mk46", ModBlocks.MK46_BLOCK::get);
    }

    /** Missiles that leave the rack with 30% of the carrier's velocity instead of 5% (createarms' "direct fire" set). */
    private static boolean isDirectFire(Block block) {
        return block == ModBlocks.AIM9_BLOCK.get() || block == ModBlocks.AMRAAM_BLOCK.get() || block == ModBlocks.R73_BLOCK.get()
            || block == ModBlocks.R77M_BLOCK.get() || block == ModBlocks.MAVERICK_BLOCK.get();
    }

    private ArmsMissiles() {
    }

    static String itemName(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    /** Key of a launcher block: dimension and position (plot position while on a ship). */
    static String ownerKey(BlockEntity launcher) {
        Level level = launcher.getLevel();
        return (level == null ? "?" : level.dimension().location().toString()) + "@" + launcher.getBlockPos().asLong();
    }

    /** How many munitions a rack holds, as createarms' rack docking counts them. */
    static int rackCapacity(MunitionRackBlockEntity rack) {
        if (rack instanceof DualRackBlockEntity || rack instanceof SidewinderRackBlockEntity || rack instanceof SidewinderRightRackBlockEntity) return 2;
        if (rack instanceof Agm114FourRackBlockEntity || rack instanceof Gbu39RackBlockEntity) return 4;
        return 1;
    }

    static boolean canFireFromRack(ItemStack stack) {
        return !stack.isEmpty() && (RACK_MISSILES.containsKey(itemName(stack)) || itemName(stack).equals("torpedo"));
    }

    // ---- launching ----

    /** Result of a launch: the munition's id, or why nothing left. */
    record Launch(@Nullable UUID id, @Nullable String error) {
        static Launch fail(String error) {
            return new Launch(null, error);
        }
    }

    /**
     * Fires one munition from a rack: places the missile block under the rack, assembles it into its own sub-level and
     * activates it, like createarms' MFD launch but without the player. With no target it flies 500 blocks ahead.
     */
    static Launch fireRack(MunitionRackBlockEntity rack, ArmsTarget target, String owner) {
        if (!(rack.getLevel() instanceof ServerLevel level)) return Launch.fail("not loaded");
        ItemStack stored = rack.getStoredItem();
        if (stored.isEmpty()) return Launch.fail("empty");
        String name = itemName(stored);
        BlockPos rackPos = rack.getBlockPos();
        Direction facing = rack.getBlockState().hasProperty(MunitionRackBlock.FACING) ? rack.getBlockState().getValue(MunitionRackBlock.FACING) : Direction.NORTH;
        Vec3 forward = Sable.HELPER.getContaining(level, rackPos) instanceof SubLevel sub
            ? sub.logicalPose().transformNormal(Vec3.atLowerCornerOf(facing.getNormal())) : Vec3.atLowerCornerOf(facing.getNormal());
        Vec3 spawn = Sable.HELPER.projectOutOfSubLevel(level, Vec3.atCenterOf(rackPos));
        Vec3 carrier = carrierVelocity(level, Vec3.atCenterOf(rackPos));
        int iff = VlsHatchBlock.findNearbyRadarIff(level, rackPos);
        UUID id;

        if (name.equals("torpedo")) {
            TorpedoEntity torpedo = new TorpedoEntity(ModEntities.TORPEDO.get(), level);
            torpedo.setPos(spawn.x, spawn.y - 1.0, spawn.z);
            torpedo.setIffCode(iff);
            torpedo.setTargetPos(target.pos() != null ? target.pos() : spawn.add(forward.scale(500.0)));
            torpedo.setDeltaMovement(carrier.add(forward.scale(1.5)));
            torpedo.setLaunchDir(forward);
            torpedo.getPersistentData().putString(OWNER, owner);
            level.addFreshEntity(torpedo);
            id = torpedo.getUUID();
        } else {
            Supplier<Block> block = RACK_MISSILES.get(name);
            if (block == null) return Launch.fail("unsupported");
            BlockPos missilePos = rackPos.below();
            if (!level.getBlockState(missilePos).canBeReplaced()) return Launch.fail("blocked");
            BlockState state = block.get().defaultBlockState();
            if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
            level.setBlock(missilePos, state, 3);
            ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(level, missilePos, List.of(missilePos), new BoundingBox3i(missilePos, missilePos));
            if (sub == null) {
                level.removeBlock(missilePos, false);
                return Launch.fail("assembly failed");
            }
            Vec3 velocity = carrier.scale(isDirectFire(block.get()) ? 0.3 : 0.05).add(forward);
            RigidBodyHandle handle = RigidBodyHandle.of(sub);
            if (handle != null && handle.isValid()) handle.addLinearAndAngularVelocity(new Vector3d(velocity.x, velocity.y, velocity.z), new Vector3d());
            if (!(missileAt(sub) instanceof AbstractShipMissileBlockEntity missile)) return Launch.fail("assembly failed");
            missile.setLaunchVelocityBoost(velocity);
            missile.setIffCode(iff);
            UUID source = com.ashcc.compat.SableCompat.subLevelAt(level, rackPos);
            if (source != null) missile.getPersistentData().putUUID("SourceShip", source);
            missile.getPersistentData().putString(OWNER, owner);
            ArmsTarget aim = target.isNone() ? new ArmsTarget(spawn.add(forward.scale(500.0)), null, null) : target;
            if (aim.subLevel() != null) missile.getPersistentData().putUUID("TargetSubLevel", aim.subLevel());
            missile.activate(aim.pos(), aim.entity(), facing);
            id = sub.getUniqueId();
        }

        ItemStack left = stored.copy();
        left.shrink(1);
        rack.setStoredItem(left.isEmpty() ? ItemStack.EMPTY : left);
        PacketDistributor.sendToAllPlayers(new ModPackets.SyncRackItemPayload(rackPos, left.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(left.getItem()).toString(), left.getCount()));
        level.playSound(null, spawn.x, spawn.y, spawn.z, ModSounds.getLaunchSoundForItem(stored), SoundSource.BLOCKS, 0.4f, 0.8f);
        return new Launch(id, null);
    }

    /** Launches the VLS cell's missile with createarms' own VLS launch (opens the hatch; it closes again by itself). */
    static Launch launchVls(VlsHatchBlockEntity vls, ArmsTarget target, String owner) {
        if (!(vls.getLevel() instanceof ServerLevel level)) return Launch.fail("not loaded");
        if (vls.getStoredItem().isEmpty()) return Launch.fail("empty");
        if (!vls.isValidVlsStructure()) return Launch.fail("no cell");
        if (!level.getBlockState(vls.getBlockPos().above()).canBeReplaced()) return Launch.fail("blocked");
        // createarms needs a point even for a tracked target; with none, go straight up 500 blocks
        Vec3 aim = target.pos() != null ? target.pos() : Sable.HELPER.projectOutOfSubLevel(level, Vec3.atCenterOf(vls.getBlockPos())).add(0, 500, 0);
        vls.triggerHatchOpen();
        UUID id = vls.launchAt(BlockPos.containing(aim), target.followed(), VlsHatchBlock.findNearbyRadarIff(level, vls.getBlockPos()), null);
        if (id == null) return Launch.fail("unsupported");
        if (find(level, id) instanceof Tracked.Ship ship) {
            ship.missile().getPersistentData().putString(OWNER, owner);
            // The VLS launch keeps only the ship id in TargetSubLevel; the missile follows a ship only with its entity id set too
            if (target.subLevel() != null) retarget(ship.missile(), target);
        }
        return new Launch(id, null);
    }

    private static Vec3 carrierVelocity(ServerLevel level, Vec3 blockCenter) {
        try {
            Vec3 v = SableCompanion.INSTANCE.getVelocity(level, blockCenter);
            return v == null ? Vec3.ZERO : v;
        } catch (RuntimeException e) {
            return Vec3.ZERO;
        }
    }

    private static @Nullable BlockEntity missileAt(ServerSubLevel sub) {
        return sub.getLevel().getBlockEntity(sub.getPlot().getCenterBlock());
    }

    // ---- in flight ----

    /** A munition in flight. */
    sealed interface Tracked {
        UUID id();

        CompoundTag data();

        record Ship(ServerSubLevel sub, AbstractShipMissileBlockEntity missile) implements Tracked {
            public UUID id() {
                return sub.getUniqueId();
            }

            public CompoundTag data() {
                return missile.getPersistentData();
            }
        }

        record Projectile(Entity entity) implements Tracked {
            public UUID id() {
                return entity.getUUID();
            }

            public CompoundTag data() {
                return entity.getPersistentData();
            }
        }
    }

    static @Nullable Tracked find(ServerLevel level, UUID id) {
        if (SubLevelContainer.getContainer(level).getSubLevel(id) instanceof ServerSubLevel sub && !sub.isRemoved()
            && missileAt(sub) instanceof AbstractShipMissileBlockEntity missile) {
            return new Tracked.Ship(sub, missile);
        }
        Entity entity = level.getEntity(id);
        if ((entity instanceof TorpedoEntity || entity instanceof GuidedMissileEntity) && entity.isAlive()) return new Tracked.Projectile(entity);
        return null;
    }

    /** Every active munition in the level (ship missiles and torpedo / missile entities). */
    static List<Tracked> all(ServerLevel level) {
        List<Tracked> out = new ArrayList<>();
        for (SubLevel s : SubLevelContainer.getContainer(level).getAllSubLevels()) {
            if (s instanceof ServerSubLevel sub && !sub.isRemoved() && missileAt(sub) instanceof AbstractShipMissileBlockEntity missile && missile.isActive()) {
                out.add(new Tracked.Ship(sub, missile));
            }
        }
        for (Entity entity : level.getAllEntities()) {
            if ((entity instanceof TorpedoEntity || entity instanceof GuidedMissileEntity) && entity.isAlive()) out.add(new Tracked.Projectile(entity));
        }
        return out;
    }

    static String owner(Tracked t) {
        return t.data().getString(OWNER);
    }

    static Map<String, Object> info(Tracked t) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", t.id().toString());
        Vec3 pos;
        Vec3 vel;
        if (t instanceof Tracked.Ship ship) {
            AbstractShipMissileBlockEntity missile = ship.missile();
            Vector3dc p = ship.sub().logicalPose().position();
            pos = new Vec3(p.x(), p.y(), p.z());
            RigidBodyHandle handle = RigidBodyHandle.of(ship.sub());
            Vector3dc v = handle != null && handle.isValid() ? handle.getLinearVelocity() : null;
            vel = v == null ? Vec3.ZERO : new Vec3(v.x(), v.y(), v.z());
            m.put("kind", "missile");
            m.put("name", BuiltInRegistries.BLOCK.getKey(missile.getBlockState().getBlock()).getPath());
            m.put("active", missile.isActive());
            m.put("age", missile.getTicksAlive() / 20.0);
            m.put("iff", missile.getIffCode());
            MissileProperties props = missile.getMissileProperties();
            if (props != null) {
                m.put("maxAge", props.maxTicksAlive / 20.0);
                m.put("guidance", props.guidance.name().toLowerCase());
                m.put("launchType", props.launchType.name().toLowerCase());
                m.put("warhead", props.warhead.name().toLowerCase());
            }
            Vec3 target = get(missile, "targetPos") instanceof Vec3 tp ? tp : null;
            if (target != null) m.put("target", com.ashcc.radar.LuaUtil.vec(target));
            UUID followed = missile.getTargetUUID();
            if (followed != null) m.put("targetId", followed.toString());
        } else {
            Entity e = ((Tracked.Projectile) t).entity();
            pos = e.position();
            vel = e.getDeltaMovement().scale(20.0);
            m.put("kind", e instanceof TorpedoEntity ? "torpedo" : "missile");
            m.put("name", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath());
            m.put("active", true);
            m.put("age", e.tickCount / 20.0);
            if (e instanceof TorpedoEntity torpedo) {
                m.put("iff", torpedo.getIffCode());
                m.put("target", com.ashcc.radar.LuaUtil.vec(torpedo.targetPos));
            } else if (e instanceof GuidedMissileEntity missile) {
                m.put("iff", missile.getIffCode());
                m.put("guidance", missile.getGuidanceType().name().toLowerCase());
                m.put("target", com.ashcc.radar.LuaUtil.vec(missile.getTargetPos()));
            }
        }
        m.put("x", pos.x);
        m.put("y", pos.y);
        m.put("z", pos.z);
        m.put("vx", vel.x);
        m.put("vy", vel.y);
        m.put("vz", vel.z);
        m.put("speed", vel.length());
        return m;
    }

    /** Points a munition at a new target without restarting its flight (age, boost and fuze timers keep running). */
    static boolean retarget(Tracked t, ArmsTarget target) {
        if (t instanceof Tracked.Ship ship) {
            retarget(ship.missile(), target);
            return true;
        }
        Entity e = ((Tracked.Projectile) t).entity();
        Entity followed = target.entity() != null && target.subLevel() == null && e.level() instanceof ServerLevel sl ? sl.getEntity(target.entity()) : null;
        if (e instanceof TorpedoEntity torpedo) {
            if (followed != null) torpedo.setTargetEntity(followed);
            else if (target.pos() != null) torpedo.setTargetPos(target.pos());
            else return false;
            return true;
        }
        if (e instanceof GuidedMissileEntity missile) {
            if (followed != null) missile.setTargetEntity(followed);
            else if (target.pos() != null) missile.setTargetPos(target.pos());
            else return false;
            return true;
        }
        return false;
    }

    static void retarget(AbstractShipMissileBlockEntity missile, ArmsTarget target) {
        if (!missile.isActive()) {
            missile.activate(target.pos(), target.entity(), missile.getFacing());
        }
        CompoundTag data = missile.getPersistentData();
        data.remove("LaserDesignator");
        if (target.subLevel() != null) data.putUUID("TargetSubLevel", target.subLevel());
        else data.remove("TargetSubLevel");
        boolean ok = set(missile, "targetPos", target.pos()) && set(missile, "targetEntityUUID", target.entity());
        set(missile, "cachedTargetSubLevel", null);
        set(missile, "terminalAcquired", false);
        set(missile, "blindSinceTick", -1);
        // activate() restarts the flight clock, so it is only the fallback if createarms' fields have changed
        if (!ok) missile.activate(target.pos(), target.entity(), missile.getFacing());
        missile.setChanged();
    }

    static boolean detonate(Tracked t) {
        if (t instanceof Tracked.Ship ship) {
            ship.missile().explode();
            return true;
        }
        return false;
    }

    // ---- reflection on AbstractShipMissileBlockEntity's private guidance state ----

    private static final Map<String, Field> FIELDS = new HashMap<>();

    private static @Nullable Field field(String name) {
        return FIELDS.computeIfAbsent(name, n -> {
            try {
                Field f = AbstractShipMissileBlockEntity.class.getDeclaredField(n);
                f.setAccessible(true);
                return f;
            } catch (ReflectiveOperationException | RuntimeException e) {
                AshCC.LOGGER.warn("AshCC: createarms missile field {} not found ({})", n, e.toString());
                return null;
            }
        });
    }

    private static @Nullable Object get(AbstractShipMissileBlockEntity missile, String name) {
        Field f = field(name);
        try {
            return f == null ? null : f.get(missile);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static boolean set(AbstractShipMissileBlockEntity missile, String name, @Nullable Object value) {
        Field f = field(name);
        if (f == null) return false;
        try {
            f.set(missile, value);
            return true;
        } catch (IllegalAccessException | IllegalArgumentException e) {
            return false;
        }
    }
}
