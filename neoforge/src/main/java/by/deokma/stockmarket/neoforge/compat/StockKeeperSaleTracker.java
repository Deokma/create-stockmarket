package by.deokma.stockmarket.neoforge.compat;

import by.deokma.stockmarket.CreateStockMarket;
import by.deokma.stockmarket.market.TradeStatsSavedData;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlock;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import com.simibubi.create.content.logistics.stockTicker.StockTickerInteractionHandler;
import com.simibubi.create.content.logistics.tableCloth.ShoppingListItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Counts Create Stock Keeper sales.
 * <p>
 * Supports:
 * - EntityInteractSpecific: seated mob vendors.
 * - RightClickBlock: Blaze Burner and other block-based vendors.
 */
@EventBusSubscriber(modid = CreateStockMarket.MOD_ID)
public final class StockKeeperSaleTracker {

    private static final Logger LOGGER = LogManager.getLogger("stockmarket");

    /**
     * Player UUID -> shop owner UUID saved before checkout.
     */
    private static final Map<UUID, UUID> pendingShopOwner = new HashMap<>();
    private static final Map<UUID, UUID> delayedBlockChecks = new HashMap<>();

    private StockKeeperSaleTracker() {
    }

    // ------------------------------------------------------------------------
    // PRE EVENTS
    // ------------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onEntityInteractHigh(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        // Standard Create mob vendor detection
        if (StockTickerInteractionHandler.getStockTickerPosition(event.getTarget()) == null) return;

        captureShoppingList(player);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBlockInteractHigh(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        /*if (!(event.getLevel().getBlockEntity(event.getPos()) instanceof StockTickerBlockEntity ticker))
            return;*/

        var be = event.getLevel().getBlockEntity(event.getPos());
        if (be instanceof StockTickerBlockEntity ticker && ticker.isKeeperPresent()) {
            // уже работает
        } else {
            // Blaze Burner — ищем соседний StockTicker
            BlockPos tickerPos = findAdjacentStockTicker(event.getLevel(), event.getPos());
            if (tickerPos == null) return;
            if (!(event.getLevel().getBlockEntity(tickerPos) instanceof StockTickerBlockEntity ticker)) return;
            if (!ticker.isKeeperPresent()) return;
            // дальше сохраняем в delayedBlockChecks
        }

        ItemStack main = player.getMainHandItem();
        if (!AllItems.SHOPPING_LIST.isIn(main))
            return;

        var list = ShoppingListItem.getList(main);
        if (list == null)
            return;

        // Проверим результат на следующем тике
        delayedBlockChecks.put(player.getUUID(), list.shopOwner());
    }


    // ------------------------------------------------------------------------
    // POST EVENTS
    // ------------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onEntityInteractLow(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        completeSale(player);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onBlockInteractLow(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        // Игрок должен кликнуть именно по Stock Ticker
        if (!(event.getLevel().getBlockEntity(event.getPos()) instanceof StockTickerBlockEntity ticker))
            return;

        // Убедимся, что продавец действительно присутствует
        if (!ticker.isKeeperPresent())
            return;

        UUID shopOwner = pendingShopOwner.remove(player.getUUID());
        if (shopOwner == null)
            return;

        // После успешной покупки Create очищает Shopping List:
        // player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        // Если список всё ещё в руке, покупка не завершилась.
        ItemStack main = player.getMainHandItem();
        if (AllItems.SHOPPING_LIST.isIn(main))
            return;

        MinecraftServer server = player.getServer();
        if (server == null)
            return;

        String ownerName = resolveOwnerName(server, shopOwner);

        LOGGER.debug(
                "[StockKeeperTracker] Stock Keeper sale recorded for owner {} (player {})",
                ownerName,
                player.getName().getString()
        );

        TradeStatsSavedData.getOrCreate(server).recordSale(ownerName, 1);
    }

    // ------------------------------------------------------------------------
    // CORE LOGIC
    // ------------------------------------------------------------------------

    /**
     * Saves shop owner UUID from the shopping list before Create processes the checkout.
     */
    private static void captureShoppingList(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (!AllItems.SHOPPING_LIST.isIn(main)) return;

        var list = ShoppingListItem.getList(main);
        if (list == null) return;

        pendingShopOwner.put(player.getUUID(), list.shopOwner());
    }

    /**
     * Checks whether the shopping list was consumed and records the sale.
     */
    private static void completeSale(ServerPlayer player) {
        UUID shopOwner = pendingShopOwner.remove(player.getUUID());
        if (shopOwner == null) return;

        // If the same shopping list is still present, checkout did not complete.
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof ShoppingListItem) {
            var list = ShoppingListItem.getList(main);
            if (list != null && shopOwner.equals(list.shopOwner())) {
                return;
            }
        }

        MinecraftServer server = player.getServer();
        if (server == null) return;

        String ownerName = resolveOwnerName(server, shopOwner);

        LOGGER.debug(
                "[StockKeeperTracker] Sale recorded for owner {} (player {})",
                ownerName,
                player.getName().getString()
        );

        TradeStatsSavedData.getOrCreate(server).recordSale(ownerName, 1);
    }

    // ------------------------------------------------------------------------
    // STOCK TICKER SEARCH
    // ------------------------------------------------------------------------

    /**
     * Finds a StockTicker adjacent to the clicked block.
     * Checks all 6 directions.
     */
    private static BlockPos findAdjacentStockTicker(Level level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.relative(direction);

            if (!(level.getBlockState(neighbor).getBlock() instanceof StockTickerBlock))
                continue;

            if (level.getBlockEntity(neighbor) instanceof StockTickerBlockEntity)
                return neighbor;
        }

        return null;
    }

    // ------------------------------------------------------------------------
    // UTILITIES
    // ------------------------------------------------------------------------

    public static void clear() {
        pendingShopOwner.clear();
    }

    private static String resolveOwnerName(MinecraftServer server, UUID uuid) {
        var online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            return online.getName().getString();
        }

        var cache = server.getProfileCache();
        if (cache != null) {
            var opt = cache.get(uuid);
            if (opt.isPresent() && opt.get().getName() != null) {
                return opt.get().getName();
            }
        }

        return uuid.toString().substring(0, 8);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (delayedBlockChecks.isEmpty())
            return;

        MinecraftServer server = event.getServer();
        var iterator = delayedBlockChecks.entrySet().iterator();

        while (iterator.hasNext()) {
            var entry = iterator.next();
            iterator.remove();

            UUID playerId = entry.getKey();
            UUID shopOwner = entry.getValue();

            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null)
                continue;

            // Если Shopping List всё ещё в руке, покупка не завершилась
            if (AllItems.SHOPPING_LIST.isIn(player.getMainHandItem()))
                continue;

            String ownerName = resolveOwnerName(server, shopOwner);

            LOGGER.debug(
                    "[StockKeeperTracker] Blaze Burner sale recorded for owner {} (player {})",
                    ownerName,
                    player.getName().getString()
            );

            TradeStatsSavedData.getOrCreate(server).recordSale(ownerName, 1);
        }
    }
}