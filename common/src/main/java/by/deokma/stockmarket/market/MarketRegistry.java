package by.deokma.stockmarket.market;

import by.deokma.stockmarket.config.MarketConfig;
import by.deokma.stockmarket.shop.ShopEntry;
import by.deokma.stockmarket.shop.VendorRegistry;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregates shop data from {@link VendorRegistry} into {@link MarketEntry} snapshots.
 *
 * Fully platform-agnostic — depends only on vanilla Minecraft classes and common module code.
 */
public final class MarketRegistry implements IMarketRegistry {

    private static final Logger LOGGER = LogManager.getLogger("stockmarket");

    /** Grouping identity: the item, plus its component variant when splitting is enabled. */
    private record StockId(ResourceLocation itemId, String variant) {
        String key() { return variant.isEmpty() ? itemId.toString() : itemId + "#" + variant; }
    }

    // ── IMarketRegistry ───────────────────────────────────────────────────────

    @Override
    public List<MarketEntry> buildEntries(MinecraftServer server) {
        return build(server);
    }

    @Override
    public void takeSnapshot(MinecraftServer server) {
        snapshot(server);
    }

    // ── Static helpers (called from platform event handlers) ──────────────────

    public static List<MarketEntry> build(MinecraftServer server) {
        try {
            List<ShopEntry> shops = VendorRegistry.getAll();
            HolderLookup.Provider registries = server.registryAccess();
            PriceHistorySavedData histData = PriceHistorySavedData.getOrCreate(server);

            // Group by item, splitting distinct component variants into separate stocks so
            // that e.g. two differently enchanted books never share one averaged price.
            Map<StockId, List<ShopEntry>> grouped = new LinkedHashMap<>();
            for (ShopEntry e : shops) {
                ItemStack selling = e.sellingItem();
                if (!StockFilter.isListed(selling)) continue;

                ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(selling.getItem());
                grouped.computeIfAbsent(new StockId(itemId, variantOf(selling, registries)), k -> new ArrayList<>())
                        .add(e);
            }

            List<MarketEntry> result = new ArrayList<>();
            for (Map.Entry<StockId, List<ShopEntry>> group : grouped.entrySet()) {
                StockId stockId         = group.getKey();
                List<ShopEntry> entries = group.getValue();
                String stockKey         = stockId.key();

                // Prices only from VENDOR SELL shops. A vendor's price is for its whole lot, so
                // "1 diamond for 1 cog" and "64 diamonds for 8 cogs" are compared per item and
                // then quoted for the lot size most shops use. Averaging the raw lot prices
                // produced a number that matched no shop at all.
                List<ShopEntry> vendorSells = entries.stream()
                        .filter(e -> "VENDOR".equals(e.shopType()) && "SELL".equals(e.mode())
                                && e.totalPriceInSpurs() > 0)
                        .collect(Collectors.toList());

                int lotSize   = commonLotSize(vendorSells);
                int minPrice  = quote(vendorSells.stream()
                        .mapToDouble(MarketRegistry::unitPrice).min(), lotSize);
                int avgPrice  = quote(vendorSells.stream()
                        .mapToDouble(MarketRegistry::unitPrice).average(), lotSize);
                int sellCount = (int) entries.stream().filter(e -> "SELL".equals(e.mode())).count();
                int buyCount  = (int) entries.stream().filter(e -> "BUY".equals(e.mode())).count();

                List<Integer> history = histData.getHistory(stockKey, 10);
                PriceTrend trend = computeTrend(histData.getHistory(stockKey));

                // Representative icon: the variant most shops actually list, not an arbitrary first.
                ItemStack display = mostCommon(entries.stream()
                        .map(ShopEntry::sellingItem)
                        .filter(s -> !s.isEmpty())
                        .toList(), registries).copyWithCount(1);

                // Barter payment: likewise the most common payment stack across barter SELL shops.
                // The stack's count is the asking amount, so it must survive intact.
                ItemStack barterItem = mostCommon(entries.stream()
                        .filter(e -> e.usesItemPrice() && "SELL".equals(e.mode())
                                && !e.priceItem().isEmpty())
                        .map(ShopEntry::priceItem)
                        .toList(), registries).copy();

                result.add(new MarketEntry(stockId.itemId(), stockKey, display,
                        MarketConfig.nameOverride(stockId.itemId()),
                        minPrice, avgPrice, lotSize, sellCount, buyCount, trend, history, barterItem));
            }
            return result;
        } catch (Exception e) {
            LOGGER.warn("[MarketRegistry] buildEntries failed", e);
            return List.of();
        }
    }

    /**
     * {@link #build} result reused for up to {@link #CACHE_TICKS} ticks. Display Links poll
     * their source every few ticks each, and every poll used to regroup every shop on the
     * server; the market cannot meaningfully change faster than this anyway.
     */
    public static List<MarketEntry> buildCached(MinecraftServer server) {
        CachedBuild cached = cache;
        int now = server.getTickCount();
        if (cached != null && cached.server() == server && now - cached.tick() < CACHE_TICKS) {
            return cached.entries();
        }
        List<MarketEntry> entries = List.copyOf(build(server));
        cache = new CachedBuild(server, now, entries);
        return entries;
    }

