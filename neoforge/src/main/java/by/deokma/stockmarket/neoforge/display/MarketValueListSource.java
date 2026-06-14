package by.deokma.stockmarket.neoforge.display;

import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.source.ValueListDisplaySource;
import net.createmod.catnip.data.IntAttached;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;

import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Generic multi-row Display Link source — one registered instance per leaderboard
 * (top sellers, most expensive, …). The data {@code provider} runs server-side.
 */
public class MarketValueListSource extends ValueListDisplaySource {

    private final Function<MinecraftServer, Stream<IntAttached<MutableComponent>>> provider;

    public MarketValueListSource(Function<MinecraftServer, Stream<IntAttached<MutableComponent>>> provider) {
        this.provider = provider;
    }

    @Override
    protected Stream<IntAttached<MutableComponent>> provideEntries(DisplayLinkContext context, int maxRows) {
        MinecraftServer server = context.level().getServer();
        if (server == null) return Stream.empty(); // client-side preview has no server data
        return provider.apply(server).limit(maxRows);
    }

    @Override
    protected boolean valueFirst() {
        return false; // label first, numeric value second
    }
}
