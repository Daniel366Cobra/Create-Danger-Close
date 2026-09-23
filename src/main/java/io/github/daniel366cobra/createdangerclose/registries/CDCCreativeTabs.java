package io.github.daniel366cobra.createdangerclose.registries;

import com.tterrag.registrate.util.entry.RegistryEntry;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class CDCCreativeTabs {
    private static final DeferredRegister<CreativeModeTab> REGISTER =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CreateDangerClose.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = REGISTER.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.createdangerclose.main"))
                    .icon(() -> CDCBlocks.NETWORK_INTERFACE.asStack())
                    .displayItems(new CDCDisplayItemsGenerator())
                    .build());

    public static void register(IEventBus modEventBus) {
        REGISTER.register(modEventBus);
    }

    private static class CDCDisplayItemsGenerator implements CreativeModeTab.DisplayItemsGenerator {
        @Override
        public void accept(CreativeModeTab.ItemDisplayParameters parameters, CreativeModeTab.Output output) {
            // Iterate all your registered blocks
            for (RegistryEntry<Block, Block> entry : CreateDangerClose.getRegistrate().getAll(Registries.BLOCK)) {
                Item item = entry.get().asItem();
                if (item != Items.AIR) {
                    output.accept(item);
                }
            }
            // Iterate all your registered items
            for (RegistryEntry<Item, Item> entry : CreateDangerClose.getRegistrate().getAll(Registries.ITEM)) {
                Item item = entry.get();
                if (!(item instanceof BlockItem)) {
                    output.accept(item);
                }
            }
        }
    }
}