package com.ashcc.compat.createarms;

import com.defpwoq.createarms.block.CicTerminalBlockEntity;
import com.defpwoq.createarms.block.RadarBlockEntity;
import com.defpwoq.createarms.block.VlsHatchBlockEntity;
import com.defpwoq.createarms.util.SableHelper;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lua API of the createarms CIC terminal (peripheral type "cic_terminal"): its linked VLS cells, the sequential launch
 * queue (hatch opens, a second later the missile leaves, cells fire 0.75 s apart), auto-engage and its radar.
 */
public class CicTerminalPeripheral extends ArmsLauncherPeripheral<CicTerminalBlockEntity> {
    CicTerminalPeripheral(CicTerminalBlockEntity cic) {
        super(cic);
    }

    @Override
    public String getType() {
        return "cic_terminal";
    }

    /** Missiles the terminal launched (its own list), from its linked cells through CC, or through this peripheral. */
    @Override
    protected boolean controls(ArmsMissiles.Tracked missile) {
        if (super.controls(missile) || block.getActiveMissiles().contains(missile.id())) return true;
        String owner = ArmsMissiles.owner(missile);
        if (owner.isEmpty()) return false;
        for (VlsHatchBlockEntity vls : cells()) {
            if (owner.equals(ArmsMissiles.ownerKey(vls))) return true;
        }
        return false;
    }

    private @Nullable VlsHatchBlockEntity cell(BlockPos pos) {
        return SableHelper.findBlockEntity(block.getLevel(), pos) instanceof VlsHatchBlockEntity vls ? vls : null;
    }

    private List<VlsHatchBlockEntity> cells() {
        List<VlsHatchBlockEntity> out = new ArrayList<>();
        for (BlockPos pos : block.getLinkedVls()) {
            VlsHatchBlockEntity vls = cell(pos);
            if (vls != null) out.add(vls);
        }
        return out;
    }

    /** List of { index, x, y, z, loaded, missile = { name, displayName, count } or nil, ready } in link order. */
    @LuaFunction(mainThread = true)
    public final List<Map<String, Object>> getLinkedVls() throws LuaException {
        level();
        List<Map<String, Object>> out = new ArrayList<>();
        List<BlockPos> linked = block.getLinkedVls();
        for (int i = 0; i < linked.size(); i++) {
            BlockPos pos = linked.get(i);
            VlsHatchBlockEntity vls = cell(pos);
            Map<String, Object> t = new HashMap<>();
            t.put("index", i + 1);
            t.put("x", pos.getX());
            t.put("y", pos.getY());
            t.put("z", pos.getZ());
            t.put("loaded", vls != null);
            if (vls != null) {
                Map<String, Object> missile = MissileRackPeripheral.stack(vls.getStoredItem());
                if (missile != null) t.put("missile", missile);
                t.put("ready", !vls.getStoredItem().isEmpty() && vls.isValidVlsStructure());
            } else {
                t.put("ready", false);
            }
            out.add(t);
        }
        return out;
    }

