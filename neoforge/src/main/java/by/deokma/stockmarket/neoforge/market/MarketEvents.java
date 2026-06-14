package by.deokma.stockmarket.neoforge.market;

import by.deokma.stockmarket.market.MarketRegistry;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class MarketEvents {

    private static final int SNAPSHOT_INTERVAL = 12000; // 600 seconds at 20 TPS
    private static int tickCounter = 0;

    private MarketEvents() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(MarketEvents::onServerTick);
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter >= SNAPSHOT_INTERVAL) {
            tickCounter = 0;
            MarketRegistry.snapshot(event.getServer());
        }
    }
}
