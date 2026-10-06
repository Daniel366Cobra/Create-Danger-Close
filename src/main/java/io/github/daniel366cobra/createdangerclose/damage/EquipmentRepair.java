package io.github.daniel366cobra.createdangerclose.damage;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.api.damage.Fault;
import io.github.daniel366cobra.createdangerclose.block.cannon_servo.CannonServoInputBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.cannon_servo.CannonServoOutputBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.coupler.CouplerInputBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.coupler.CouplerOutputBlockEntity;
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
 * Repairing equipment by hand:
 * <ul>
 *   <li>jostled: left-click with a wrench (left, so a hurried double right-click in
 *       battle cannot knock anything out of true). Bonk.</li>
 *   <li>broken cog: right-click with a cogwheel; broken mechanism: with a precision
 *       mechanism. The item is used up.</li>
 *   <li>honeycomb: lubricates (see {@link EquipmentHealth#lubricate}), freeing a seized one.</li>
 * </ul>
 * The click is taken on both sides when it repairs, so it neither mines the block
 * nor places the cogwheel.
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
        level.playSound(null, event.getPos(), SoundEvents.SMITHING_TABLE_USE, SoundSource.BLOCKS, 0.8f, 1.1f);
    }

    /**
     * The health record a click on this block reaches: its own, or for a cannon servo's
     * or coupler's input half, the one kept by its output half.
     */
    @Nullable
    private static EquipmentHealth healthAt(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof CannonServoInputBlockEntity input) {
            CannonServoOutputBlockEntity output = input.getAdjacentOutput();
            return output != null ? EquipmentHealth.of(output) : null;
        }
        if (level.getBlockEntity(pos) instanceof CouplerInputBlockEntity input) {
            CouplerOutputBlockEntity output = input.getAdjacentOutput();
            return output != null ? EquipmentHealth.of(output) : null;
        }
        return EquipmentHealth.at(level, pos);
    }

    /** The fault a held item repairs, if any. */
    @Nullable
    private static Fault faultRepairedBy(ItemStack held) {
        if (AllBlocks.COGWHEEL.isIn(held))
            return Fault.BROKEN_COG;
        if (AllItems.PRECISION_MECHANISM.isIn(held))
            return Fault.BROKEN_MECHANISM;
        return null;
    }
}
