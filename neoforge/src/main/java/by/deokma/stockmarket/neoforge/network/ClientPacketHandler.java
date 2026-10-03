package by.deokma.stockmarket.neoforge.network;

import by.deokma.stockmarket.market.MarketData;
import by.deokma.stockmarket.market.MarketEntry;
import by.deokma.stockmarket.market.TradeStatsData;
import by.deokma.stockmarket.neoforge.client.ShopListScreen;
import by.deokma.stockmarket.neoforge.client.StockMarketScreen;
import by.deokma.stockmarket.shop.ShopEntry;
import by.deokma.stockmarket.shop.ShopListData;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * All client-side packet handling logic lives here.
 * This class is only loaded on the CLIENT dist — never on the dedicated server.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientPacketHandler {

    private ClientPacketHandler() {}

    public static void handleShopList(List<ShopEntry> entries) {
        ShopListData.set(entries);
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof StockMarketScreen sms) {
            sms.refreshShopEntries();
        } else if (mc.screen instanceof ShopListScreen screen) {
            screen.refreshEntries();
        }
    }

    public static void handleOpenShopList() {
        Minecraft.getInstance().setScreen(new ShopListScreen());
    }

    public static void handleMarketData(List<MarketEntry> entries, int hotVolume) {
        MarketData.set(entries);
        MarketData.setHotVolume(hotVolume);
        MarketData.setLoading(false);
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof StockMarketScreen screen) {
            screen.refreshEntries();
        }
    }

    public static void handleOpenStockMarket() {
        Minecraft.getInstance().setScreen(new StockMarketScreen());
    }

    public static void handleTradeStats(java.util.List<java.util.Map.Entry<String, Long>> topSellers) {
        TradeStatsData.set(topSellers);
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof StockMarketScreen screen) {
            screen.refreshTopSellers();
        }
    }
}
