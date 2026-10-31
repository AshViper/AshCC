package com.ashcc.panel;

import com.ashcc.seat.SeatEntity;
import com.ashcc.seat.Seats;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.fml.loading.FMLEnvironment;
import org.jetbrains.annotations.Nullable;

/**
 * A whole cockpit in one block: a control panel that comes with a full layout already on it — two MFDs (flight
 * instruments and radar), stick, throttle, slider, knob, buttons and toggles on Drive By Wire channels 1-33 — plus
 * side consoles in its model and a seat. Right-click with an empty hand to sit down; while seated the stick and
 * throttle are held for you (HOTAS keys as usual), and clicking a screen cycles its built-in page. The layout is a
 * normal panel layout: sneak-right-click any cell to rearrange it.
 */
public class CockpitBlock extends ControlPanelBlock {
    public static final MapCodec<CockpitBlock> CODEC = simpleCodec(CockpitBlock::new);

    public CockpitBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof ControlPanelBlockEntity be && be.controls().isEmpty()) {
            applyDefaultLayout(be);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof ControlPanelBlockEntity be)) return InteractionResult.PASS;
        boolean seatedHere = player.getVehicle() instanceof SeatEntity seat && pos.equals(seat.blockPos());
        if (level.isClientSide) {
            // Seated (or setting up): work the controls. Otherwise the server seats the player.
            if (FMLEnvironment.dist.isClient() && (player.isShiftKeyDown() || seatedHere)) {
                com.ashcc.panel.client.PanelClient.click(be, player.isShiftKeyDown());
            }
            return InteractionResult.SUCCESS;
        }
        if (player.isShiftKeyDown() || seatedHere) return InteractionResult.CONSUME;
        return Seats.sit(level, pos, player) ? InteractionResult.CONSUME : InteractionResult.PASS;
    }

    /** The factory layout: an 8x8 panel, tilted 30 degrees, with the two MFDs up top and the HOTAS below. */
    public static void applyDefaultLayout(ControlPanelBlockEntity be) {
        be.setLayout(8, 30);
        // Top row: knob, the two MFDs, two buttons
        be.configure(0, 0, PanelControl.Type.KNOB, 2, 2, 15, DyeColor.LIGHT_GRAY);
        be.configure(2, 0, PanelControl.Type.SCREEN, 4, 3, 1, DyeColor.BLACK, PanelControl.MODE_INSTRUMENTS);
        be.configure(6, 0, PanelControl.Type.SCREEN, 2, 2, 1, DyeColor.BLACK, PanelControl.MODE_RADAR);
        be.configure(6, 2, PanelControl.Type.BUTTON, 1, 1, 16, DyeColor.RED);
        be.configure(7, 2, PanelControl.Type.BUTTON, 1, 1, 17, DyeColor.YELLOW);
        // Middle: throttle, stick, slider, buttons and toggles
        be.configure(0, 3, PanelControl.Type.THROTTLE, 2, 4, 1, DyeColor.BLACK);
        be.configure(2, 3, PanelControl.Type.STICK, 3, 4, 4, DyeColor.BLACK);
        be.configure(5, 3, PanelControl.Type.SLIDER, 1, 4, 14, DyeColor.LIGHT_GRAY);
        be.configure(6, 3, PanelControl.Type.BUTTON, 1, 1, 18, DyeColor.LIME);
        be.configure(7, 3, PanelControl.Type.BUTTON, 1, 1, 19, DyeColor.LIME);
        be.configure(6, 4, PanelControl.Type.BUTTON, 1, 1, 20, DyeColor.WHITE);
        be.configure(7, 4, PanelControl.Type.BUTTON, 1, 1, 21, DyeColor.WHITE);
        be.configure(6, 5, PanelControl.Type.TOGGLE, 1, 1, 26, DyeColor.ORANGE);
        be.configure(7, 5, PanelControl.Type.TOGGLE, 1, 1, 27, DyeColor.ORANGE);
        be.configure(6, 6, PanelControl.Type.BUTTON, 1, 1, 22, DyeColor.CYAN);
        be.configure(7, 6, PanelControl.Type.BUTTON, 1, 1, 23, DyeColor.CYAN);
        // Bottom row
        be.configure(0, 7, PanelControl.Type.BUTTON, 1, 1, 24, DyeColor.MAGENTA);
        be.configure(1, 7, PanelControl.Type.BUTTON, 1, 1, 25, DyeColor.MAGENTA);
        be.configure(2, 7, PanelControl.Type.KNOB, 1, 1, 28, DyeColor.LIGHT_GRAY);
        be.configure(3, 7, PanelControl.Type.TOGGLE, 1, 1, 29, DyeColor.RED);
        be.configure(4, 7, PanelControl.Type.BUTTON, 1, 1, 30, DyeColor.WHITE);
        be.configure(5, 7, PanelControl.Type.BUTTON, 1, 1, 31, DyeColor.WHITE);
        be.configure(6, 7, PanelControl.Type.BUTTON, 1, 1, 32, DyeColor.GRAY);
        be.configure(7, 7, PanelControl.Type.BUTTON, 1, 1, 33, DyeColor.GRAY);
    }
}
