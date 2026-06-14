package by.deokma.stockmarket.neoforge.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Sent client → server to request the full trade stats leaderboard. */
public record RequestTradeStatsPacket() implements CustomPacketPayload {

    public static final Type<RequestTradeStatsPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("stockmarket", "request_trade_stats"));

    public static final StreamCodec<FriendlyByteBuf, RequestTradeStatsPacket> CODEC =
            StreamCodec.unit(new RequestTradeStatsPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(RequestTradeStatsPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            var stats = by.deokma.stockmarket.market.TradeStatsSavedData
                    .getOrCreate(player.server);
            // Send all entries — no limit
            NetworkHandler.sendToPlayer(player, new TradeStatsPacket(stats.getTopSellers(Integer.MAX_VALUE)));
        });
    }
}
