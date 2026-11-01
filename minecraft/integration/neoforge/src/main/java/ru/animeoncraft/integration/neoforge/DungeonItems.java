package ru.animeoncraft.integration.neoforge;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Items that only the Rift Gate gives. Rift Essence is the crafting material of the coming Rift Forge. */
final class DungeonItems {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, "animeoncraft");
    static final DeferredHolder<Item, Item> ESSENCE = ITEMS.register("rift_essence", () -> new Item(new Item.Properties().rarity(Rarity.RARE)));
    private DungeonItems() {}
    static void register(IEventBus bus) {
        ITEMS.register(bus);
        bus.addListener((net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == net.minecraft.world.item.CreativeModeTabs.INGREDIENTS) event.accept(ESSENCE.get());
        });
    }
}
