package by.deokma.stockmarket.market;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Persists trade statistics: how many times each shop owner's shops were purchased from.
 * Stored as "stockmarket_trade_stats.dat" in the overworld data directory.
 *
 * {@link SavedData} is a vanilla Minecraft class — available on both NeoForge and Fabric.
 */
public class TradeStatsSavedData extends SavedData {

    public static final String NAME = "stockmarket_trade_stats";
    private static final Logger LOGGER = LogManager.getLogger("stockmarket");

    /** owner name → total sales count */
    private final Map<String, Long> salesByOwner = new LinkedHashMap<>();

    // ── Factory ───────────────────────────────────────────────────────────────

    public static TradeStatsSavedData getOrCreate(MinecraftServer server) {
        DimensionDataStorage storage = server.overworld().getDataStorage();
        return storage.computeIfAbsent(
                new SavedData.Factory<>(
                        TradeStatsSavedData::new,
                        (tag, reg) -> TradeStatsSavedData.load(tag, reg),
                        null
                ),
                NAME
        );
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Record a sale from the given owner's shop. */
    public void recordSale(String ownerName, int quantity) {
        salesByOwner.merge(ownerName, (long) quantity, Long::sum);
        setDirty();
    }

    /** Returns top N owners sorted by sales count descending. */
    public List<Map.Entry<String, Long>> getTopSellers(int limit) {
        return salesByOwner.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag sales = new CompoundTag();
        for (Map.Entry<String, Long> e : salesByOwner.entrySet()) {
            sales.putLong(e.getKey(), e.getValue());
        }
        tag.put("sales", sales);
        return tag;
    }

    public static TradeStatsSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        TradeStatsSavedData data = new TradeStatsSavedData();
        CompoundTag sales = tag.getCompound("sales");
        for (String key : sales.getAllKeys()) {
            data.salesByOwner.put(key, sales.getLong(key));
        }
        LOGGER.info("[TradeStatsSavedData] Loaded sales stats for {} owners", data.salesByOwner.size());
        return data;
    }
}
