package io.github.daniel366cobra.createdangerclose.block.map_table;

import com.simibubi.create.api.connectivity.ConnectivityHandler;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;
import com.simibubi.create.foundation.item.ItemHelper;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A plotting table for maps. Right-click a block with a filled map to lay it
 * on that block; right-click with an empty hand to open the table.
 */
public class MapTableBlock extends Block implements IWrenchable, IBE<MapTableBlockEntity> {

    public MapTableBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!stack.is(Items.FILLED_MAP))
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide)
            return ItemInteractionResult.SUCCESS;

        withBlockEntityDo(level, pos, table -> {
            if (!table.getMap().isEmpty()) {
                player.displayClientMessage(Component.translatable("createdangerclose.map_table.occupied"), true);
                return;
            }
            if (!table.canHold(stack)) {
                player.displayClientMessage(Component.translatable("createdangerclose.map_table.does_not_fit"), true);
                return;
            }
            table.getInventory().setStackInSlot(0, stack.copyWithCount(1));
            if (!player.getAbilities().instabuild)
                stack.shrink(1);
            level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1f, 1f);
        });
        return ItemInteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (level.isClientSide)
            return InteractionResult.SUCCESS;
        withBlockEntityDo(level, pos, table -> {
            MapTableBlockEntity controller = table.getControllerBE();
            if (controller != null && player instanceof ServerPlayer serverPlayer)
                serverPlayer.openMenu(controller, buf -> {
                    buf.writeBlockPos(controller.getBlockPos());
                    buf.writeVarInt(controller.getWidth());
                });
        });
        return InteractionResult.SUCCESS;
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        if (oldState.getBlock() == state.getBlock() || isMoving)
            return;
        withBlockEntityDo(level, pos, MapTableBlockEntity::updateConnectivity);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (state.hasBlockEntity() && (state.getBlock() != newState.getBlock() || !newState.hasBlockEntity())) {
            if (level.getBlockEntity(pos) instanceof MapTableBlockEntity table) {
                ItemHelper.dropContents(level, pos, table.getInventory());
                level.removeBlockEntity(pos);
                ConnectivityHandler.splitMulti(table);
            }
        }
    }

    @Override
    public Class<MapTableBlockEntity> getBlockEntityClass() {
        return MapTableBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends MapTableBlockEntity> getBlockEntityType() {
        return CDCBlockEntities.MAP_TABLE.get();
    }
}
