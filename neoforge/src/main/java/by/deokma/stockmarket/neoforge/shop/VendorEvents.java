package by.deokma.stockmarket.neoforge.shop;

import by.deokma.stockmarket.neoforge.compat.NumismaticsCompat;
import by.deokma.stockmarket.neoforge.compat.StockKeeperSaleTracker;
import by.deokma.stockmarket.neoforge.compat.VendorIndexer;
import by.deokma.stockmarket.neoforge.compat.VendorTransactionTracker;
import by.deokma.stockmarket.shop.VendorRegistry;
import com.simibubi.create.content.logistics.tableCloth.TableClothBlock;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Keeps the shop index in sync with the world.
 *
 * <p>TableCloth sales are not polled here: a cloth purchase is paid at the Stock Keeper,
 * which {@link StockKeeperSaleTracker} records. The per-second cloth poll that used to live
 * here serialised every cloth's NBT each second and could only ever see the owner editing
 * an offer, which it then counted as a sale.
 */
public final class VendorEvents {

    private static final int REFRESH_INTERVAL = 6000; // 5 minutes at 20 TPS

    private static int tickCounter = 0;

    private VendorEvents() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(VendorEvents::onServerStarted);
        NeoForge.EVENT_BUS.addListener(VendorEvents::onServerStopping);
        NeoForge.EVENT_BUS.addListener(VendorEvents::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(VendorEvents::onBlockPlace);
        NeoForge.EVENT_BUS.addListener(VendorEvents::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(VendorEvents::onServerTick);
        // Register transaction tracker if Numismatics is present
        if (NumismaticsCompat.isPresent()) {
            VendorTransactionTracker.register();
        }
    }

    private static void onServerStarted(ServerStartedEvent event) {
        tickCounter = 0;
        VendorRegistry.onServerStart(event.getServer());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        VendorRegistry.onServerStop();
        if (NumismaticsCompat.isPresent()) {
            VendorTransactionTracker.clear();
        }
        StockKeeperSaleTracker.clear();
    }

    private static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;
        VendorRegistry.onChunkLoad(level, chunk);
    }

    private static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!isShopBlock(event.getPlacedBlock().getBlock())) return;
        VendorRegistry.onBlockPlace(level, event.getPos());
    }

    private static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!isShopBlock(event.getState().getBlock())) return;
        VendorRegistry.onBlockBreak(level, event.getPos());
    }

    private static boolean isShopBlock(Block block) {
        return block instanceof TableClothBlock
                || (NumismaticsCompat.isPresent() && VendorIndexer.isVendorBlock(block));
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter < REFRESH_INTERVAL) return;
        tickCounter = 0;
        VendorRegistry.refreshLoaded(event.getServer());
    }
}
