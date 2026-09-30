package io.github.daniel366cobra.createdangerclose.block.rangefinder;

import com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

/** Repairing a cracked lens on an assembled tube: right-click it with a glass pane. */
public class RangefinderLensInteraction extends MovingInteractionBehaviour {

    @Override
    public boolean handlePlayerInteraction(Player player, InteractionHand activeHand, BlockPos localPos,
                                           AbstractContraptionEntity contraptionEntity) {
        StructureBlockInfo info = contraptionEntity.getContraption().getBlocks().get(localPos);
        ItemStack held = player.getItemInHand(activeHand);
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
