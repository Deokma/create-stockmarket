package by.deokma.stockmarket.neoforge.shop;

import by.deokma.stockmarket.neoforge.compat.NumismaticsCompat;
import by.deokma.stockmarket.neoforge.compat.TradeworksCompat;
import by.deokma.stockmarket.neoforge.compat.VendorIndexer;
import by.deokma.stockmarket.platform.IPlatformVendorHelper;
import by.deokma.stockmarket.shop.ShopEntry;
import by.deokma.stockmarket.shop.ShopSavedData;
import com.simibubi.create.content.logistics.tableCloth.TableClothBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * NeoForge implementation of {@link IPlatformVendorHelper}.
 *
 * Handles all mod-specific shop types:
 * <ul>
 *   <li>Create TableCloth (always)</li>
 *   <li>Tradeworks shelves (when Tradeworks is loaded)</li>
 *   <li>Numismatics Vendor (when Numismatics is loaded)</li>
 * </ul>
 *
 * Also provides NeoForge-specific chunk iteration via {@code ChunkMap} reflection.
 */
public final class NeoForgeVendorHelper implements IPlatformVendorHelper {

    private static final Logger LOGGER = LogManager.getLogger("stockmarket");

    // ── IPlatformVendorHelper ─────────────────────────────────────────────────

    @Override
    public boolean isShopEntity(BlockEntity be) {
        if (be instanceof TableClothBlockEntity) return true;
        return NumismaticsCompat.isPresent() && VendorIndexer.isVendorEntity(be);
    }

    @Override
    public boolean tryIndex(ServerLevel level, BlockEntity be,
                            ShopSavedData store, Map<UUID, String> nameCache) {
        // Numismatics Vendor — only if mod is present
        if (NumismaticsCompat.isPresent() && VendorIndexer.isVendorEntity(be)) {
            VendorIndexer.indexVendor(level, be, store, nameCache);
            return true;
        }
        // Create TableCloth (also covers Tradeworks shelves that extend it)
        if (be instanceof TableClothBlockEntity cloth) {
            indexTableCloth(level, cloth, store, nameCache);
            return true;
        }
        return false;
    }

    @Override
    public void forEachLoadedChunk(MinecraftServer server, BiConsumer<ServerLevel, LevelChunk> action) {
        for (ServerLevel level : server.getAllLevels()) {
            try {
                var method = net.minecraft.server.level.ChunkMap.class.getDeclaredMethod("getChunks");
                method.setAccessible(true);
                Object result = method.invoke(level.getChunkSource().chunkMap);
                for (Object obj : (Iterable<?>) result) {
                    net.minecraft.server.level.ChunkHolder holder =
                            (net.minecraft.server.level.ChunkHolder) obj;
                    LevelChunk chunk = holder.getTickingChunk();
                    if (chunk == null) continue;
                    action.accept(level, chunk);
                }
            } catch (Exception e) {
                LOGGER.debug("[NeoForgeVendorHelper] forEachLoadedChunk: {}", e.getMessage());
            }
        }
    }

    // ── TableCloth indexing ───────────────────────────────────────────────────

