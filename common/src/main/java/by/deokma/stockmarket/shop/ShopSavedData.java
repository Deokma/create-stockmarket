package by.deokma.stockmarket.shop;

import by.deokma.stockmarket.util.ItemStackPersistence;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists the full shop list across server restarts.
 * Stored in the overworld data directory as "stockmarket_shops.dat".
 *
 * The in-memory map is the authoritative source; loaded chunks update it,
 * and it is flushed to disk automatically by the SavedData system.
 *
 * {@link SavedData} is a vanilla Minecraft class — available on both NeoForge and Fabric.
 */
public class ShopSavedData extends SavedData {

    public static final String NAME = "stockmarket_shops";
    private static final Logger LOGGER = LogManager.getLogger("stockmarket");

    /** Key → ShopEntry (key = "dim|x,y,z" or "dim|x,y,z#offerIndex") */
    private final Map<String, ShopEntry> shops = new ConcurrentHashMap<>();

    // ── Factory ───────────────────────────────────────────────────────────────

    public static ShopSavedData getOrCreate(MinecraftServer server) {
        DimensionDataStorage storage = server.overworld().getDataStorage();
        return storage.computeIfAbsent(
                new SavedData.Factory<>(
                        ShopSavedData::new,
                        (tag, reg) -> ShopSavedData.load(tag, reg, server),
                        null
                ),
                NAME
        );
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void put(String key, ShopEntry entry) {
        shops.put(key, entry);
        setDirty();
    }

    public void remove(String key) {
        if (shops.remove(key) != null) setDirty();
    }

    /** Removes entry for {@code baseKey} and all TableCloth multi-offer keys {@code baseKey#0}, {@code baseKey#1}, … */
    public void removeByBaseKey(String baseKey) {
        boolean removed = shops.keySet().removeIf(k -> k.equals(baseKey) || k.startsWith(baseKey + "#"));
        if (removed) setDirty();
    }

    public List<ShopEntry> getAll() {
        return new ArrayList<>(shops.values());
    }

    public void clear() {
        shops.clear();
        setDirty();
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<String, ShopEntry> e : shops.entrySet()) {
            try {
                CompoundTag entry = new CompoundTag();
                entry.putString("key", e.getKey());
                ShopEntry s = e.getValue();
                entry.putInt("x",   s.pos().getX());
                entry.putInt("y",   s.pos().getY());
                entry.putInt("z",   s.pos().getZ());
                entry.putString("dim",       s.dimensionId());
                entry.putString("owner",     s.ownerName());
                entry.putString("ownerUuid", s.ownerUuid().toString());
                entry.putString("mode",      s.mode());
                entry.putString("shopType",  s.shopType());
                entry.putInt("offerIndex",   s.offerIndex());
                entry.putInt("price",        s.totalPriceInSpurs());
                if (!s.sellingItem().isEmpty())
                    ItemStackPersistence.writeIntoTag(entry, "sellingItem", s.sellingItem(), registries);
                if (!s.priceItem().isEmpty())
                    ItemStackPersistence.writeIntoTag(entry, "priceItem", s.priceItem(), registries);
                list.add(entry);
            } catch (Exception ex) {
                LOGGER.debug("[ShopSavedData] save entry failed: {}", ex.getMessage());
            }
        }
        tag.put("shops", list);
        LOGGER.debug("[ShopSavedData] saved {} shops", shops.size());
        return tag;
    }

    public static ShopSavedData load(CompoundTag tag, HolderLookup.Provider registries,
                                     MinecraftServer server) {
        ShopSavedData data = new ShopSavedData();
        ListTag list = tag.getList("shops", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            try {
                CompoundTag e   = list.getCompound(i);
                String key      = e.getString("key");
                BlockPos pos    = new BlockPos(e.getInt("x"), e.getInt("y"), e.getInt("z"));
                String dim      = e.getString("dim");
                String owner    = e.getString("owner");
                UUID ownerUuid  = UUID.fromString(e.getString("ownerUuid"));
                String mode     = e.getString("mode");
                String shopType = e.getString("shopType");
                int offerIndex  = e.contains("offerIndex") ? e.getInt("offerIndex") : -1;
                int price       = e.getInt("price");

                ItemStack selling   = ItemStackPersistence.readFromTag(e, "sellingItem", registries);
                ItemStack priceItem = ItemStackPersistence.readFromTag(e, "priceItem", registries);

                data.shops.put(key, new ShopEntry(pos, dim, selling, price,
                        priceItem, ownerUuid, owner, mode, shopType, offerIndex));
            } catch (Exception ex) {
                LOGGER.debug("[ShopSavedData] load entry failed: {}", ex.getMessage());
            }
        }
        LOGGER.info("[ShopSavedData] loaded {} shops from disk", data.shops.size());
        return data;
    }
}
