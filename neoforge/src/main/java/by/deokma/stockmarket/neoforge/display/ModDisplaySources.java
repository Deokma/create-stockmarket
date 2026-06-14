package by.deokma.stockmarket.neoforge.display;

import by.deokma.stockmarket.CreateStockMarket;
import by.deokma.stockmarket.neoforge.ModBlocks;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.registry.CreateRegistries;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.function.Supplier;

/**
 * Registers Create Display Link sources for the Market Terminal and binds them to the block,
 * so a Display Link can read live market data onto signs/boards/nixie tubes.
 *
 * <p>Two generic source types ({@link MarketValueListSource}, {@link MarketStatSource}) are
 * instantiated once per criterion — each registration name becomes the source id and its
 * translation key ({@code stockmarket.display_source.<name>}).
 */
public final class ModDisplaySources {

    public static final DeferredRegister<DisplaySource> DISPLAY_SOURCES =
            DeferredRegister.create(CreateRegistries.DISPLAY_SOURCE, CreateStockMarket.MOD_ID);

    // ── Leaderboards (multi-row) ──────────────────────────────────────────────
    public static final Supplier<MarketValueListSource> TOP_SELLERS =
            DISPLAY_SOURCES.register("top_sellers", () -> new MarketValueListSource(MarketDisplayData::topSellers));
    public static final Supplier<MarketValueListSource> TOP_SHOPS =
            DISPLAY_SOURCES.register("top_shops", () -> new MarketValueListSource(MarketDisplayData::topShops));
    public static final Supplier<MarketValueListSource> MOST_TRADED =
            DISPLAY_SOURCES.register("most_traded", () -> new MarketValueListSource(MarketDisplayData::mostTraded));

    // ── Scalar stats (single line) ────────────────────────────────────────────
    public static final Supplier<MarketStatSource> SHOP_COUNT =
            DISPLAY_SOURCES.register("shop_count", () -> new MarketStatSource(MarketDisplayData::shopCount));
    public static final Supplier<MarketStatSource> SELLER_COUNT =
            DISPLAY_SOURCES.register("seller_count", () -> new MarketStatSource(MarketDisplayData::sellerCount));
    public static final Supplier<MarketStatSource> TOTAL_SALES =
            DISPLAY_SOURCES.register("total_sales", () -> new MarketStatSource(MarketDisplayData::totalSales));

    private static final List<Supplier<? extends DisplaySource>> ALL = List.of(
            TOP_SELLERS, TOP_SHOPS, MOST_TRADED,
            SHOP_COUNT, SELLER_COUNT, TOTAL_SALES);

    public static void register(IEventBus modEventBus) {
        DISPLAY_SOURCES.register(modEventBus);
    }

    /**
     * Associates every registered source with the Market Terminal block.
     * Call once after registries are populated (e.g. in common setup).
     */
    public static void bindToBlock() {
        Block terminal = ModBlocks.MARKET_TERMINAL.get();
        for (Supplier<? extends DisplaySource> source : ALL) {
            DisplaySource.BY_BLOCK.add(terminal, source.get());
        }
    }

    private ModDisplaySources() {
    }
}
