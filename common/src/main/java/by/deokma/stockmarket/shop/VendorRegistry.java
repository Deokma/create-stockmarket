package by.deokma.stockmarket.shop;

import by.deokma.stockmarket.platform.IPlatformVendorHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side shop registry backed by {@link ShopSavedData}.
 *
 * <p>All shops (from loaded chunks) are written to SavedData so they persist
 * across server restarts. When a player requests the shop list they receive
 * ALL known shops, not just those in currently loaded chunks.
 *
 * <p>This class is fully platform-agnostic — it contains <b>no</b> direct references
 * to Create, Numismatics, or any other mod. All mod-specific operations are
 * delegated to the {@link IPlatformVendorHelper} injected at startup.
 */
public final class VendorRegistry {

    private static final Logger LOGGER = LogManager.getLogger("stockmarket");

    /** In-memory name cache — rebuilt from SavedData entries on load. */
    private static final Map<UUID, String> nameCache = new ConcurrentHashMap<>();

    /** Reference to the current world's SavedData. Set on server start, cleared on stop. */
    private static ShopSavedData savedData = null;

    /** Platform-specific helper (chunk iteration + all shop indexing). */
    private static IPlatformVendorHelper platformHelper = null;

    /** Guards the indexing-failure log so a broken shop type reports once, not per block. */
    private static boolean indexFailureLogged = false;

    /**
     * Minimum gap between full rescans triggered by players opening the shop list.
     * Each rescan walks every block entity in every loaded chunk on the server thread,
     * so letting each request trigger one made the list button a lag lever.
     */
    private static final long MIN_REQUEST_REFRESH_MS = 10_000;
    private static long lastRefreshMs = 0;

    private VendorRegistry() {}

    // ── Platform injection ────────────────────────────────────────────────────

    /** Called once at mod startup by the platform entrypoint. */
    public static void setPlatformHelper(IPlatformVendorHelper helper) {
        platformHelper = helper;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Call once when the server starts.
     * Loads persisted shops from disk and rescans all already-loaded chunks.
     */
    public static void onServerStart(MinecraftServer server) {
        savedData = ShopSavedData.getOrCreate(server);
        for (ShopEntry e : savedData.getAll()) {
            nameCache.put(e.ownerUuid(), e.ownerName());
        }
        LOGGER.info("[VendorRegistry] Loaded {} shops from disk", savedData.getAll().size());

        // Spawn chunks fire ChunkEvent.Load before ServerStartedEvent, so savedData
        // was null at that point — rescan now that it is ready.
        refreshLoaded(server);
        LOGGER.info("[VendorRegistry] Post-start refresh: {} shops indexed", savedData.getAll().size());
    }

    /** Call when the server stops / world unloads. */
    public static void onServerStop() {
        savedData = null;
        nameCache.clear();
        lastRefreshMs = 0;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns ALL known shops (loaded + persisted from previous sessions). */
    public static List<ShopEntry> getAll() {
        if (savedData == null) return List.of();
        return savedData.getAll();
    }

    public static void onChunkLoad(ServerLevel level, net.minecraft.world.level.chunk.LevelChunk chunk) {
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            tryIndex(level, be);
        }
    }

    public static void onBlockPlace(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) tryIndex(level, be);
    }

    public static void onBlockBreak(ServerLevel level, BlockPos pos) {
        if (savedData == null) return;
        savedData.removeByBaseKey(makeKey(level, pos));
    }

    /**
     * Re-scans all currently loaded chunks and then prunes any persisted entries
     * whose chunk is loaded but whose block no longer exists.
     */
    public static void refreshLoaded(MinecraftServer server) {
        lastRefreshMs = System.currentTimeMillis();
        if (platformHelper != null) {
            platformHelper.forEachLoadedChunk(server, (level, chunk) -> {
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    tryIndex(level, be);
                }
            });
        }
        pruneStaleEntries(server);
    }

    /**
     * {@link #refreshLoaded} for player-triggered requests: skipped when a rescan ran
     * recently. Chunk-load, place and break events keep the index current in between.
     */
    public static void refreshLoadedThrottled(MinecraftServer server) {
        if (System.currentTimeMillis() - lastRefreshMs < MIN_REQUEST_REFRESH_MS) return;
        refreshLoaded(server);
    }

    /** Clears only the in-memory name cache; persisted shops are kept on disk. */
    public static void clear() {
        nameCache.clear();
    }

    // ── Indexing ──────────────────────────────────────────────────────────────

    private static void tryIndex(ServerLevel level, BlockEntity be) {
        if (savedData == null || platformHelper == null) return;
        try {
            platformHelper.tryIndex(level, be, savedData, nameCache);
        } catch (Exception | LinkageError e) {
            // Indexing runs on the server thread from chunk-load and tick events. An exception
            // escaping here — e.g. a NoSuchMethodError after a shop mod changed its API —
            // crashes the server, and keeps crashing it every time that chunk loads.
            logIndexFailure(be, e);
        }
    }

    private static void logIndexFailure(BlockEntity be, Throwable e) {
        if (indexFailureLogged) {
            LOGGER.debug("[VendorRegistry] indexing {} at {} failed: {}",
                    be.getClass().getSimpleName(), be.getBlockPos(), e.toString());
            return;
        }
        indexFailureLogged = true;
        LOGGER.error("[VendorRegistry] Failed to index {} at {} — this shop type is likely "
                + "incompatible with the installed version of its mod. The server keeps running; "
                + "further failures log at DEBUG.", be.getClass().getName(), be.getBlockPos(), e);
    }

    // ── Stale entry pruning ───────────────────────────────────────────────────

    /**
     * Removes persisted entries whose chunk is currently loaded but the block
     * entity at that position is no longer a valid shop (broken block, removed mod, etc.).
     * Entries in unloaded chunks are left alone — verified once that chunk loads.
     */
    private static void pruneStaleEntries(MinecraftServer server) {
        if (savedData == null) return;

        Set<String> toRemove = new HashSet<>();

        for (ShopEntry entry : savedData.getAll()) {
            ResourceLocation dimId = ResourceLocation.tryParse(entry.dimensionId());
            if (dimId == null) {
                toRemove.add(entryBaseKey(entry));
                continue;
            }

            // Keyed lookup — scanning getAllLevels() per entry made pruning
            // O(shops x dimensions) on every refresh.
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimId));
            if (level == null) continue; // dimension not loaded — can't verify

            if (!level.isLoaded(entry.pos())) continue; // chunk not loaded — skip

            // Chunk is loaded: block must exist and be a recognised shop entity
            BlockEntity be = level.getBlockEntity(entry.pos());
            if (be == null || (platformHelper != null && !platformHelper.isShopEntity(be))) {
                toRemove.add(entryBaseKey(entry));
            }
        }

        for (String baseKey : toRemove) {
            savedData.removeByBaseKey(baseKey);
            LOGGER.info("[VendorRegistry] Pruned stale shop entry: {}", baseKey);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String makeKey(ServerLevel level, BlockPos pos) {
        return level.dimension().location() + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String entryBaseKey(ShopEntry entry) {
        BlockPos p = entry.pos();
        return entry.dimensionId() + "|" + p.getX() + "," + p.getY() + "," + p.getZ();
    }
}
