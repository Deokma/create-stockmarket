package by.deokma.stockmarket.neoforge.compat;

import by.deokma.stockmarket.market.TradeStatsSavedData;
import dev.ithundxr.createnumismatics.content.vendor.VendorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks Numismatics Vendor purchases by capturing stock before the interaction
 * and comparing on the next server tick — mirrors StockKeeperSaleTracker's approach.
 *
 * This class must ONLY be loaded when {@link NumismaticsCompat#isPresent()} is true.
 */
public final class VendorTransactionTracker {

    private static final Logger LOGGER = LogManager.getLogger("stockmarket");

    private record VendorSnapshot(BlockPos pos, String dimKey, int initialCount, UUID ownerUuid) {}

    /** Player UUID → state captured just before the vendor interaction. */
    private static final Map<UUID, VendorSnapshot> pendingChecks = new ConcurrentHashMap<>();

    private VendorTransactionTracker() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, VendorTransactionTracker::onInteractHigh);
        NeoForge.EVENT_BUS.addListener(VendorTransactionTracker::onServerTick);
    }

    /** Clears state on server stop. */
    public static void clear() {
        pendingChecks.clear();
    }

    // ── Event handlers ────────────────────────────────────────────────────────

    /**
     * Fires before the vendor processes the purchase.
     * Captures the current stock count so we can compare after the tick.
     */
    private static void onInteractHigh(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        BlockEntity be = level.getBlockEntity(event.getPos());
        if (!(be instanceof VendorBlockEntity vendor)) return;
        if (vendor.getMode() != VendorBlockEntity.Mode.SELL) return;

        int stock = readStockCount(vendor);
        if (stock < 0) return;

        UUID ownerUuid = readOwnerUuid(vendor, level.getServer());
        if (ownerUuid == null) return;

        String dimKey = level.dimension().location() + "|" + event.getPos().toShortString();
        pendingChecks.put(player.getUUID(),
                new VendorSnapshot(event.getPos(), dimKey, stock, ownerUuid));
    }

    /**
     * Fires on the tick after the interaction — by then BlockBehaviour.use() has run
     * and the vendor inventory has been updated.
     */
    private static void onServerTick(ServerTickEvent.Post event) {
        if (pendingChecks.isEmpty()) return;

        MinecraftServer server = event.getServer();
        var iterator = pendingChecks.entrySet().iterator();

        while (iterator.hasNext()) {
            var entry = iterator.next();
            iterator.remove();

            VendorSnapshot snapshot = entry.getValue();

            ServerLevel level = findLevel(server, snapshot.dimKey());
            if (level == null) continue;

            BlockEntity be = level.getBlockEntity(snapshot.pos());
            if (!(be instanceof VendorBlockEntity vendor)) continue;

            int newStock = readStockCount(vendor);
            if (newStock < 0 || newStock >= snapshot.initialCount()) continue;

            int sold = snapshot.initialCount() - newStock;
            String ownerName = resolveOwnerName(server, snapshot.ownerUuid());
            // Record 1 transaction per purchase, regardless of how many items
            // were in the stack — buying 64 blocks at once is still 1 sale.
            TradeStatsSavedData.getOrCreate(server).recordSale(ownerName, 1);

            LOGGER.debug("[VendorTracker] Sale at {} owner={} items={}",
                    snapshot.pos(), ownerName, sold);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Counts all items in the vendor's container that match the selling template.
     * Uses Container interface directly — avoids fragile NBT key guessing.
     */
    private static int readStockCount(VendorBlockEntity vendor) {
        try {
            ItemStack template = vendor.getSellingItem();
            if (template == null || template.isEmpty()) return -1;

            int total = 0;
            int size = vendor.getContainerSize();
            for (int i = 0; i < size; i++) {
                ItemStack stack = vendor.getItem(i);
                if (!stack.isEmpty() && ItemStack.isSameItem(stack, template)) {
                    total += stack.getCount();
                }
            }
            return total;
        } catch (Exception e) {
            return -1;
        }
    }

    private static UUID readOwnerUuid(VendorBlockEntity vendor, MinecraftServer server) {
        try {
            var tag = vendor.saveWithoutMetadata(server.registryAccess());
            if (tag.hasUUID("Owner")) return tag.getUUID("Owner");
            if (tag.hasUUID("owner")) return tag.getUUID("owner");
        } catch (Exception ignored) {}
        return null;
    }

    private static ServerLevel findLevel(MinecraftServer server, String dimKey) {
        for (ServerLevel level : server.getAllLevels()) {
            String key = level.dimension().location() + "|";
            if (dimKey.startsWith(key)) return level;
        }
        return null;
    }

    private static String resolveOwnerName(MinecraftServer server, UUID uuid) {
        var player = server.getPlayerList().getPlayer(uuid);
        if (player != null) return player.getName().getString();
        var cache = server.getProfileCache();
        if (cache != null) {
            var opt = cache.get(uuid);
            if (opt.isPresent() && opt.get().getName() != null) return opt.get().getName();
        }
        return uuid.toString().substring(0, 8);
    }
}
