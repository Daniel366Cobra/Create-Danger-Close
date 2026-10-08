package io.github.daniel366cobra.createdangerclose.block.rangefinder;

import com.mojang.serialization.MapCodec;
import io.github.daniel366cobra.createdangerclose.registries.CDCStats;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * An objective lens; the spacing of a tube's two is its baseline. {@link #CRACKED} by blast or hits
 * (see {@link io.github.daniel366cobra.createdangerclose.damage.BattleDamage}) until repaired with a glass pane;
 * kept in the block state through (dis)assembly.
 */
public class RangefinderLensBlock extends RangefinderOpticBlock {

    public static final MapCodec<RangefinderLensBlock> CODEC = simpleCodec(RangefinderLensBlock::new);

    public static final BooleanProperty CRACKED = BooleanProperty.create("cracked");

    public RangefinderLensBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(CRACKED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(CRACKED);
    }

    /** Cracked, and the held item is a glass pane. */
    public static boolean canRepair(BlockState state, ItemStack held) {
        return state.getBlock() instanceof RangefinderLensBlock && state.getValue(CRACKED) && held.is(Items.GLASS_PANE);
    }

    /** Server: uses up the pane and credits the repair; the caller replaces the state. */
    public static void onRepaired(Player player, ItemStack held, BlockPos soundPos) {
        if (!player.getAbilities().instabuild)
            held.shrink(1);
        player.level().playSound(null, soundPos, SoundEvents.AMETHYST_BLOCK_PLACE, SoundSource.BLOCKS, 1f, 1.2f);
        CDCStats.EQUIPMENT_REPAIRED.awardTo(player);
    }

    /** Not part of an assembled tube. */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!canRepair(state, stack))
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!level.isClientSide) {
            level.setBlockAndUpdate(pos, state.setValue(CRACKED, false));
            onRepaired(player, stack, pos);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }
}