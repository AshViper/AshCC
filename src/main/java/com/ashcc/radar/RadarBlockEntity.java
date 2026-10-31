package com.ashcc.radar;

import com.ashcc.AshCC;
import com.ashcc.AshCCConfig;
import dan200.computercraft.api.peripheral.AttachedComputerSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

public class RadarBlockEntity extends BlockEntity {
    final AttachedComputerSet computers = new AttachedComputerSet();
    private @Nullable RadarPeripheral peripheral;

    private double range = -1;

    // Watch mode: periodic scans that queue radar_contact / radar_lost events
    private boolean watching;
    private int watchInterval = 10;
    private boolean watchEntities = true;
    private boolean watchSubLevels = true;
    /** Normalized entity filter (type -> List of ids, player/living -> Boolean, name -> String). */
    private Map<String, Object> watchFilter = new HashMap<>();
    private Predicate<Entity> watchPredicate = e -> true;
    private final Map<UUID, String> tracked = new HashMap<>();

    public RadarBlockEntity(BlockPos pos, BlockState state) {
        super(AshCC.RADAR_BE.get(), pos, state);
    }

    public RadarPeripheral peripheral() {
        if (peripheral == null) peripheral = new RadarPeripheral(this);
        return peripheral;
    }

    public RadarFrame frame() {
        return RadarFrame.of(level, worldPosition, getBlockState().getValue(RadarBlock.FACING));
    }

    public double range() {
        return range < 0 ? AshCCConfig.RADAR_DEFAULT_RANGE.get() : range;
    }

    public double setRange(double r) {
        range = Math.max(1, r);
        setChanged();
        return range;
    }

    /** Range argument from Lua: defaults to the radar's own range. */
    public double effectiveRange(@Nullable Double r) {
        return r == null ? range() : Math.max(0, r);
    }

    public void setWatch(boolean enabled, int interval, boolean entities, boolean subLevels, Map<String, Object> filter) {
        watching = enabled;
        watchInterval = Math.max(interval, AshCCConfig.RADAR_MIN_WATCH_INTERVAL.get());
        watchEntities = entities;
        watchSubLevels = subLevels;
        watchFilter = filter;
        watchPredicate = RadarScanner.filter(filter);
        tracked.clear();
        setChanged();
    }

    public Map<String, Object> watchInfo() {
        Map<String, Object> t = new HashMap<>();
        t.put("enabled", watching);
        t.put("interval", watchInterval);
        t.put("entities", watchEntities);
        t.put("subLevels", watchSubLevels);
        t.put("filter", watchFilter);
        t.put("tracked", tracked.size());
        return t;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, RadarBlockEntity be) {
        if (!be.watching || !be.computers.hasComputers()) return;
        if (level.getGameTime() % be.watchInterval != 0) return;
        be.watchScan((ServerLevel) level);
    }

    private void watchScan(ServerLevel level) {
        RadarFrame frame = frame();
        double r = range();
        int limit = AshCCConfig.RADAR_MAX_RESULTS.get();
        List<Contact> found = new ArrayList<>();
        if (watchEntities) found.addAll(RadarScanner.scanEntities(level, frame, r, watchPredicate, limit));
        if (watchSubLevels) found.addAll(RadarScanner.scanSubLevels(level, frame, r, limit));

        Map<UUID, String> now = new HashMap<>();
        for (Contact c : found) {
            now.put(c.id(), c.kind());
            if (!tracked.containsKey(c.id())) {
                Map<String, Object> table = c.toLua(frame);
                computers.forEach(computer -> computer.queueEvent("radar_contact", computer.getAttachmentName(), table));
            }
        }
        tracked.forEach((id, kind) -> {
            if (!now.containsKey(id)) {
                String sid = id.toString();
                computers.forEach(computer -> computer.queueEvent("radar_lost", computer.getAttachmentName(), sid, kind));
            }
        });
        tracked.clear();
        tracked.putAll(now);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (range >= 0) tag.putDouble("Range", range);
        CompoundTag watch = new CompoundTag();
        watch.putBoolean("Enabled", watching);
        watch.putInt("Interval", watchInterval);
        watch.putBoolean("Entities", watchEntities);
        watch.putBoolean("SubLevels", watchSubLevels);
        watch.put("Filter", filterToTag(watchFilter));
        tag.put("Watch", watch);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        range = tag.contains("Range") ? tag.getDouble("Range") : -1;
        if (tag.contains("Watch")) {
            CompoundTag watch = tag.getCompound("Watch");
            watching = watch.getBoolean("Enabled");
            watchInterval = Math.max(1, watch.getInt("Interval"));
            watchEntities = watch.getBoolean("Entities");
            watchSubLevels = watch.getBoolean("SubLevels");
            watchFilter = filterFromTag(watch.getCompound("Filter"));
            watchPredicate = RadarScanner.filter(watchFilter);
        }
    }

    private static CompoundTag filterToTag(Map<String, Object> filter) {
        CompoundTag tag = new CompoundTag();
        filter.forEach((k, v) -> {
            if (v instanceof Boolean b) tag.putBoolean(k, b);
            else if (v instanceof String s) tag.putString(k, s);
            else if (v instanceof List<?> list) {
                ListTag l = new ListTag();
                for (Object o : list) l.add(StringTag.valueOf(o.toString()));
                tag.put(k, l);
            }
        });
        return tag;
    }

    private static Map<String, Object> filterFromTag(CompoundTag tag) {
        Map<String, Object> filter = new HashMap<>();
        for (String k : tag.getAllKeys()) {
            Tag v = tag.get(k);
            if (v instanceof ListTag list) {
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < list.size(); i++) ids.add(list.getString(i));
                filter.put(k, ids);
            } else if (k.equals("player") || k.equals("living")) {
                filter.put(k, tag.getBoolean(k));
            } else {
                filter.put(k, tag.getString(k));
            }
        }
        return filter;
    }
}