    private static void indexTableCloth(ServerLevel level, TableClothBlockEntity cloth,
                                        ShopSavedData savedData, Map<UUID, String> nameCache) {
        try {
            MinecraftServer server = level.getServer();
            if (server == null) return;

            CompoundTag tag = cloth.saveWithoutMetadata(server.registryAccess());
            LOGGER.debug("[NeoForgeVendorHelper] TableCloth NBT keys at {}: {}", cloth.getBlockPos(), tag.getAllKeys());

            if (!tag.hasUUID("OwnerUUID")) return;
            UUID ownerUuid = tag.getUUID("OwnerUUID");

            net.minecraft.nbt.ListTag entryList;
            try {
                var requestData   = tag.getCompound("RequestData");
                var encodedReq    = requestData.getCompound("encoded_request");
                var orderedStacks = encodedReq.getCompound("ordered_stacks");
                entryList = orderedStacks.getList("entries", 10);
            } catch (Exception ignored) {
                return;
            }
            if (entryList.isEmpty()) return;

            ItemStack paymentTemplate = parsePaymentItem(tag, server);

            String ownerName = nameCache.computeIfAbsent(ownerUuid, id -> {
                var player = server.getPlayerList().getPlayer(id);
                if (player != null) return player.getName().getString();
                var cache = server.getProfileCache();
                if (cache != null) {
                    var opt = cache.get(id);
                    if (opt.isPresent() && opt.get().getName() != null) return opt.get().getName();
                }
                return id.toString().substring(0, 8);
            });

            String baseKey = makeKey(level, cloth.getBlockPos());
            savedData.removeByBaseKey(baseKey);

            String shopType = TradeworksCompat.isPresent() && isTradeworksBlock(cloth)
                    ? "TRADEWORKS"
                    : "TABLECLOTH";

            int indexedRows = 0;
            for (int i = 0; i < entryList.size(); i++) {
                var offer        = entryList.getCompound(i);
                var itemStackTag = offer.getCompound("item_stack");
                ItemStack sellingItem = ItemStack.parseOptional(server.registryAccess(), itemStackTag);
                if (!sellingItem.isEmpty() && offer.contains("count")) {
                    sellingItem.setCount(offer.getInt("count"));
                }
                if (sellingItem.isEmpty()) continue;

                ItemStack paymentItem = paymentTemplate.isEmpty()
                        ? ItemStack.EMPTY
                        : paymentTemplate.copy();

                savedData.put(baseKey + "#" + i, new ShopEntry(
                        cloth.getBlockPos(),
                        level.dimension().location().toString(),
                        sellingItem,
                        0,
                        paymentItem,
                        ownerUuid,
                        ownerName,
                        "SELL",
                        shopType,
                        i
                ));
                indexedRows++;
            }
            if (indexedRows > 0) {
                LOGGER.debug("[NeoForgeVendorHelper] TableCloth at {} indexed {} offer row(s)",
                        cloth.getBlockPos(), indexedRows);
            }
        } catch (Exception e) {
            LOGGER.debug("[NeoForgeVendorHelper] indexTableCloth failed: {}", e.getMessage());
        }
    }

    /**
     * Payment item type comes from the Filter slot; required amount is stored separately.
     * Do not use the filter stack's serialized Count — it often mirrors a full slot (64)
     * while the real price is 1.
     */
    private static ItemStack parsePaymentItem(CompoundTag tag, MinecraftServer server) {
        if (!tag.contains("Filter")) return ItemStack.EMPTY;
        CompoundTag filterTag = tag.getCompound("Filter");
        ItemStack paymentItem = ItemStack.parseOptional(server.registryAccess(), filterTag).copy();
        if (paymentItem.isEmpty()) return ItemStack.EMPTY;

        int amount = 1;
        if (tag.contains("FilterAmount")) {
            amount = Math.max(1, tag.getInt("FilterAmount"));
        } else if (tag.contains("filter_amount")) {
            amount = Math.max(1, tag.getInt("filter_amount"));
        }
        // Create's table cloth price UI is 1–100; not limited by maxStackSize.
        paymentItem.setCount(Mth.clamp(amount, 1, 100));
        return paymentItem;
    }

    /**
     * Checks if the TableCloth block entity belongs to Tradeworks by its block's registry namespace.
     */
    private static boolean isTradeworksBlock(TableClothBlockEntity cloth) {
        try {
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(cloth.getBlockState().getBlock());
            return key != null && "tradeworks".equals(key.getNamespace());
        } catch (Exception e) {
            return false;
        }
    }

    private static String makeKey(ServerLevel level, net.minecraft.core.BlockPos pos) {
        return level.dimension().location() + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
