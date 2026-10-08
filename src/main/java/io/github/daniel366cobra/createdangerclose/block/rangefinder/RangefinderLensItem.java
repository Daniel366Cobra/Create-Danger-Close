package io.github.daniel366cobra.createdangerclose.block.rangefinder;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.block.Block;

/** A cracked lens stays cracked as an item (its state rides on the stack), with its own name and model. */
public class RangefinderLensItem extends BlockItem {

    public RangefinderLensItem(Block block, Properties properties) {
        super(block, properties);
    }

    public static boolean isCracked(ItemStack stack) {
        Boolean cracked = stack.getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY)
                .get(RangefinderLensBlock.CRACKED);
        return cracked != null && cracked;
    }

    @Override
    public Component getName(ItemStack stack) {
        return isCracked(stack) ? Component.translatable(getDescriptionId() + ".cracked") : super.getName(stack);
    }
}