    private static final int CACHE_TICKS = 20;

    private record CachedBuild(MinecraftServer server, int tick, List<MarketEntry> entries) {}

    private static volatile CachedBuild cache;

    public static void snapshot(MinecraftServer server) {
        try {
            List<MarketEntry> entries = build(server);
            PriceHistorySavedData histData = PriceHistorySavedData.getOrCreate(server);
            for (MarketEntry entry : entries) {
                if (entry.avgPrice() > 0) {
                    histData.addSnapshot(entry.stockKey(), entry.avgPrice());
                }
            }
            // Stocks nobody trades any more would otherwise keep their history forever.
            histData.pruneStale(MarketConfig.historyRetentionDays());
        } catch (Exception e) {
            LOGGER.debug("[MarketRegistry] takeSnapshot failed: {}", e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Trend of the latest snapshot against the average of the preceding window.
     * Averaging the baseline keeps a single odd listing from flipping the arrow,
     * which comparing only the last two snapshots did.
     */
    static PriceTrend computeTrend(List<Integer> history) {
        if (history.size() < 2) return PriceTrend.STABLE;

        int last      = history.get(history.size() - 1);
        int window    = Math.max(1, MarketConfig.trendWindow());
        int threshold = Math.max(0, MarketConfig.trendThreshold());

        List<Integer> baseline = history.subList(
                Math.max(0, history.size() - 1 - window), history.size() - 1);
        if (baseline.isEmpty()) return PriceTrend.STABLE;

        double base = baseline.stream().mapToInt(i -> i).average().orElse(last);
        if (last - base > threshold) return PriceTrend.RISING;
        if (base - last > threshold) return PriceTrend.FALLING;
        return PriceTrend.STABLE;
    }

    /**
     * Price of {@code lotSize} items at {@code unitPrice}, in whole spurs. Never rounds a paid
     * listing down to 0, which the UI would show as "Free"; 0 only when there is no price.
     */
    private static int quote(OptionalDouble unitPrice, int lotSize) {
        if (unitPrice.isEmpty()) return 0;
        return (int) Math.max(1, Math.round(unitPrice.getAsDouble() * lotSize));
    }

    /** Spurs per single item of a vendor listing. */
    private static double unitPrice(ShopEntry e) {
        return e.totalPriceInSpurs() / (double) Math.max(1, e.sellingItem().getCount());
    }

    /**
     * The lot size most listings use — prices are quoted for that many items. Ties go to the
     * smaller lot so the quote stays close to a single item. 1 when there are no listings.
     */
    static int commonLotSize(List<ShopEntry> listings) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (ShopEntry e : listings) {
            counts.merge(Math.max(1, e.sellingItem().getCount()), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Map.Entry.<Integer, Integer>comparingByValue()
                        .thenComparing(Map.Entry.<Integer, Integer>comparingByKey().reversed()))
                .map(Map.Entry::getKey)
                .orElse(1);
    }

    /** The stack that occurs most often in the list; {@link ItemStack#EMPTY} when the list is empty. */
    private static ItemStack mostCommon(List<ItemStack> stacks, HolderLookup.Provider registries) {
        if (stacks.isEmpty()) return ItemStack.EMPTY;

        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, ItemStack> samples = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            String key = BuiltInRegistries.ITEM.getKey(stack.getItem())
                    + "|" + stack.getCount() + "|" + variantOf(stack, registries);
            counts.merge(key, 1, Integer::sum);
            samples.putIfAbsent(key, stack);
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> samples.get(e.getKey()))
                .orElse(stacks.get(0));
    }

    /**
     * Stable signature of a stack's component patch — {@code ""} for a plain, unmodified item.
     *
     * <p>Only the patch is hashed (not the item's prototype defaults), and FNV-1a is used rather
     * than {@code hashCode()} because this value is persisted as a price-history key.
     *
     * <p>The patch is serialised through its codec: NBT prints compound keys sorted, so equal
     * patches always give equal text. The values' own {@code toString()} is only a fallback —
     * a component class without one prints its identity hash, which would make every stack
     * (and every restart) a new stock.
     */
    private static String variantOf(ItemStack stack, HolderLookup.Provider registries) {
        if (!MarketConfig.splitByComponents()) return "";
        DataComponentPatch patch = stack.getComponentsPatch();
        if (patch.isEmpty()) return "";

        String text = DataComponentPatch.CODEC
                .encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), patch)
                .result()
                .map(Tag::toString)
                .orElseGet(() -> {
                    List<String> parts = new ArrayList<>();
                    for (var entry : patch.entrySet()) {
                        parts.add(entry.getKey() + "=" + entry.getValue().orElse(null));
                    }
                    Collections.sort(parts);
                    return String.join(";", parts);
                });
        return Long.toHexString(fnv1a(text));
    }

    private static long fnv1a(String text) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
