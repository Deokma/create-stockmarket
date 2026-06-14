package by.deokma.stockmarket.neoforge.compat;

import by.deokma.stockmarket.shop.ShopEntry;
import by.deokma.stockmarket.shop.ShopSavedData;
import dev.ithundxr.createnumismatics.content.backend.behaviours.SliderStylePriceBehaviour;
import dev.ithundxr.createnumismatics.content.vendor.VendorBlock;
import dev.ithundxr.createnumismatics.content.vendor.VendorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
 */
public final class VendorIndexer {

    private static final Logger LOGGER = LogManager.getLogger("stockmarket");

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
        try {
            MinecraftServer server = level.getServer();
            if (server == null) return;

            UUID ownerUuid = readOwnerNbt(vendor, server);
            if (ownerUuid == null) return;

            ItemStack sellingItem = readSellingItem(vendor, server);
            if (sellingItem == null || sellingItem.isEmpty()) return;

            SliderStylePriceBehaviour price = vendor.getBehaviour(SliderStylePriceBehaviour.TYPE);
            int spurs = price != null ? price.getTotalPrice() : 0;

            VendorBlockEntity.Mode mode = vendor.getMode();
            String modeStr = mode != null ? mode.name() : "SELL";

            String ownerName = resolveName(server, ownerUuid, nameCache);
            String key = makeKey(level, vendor.getBlockPos());

            savedData.put(key, new ShopEntry(
                    vendor.getBlockPos(),
                    level.dimension().location().toString(),
                    sellingItem.copy(),
                    spurs,
                    ItemStack.EMPTY,
                    ownerUuid,
                    ownerName,
                    modeStr,
                    "VENDOR",
                    -1
            ));
        } catch (Exception e) {
            LOGGER.debug("[VendorIndexer] indexVendor failed: {}", e.getMessage());
        }
    }

    /**
     * Prefer API getter, but fall back to serialized BE NBT.
     * Some stacks with rich data/components (e.g. enchanted books) may not be exposed reliably
     * through the direct getter in every compat/runtime scenario.
     */
    private static ItemStack readSellingItem(VendorBlockEntity vendor, MinecraftServer server) {
        ItemStack direct = vendor.getSellingItem();
        if (direct != null && !direct.isEmpty()) return direct;
        try {
            CompoundTag tag = vendor.saveWithoutMetadata(server.registryAccess());
            String[] candidates = {"SellingItem", "sellingItem", "Item", "item"};
            for (String key : candidates) {
                if (!tag.contains(key, net.minecraft.nbt.Tag.TAG_COMPOUND)) continue;
                ItemStack parsed = ItemStack.parseOptional(server.registryAccess(), tag.getCompound(key));
                if (!parsed.isEmpty()) return parsed;
            }
        } catch (Exception ignored) {
        }
        return direct == null ? ItemStack.EMPTY : direct;
    }

    private static UUID readOwnerNbt(VendorBlockEntity vendor, MinecraftServer server) {
        try {
            CompoundTag tag = vendor.saveWithoutMetadata(server.registryAccess());
            if (tag.hasUUID("Owner")) return tag.getUUID("Owner");
            if (tag.hasUUID("owner")) return tag.getUUID("owner");
        } catch (Exception ignored) {}
        return null;
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