    /**
     * launch([target], [cells]) -> number of missiles queued.
     * target: nil (the terminal's stored target point), {x,y,z} or a ship / entity id;
     * cells: nil for every linked cell, an index, or a list of indices from getLinkedVls().
     * Anti-ship missiles (LRASM, Tomahawk) are skipped for an id target, as in the terminal's own screen.
     */
    @LuaFunction(mainThread = true)
    public final int launch(IArguments args) throws LuaException {
        var level = level();
        ArmsTarget target = args.get(0) instanceof Number ? null : ArmsTarget.read(level, args, 0);
        if (target == null) throw new LuaException("bad argument #1 (expected nil, {x,y,z} or id string)");
        Vec3 point = target.pos() != null ? target.pos() : (block.getTargetPos().equals(BlockPos.ZERO) ? null : Vec3.atCenterOf(block.getTargetPos()));
        if (point == null) throw new LuaException("no target: give one, or set the terminal's target point");

        List<BlockPos> linked = block.getLinkedVls();
        List<BlockPos> selected = new ArrayList<>();
        Object cellsArg = args.get(1);
        if (cellsArg == null) {
            selected.addAll(linked);
        } else if (cellsArg instanceof Number) {
            selected.add(linkedAt(linked, args.getInt(1)));
        } else if (cellsArg instanceof Map<?, ?> table) {
            for (Object v : table.values()) {
                if (!(v instanceof Number n)) throw new LuaException("cells must be a list of indices");
                selected.add(linkedAt(linked, n.intValue()));
            }
        } else {
            throw new LuaException("bad argument #2 (expected nil, index or list of indices)");
        }

        int before = queueSize();
        var known = new HashSet<>(block.getActiveMissiles());
        block.launchSelected(null, BlockPos.containing(point), target.followed(), selected);
        int queued = Math.max(0, queueSize() - before);
        if (queued > 0) CreateArmsCompat.watchCicLaunch(block, known, target, ownerKey(), queued);
        return queued;
    }

    private static BlockPos linkedAt(List<BlockPos> linked, int index) throws LuaException {
        if (index < 1 || index > linked.size()) throw new LuaException("no linked cell " + index + " (have " + linked.size() + ")");
        return linked.get(index - 1);
    }

    private static @Nullable Field queueField;

    /** Size of the terminal's private launch queue, or 0 if createarms changed it. */
    private int queueSize() {
        try {
            if (queueField == null) {
                queueField = CicTerminalBlockEntity.class.getDeclaredField("launchQueue");
                queueField.setAccessible(true);
            }
            return ((List<?>) queueField.get(block)).size();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    /** Missiles still waiting in the launch queue. */
    @LuaFunction(mainThread = true)
    public final int getQueueSize() {
        return queueSize();
    }

    /** Ids of the terminal's missiles in flight (also ones launched from its screen). */
    @LuaFunction(mainThread = true)
    public final List<String> getActiveMissiles() {
        List<String> out = new ArrayList<>();
        for (UUID id : block.getActiveMissiles()) out.add(id.toString());
        return out;
    }

    /** The stored target point {x,y,z}, or nil. */
    @LuaFunction(mainThread = true)
    public final @Nullable Map<String, Object> getTargetPos() {
        BlockPos p = block.getTargetPos();
        return p.equals(BlockPos.ZERO) ? null : com.ashcc.radar.LuaUtil.vec(p.getX(), p.getY(), p.getZ());
    }

    @LuaFunction(mainThread = true)
    public final void setTargetPos(double x, double y, double z) {
        block.setTargetPos(BlockPos.containing(x, y, z));
    }

    /** { vls = bool, ciws = bool } */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getAutoEngage() {
        Map<String, Object> t = new HashMap<>();
        t.put("vls", block.isVlsAutoEngage());
        t.put("ciws", block.isCiwsAutoEngage());
        return t;
    }

    /** VLS auto-engage fires loaded Patriots at hostile missiles the linked radar sees. */
    @LuaFunction(mainThread = true)
    public final void setVlsAutoEngage(boolean on) {
        if (block.isVlsAutoEngage() != on) block.toggleVlsAutoEngage();
    }

    @LuaFunction(mainThread = true)
    public final void setCiwsAutoEngage(boolean on) {
        if (block.isCiwsAutoEngage() != on) block.toggleCiwsAutoEngage();
    }

    /** Contacts of the terminal's radar (see missile_radar.getContacts), or nil and "no radar". */
    @LuaFunction(mainThread = true)
    public final MethodResult getContacts() throws LuaException {
        level();
        RadarBlockEntity radar = block.getLinkedRadar();
        if (radar == null) return MethodResult.of(null, "no radar");
        return MethodResult.of(MissileRadarPeripheral.contacts(radar));
    }
}
