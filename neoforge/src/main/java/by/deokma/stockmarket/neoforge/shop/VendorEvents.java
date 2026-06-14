package by.deokma.stockmarket.neoforge.shop;

import by.deokma.stockmarket.neoforge.compat.NumismaticsCompat;
import by.deokma.stockmarket.neoforge.compat.StockKeeperSaleTracker;
import by.deokma.stockmarket.neoforge.compat.TableClothTransactionTracker;
import by.deokma.stockmarket.neoforge.compat.VendorIndexer;
import by.deokma.stockmarket.neoforge.compat.VendorTransactionTracker;
import by.deokma.stockmarket.shop.ShopEntry;
import by.deokma.stockmarket.shop.VendorRegistry;
import com.simibubi.create.content.logistics.tableCloth.TableClothBlock;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

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
        NeoForge.EVENT_BUS.addListener(VendorEvents::onServerTickCloth);
        NeoForge.EVENT_BUS.addListener(VendorEvents::onLevelUnload);
        // Register transaction tracker if Numismatics is present
        if (NumismaticsCompat.isPresent()) {
            VendorTransactionTracker.register();
        }
        // TableCloth transaction tracker — always active (no optional dependency)
        TableClothTransactionTracker.register();
    }

    private static void onServerStarted(ServerStartedEvent event) {
        VendorRegistry.onServerStart(event.getServer());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        VendorRegistry.onServerStop();
        if (NumismaticsCompat.isPresent()) {
            VendorTransactionTracker.clear();
        }
        TableClothTransactionTracker.clear();
        StockKeeperSaleTracker.clear();
    }

    private static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;
        VendorRegistry.onChunkLoad(level, chunk);
    }

    private static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Block block = event.getPlacedBlock().getBlock();
        boolean isShopBlock = block instanceof TableClothBlock
                || (NumismaticsCompat.isPresent() && VendorIndexer.isVendorBlock(block));
        if (!isShopBlock) return;
        VendorRegistry.onBlockPlace(level, event.getPos());
    }

    private static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Block block = event.getState().getBlock();
        boolean isShopBlock = block instanceof TableClothBlock
                || (NumismaticsCompat.isPresent() && VendorIndexer.isVendorBlock(block));
        if (!isShopBlock) return;
        VendorRegistry.onBlockBreak(level, event.getPos());
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter < REFRESH_INTERVAL) return;
        tickCounter = 0;
        VendorRegistry.refreshLoaded(event.getServer());
    }

    /** Tick interval for TableCloth transaction polling (every 20 ticks = 1 second). */
    private static int clothTickCounter = 0;
    private static final int CLOTH_CHECK_INTERVAL = 20;

    private static void onServerTickCloth(ServerTickEvent.Post event) {
        if (++clothTickCounter < CLOTH_CHECK_INTERVAL) return;
        clothTickCounter = 0;
        MinecraftServer server = event.getServer();

        // Use VendorRegistry's known shop positions — no chunk iteration needed
        for (ShopEntry entry : VendorRegistry.getAll()) {
            if (!entry.usesItemPrice()) continue;

            // Find the matching ServerLevel by dimension ID
            ResourceLocation dimId = ResourceLocation.tryParse(entry.dimensionId());
            if (dimId == null) continue;

            ServerLevel level = null;
            for (ServerLevel l : server.getAllLevels()) {
                if (l.dimension().location().equals(dimId)) {
                    level = l;
                    break;
                }
            }
            if (level == null) continue;

            // Only check if the chunk is loaded (avoid force-loading)
            if (!level.isLoaded(entry.pos())) continue;

            BlockEntity be = level.getBlockEntity(entry.pos());
            if (be == null) continue;

            TableClothTransactionTracker.checkTransaction(level, be, entry.offerIndex());
        }
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        // No-op: shops are persisted in SavedData, no need to clear on level unload.
        // VendorRegistry.onServerStop() handles cleanup when the server fully stops.
    }
}
