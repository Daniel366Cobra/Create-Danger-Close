package io.github.daniel366cobra.createdangerclose.damage;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.block.kinetic_pair.PairInputBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.kinetic_pair.PairOutputBlockEntity;
import io.github.daniel366cobra.createdangerclose.foundation.damage.Fault;
import io.github.daniel366cobra.createdangerclose.registries.CDCStats;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Repairs by hand: wrench left-click realigns (left, so a hurried double right-click cannot knock
 * anything out of true); cogwheel or precision mechanism right-click mends, used up; honeycomb lubricates.
 * A repairing click is taken on both sides, so it neither mines nor places.
 */
@EventBusSubscriber(modid = CreateDangerClose.MOD_ID)
public final class EquipmentRepair {

    private EquipmentRepair() {}

    @SubscribeEvent
    static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START)
            return;
        Level level = event.getLevel();
        EquipmentHealth health = healthAt(level, event.getPos());
        if (health == null || !health.has(Fault.JOSTLED) || !AllItems.WRENCH.isIn(event.getItemStack()))
            return;
        event.setCanceled(true);
        if (level.isClientSide)
            return;
        health.repair(Fault.JOSTLED);
        CDCStats.EQUIPMENT_REALIGNED.awardTo(event.getEntity());
        level.playSound(null, event.getPos(), SoundEvents.ANVIL_LAND, SoundSource.BLOCKS,
                0.3f, 1.3f + level.random.nextFloat() * 0.4f);
    }

    @SubscribeEvent
    static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        EquipmentHealth health = healthAt(level, event.getPos());
        if (health == null)
            return;
        ItemStack held = event.getItemStack();
        if (held.is(Items.HONEYCOMB) && health.isLubricated()) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
            if (!level.isClientSide && health.lubricate(event.getEntity(), held))
                level.playSound(null, event.getPos(), SoundEvents.HONEYCOMB_WAX_ON, SoundSource.BLOCKS, 0.8f, 1.1f);
            return;
        }
        Fault fault = faultRepairedBy(held);
        if (fault == null || !health.has(fault))
            return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        if (level.isClientSide)
            return;
        health.repair(fault);
        Player player = event.getEntity();
        if (!player.getAbilities().instabuild)
            held.shrink(1);
        CDCStats.EQUIPMENT_REPAIRED.awardTo(player);
        level.playSound(null, event.getPos(), SoundEvents.SMITHING_TABLE_USE, SoundSource.BLOCKS, 0.8f, 1.1f);
    }

    /** Its own, or for a servo's or coupler's input half, its output half's. */
    @Nullable
    private static EquipmentHealth healthAt(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof PairInputBlockEntity input) {
            PairOutputBlockEntity output = input.getPairedOutput();
            return output != null ? EquipmentHealth.of(output) : null;
        }
        return EquipmentHealth.at(level, pos);
    }

    @Nullable
    private static Fault faultRepairedBy(ItemStack held) {
        if (AllBlocks.COGWHEEL.isIn(held))
            return Fault.BROKEN_COG;
        if (AllItems.PRECISION_MECHANISM.isIn(held))
            return Fault.BROKEN_MECHANISM;
        return null;
    }
}