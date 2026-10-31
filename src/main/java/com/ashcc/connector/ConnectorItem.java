package com.ashcc.connector;

import com.ashcc.AshCC;
import com.ashcc.compat.SableConnector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Sticks an offset connector plate on the clicked face, shifted towards the ninth of it that was clicked (one plate per ninth). */
public class ConnectorItem extends Item {
    public ConnectorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        Direction face = ctx.getClickedFace();
        Vec3 hit = ctx.getClickLocation();
        // Hits on a ship can come back in world space; the face grid is in the block's own coordinates
        if (AshCC.sableLoaded && hit.distanceToSqr(Vec3.atCenterOf(pos)) > 4) hit = SableConnector.toBlockSpace(level, pos, hit);
        Vec3 anchor = ConnectorEntity.anchorFor(pos, face, hit);
        ConnectorEntity plate = ConnectorEntity.create(level, pos, face, anchor);
        // Several plates per face, one per ninth
        for (ConnectorEntity other : level.getEntitiesOfClass(ConnectorEntity.class, plate.getBoundingBox().inflate(0.6))) {
            if (other.samePlace(pos, face, anchor)) return InteractionResult.FAIL;
        }
        if (!level.isClientSide) {
            level.addFreshEntity(plate);
            plate.playSound(SoundEvents.ITEM_FRAME_PLACE, 1, 1);
            if (ctx.getPlayer() == null || !ctx.getPlayer().getAbilities().instabuild) ctx.getItemInHand().shrink(1);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
