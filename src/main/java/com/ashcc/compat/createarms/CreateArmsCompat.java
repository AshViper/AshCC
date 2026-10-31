package com.ashcc.compat.createarms;

import com.defpwoq.createarms.block.CicTerminalBlockEntity;
import com.defpwoq.createarms.block.CountermeasureDispenserBlockEntity;
import com.defpwoq.createarms.block.IronDomeRadarBlockEntity;
import com.defpwoq.createarms.block.MunitionRackBlockEntity;
import com.defpwoq.createarms.block.RadarBlockEntity;
import com.defpwoq.createarms.block.VlsHatchBlockEntity;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * CC peripherals on Create Modern Warfare (createarms) blocks: munition racks, VLS hatches, the CIC terminal, its radars,
 * the countermeasure dispenser and Iron Dome. Only loaded when createarms is installed (it needs Sable).
 */
public final class CreateArmsCompat {
    private CreateArmsCompat() {
    }

    public static void init(IEventBus modBus) {
        modBus.addListener(CreateArmsCompat::registerCapabilities);
        NeoForge.EVENT_BUS.addListener(CreateArmsCompat::serverTick);
    }

    /** Every createarms block entity type, so racks added in later versions work too; the class decides the peripheral. */
    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        for (BlockEntityType<?> type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            var key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
            if (key != null && key.getNamespace().equals("createarms")) {
                event.registerBlockEntity(PeripheralCapability.get(), type, (be, side) -> peripheral(be));
            }
        }
    }

    private static @Nullable IPeripheral peripheral(BlockEntity be) {
        if (be instanceof MunitionRackBlockEntity rack) return new MissileRackPeripheral(rack);
        if (be instanceof VlsHatchBlockEntity vls) return new VlsPeripheral(vls);
        if (be instanceof CicTerminalBlockEntity cic) return new CicTerminalPeripheral(cic);
        if (be instanceof RadarBlockEntity radar) return new MissileRadarPeripheral(radar);
        if (be instanceof CountermeasureDispenserBlockEntity dispenser) return new CountermeasurePeripheral(dispenser);
        if (be instanceof IronDomeRadarBlockEntity dome) return new IronDomePeripheral(dome);
        return null;
    }

    // ---- CIC launches: the terminal's queue fires later, so its new missiles are picked up as they appear ----

    private record CicWatch(CicTerminalBlockEntity cic, Set<UUID> known, ArmsTarget target, String owner, int[] left, long deadline) {
    }

    private static final List<CicWatch> CIC_WATCHES = new ArrayList<>();

    /** Tags the next {@code count} missiles the terminal launches as ours and lets them follow a ship target. */
    static void watchCicLaunch(CicTerminalBlockEntity cic, Set<UUID> known, ArmsTarget target, String owner, int count) {
        // 20 ticks of hatch per missile plus 15 between them, with slack
        long deadline = cic.getLevel().getGameTime() + 40L * count + 100;
        CIC_WATCHES.add(new CicWatch(cic, known, target, owner, new int[]{count}, deadline));
    }

    private static void serverTick(ServerTickEvent.Post event) {
        for (Iterator<CicWatch> it = CIC_WATCHES.iterator(); it.hasNext(); ) {
            CicWatch w = it.next();
            if (w.cic.isRemoved() || !(w.cic.getLevel() instanceof ServerLevel level) || level.getGameTime() > w.deadline) {
                it.remove();
                continue;
            }
            for (UUID id : w.cic.getActiveMissiles()) {
                if (!w.known.add(id)) continue;
                if (ArmsMissiles.find(level, id) instanceof ArmsMissiles.Tracked.Ship ship) {
                    ship.missile().getPersistentData().putString(ArmsMissiles.OWNER, w.owner);
                    if (w.target.subLevel() != null) ArmsMissiles.retarget(ship.missile(), w.target);
                }
                w.left[0]--;
            }
            if (w.left[0] <= 0) it.remove();
        }
    }
}
