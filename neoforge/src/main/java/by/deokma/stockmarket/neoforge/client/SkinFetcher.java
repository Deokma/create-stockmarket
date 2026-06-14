package by.deokma.stockmarket.neoforge.client;

import com.mojang.authlib.GameProfile;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton that resolves player names to skin {@link ResourceLocation}s.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>In-memory session cache (instant, no I/O)</li>
 *   <li>Minecraft's {@code SkinManager} via {@code getOrLoad} (async, background thread)</li>
 *   <li>Fallback (Steve) texture while loading or on failure</li>
 * </ol>
 *
 * <p>All network I/O is performed on a background thread via the
 * {@code CompletableFuture} returned by {@code SkinManager.getOrLoad}.
 * The render thread only reads from {@link ConcurrentHashMap}-backed collections
 * and never blocks.
 *
 * <p>Failed names are never retried within the same game session.
 * Call {@link #clear()} on world disconnect to free memory.
 */
public final class SkinFetcher {

    /** Shared singleton instance. */
    public static final SkinFetcher INSTANCE = new SkinFetcher();

    // ── Fallback texture ──────────────────────────────────────────────────────
    private static final ResourceLocation FALLBACK;

    static {
        ResourceLocation fb = null;
        try {
            fb = DefaultPlayerSkin.getDefaultTexture();
        } catch (Exception ignored) {}
        FALLBACK = fb;
    }

    // ── Session state (all ConcurrentHashMap-backed, safe for render thread) ──
    /** Resolved textures keyed by cache key (player name, or UUID string when no name). */
    private final Map<String, ResourceLocation> cache      = new ConcurrentHashMap<>();
    /** Keys with an in-flight fetch. */
    private final Set<String>                   pending    = ConcurrentHashMap.newKeySet();
    /** Keys that failed this session — no retry. */
    private final Set<String>                   failed     = ConcurrentHashMap.newKeySet();
    /** Whether each resolved skin uses the legacy 64×32 format. */
    private final Map<String, Boolean>          legacyFlags = new ConcurrentHashMap<>();

    private SkinFetcher() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the skin texture for the given player name, or the fallback
     * texture if the skin is not yet available.
     *
     * <p>Initiates an async fetch if the name is not cached and not already pending.
     *
     * @param playerName case-sensitive Minecraft player name; may be null
     * @return {@link ResourceLocation} of the skin texture, never {@code null}
     *         (falls back to Steve if the fallback itself is null, callers must guard)
     */
    public ResourceLocation getTexture(String playerName) {
        return getTexture(null, playerName);
    }

    /**
     * Returns the skin texture for the given player, resolved by the real account
     * {@code uuid} when available (falls back to the name otherwise).
     *
     * <p>Passing the real UUID is what makes Mojang return the player's actual skin
     * instead of the default Steve/Alex head.
     *
     * @param uuid       real account UUID, or {@code null} if unknown
     * @param playerName case-sensitive Minecraft player name; may be null
     */
    public ResourceLocation getTexture(UUID uuid, String playerName) {
        String key = cacheKey(uuid, playerName);
        if (key == null) return fallback();
        ResourceLocation cached = cache.get(key);
        if (cached != null) return cached;
        if (failed.contains(key))   return fallback();
        if (pending.contains(key))  return fallback();
        startFetch(key, uuid, playerName);
        return fallback();
    }

    /**
     * Returns {@code true} if the resolved skin for this player uses the
     * legacy 64×32 format (no hat overlay).
     */
    public boolean isLegacy(String playerName) {
        return isLegacy(null, playerName);
    }

    public boolean isLegacy(UUID uuid, String playerName) {
        String key = cacheKey(uuid, playerName);
        return key != null && legacyFlags.getOrDefault(key, false);
    }


    // ── Private implementation ────────────────────────────────────────────────

    private ResourceLocation fallback() {
        return FALLBACK;
    }

    private static String cacheKey(UUID uuid, String playerName) {
        if (playerName != null && !playerName.isBlank()) return playerName;
        if (uuid != null) return uuid.toString();
        return null;
    }

    private void startFetch(String key, UUID uuid, String playerName) {
        pending.add(key);
        try {
            // 1) Online player? Use the live skin from the tab list — instant and accurate.
            PlayerSkin live = liveSkin(uuid, playerName);
            if (live != null) {
                acceptSkin(key, live);
                return;
            }

            // 2) Offline: resolve via the real account UUID so Mojang returns the right skin.
            if (uuid != null) {
                resolveByUuid(key, uuid, playerName);
                return;
            }

            // 3) No UUID and not online — nothing reliable to resolve; show the fallback.
            onFetchFailure(key);
        } catch (Exception ex) {
            onFetchFailure(key);
        }
    }

    /** Looks up a skin from the connected-player list (works only for online players). */
    private static PlayerSkin liveSkin(UUID uuid, String playerName) {
        ClientPacketListener conn = Minecraft.getInstance().getConnection();
        if (conn == null) return null;
        PlayerInfo info = null;
        if (uuid != null) info = conn.getPlayerInfo(uuid);
        if (info == null && playerName != null && !playerName.isBlank()) {
            info = conn.getPlayerInfo(playerName);
        }
        return info != null ? info.getSkin() : null;
    }

    /**
     * Fetches the textured {@link GameProfile} for the real UUID off-thread (blocking HTTP),
     * then registers it with the {@code SkinManager} on the main thread.
     */
    private void resolveByUuid(String key, UUID uuid, String playerName) {
        var sessionService = Minecraft.getInstance().getMinecraftSessionService();
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture
                .supplyAsync(() -> {
                    var result = sessionService.fetchProfile(uuid, false);
                    return result != null ? result.profile()
                            : new GameProfile(uuid, playerName != null ? playerName : "");
                }, Util.backgroundExecutor())
                .thenComposeAsync(profile -> mc.getSkinManager().getOrLoad(profile), mc)
                .thenAccept(skin -> {
                    if (skin != null) acceptSkin(key, skin);
                    else onFetchFailure(key);
                })
                .exceptionally(ex -> {
                    onFetchFailure(key);
                    return null;
                });
    }

    private void acceptSkin(String key, PlayerSkin skin) {
        legacyFlags.put(key, false); // modern skins; hat overlay always attempted
        onFetchSuccess(key, skin.texture());
    }

    private void onFetchSuccess(String key, ResourceLocation texture) {
        // Discard if clear() was called while the fetch was in flight.
        if (cache.isEmpty() && !pending.contains(key)) {
            pending.remove(key);
            return;
        }
        cache.put(key, texture);
        pending.remove(key);
    }

    private void onFetchFailure(String key) {
        failed.add(key);
        pending.remove(key);
    }
}
