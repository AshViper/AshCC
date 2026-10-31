package com.ashcc.jet.client;

import com.ashcc.AshCC;
import com.ashcc.compat.SableCompat;
import com.ashcc.jet.JetEngineBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * Looping engine sound: the core loop rises in pitch and volume with N2 (so a start is heard as the spool-up),
 * the afterburner loop fades in with reheat. Follows the nozzle, also on a moving Sable ship. Client only.
 */
public final class JetEngineSound extends AbstractTickableSoundInstance {
    private final JetEngineBlockEntity engine;
    private final boolean afterburner;

    private JetEngineSound(JetEngineBlockEntity engine, SoundEvent event, boolean afterburner) {
        super(event, SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
        this.engine = engine;
        this.afterburner = afterburner;
        this.looping = true;
        this.delay = 0;
        this.volume = 0.01f;
        place();
    }

    /** Pair of sounds kept in the block entity's clientSound slot. */
    private record Pair(JetEngineSound core, JetEngineSound ab) {
    }

    public static void tick(JetEngineBlockEntity be) {
        boolean audible = be.clientN2(1) > 0.03;
        if (!audible) {
            stop(be);
            return;
        }
        if (be.clientSound instanceof Pair p && !p.core.isStopped()) return;
        Pair p = new Pair(new JetEngineSound(be, AshCC.JET_CORE_SOUND.get(), false), new JetEngineSound(be, AshCC.JET_AB_SOUND.get(), true));
        be.clientSound = p;
        Minecraft.getInstance().getSoundManager().play(p.core);
        Minecraft.getInstance().getSoundManager().play(p.ab);
    }

    public static void stop(JetEngineBlockEntity be) {
        if (be.clientSound instanceof Pair p) {
            p.core.stop();
            p.ab.stop();
        }
        be.clientSound = null;
    }

    private void place() {
        Vec3 c = Vec3.atCenterOf(engine.getBlockPos());
        if (AshCC.sableLoaded && engine.getLevel() != null) c = SableCompat.worldCenter(engine.getLevel(), engine.getBlockPos());
        x = c.x;
        y = c.y;
        z = c.z;
    }

    @Override
    public void tick() {
        if (engine.isRemoved()) {
            stop();
            return;
        }
        place();
        double n2 = engine.clientN2(1);
        double ab = engine.clientAb(1);
        if (afterburner) {
            volume = (float) (3.0 * ab);
            pitch = (float) (0.85 + 0.15 * ab);
        } else {
            double n1 = JetEngineBlockEntity.n1(n2);
            // Whine dominates at low power, roar (louder, lower relative) near military
            volume = (float) (0.3 + 2.2 * Math.min(1, n2) * (0.5 + n1));
            pitch = (float) (0.5 + 0.65 * Math.min(1.05, n2));
        }
        if (volume < 0.01f) volume = 0.01f;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }
}
