package by.deokma.stockmarket.neoforge.display;

import by.deokma.stockmarket.market.MarketEntry;
import by.deokma.stockmarket.market.MarketRegistry;
import by.deokma.stockmarket.market.TradeStatsSavedData;
import by.deokma.stockmarket.shop.ShopEntry;
import by.deokma.stockmarket.shop.VendorRegistry;
import net.createmod.catnip.data.IntAttached;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Server-side data providers for the Market Terminal's Display Link sources.
 * Each value-list method returns an already-sorted stream; the source applies the row limit.
 */
public final class MarketDisplayData {

    private MarketDisplayData() {
    }

    // ── Value lists (leaderboards) ────────────────────────────────────────────

    /** Players ranked by completed sales. */
    public static Stream<IntAttached<MutableComponent>> topSellers(MinecraftServer server) {
        return TradeStatsSavedData.getOrCreate(server).getTopSellers(Integer.MAX_VALUE).stream()
                .map(e -> attach(e.getValue(), Component.literal(e.getKey())));
    }

    /** Players ranked by how many shop listings they own. */
    public static Stream<IntAttached<MutableComponent>> topShops(MinecraftServer server) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ShopEntry e : VendorRegistry.getAll()) counts.merge(e.ownerName(), 1L, Long::sum);
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> attach(e.getValue(), Component.literal(e.getKey())));
    }

    /** Items ranked by total traded volume (sells + buys). */
    public static Stream<IntAttached<MutableComponent>> mostTraded(MinecraftServer server) {
        return MarketRegistry.build(server).stream()
                .filter(m -> m.sellCount() + m.buyCount() > 0)
                .sorted(Comparator.comparingInt((MarketEntry m) -> m.sellCount() + m.buyCount()).reversed())
                .map(m -> IntAttached.with(m.sellCount() + m.buyCount(), itemName(m)));
    }

    // ── Single-line counts ────────────────────────────────────────────────────

    /** Total number of shop listings (offers). */
    public static long shopCount(MinecraftServer server) {
        return VendorRegistry.getAll().size();
    }

    /** Number of distinct players running shops. */
    public static long sellerCount(MinecraftServer server) {
        return VendorRegistry.getAll().stream().map(ShopEntry::ownerName).distinct().count();
    }

    /** Sum of all completed sales across every seller. */
    public static long totalSales(MinecraftServer server) {
        return TradeStatsSavedData.getOrCreate(server).getTopSellers(Integer.MAX_VALUE).stream()
                .mapToLong(Map.Entry::getValue).sum();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static MutableComponent itemName(MarketEntry m) {
        return m.displayStack().getHoverName().copy();
    }

    private static IntAttached<MutableComponent> attach(long value, MutableComponent label) {
        return IntAttached.with((int) Math.min(value, Integer.MAX_VALUE), label);
    }
}
