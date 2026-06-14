package by.deokma.stockmarket.platform;

import by.deokma.stockmarket.shop.ShopSavedData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Platform-specific shop indexing operations needed by
 * {@link by.deokma.stockmarket.shop.VendorRegistry}.
 *
 * <p>Common code has <b>zero</b> direct references to Create or any other mod's classes —
 * all mod-specific logic lives here. This makes the common module compile cleanly on every
 * loader, even when mod jars are absent from the compile classpath (e.g. Fabric without
 * Create-Fabric).
 *
 * <p>NeoForge provides {@code NeoForgeVendorHelper}; Fabric will provide its own.
 * Injected at startup via {@link by.deokma.stockmarket.shop.VendorRegistry#setPlatformHelper}.
 */
public interface IPlatformVendorHelper {

    /**
     * Returns {@code true} if the given block entity is a shop that should be indexed
     * (TableCloth, Numismatics Vendor, Tradeworks shelf, …).
     * Used by the stale-entry pruning logic in VendorRegistry.
     */
    boolean isShopEntity(BlockEntity be);

    /**
     * Attempts to index the given block entity into the data store.
     * The implementation is responsible for all serialisation, key generation, and
     * name-cache updates. Called for every block entity in every loaded chunk.
     *
     * @return {@code true} if the entity was recognised and indexing was attempted
     */
    boolean tryIndex(ServerLevel level, BlockEntity be,
                     ShopSavedData store, Map<UUID, String> nameCache);

    /**
     * Iterates over every currently ticking chunk in every loaded level, invoking
     * {@code action} for each (level, chunk) pair.
     * The iteration strategy is loader-specific:
     * <ul>
     *   <li>NeoForge — {@code ChunkMap.getChunks()} via reflection</li>
     *   <li>Fabric — Fabric API chunk utilities</li>
     * </ul>
     */
    void forEachLoadedChunk(MinecraftServer server, BiConsumer<ServerLevel, LevelChunk> action);
}
