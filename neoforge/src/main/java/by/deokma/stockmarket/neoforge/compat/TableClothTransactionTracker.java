package by.deokma.stockmarket.neoforge.compat;

import by.deokma.stockmarket.market.TradeStatsSavedData;
import com.simibubi.create.content.logistics.tableCloth.ShoppingListItem;
import com.simibubi.create.content.logistics.tableCloth.TableClothBlockEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks Create TableCloth shop transactions.
 * <p>
 * Strategy A (primary) — Shopping list consumption:
 * When a player right-clicks any block while holding a ShoppingListItem,
 * we record the owner UUID from the list's shopOwner field BEFORE the
 * interaction, then check AFTER whether the item was consumed (stack shrank
 * or became empty). If so, a purchase happened.
 * <p>
 * Strategy B (fallback) — Stock count polling:
 * Called from VendorEvents every second. Compares current stock count with
 * the last known count; a decrease means a sale.
 */
public final class TableClothTransactionTracker {

    private static final Logger LOGGER = LogManager.getLogger("stockmarket");

    // ── Strategy B state ──────────────────────────────────────────────────────
    /**
     * Last known stock count per cloth offer: {@code dim|x,y,z#slot} (Vendor unused here).
     */
    private static final Map<String, Integer> lastKnownCount = new HashMap<>();

    private TableClothTransactionTracker() {
    }

    public static void register() {
        // Strategy A: intercept right-click with shopping list
        NeoForge.EVENT_BUS.addListener(TableClothTransactionTracker::onRightClickBlockPre);
        NeoForge.EVENT_BUS.addListener(TableClothTransactionTracker::onRightClickBlockPost);
    }

    // ── Strategy A ────────────────────────────────────────────────────────────

    /**
     * Snapshot of the shopping list item before the interaction.
     */
    private static final Map<UUID, ShoppingListSnapshot> pendingSnapshots = new HashMap<>();

    private record ShoppingListSnapshot(UUID shopOwner, int stackSize, InteractionHand hand) {
    }

    private static void onRightClickBlockPre(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ItemStack held = player.getItemInHand(event.getHand());
        if (!(held.getItem() instanceof ShoppingListItem)) return;

        ShoppingListItem.ShoppingList list = ShoppingListItem.getList(held);
        if (list == null) return;

        // Snapshot: remember owner UUID and current stack size
        pendingSnapshots.put(player.getUUID(),
                new ShoppingListSnapshot(list.shopOwner(), held.getCount(), event.getHand()));
    }

    private static void onRightClickBlockPost(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ShoppingListSnapshot snapshot = pendingSnapshots.remove(player.getUUID());
        if (snapshot == null) return;

        ItemStack after = player.getItemInHand(snapshot.hand());
        if (!isConsumed(after, snapshot)) return;

        // Shopping list was consumed → purchase completed
        MinecraftServer server = player.getServer();
        if (server == null) return;

        String ownerName = resolveOwnerName(server, snapshot.shopOwner());
        LOGGER.debug("[TableClothTracker] Purchase via shopping list by {} from shop owner {}",
                player.getName().getString(), ownerName);

        TradeStatsSavedData stats = TradeStatsSavedData.getOrCreate(server);
        stats.recordSale(ownerName, 1);
    }

    /**
     * Returns true if the stack no longer contains a ShoppingListItem
     * (consumed or replaced with something else).
     */
    private static boolean isConsumed(ItemStack stack, ShoppingListSnapshot snapshot) {
        if (stack.isEmpty()) return true;
        if (!(stack.getItem() instanceof ShoppingListItem)) return true;
        // Same item type but count decreased
        ShoppingListItem.ShoppingList list = ShoppingListItem.getList(stack);
        if (list == null) return true;
        // Different owner → different list, original was consumed
        if (!snapshot.shopOwner().equals(list.shopOwner())) return true;
        return false;
    }

    // ── Strategy B (fallback polling) ─────────────────────────────────────────

    /**
     * Called from VendorEvents every second for each loaded TableCloth.
     * Detects stock decreases as a fallback for cases Strategy A misses.
     */
    public static void checkTransaction(ServerLevel level, BlockEntity be, int offerIndex) {
        if (!(be instanceof TableClothBlockEntity cloth)) return;
        try {
            MinecraftServer server = level.getServer();
            if (server == null) return;

            String base = level.dimension().location() + "|"
                    + be.getBlockPos().getX() + ","
                    + be.getBlockPos().getY() + ","
                    + be.getBlockPos().getZ();
            String key = offerIndex >= 0 ? base + "#" + offerIndex : base;

            int currentCount = readSellingCount(cloth, server, offerIndex);
            if (currentCount < 0) return;

            Integer lastCount = lastKnownCount.get(key);
            if (lastCount != null && currentCount < lastCount) {
                int sold = lastCount - currentCount;
                LOGGER.debug("[TableClothTracker] Stock decrease at {} idx={} sold={} (fallback)",
                        be.getBlockPos(), offerIndex, sold);

                UUID ownerUuid = readOwnerUuid(cloth, server);
                if (ownerUuid != null) {
                    String ownerName = resolveOwnerName(server, ownerUuid);
                    TradeStatsSavedData stats = TradeStatsSavedData.getOrCreate(server);
                    stats.recordSale(ownerName, sold);
                }
            }

            lastKnownCount.put(key, currentCount);
        } catch (Exception e) {
            LOGGER.debug("[TableClothTracker] checkTransaction failed: {}", e.getMessage());
        }
    }

    /**
     * Clears all tracking state (call on server stop).
     */
    public static void clear() {
        lastKnownCount.clear();
        pendingSnapshots.clear();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int readSellingCount(TableClothBlockEntity cloth, MinecraftServer server, int offerIndex) {
        try {
            var tag = cloth.saveWithoutMetadata(server.registryAccess());
            var requestData = tag.getCompound("RequestData");
            var encodedReq = requestData.getCompound("encoded_request");
            var orderedStacks = encodedReq.getCompound("ordered_stacks");
            var entryList = orderedStacks.getList("entries", 10);
            if (entryList.isEmpty()) return -1;
            if (offerIndex < 0 || offerIndex >= entryList.size()) return -1;
            var row = entryList.getCompound(offerIndex);
            if (row.contains("count")) return row.getInt("count");
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static UUID readOwnerUuid(TableClothBlockEntity cloth, MinecraftServer server) {
        try {
            var tag = cloth.saveWithoutMetadata(server.registryAccess());
            if (tag.hasUUID("OwnerUUID")) return tag.getUUID("OwnerUUID");
        } catch (Exception ignored) {
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
