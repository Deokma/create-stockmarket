package by.deokma.stockmarket.config;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * Loader-agnostic holder for the admin-facing market settings.
 *
 * <p>The common module must not depend on any loader's config system, so the
 * platform entrypoint parses its own config file and pushes the result here via
 * {@link #set(Values)} — the same injection pattern used by
 * {@code VendorRegistry.setPlatformHelper} and friends.
 *
 * <p>Until a platform pushes anything, {@link #DEFAULTS} applies. Those defaults
 * are exactly the constants that used to be hardcoded, so behaviour is unchanged
 * for a server that never touches the config file.
 */
public final class MarketConfig {

    private MarketConfig() {}

    /**
     * @param snapshotIntervalTicks   ticks between price-history snapshots
     * @param maxSnapshots            snapshots retained per stock
     * @param trendThreshold          spurs the price must move to count as rising/falling
     * @param trendWindow             how many earlier snapshots the trend baseline averages
     * @param hotVolume               trade volume at which a row is highlighted as "hot"
     * @param splitByComponents       list item variants (enchants, custom names) as separate stocks
     * @param historyRetentionDays    drop price history untouched for this many days (0 = keep forever)
     * @param shopListPermissionLevel vanilla permission level required to run {@code /shoplist}
     * @param nameOverrides           item id → custom stock name shown instead of the item name
     */
    public record Values(
            int snapshotIntervalTicks,
            int maxSnapshots,
            int trendThreshold,
            int trendWindow,
            int hotVolume,
            boolean splitByComponents,
            int historyRetentionDays,
            int shopListPermissionLevel,
            Map<String, String> nameOverrides
    ) {
        public Values {
            nameOverrides = Map.copyOf(nameOverrides);
        }
    }

    /** Mirrors the values that were hardcoded before the config existed. */
    public static final Values DEFAULTS =
            new Values(12000, 144, 1, 3, 5, true, 7, 0, Map.of());

    private static volatile Values current = DEFAULTS;

    /** Called by the platform whenever the config file is loaded or reloaded. */
    public static void set(Values values) {
        current = values == null ? DEFAULTS : values;
    }

    public static Values get() { return current; }

    // ── Convenience accessors ─────────────────────────────────────────────────

    public static int snapshotIntervalTicks()   { return current.snapshotIntervalTicks(); }
    public static int maxSnapshots()            { return current.maxSnapshots(); }
    public static int trendThreshold()          { return current.trendThreshold(); }
    public static int trendWindow()             { return current.trendWindow(); }
    public static int hotVolume()               { return current.hotVolume(); }
    public static boolean splitByComponents()   { return current.splitByComponents(); }
    public static int historyRetentionDays()    { return current.historyRetentionDays(); }
    public static int shopListPermissionLevel() { return current.shopListPermissionLevel(); }

    /**
     * Custom display name for an item, or {@code ""} when the item's own name should be used.
     * Overrides are keyed by plain item id, so every variant of an item shares one name.
     */
    public static String nameOverride(ResourceLocation itemId) {
        if (itemId == null) return "";
        return current.nameOverrides().getOrDefault(itemId.toString(), "");
    }
}
