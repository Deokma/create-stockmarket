package by.deokma.stockmarket.market;

import net.minecraft.server.MinecraftServer;

import java.util.List;

public interface IMarketRegistry {
    List<MarketEntry> buildEntries(MinecraftServer server);
    void takeSnapshot(MinecraftServer server);
}
