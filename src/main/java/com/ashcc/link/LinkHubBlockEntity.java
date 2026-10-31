package com.ashcc.link;

import com.ashcc.AshCC;
import com.ashcc.AshCCConfig;
import com.ashcc.compat.SableCompat;
import com.ashcc.tracker.HeadTrackerPeripheral;
import com.ashcc.tracker.TrackedPlayer;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Keeps the list of linked peripheral positions and, every few ticks, (re)attaches the ones whose chunks are loaded.
 */
public class LinkHubBlockEntity extends BlockEntity {
    private static final int REFRESH_TICKS = 10;
    private static final String HEAD_TRACKER = "head_tracker";

    /** Mutable so rename keeps the order. Either a block ({@code target}) or a head tracker ({@code player}). */
    private static final class Link {
        String name;
        final @Nullable LinkTarget target;
        final @Nullable TrackedPlayer player;

        Link(String name, LinkTarget target) {
            this.name = name;
            this.target = target;
            this.player = null;
        }

        Link(String name, TrackedPlayer player) {
            this.name = name;
            this.target = null;
            this.player = player;
        }
    }

    private final List<Link> links = new ArrayList<>();
    private @Nullable LinkHubPeripheral peripheral;
    private boolean dirty = true;

    public LinkHubBlockEntity(BlockPos pos, BlockState state) {
        super(AshCC.LINK_HUB_BE.get(), pos, state);
    }

    public LinkHubPeripheral peripheral() {
        if (peripheral == null) peripheral = new LinkHubPeripheral(this);
        return peripheral;
    }

    // ---- Editing ----

    public record Result(int changed, int skipped) {
    }

    public Result addTargets(List<LinkTarget> targets) {
        int added = 0;
        int skipped = 0;
        for (LinkTarget t : targets) {
            boolean self = t.pos().dimension().equals(level.dimension()) && t.pos().pos().equals(worldPosition);
            if (self || find(t) != null || links.size() >= AshCCConfig.LINK_MAX.get()) {
                skipped++;
                continue;
            }
            links.add(new Link(uniqueName(t.type()), t));
            added++;
        }
        changed();
        return new Result(added, skipped);
    }

    public Result removeTargets(List<LinkTarget> targets) {
        int removed = 0;
        for (LinkTarget t : targets) {
            Link l = find(t);
            if (l != null) {
                links.remove(l);
                peripheral().detachRemote(l.name);
                removed++;
            }
        }
        changed();
        return new Result(removed, targets.size() - removed);
    }

    /** Registers a head tracker. False if that player is already linked or the hub is full. */
    public boolean addPlayer(TrackedPlayer player) {
        if (findPlayer(player) != null || links.size() >= AshCCConfig.LINK_MAX.get()) return false;
        links.add(new Link(uniqueName(HEAD_TRACKER), player));
        changed();
        return true;
    }

    public boolean removePlayer(TrackedPlayer player) {
        Link l = findPlayer(player);
        if (l == null) return false;
        links.remove(l);
        peripheral().detachRemote(l.name);
        changed();
        return true;
    }

    boolean removeLink(String name) {
        Link l = byName(name);
        if (l == null) return false;
        links.remove(l);
        peripheral().detachRemote(name);
        changed();
        return true;
    }

    boolean renameLink(String name, String newName) {
        Link l = byName(name);
        if (l == null || byName(newName) != null) return false;
        peripheral().detachRemote(name);
        l.name = newName;
        changed();
        return true;
    }

    public int linkCount() {
        return links.size();
    }

    private void changed() {
        dirty = true;
        setChanged();
    }

    private @Nullable Link find(LinkTarget t) {
        for (Link l : links) if (l.target != null && l.target.sameBlock(t)) return l;
        return null;
    }

    private @Nullable Link findPlayer(TrackedPlayer p) {
        for (Link l : links) if (l.player != null && l.player.id().equals(p.id())) return l;
        return null;
    }

    private @Nullable Link byName(String name) {
        for (Link l : links) if (l.name.equals(name)) return l;
        return null;
    }

    /** "radar_0", "radar_1", ... like CC's wired modems. */
    private String uniqueName(String type) {
        for (int i = 0; ; i++) {
            String name = type + "_" + i;
            if (byName(name) == null) return name;
        }
    }

