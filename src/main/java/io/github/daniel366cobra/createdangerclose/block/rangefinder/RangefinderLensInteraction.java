package io.github.daniel366cobra.createdangerclose.block.rangefinder;

import com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import io.github.daniel366cobra.createdangerclose.registries.CDCStats;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

/** Seeing to a lens on an assembled tube: a glass pane repairs a crack, a water bucket washes off dust. */
public class RangefinderLensInteraction extends MovingInteractionBehaviour {

    @Override
    public boolean handlePlayerInteraction(Player player, InteractionHand activeHand, BlockPos localPos,
                                           AbstractContraptionEntity contraptionEntity) {
        StructureBlockInfo info = contraptionEntity.getContraption().getBlocks().get(localPos);
        ItemStack held = player.getItemInHand(activeHand);
        if (held.is(Items.WATER_BUCKET) && contraptionEntity.getContraption() instanceof RangefinderContraption tube
                // The bearing stands behind the anchor, the tube's first block.
                && player.level().getBlockEntity(tube.anchor.relative(tube.getFacing().getOpposite()))
                        instanceof RangefinderBearingBlockEntity bearing
                && bearing.getLensDust(bearing.lensIndex(localPos)) > 0.01f) {
            // The bucket is not used up: a wipe, not a fill.
            if (!player.level().isClientSide && bearing.cleanLens(localPos)) {
                player.level().playSound(null, BlockPos.containing(contraptionEntity.toGlobalVector(localPos.getCenter(), 1f)),
                        SoundEvents.GENERIC_SPLASH, SoundSource.BLOCKS, 0.4f, 1.3f);
                CDCStats.LENSES_CLEANED.awardTo(player);
            }
            return true;
        }
        if (info == null || !RangefinderLensBlock.canRepair(info.state(), held))
            return false;
        if (!player.level().isClientSide) {
            setContraptionBlockData(contraptionEntity, localPos, new StructureBlockInfo(info.pos(),
                    info.state().setValue(RangefinderLensBlock.CRACKED, false), info.nbt()));
            RangefinderLensBlock.onRepaired(player, held,
                    BlockPos.containing(contraptionEntity.toGlobalVector(localPos.getCenter(), 1f)));
        }
        return true;
    }
}