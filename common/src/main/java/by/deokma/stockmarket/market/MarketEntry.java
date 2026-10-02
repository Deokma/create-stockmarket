package by.deokma.stockmarket.market;

import by.deokma.stockmarket.util.ItemStackPersistence;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of aggregated market data for a single stock.
 * Safe to send over the network.
 *
 * <p>A "stock" is normally one item variant: two diamond swords with different
 * enchantments are two stocks, because averaging their prices together is
 * meaningless. {@link #itemId} stays the plain item id (client-side filters group
 * by it), while {@link #stockKey} is the finer identity used for price history.
 *
 * <p>{@link #minPrice} and {@link #avgPrice} are the price of {@link #lotSize} items,
 * so shops selling the same item in different quantities are compared fairly.
 *
 * <p>barterItem — the payment item for TableCloth shops (EMPTY if none / Vendor-only).
 */
public record MarketEntry(
        ResourceLocation itemId,       // plain item id — shared by every variant
        String           stockKey,     // variant-aware identity; key for price history
        ItemStack        displayStack,
        String           displayName,  // admin override; "" = use the item's own name
        int              minPrice,     // spurs per lotSize items
        int              avgPrice,     // spurs per lotSize items
        int              lotSize,      // item count the prices refer to (≥ 1)
        int              sellCount,
        int              buyCount,
        PriceTrend       trend,
        List<Integer>    priceHistory,
        ItemStack        barterItem    // payment item for TableCloth; EMPTY if not barter
) {
    /** True when this entry has no Vendor price — only TableCloth barter shops. */
    public boolean isBarterOnly() { return minPrice <= 0 && avgPrice <= 0 && !barterItem.isEmpty(); }

    /** Name to show for this stock — the admin override when set, else the item's name. */
    public Component label() {
        return displayName.isEmpty() ? displayStack.getHoverName() : Component.literal(displayName);
    }

    /** Plain-text form of {@link #label()}, for width measuring, search and sorting. */
    public String labelText() {
        return displayName.isEmpty() ? displayStack.getHoverName().getString() : displayName;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(itemId);
        buf.writeUtf(stockKey);
        writeItem(buf, displayStack);
        buf.writeUtf(displayName);
        buf.writeInt(minPrice);
        buf.writeInt(avgPrice);
        buf.writeVarInt(lotSize);
        buf.writeInt(sellCount);
        buf.writeInt(buyCount);
        buf.writeByte(trend.ordinal());
        buf.writeInt(priceHistory.size());
        for (int price : priceHistory) buf.writeInt(price);
        writeItem(buf, barterItem);
    }

    public static MarketEntry read(FriendlyByteBuf buf) {
        ResourceLocation itemId       = buf.readResourceLocation();
        String           stockKey     = buf.readUtf();
        ItemStack        displayStack = readItem(buf);
        String           displayName  = buf.readUtf();
        int              minPrice     = buf.readInt();
        int              avgPrice     = buf.readInt();
        int              lotSize      = buf.readVarInt();
        int              sellCount    = buf.readInt();
        int              buyCount     = buf.readInt();
        PriceTrend       trend        = PriceTrend.values()[buf.readByte()];
        int              histSize     = buf.readInt();
        List<Integer>    priceHistory = new ArrayList<>(histSize);
        for (int i = 0; i < histSize; i++) priceHistory.add(buf.readInt());
        ItemStack        barterItem   = readItem(buf);
        return new MarketEntry(itemId, stockKey, displayStack, displayName, minPrice, avgPrice,
                lotSize, sellCount, buyCount, trend, priceHistory, barterItem);
    }

    private static void writeItem(FriendlyByteBuf buf, ItemStack stack) {
        var reg = buf instanceof net.minecraft.network.RegistryFriendlyByteBuf rfbb
                ? rfbb.registryAccess() : net.minecraft.core.RegistryAccess.EMPTY;
        if (stack.isEmpty()) {
            buf.writeNbt(null);
        } else {
            ItemStackPersistence.writeToNetworkBuf(buf, stack, reg);
        }
    }

    private static ItemStack readItem(FriendlyByteBuf buf) {
        var tag = buf.readNbt();
        var reg = buf instanceof net.minecraft.network.RegistryFriendlyByteBuf rfbb
                ? rfbb.registryAccess() : net.minecraft.core.RegistryAccess.EMPTY;
        return ItemStackPersistence.parseNetworkNbt(tag, reg);
    }
}