    // ---- Resolving ----

    public static void serverTick(Level level, BlockPos pos, BlockState state, LinkHubBlockEntity be) {
        if (be.dirty || level.getGameTime() % REFRESH_TICKS == 0) be.refresh((ServerLevel) level);
    }

    private void refresh(ServerLevel level) {
        dirty = false;
        LinkHubPeripheral p = peripheral();
        for (Link l : links) {
            IPeripheral target = l.target != null ? resolve(level, l.target) : resolve(level, Objects.requireNonNull(l.player));
            if (target != null) p.attachRemote(l.name, target);
            else p.detachRemote(l.name);
        }
    }

    private @Nullable IPeripheral resolve(ServerLevel here, LinkTarget t) {
        ServerLevel there = here.getServer().getLevel(t.pos().dimension());
        if (there == null) return null;
        BlockPos pos = t.pos().pos();
        if (!there.isLoaded(pos)) return null;
        if (!reachable(here, there, worldCenter(there, pos))) return null;
        IPeripheral p = there.getCapability(PeripheralCapability.get(), pos, t.side());
        // Never expose another hub (or this one) through a hub
        return p instanceof LinkHubPeripheral ? null : p;
    }

    /** Head trackers are connected while their player is online and within the link range. */
    private @Nullable IPeripheral resolve(ServerLevel here, TrackedPlayer t) {
        ServerPlayer player = here.getServer().getPlayerList().getPlayer(t.id());
        if (player == null || !reachable(here, player.serverLevel(), player.getEyePosition())) return null;
        return new HeadTrackerPeripheral(this, t);
    }

    private boolean reachable(ServerLevel here, ServerLevel there, Vec3 worldPos) {
        if (there != here) return AshCCConfig.LINK_INTERDIMENSIONAL.get();
        double range = AshCCConfig.LINK_RANGE.get();
        return range <= 0 || worldCenter(here, worldPosition).distanceTo(worldPos) <= range;
    }

    private static Vec3 worldCenter(Level level, BlockPos pos) {
        return AshCC.sableLoaded ? SableCompat.worldCenter(level, pos) : Vec3.atCenterOf(pos);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (peripheral != null) for (Link l : links) peripheral.detachRemote(l.name);
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        dirty = true;
    }

    // ---- Info / save ----

    List<Map<String, Object>> linksInfo() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Link l : links) {
            Map<String, Object> t = new HashMap<>();
            t.put("name", l.name);
            if (l.target != null) {
                t.put("type", l.target.type());
                t.put("dimension", l.target.pos().dimension().location().toString());
                t.put("x", l.target.pos().pos().getX());
                t.put("y", l.target.pos().pos().getY());
                t.put("z", l.target.pos().pos().getZ());
                t.put("side", l.target.side().getSerializedName());
            } else if (l.player != null) {
                t.put("type", HEAD_TRACKER);
                t.put("player", l.player.name());
                t.put("uuid", l.player.id().toString());
            }
            t.put("connected", peripheral().isAvailable(l.name));
            out.add(t);
        }
        return out;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag list = new ListTag();
        for (Link l : links) {
            CompoundTag c = new CompoundTag();
            c.putString("Name", l.name);
            if (l.target != null) LinkTarget.CODEC.encodeStart(NbtOps.INSTANCE, l.target).result().ifPresent(t -> c.put("Target", t));
            if (l.player != null) TrackedPlayer.CODEC.encodeStart(NbtOps.INSTANCE, l.player).result().ifPresent(t -> c.put("Player", t));
            list.add(c);
        }
        tag.put("Links", list);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        links.clear();
        ListTag list = tag.getList("Links", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            if (c.contains("Player")) {
                TrackedPlayer.CODEC.parse(NbtOps.INSTANCE, c.get("Player")).result()
                    .ifPresent(p -> links.add(new Link(c.getString("Name"), p)));
            } else {
                LinkTarget.CODEC.parse(NbtOps.INSTANCE, c.get("Target")).result()
                    .ifPresent(t -> links.add(new Link(c.getString("Name"), t)));
            }
        }
        dirty = true;
    }
}
