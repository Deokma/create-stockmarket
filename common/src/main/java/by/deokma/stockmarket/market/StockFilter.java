package by.deokma.stockmarket.market;

import by.deokma.stockmarket.CreateStockMarket;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Datapack-driven control over which items are listed on the market.
 *
 * <ul>
 *   <li>{@code #stockmarket:hidden} — always excluded, whatever else says.</li>
 *   <li>{@code #stockmarket:tradable} — when this tag has at least one entry it
 *       becomes a strict whitelist; while it is empty every item is listed.</li>
 * </ul>
 *
 * Tags are read from the server's datapacks, so admins change them without
 * touching the mod jar or the client.
 */
public final class StockFilter {

    public static final TagKey<Item> TRADABLE = TagKey.create(
            Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(CreateStockMarket.MOD_ID, "tradable"));

    public static final TagKey<Item> HIDDEN = TagKey.create(
            Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(CreateStockMarket.MOD_ID, "hidden"));

    private StockFilter() {}

    /** True when the item may appear on the market. */
    public static boolean isListed(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.is(HIDDEN)) return false;
        return !whitelistActive() || stack.is(TRADABLE);
    }

    /** The tradable tag only acts as a whitelist once a datapack puts something in it. */
    private static boolean whitelistActive() {
        return BuiltInRegistries.ITEM.getTag(TRADABLE)
                .map(holders -> holders.size() > 0)
                .orElse(false);
    }
}
