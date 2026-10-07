package io.github.daniel366cobra.createdangerclose.block.kinetic_pair;

import io.github.daniel366cobra.createdangerclose.foundation.CDCLang;
import net.createmod.catnip.data.Pair;
import net.createmod.catnip.outliner.Outliner;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Places a pair's input half; when there is no room for the output half, shows where it would go. */
public class PairBlockItem extends BlockItem {

    // The status message when the output half has no room.
    private final String notEnoughSpaceKey;

    public PairBlockItem(Block block, Properties properties, String notEnoughSpaceKey) {
        super(block, properties);
        this.notEnoughSpaceKey = notEnoughSpaceKey;
    }

    @Override
    public InteractionResult place(BlockPlaceContext ctx) {
        InteractionResult result = super.place(ctx);
        if (result == InteractionResult.FAIL && ctx.getLevel().isClientSide())
            CatnipServices.PLATFORM.executeOnClientOnly(() -> () -> showBounds(ctx));
        return result;
    }

    @OnlyIn(Dist.CLIENT)
    public void showBounds(BlockPlaceContext context) {
        if (!(context.getPlayer() instanceof LocalPlayer localPlayer))
            return;
        BlockPos pos = context.getClickedPos();
        Direction facing = ((PairInputBlock<?>) getBlock()).getDirectionForPlacement(context);
        Outliner.getInstance()
                .showAABB(Pair.of("pair_input", pos),
                        new AABB(pos).expandTowards(facing.getStepX(), facing.getStepY(), facing.getStepZ()))
                .colored(0xFF_ff5d6c);
        CDCLang.translate(notEnoughSpaceKey)
                .color(0xFF_ff5d6c)
                .sendStatus(localPlayer);
    }
}
