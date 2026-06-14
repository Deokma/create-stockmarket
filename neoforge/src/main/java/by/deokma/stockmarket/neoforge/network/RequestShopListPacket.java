package by.deokma.stockmarket.neoforge.network;

import by.deokma.stockmarket.shop.ShopEntry;
import by.deokma.stockmarket.shop.VendorRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/** Sent client → server to request the shop list. */
public record RequestShopListPacket() implements CustomPacketPayload {

    public static final Type<RequestShopListPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("stockmarket", "request_shop_list"));

    public static final StreamCodec<FriendlyByteBuf, RequestShopListPacket> CODEC =
            StreamCodec.unit(new RequestShopListPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(RequestShopListPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            // Refresh loaded chunks to pick up any recent changes, then send all known shops
            VendorRegistry.refreshLoaded(player.server);
            List<ShopEntry> entries = VendorRegistry.getAll();
            NetworkHandler.sendToPlayer(player, new ShopListPacket(entries));
        });
    }
}
