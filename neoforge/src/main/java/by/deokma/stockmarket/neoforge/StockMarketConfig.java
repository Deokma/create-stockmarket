package by.deokma.stockmarket.neoforge;

import by.deokma.stockmarket.config.MarketConfig;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NeoForge config file for the market, written to {@code <world>/serverconfig/stockmarket-server.toml}.
 *
 * <p>Parsed values are pushed into the loader-agnostic {@link MarketConfig} holder, so the
 * common module never has to know a config system exists.
 */
public final class StockMarketConfig {

    private static final Logger LOGGER = LogManager.getLogger("stockmarket");

    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.IntValue SNAPSHOT_INTERVAL_TICKS;
    private static final ModConfigSpec.IntValue MAX_SNAPSHOTS;
    private static final ModConfigSpec.IntValue TREND_THRESHOLD;
    private static final ModConfigSpec.IntValue TREND_WINDOW;
    private static final ModConfigSpec.IntValue HOT_VOLUME;
    private static final ModConfigSpec.BooleanValue SPLIT_BY_COMPONENTS;
    private static final ModConfigSpec.IntValue HISTORY_RETENTION_DAYS;
    private static final ModConfigSpec.IntValue SHOP_LIST_PERMISSION_LEVEL;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> STOCK_NAMES;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Market aggregation and price history").push("market");

        SNAPSHOT_INTERVAL_TICKS = builder
                .comment("Ticks between price history snapshots (20 ticks = 1 second).",
                         "Default 12000 = 10 minutes.")
                .defineInRange("snapshotIntervalTicks", MarketConfig.DEFAULTS.snapshotIntervalTicks(),
                        200, 1_728_000);

        MAX_SNAPSHOTS = builder
                .comment("Snapshots kept per stock. At the default interval, 144 covers 24 hours.")
                .defineInRange("maxSnapshots", MarketConfig.DEFAULTS.maxSnapshots(), 2, 4096);

        TREND_THRESHOLD = builder
                .comment("How many spurs the average price must move before the trend arrow",
                         "changes from stable to rising/falling.")
                .defineInRange("trendThreshold", MarketConfig.DEFAULTS.trendThreshold(), 0, 100_000);

        TREND_WINDOW = builder
                .comment("How many earlier snapshots are averaged into the trend baseline.",
                         "Higher values make the arrow steadier and slower to react.")
                .defineInRange("trendWindow", MarketConfig.DEFAULTS.trendWindow(), 1, 64);

        HOT_VOLUME = builder
                .comment("Combined sell+buy listings at which a row is highlighted as high activity.")
                .defineInRange("hotVolume", MarketConfig.DEFAULTS.hotVolume(), 1, 10_000);

        SPLIT_BY_COMPONENTS = builder
                .comment("List item variants as separate stocks.",
                         "true  — two differently enchanted books are two stocks with their own prices.",
                         "false — every variant of an item collapses into one averaged row.")
                .define("splitByComponents", MarketConfig.DEFAULTS.splitByComponents());

        HISTORY_RETENTION_DAYS = builder
                .comment("Discard price history for stocks nobody has traded for this many days.",
                         "Set to 0 to keep history forever.")
                .defineInRange("historyRetentionDays", MarketConfig.DEFAULTS.historyRetentionDays(), 0, 3650);

        builder.pop();

        builder.comment("Command access").push("commands");

        SHOP_LIST_PERMISSION_LEVEL = builder
                .comment("Vanilla permission level required to run /shoplist.",
                         "0 = everyone. Raise it if shop coordinates should not be public.")
                .defineInRange("shopListPermissionLevel",
                        MarketConfig.DEFAULTS.shopListPermissionLevel(), 0, 4);

        builder.pop();

        builder.comment("Custom stock names").push("naming");

        STOCK_NAMES = builder
                .comment("Rename stocks on the market screen, one \"<item id>=<name>\" per entry.",
                         "Example: [\"minecraft:diamond=DIA\", \"create:brass_ingot=BRASS\"]",
                         "Applies to every variant of the item; leave empty to use item names.")
                .defineListAllowEmpty("stockNames", List.<String>of(),
                        () -> "minecraft:diamond=DIA",
                        entry -> entry instanceof String s && s.indexOf('=') > 0);

        builder.pop();

        SPEC = builder.build();
    }

    private StockMarketConfig() {}

    // ── Wiring ────────────────────────────────────────────────────────────────

    public static void onLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) apply();
    }

    public static void onReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) apply();
    }

    private static void apply() {
        MarketConfig.set(new MarketConfig.Values(
                SNAPSHOT_INTERVAL_TICKS.get(),
                MAX_SNAPSHOTS.get(),
                TREND_THRESHOLD.get(),
                TREND_WINDOW.get(),
                HOT_VOLUME.get(),
                SPLIT_BY_COMPONENTS.get(),
                HISTORY_RETENTION_DAYS.get(),
                SHOP_LIST_PERMISSION_LEVEL.get(),
                parseStockNames(STOCK_NAMES.get())
        ));
    }

    /** Turns the {@code "<item id>=<name>"} lines into a lookup map, skipping malformed rows. */
    private static Map<String, String> parseStockNames(List<? extends String> raw) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : raw) {
            if (line == null) continue;
            int split = line.indexOf('=');
            if (split <= 0) {
                LOGGER.warn("[StockMarketConfig] Ignoring stockNames entry without '=': {}", line);
                continue;
            }
            String itemId = line.substring(0, split).trim();
            String name   = line.substring(split + 1).trim();
            if (itemId.isEmpty() || name.isEmpty()) {
                LOGGER.warn("[StockMarketConfig] Ignoring stockNames entry with an empty side: {}", line);
                continue;
            }
            if (ResourceLocation.tryParse(itemId) == null) {
                LOGGER.warn("[StockMarketConfig] Ignoring stockNames entry with an invalid item id: {}", itemId);
                continue;
            }
            result.put(itemId, name);
        }
        return result;
    }
}
