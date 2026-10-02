package by.deokma.stockmarket.neoforge.network;

import by.deokma.stockmarket.market.MarketEntry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Sent server → client with the full market entry list.
 *
 * {@code hotVolume} rides along because the threshold is a server config value the
 * client has no way to read on its own.
 */
public record MarketPacket(List<MarketEntry> entries, int hotVolume) implements CustomPacketPayload {

    public static final Type<MarketPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("stockmarket", "market_data"));

    public static final StreamCodec<FriendlyByteBuf, MarketPacket> CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeInt(pkt.entries.size());
                for (MarketEntry e : pkt.entries) e.write(buf);
                buf.writeInt(pkt.hotVolume);
            },
            buf -> {
                int size = buf.readInt();
                List<MarketEntry> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) list.add(MarketEntry.read(buf));
                return new MarketPacket(list, buf.readInt());
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(MarketPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientPacketHandler.handleMarketData(pkt.entries(), pkt.hotVolume()));
    }
}
