package by.deokma.stockmarket.neoforge.compat;

import by.deokma.stockmarket.shop.ShopEntry;
import by.deokma.stockmarket.shop.ShopSavedData;
import dev.ithundxr.createnumismatics.content.vendor.VendorBlock;
import dev.ithundxr.createnumismatics.content.vendor.VendorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.UUID;

/**
 * All direct references to Create: Numismatics classes live here.
 * This class must ONLY be loaded when {@link NumismaticsCompat#isPresent()} is true.
 *
 * <p>Only API that exists in every supported Numismatics version is called directly;
 * anything that changed between releases goes through {@link NumismaticsVendorAccess}.
 */
public final class VendorIndexer {

    private static final Logger LOGGER = LogManager.getLogger("stockmarket");

    /** Guards the failure log so an incompatible Numismatics reports once, not per vendor. */
    private static boolean failureLogged = false;

    private VendorIndexer() {}

    /** Returns true if the block is a Numismatics VendorBlock. */
    public static boolean isVendorBlock(Block block) {
        return block instanceof VendorBlock;
    }

    /** Returns true if the BlockEntity is a Numismatics VendorBlockEntity. */
    public static boolean isVendorEntity(BlockEntity be) {
        return be instanceof VendorBlockEntity;
    }

    /**
     * Indexes a VendorBlockEntity into ShopSavedData.
     * Safe to call only when {@link NumismaticsCompat#isPresent()} is true.
     */
    public static void indexVendor(ServerLevel level, BlockEntity be,
                                   ShopSavedData savedData,
                                   Map<UUID, String> nameCache) {
        if (!(be instanceof VendorBlockEntity vendor)) return;
        MinecraftServer server = level.getServer();
        if (server == null) return;

        String key = makeKey(level, vendor.getBlockPos());
        try {
            UUID ownerUuid = NumismaticsVendorAccess.owner(vendor, server.registryAccess());
            ItemStack tradedItem = NumismaticsVendorAccess.tradedItem(vendor, server.registryAccess());

            // An unowned or unconfigured vendor is not a shop — drop any listing it used to have.
            if (ownerUuid == null || tradedItem.isEmpty()) {
                savedData.remove(key);
                return;
            }

            VendorBlockEntity.Mode mode = vendor.getMode();
            String modeStr = mode != null ? mode.name() : "SELL";

            savedData.put(key, new ShopEntry(
                    vendor.getBlockPos(),
                    level.dimension().location().toString(),
                    tradedItem.copy(),
                    vendor.getTotalPrice(),
                    ItemStack.EMPTY,
                    ownerUuid,
                    resolveName(server, ownerUuid, nameCache),
                    modeStr,
                    "VENDOR",
                    -1
            ));
        } catch (Exception | LinkageError e) {
            // LinkageError matters: an API change in Numismatics surfaces as NoSuchMethodError,
            // which `catch (Exception)` let through to the server thread and crashed it.
            logFailure(e);
        }
    }

    static void logFailure(Throwable e) {
        if (failureLogged) {
            LOGGER.debug("[VendorIndexer] vendor indexing failed: {}", e.toString());
            return;
        }
        failureLogged = true;
        LOGGER.warn("[VendorIndexer] Could not read a Numismatics vendor — vendor shops may be "
                + "missing from the market. Further failures log at DEBUG.", e);
    }

    private static String resolveName(MinecraftServer server, UUID uuid, Map<UUID, String> cache) {
        return cache.computeIfAbsent(uuid, id -> {
            var player = server.getPlayerList().getPlayer(id);
            if (player != null) return player.getName().getString();
            var pc = server.getProfileCache();
            if (pc != null) {
                var opt = pc.get(id);
                if (opt.isPresent() && opt.get().getName() != null) return opt.get().getName();
            }
            return id.toString().substring(0, 8);
        });
    }

    private static String makeKey(ServerLevel level, BlockPos pos) {
        return level.dimension().location() + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
