package by.deokma.stockmarket.neoforge.market;

import by.deokma.stockmarket.config.MarketConfig;
import by.deokma.stockmarket.market.MarketRegistry;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class MarketEvents {

    private static int tickCounter = 0;

    private MarketEvents() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(MarketEvents::onServerTick);
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        // Interval is read every tick so a config reload takes effect without a restart.
        if (++tickCounter >= MarketConfig.snapshotIntervalTicks()) {
            tickCounter = 0;
            MarketRegistry.snapshot(event.getServer());
        }
    }
}
