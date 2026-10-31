package com.ashcc.connector;

import com.ashcc.AshCC;
import com.ashcc.compat.SableConnector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLEnvironment;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * Offset connector: a thin plate stuck on a block face, shifted by -1/2, 0 or +1/2 of a block along the face (the ninth
 * that was clicked, so the outer ninths put the block's centre on the face's edge). A face can carry a plate in each ninth. The first block placed on it goes onto a sub-level of its own at that offset, welded to the block's
 * ship (or the world) by a fixed joint; further blocks are placed on that one as usual. A wrench sets the tilt.
 * The anchor is kept in the parent block's own coordinates (a ship's plot); the entity itself follows it in world space.
 */
public class ConnectorEntity extends Entity {
    private static final EntityDataAccessor<Direction> FACE = SynchedEntityData.defineId(ConnectorEntity.class, EntityDataSerializers.DIRECTION);
    private static final EntityDataAccessor<Float> YAW = SynchedEntityData.defineId(ConnectorEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> PITCH = SynchedEntityData.defineId(ConnectorEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> ROLL = SynchedEntityData.defineId(ConnectorEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<BlockPos> PARENT = SynchedEntityData.defineId(ConnectorEntity.class, EntityDataSerializers.BLOCK_POS);
    /** Anchor relative to the parent block's centre (small, so floats are fine even in a far-away plot). */
    private static final EntityDataAccessor<org.joml.Vector3f> OFFSET = SynchedEntityData.defineId(ConnectorEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Optional<UUID>> CHILD = SynchedEntityData.defineId(ConnectorEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final TagKey<Item> WRENCHES = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "tools/wrench"));
    private static final ResourceLocation CREATE_WRENCH = ResourceLocation.fromNamespaceAndPath("create", "wrench");

    /** How far the outer ninths shift the plate along the face (half a block: the block's centre on the face's edge). */
    public static final double STEP = 0.5;

    /** Plates by the child sub-level they carry, per side, so a wrench on the welded blocks finds its plate. */
    private static final java.util.Map<UUID, ConnectorEntity> CLIENT_BY_CHILD = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<UUID, ConnectorEntity> SERVER_BY_CHILD = new java.util.concurrent.ConcurrentHashMap<>();

    /** The child's touching-face middle, in its plot. */
    private Vec3 childAttach = Vec3.ZERO;
    /** Joint state kept by {@link SableConnector} (Sable types stay out of this class). */
    public @Nullable Object joint;

    public ConnectorEntity(EntityType<? extends ConnectorEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    public static ConnectorEntity create(Level level, BlockPos parentPos, Direction face, Vec3 anchor) {
        ConnectorEntity e = new ConnectorEntity(AshCC.CONNECTOR.get(), level);
        e.entityData.set(PARENT, parentPos);
        e.entityData.set(OFFSET, anchor.subtract(Vec3.atCenterOf(parentPos)).toVector3f());
        e.entityData.set(FACE, face);
        e.follow();
        return e;
    }

    /** The same spot on the same face: two plates cannot share it. */
    public boolean samePlace(BlockPos pos, Direction face, Vec3 anchor) {
        return parentPos().equals(pos) && face() == face && anchor().distanceToSqr(anchor) < 1e-4;
    }

    /** Anchor point for a click on {@code face} of {@code pos} at {@code hit} (both in the block's own coordinates). */
    public static Vec3 anchorFor(BlockPos pos, Direction face, Vec3 hit) {
        Vec3 local = hit.subtract(Vec3.atLowerCornerOf(pos));
        Vec3 anchor = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
        for (Direction.Axis axis : Direction.Axis.values()) {
            if (axis == face.getAxis()) continue;
            int cell = Math.max(0, Math.min(2, (int) Math.floor(local.get(axis) * 3))) - 1;
            anchor = anchor.add(Vec3.atLowerCornerOf(Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE).getNormal()).scale(cell * STEP));
        }
        return anchor;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(FACE, Direction.UP).define(YAW, 0f).define(PITCH, 0f).define(ROLL, 0f).define(CHILD, Optional.empty())
            .define(PARENT, BlockPos.ZERO).define(OFFSET, new org.joml.Vector3f());
    }

    public Direction face() {
        return entityData.get(FACE);
    }

    public float yawDeg() {
        return entityData.get(YAW);
    }

    public float pitchDeg() {
        return entityData.get(PITCH);
    }

    public float rollDeg() {
        return entityData.get(ROLL);
    }

    public @Nullable UUID child() {
        return entityData.get(CHILD).orElse(null);
    }

    public void setChild(@Nullable UUID id, Vec3 attach) {
        entityData.set(CHILD, Optional.ofNullable(id));
        childAttach = attach;
        index();
    }

    private java.util.Map<UUID, ConnectorEntity> byChild() {
        return level().isClientSide ? CLIENT_BY_CHILD : SERVER_BY_CHILD;
    }

    private int missingTicks;

    /** The child this plate is listed under, so it can be unlisted when the child changes. */
    private @Nullable UUID indexed;

    private void index() {
        UUID child = isRemoved() ? null : child();
        if (java.util.Objects.equals(child, indexed)) return;
        if (indexed != null) byChild().remove(indexed, this);
        indexed = child;
        if (child != null) byChild().put(child, this);
    }

    /** Server plates that carry a child, so siblings on the same ship can be welded to each other. */
    public static java.util.Collection<ConnectorEntity> serverPlatesWithChild() {
        return SERVER_BY_CHILD.values();
    }

    public static @Nullable ConnectorEntity byChild(Level level, UUID child) {
        ConnectorEntity e = (level.isClientSide ? CLIENT_BY_CHILD : SERVER_BY_CHILD).get(child);
        return e != null && !e.isRemoved() && e.level() == level ? e : null;
    }

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        index();
    }

    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        if (indexed != null) byChild().remove(indexed, this);
        indexed = null;
    }

    /** The first block placed on the plate, in the child's plot. */
    public BlockPos firstBlock() {
        return BlockPos.containing(childAttach.add(Vec3.atLowerCornerOf(face().getNormal()).scale(0.5)));
    }

    /** Air in a loaded chunk (a ship's plot that is not loaded says nothing). */
    private boolean gone(BlockPos pos) {
        return level().isLoaded(pos) && level().getBlockState(pos).isAir();
    }

    public Vec3 childAttach() {
        return childAttach;
    }

    public BlockPos parentPos() {
        return entityData.get(PARENT);
    }

    /** The point on the parent's face the plate is centred on, in the parent's own block coordinates. */
    public Vec3 anchor() {
        return Vec3.atCenterOf(parentPos()).add(new Vec3(entityData.get(OFFSET)));
    }

    /** Puts the entity where the anchor shows up in the world (it moves with the parent's ship). */
    private void follow() {
        Vec3 w = AshCC.sableLoaded ? com.ashcc.compat.SableCompat.projectOut(level(), anchor()) : anchor();
        if (!w.equals(position())) setPos(w);
    }

    /** Degrees, wrapped to -180..180; moves the welded blocks on the server. */
    public void setAngles(float yaw, float pitch, float roll) {
        entityData.set(YAW, Mth.wrapDegrees(yaw));
        entityData.set(PITCH, Mth.wrapDegrees(pitch));
        entityData.set(ROLL, Mth.wrapDegrees(roll));
        if (!level().isClientSide && AshCC.sableLoaded && child() != null) SableConnector.align(this);
    }

    @Override
    protected AABB makeBoundingBox() {
        Direction face = face();
        Vec3 p = position();
        double t = 1 / 16.0;
        Vec3 n = Vec3.atLowerCornerOf(face.getNormal());
        Vec3 half = new Vec3(face.getAxis() == Direction.Axis.X ? 0 : 0.5, face.getAxis() == Direction.Axis.Y ? 0 : 0.5, face.getAxis() == Direction.Axis.Z ? 0 : 0.5);
        return new AABB(p.subtract(half), p.add(half).add(n.scale(t)));
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (FACE.equals(key)) setBoundingBox(makeBoundingBox());
        if (PARENT.equals(key) || OFFSET.equals(key)) follow();
        if (CHILD.equals(key)) index();
    }

    @Override
    public void tick() {
        super.tick();
        follow();
        if (level().isClientSide) return;
        // Like a painting: falls off when its block goes, and lets go of what it carries
        if (gone(parentPos())) {
            dropAndRemove(null);
            return;
        }
        // A child whose only block went is removed by Sable altogether; give a loading world a moment before believing it
        missingTicks = AshCC.sableLoaded && child() != null && SableConnector.childMissing(this) ? missingTicks + 1 : 0;
        if (child() != null && (gone(firstBlock()) || missingTicks > 40)) {
            // The block sitting on the plate was broken: the rest comes loose and the plate is bare again
            if (AshCC.sableLoaded) SableConnector.release(this);
            setChild(null, Vec3.ZERO);
            return;
        }
        if (AshCC.sableLoaded && child() != null) SableConnector.tick(this);
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isNoGravity() {
        return true;
    }

    public static boolean isWrench(ItemStack stack) {
        return stack.is(WRENCHES) || CREATE_WRENCH.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /**
     * A wrench on a block welded by a plate opens the plate's screen (the plate itself is hidden under the first block).
     * Handled in the click event so the wrench does not also turn the block.
     */
    public static void onRightClickBlock(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (!AshCC.sableLoaded || !isWrench(event.getItemStack())) return;
        Level level = event.getLevel();
        UUID sub = com.ashcc.compat.SableCompat.subLevelAt(level, event.getPos());
        ConnectorEntity plate = sub == null ? null : byChild(level, sub);
        if (plate == null) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        if (level.isClientSide && FMLEnvironment.dist.isClient()) com.ashcc.connector.client.ConnectorScreen.open(plate);
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (isWrench(stack)) {
            // Only resolved on the client, so the screen class never loads on a dedicated server
            if (level().isClientSide && FMLEnvironment.dist.isClient()) com.ashcc.connector.client.ConnectorScreen.open(this);
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        if (!(stack.getItem() instanceof BlockItem) || child() != null) return InteractionResult.PASS;
        if (level().isClientSide) return InteractionResult.SUCCESS;
        BlockHitResult hit = new BlockHitResult(anchor(), face(), parentPos(), false);
        if (AshCC.sableLoaded) {
            return SableConnector.placeFirst(this, player, hand, hit) ? InteractionResult.CONSUME : InteractionResult.FAIL;
        }
        // Without Sable there is nothing to offset: place on the grid and drop the plate
        InteractionResult r = stack.useOn(new UseOnContext(player, hand, new BlockHitResult(Vec3.atCenterOf(parentPos()).add(Vec3.atLowerCornerOf(face().getNormal()).scale(0.5)), face(), parentPos(), false)));
        if (r.consumesAction()) dropAndRemove(player);
        return r;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (isInvulnerableTo(source) || level().isClientSide || isRemoved()) return !level().isClientSide;
        dropAndRemove(source.getEntity() instanceof Player p ? p : null);
        return true;
    }

    private void dropAndRemove(@Nullable Player player) {
        if (player == null || !player.getAbilities().instabuild) spawnAtLocation(AshCC.CONNECTOR_ITEM.get());
        playSound(SoundEvents.ITEM_FRAME_REMOVE_ITEM, 1, 1);
        discard();
    }

    @Override
    public void remove(RemovalReason reason) {
        if (AshCC.sableLoaded && level() instanceof ServerLevel) SableConnector.release(this);
        super.remove(reason);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        entityData.set(FACE, Direction.from3DDataValue(tag.getByte("Face")));
        entityData.set(PARENT, BlockPos.of(tag.getLong("ParentPos")));
        entityData.set(OFFSET, new org.joml.Vector3f(tag.getFloat("OffsetX"), tag.getFloat("OffsetY"), tag.getFloat("OffsetZ")));
        entityData.set(YAW, tag.getFloat("Yaw"));
        entityData.set(PITCH, tag.getFloat("Pitch"));
        entityData.set(ROLL, tag.getFloat("Roll"));
        entityData.set(CHILD, tag.hasUUID("Child") ? Optional.of(tag.getUUID("Child")) : Optional.empty());
        childAttach = new Vec3(tag.getDouble("AttachX"), tag.getDouble("AttachY"), tag.getDouble("AttachZ"));
        setBoundingBox(makeBoundingBox());
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putByte("Face", (byte) face().get3DDataValue());
        tag.putLong("ParentPos", parentPos().asLong());
        org.joml.Vector3f o = entityData.get(OFFSET);
        tag.putFloat("OffsetX", o.x);
        tag.putFloat("OffsetY", o.y);
        tag.putFloat("OffsetZ", o.z);
        tag.putFloat("Yaw", yawDeg());
        tag.putFloat("Pitch", pitchDeg());
        tag.putFloat("Roll", rollDeg());
        UUID child = child();
        if (child != null) tag.putUUID("Child", child);
        tag.putDouble("AttachX", childAttach.x);
        tag.putDouble("AttachY", childAttach.y);
        tag.putDouble("AttachZ", childAttach.z);
    }
}
