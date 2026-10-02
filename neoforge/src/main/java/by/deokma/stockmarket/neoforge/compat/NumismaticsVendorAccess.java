package by.deokma.stockmarket.neoforge.compat;

import dev.ithundxr.createnumismatics.content.vendor.VendorBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;

/**
 * Version-tolerant access to the parts of {@link VendorBlockEntity} that changed between
 * Numismatics releases.
 *
 * <p>Numismatics 1.1.0 replaced the vendor's "selling item" with a filter slot:
 * <ul>
 *   <li>1.0.x — {@code getSellingItem()} / {@code matchesSellingItem(ItemStack)}, NBT key {@code Selling}</li>
 *   <li>1.1.x — {@code getDisplayItem()} / {@code matchesFilterItem(ItemStack)}, NBT key {@code Filter}</li>
 * </ul>
 * Calling the 1.0.x methods directly against 1.1.0 threw {@link NoSuchMethodError} on the
 * server thread and crashed the server whenever a chunk with a vendor loaded. The methods
 * are therefore resolved reflectively once, and whichever one exists is used.
 *
 * <p>Must ONLY be loaded when {@link NumismaticsCompat#isPresent()} is true.
 */
final class NumismaticsVendorAccess {

    private static final Logger LOGGER = LogManager.getLogger("stockmarket");

    /** 1.1.x first: the display item resolves list/attribute filters to a real stack. */
    private static final MethodHandle GET_ITEM = findVirtual(ItemStack.class,
            "getDisplayItem", "getSellingItem");

    /**
     * 1.1.x only — the raw filter slot. {@code getDisplayItem()} is cached until the vendor's
     * next tick, so the slot itself is checked first to see a just-cleared vendor as empty.
     */
    private static final MethodHandle GET_FILTER = findOptional(MethodType.methodType(ItemStack.class),
            "getFilterItem");

    /** Matching honours list/attribute filters on 1.1.x and component equality on 1.0.x. */
    private static final MethodHandle MATCHES = findVirtual(boolean.class, ItemStack.class,
            "matchesFilterItem", "matchesSellingItem");

    private NumismaticsVendorAccess() {}

    /**
     * The stack the vendor trades, with its count set to the quantity of one transaction.
     * {@link ItemStack#EMPTY} when the vendor has nothing configured.
     */
    static ItemStack tradedItem(VendorBlockEntity vendor, HolderLookup.Provider registries) {
        if (GET_FILTER != null) {
            try {
                ItemStack filter = (ItemStack) GET_FILTER.invoke(vendor);
                if (filter == null || filter.isEmpty()) return ItemStack.EMPTY;
            } catch (Throwable t) {
                LOGGER.debug("[NumismaticsVendorAccess] filter getter failed: {}", t.toString());
            }
        }
        if (GET_ITEM != null) {
            try {
                ItemStack stack = (ItemStack) GET_ITEM.invoke(vendor);
                if (stack != null && !stack.isEmpty()) return stack;
            } catch (Throwable t) {
                LOGGER.debug("[NumismaticsVendorAccess] item getter failed: {}", t.toString());
            }
        }
        // Fallback: read the slot straight from the saved data, covering both layouts.
        CompoundTag tag = vendor.saveWithoutMetadata(registries);
        for (String key : new String[]{"Filter", "Selling"}) {
            if (!tag.contains(key, Tag.TAG_COMPOUND)) continue;
            ItemStack parsed = ItemStack.parseOptional(registries, tag.getCompound(key));
            if (!parsed.isEmpty()) return parsed;
        }
        return ItemStack.EMPTY;
    }

    /** Whether {@code stack} is something this vendor trades. */
    static boolean matches(VendorBlockEntity vendor, ItemStack template, ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (MATCHES != null) {
            try {
                return (boolean) MATCHES.invoke(vendor, stack);
            } catch (Throwable t) {
                LOGGER.debug("[NumismaticsVendorAccess] matcher failed: {}", t.toString());
            }
        }
        return ItemStack.isSameItemSameComponents(template, stack);
    }

    /** Owner UUID — stored under {@code Owner} by every Numismatics version so far. */
    static UUID owner(VendorBlockEntity vendor, HolderLookup.Provider registries) {
        CompoundTag tag = vendor.saveWithoutMetadata(registries);
        if (tag.hasUUID("Owner")) return tag.getUUID("Owner");
        if (tag.hasUUID("owner")) return tag.getUUID("owner");
        return null;
    }

    // ── Reflection ────────────────────────────────────────────────────────────

    private static MethodHandle findVirtual(Class<?> returnType, String... names) {
        return find(MethodType.methodType(returnType), names);
    }

    private static MethodHandle findVirtual(Class<?> returnType, Class<?> param, String... names) {
        return find(MethodType.methodType(returnType, param), names);
    }

    private static MethodHandle find(MethodType type, String... names) {
        MethodHandle handle = findOptional(type, names);
        if (handle == null) {
            LOGGER.warn("[NumismaticsVendorAccess] None of {} found on VendorBlockEntity — "
                    + "falling back to NBT. This Numismatics version may not be fully supported.",
                    String.join("/", names));
        }
        return handle;
    }

    /** Like {@link #find} but silent — for methods that only some versions have. */
    private static MethodHandle findOptional(MethodType type, String... names) {
        // Our own lookup, not publicLookup(): it resolves with the same module access as the
        // direct calls in this class, so it works however FML exposes Numismatics' packages.
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        for (String name : names) {
            try {
                return lookup.findVirtual(VendorBlockEntity.class, name, type);
            } catch (NoSuchMethodException | IllegalAccessException ignored) {
                // try the next name
            }
        }
        return null;
    }
}
