package by.deokma.stockmarket.neoforge.display;

import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.source.SingleLineDisplaySource;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;

import java.util.function.ToLongFunction;

/**
 * Generic single-line Display Link source — one registered instance per scalar stat
 * (shop count, seller count, …). The {@code counter} runs server-side.
 */
public class MarketStatSource extends SingleLineDisplaySource {

    private final ToLongFunction<MinecraftServer> counter;

    public MarketStatSource(ToLongFunction<MinecraftServer> counter) {
        this.counter = counter;
    }

    @Override
    protected MutableComponent provideLine(DisplayLinkContext context, DisplayTargetStats stats) {
        MinecraftServer server = context.level().getServer();
        if (server == null) return Component.literal("-"); // client-side preview has no server data
        return Component.literal(Long.toString(counter.applyAsLong(server)));
    }

    @Override
    protected boolean allowsLabeling(DisplayLinkContext context) {
        return true;
    }
}
