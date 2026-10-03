package by.deokma.stockmarket.market;

import by.deokma.stockmarket.config.MarketConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

import java.util.*;

/**
 * Persists price history snapshots across server restarts.
 * Stored as "stockmarket_price_history.dat" in the overworld data directory.
 *
 * {@link SavedData} is a vanilla Minecraft class — available on both NeoForge and Fabric.
 */
public class PriceHistorySavedData extends SavedData {

    public static final String NAME = "stockmarket_price_history";

    /** Retained snapshots per stock when the config says nothing else. */
    public static final int DEFAULT_MAX_SNAPSHOTS = 144;

    private final Map<String, List<Integer>> history = new HashMap<>();

    /** Epoch millis of the last snapshot per stock — drives {@link #pruneStale(int)}. */
    private final Map<String, Long> lastSeen = new HashMap<>();

    // ── Factory ───────────────────────────────────────────────────────────────

    public static PriceHistorySavedData getOrCreate(MinecraftServer server) {
        DimensionDataStorage storage = server.overworld().getDataStorage();
        return storage.computeIfAbsent(
                new SavedData.Factory<>(
                        PriceHistorySavedData::new,
                        (tag, registries) -> PriceHistorySavedData.load(tag, registries),
                        null
                ),
                NAME
        );
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void addSnapshot(String stockKey, int avgPrice) {
        List<Integer> list = history.computeIfAbsent(stockKey, k -> new ArrayList<>());
        list.add(avgPrice);

        int max = Math.max(2, MarketConfig.maxSnapshots());
        while (list.size() > max) {
            list.remove(0);
        }
        lastSeen.put(stockKey, System.currentTimeMillis());
        setDirty();
    }

    public List<Integer> getHistory(String stockKey, int count) {
        List<Integer> list = history.getOrDefault(stockKey, List.of());
        int from = Math.max(0, list.size() - count);
        return List.copyOf(list.subList(from, list.size()));
    }

    public List<Integer> getHistory(String stockKey) {
        return List.copyOf(history.getOrDefault(stockKey, List.of()));
    }

    /**
     * Drops history for stocks that have not been snapshotted for {@code retentionDays}.
     * Without this the map keeps a row for every item ever traded, including variants of
     * shops that were removed long ago. A non-positive value disables pruning.
     */
    public void pruneStale(int retentionDays) {
        if (retentionDays <= 0) return;

        long cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L;
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Long> entry : lastSeen.entrySet()) {
            if (entry.getValue() < cutoff) expired.add(entry.getKey());
        }
        if (expired.isEmpty()) return;

        for (String key : expired) {
            history.remove(key);
            lastSeen.remove(key);
        }
        setDirty();
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag items = new CompoundTag();
        CompoundTag seen = new CompoundTag();
        for (Map.Entry<String, List<Integer>> entry : history.entrySet()) {
            ListTag listTag = new ListTag();
            for (int price : entry.getValue()) {
                listTag.add(IntTag.valueOf(price));
            }
            items.put(entry.getKey(), listTag);

            Long ts = lastSeen.get(entry.getKey());
            if (ts != null) seen.putLong(entry.getKey(), ts);
        }
        tag.put("items", items);
        tag.put("lastSeen", seen);
        return tag;
    }

    public static PriceHistorySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        PriceHistorySavedData data = new PriceHistorySavedData();
        CompoundTag items = tag.getCompound("items");
        CompoundTag seen = tag.getCompound("lastSeen");
        long now = System.currentTimeMillis();

        for (String key : items.getAllKeys()) {
            ListTag listTag = items.getList(key, Tag.TAG_INT);
            List<Integer> prices = new ArrayList<>(listTag.size());
            for (int i = 0; i < listTag.size(); i++) {
                prices.add(((IntTag) listTag.get(i)).getAsInt());
            }
            data.history.put(key, prices);
            // Files written before lastSeen existed start their retention clock now,
            // so an upgrade never wipes history on the first snapshot.
            data.lastSeen.put(key, seen.contains(key) ? seen.getLong(key) : now);
        }
        return data;
    }
}
