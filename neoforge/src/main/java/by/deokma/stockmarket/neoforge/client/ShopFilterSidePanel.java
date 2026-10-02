package by.deokma.stockmarket.neoforge.client;

import by.deokma.stockmarket.shop.ShopEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.item.ItemStack;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Sidebar filter panel for the Shops tab.
 * Sections: Mode, Type, Currency, Items, Owner, Dimension.
 */
public class ShopFilterSidePanel {

    private static final List<String> SHOP_TYPE_KEYS = List.of("VENDOR", "TABLECLOTH", "TRADEWORKS");

    private static String typeDisplayLabel(String key) {
        return switch (key) {
            case "VENDOR" -> I18n.get("screen.stockmarket.type_vendor");
            case "TABLECLOTH" -> I18n.get("screen.stockmarket.type_tablecloth");
            case "TRADEWORKS" -> I18n.get("screen.stockmarket.type_tradeworks");
            default -> key;
        };
    }

    /** Localised SELL / BUY; the raw mode string stays the filter key. */
    static String modeDisplayLabel(String mode) {
        return switch (mode) {
            case "SELL" -> I18n.get("screen.stockmarket.mode_sell");
            case "BUY" -> I18n.get("screen.stockmarket.mode_buy");
            default -> mode;
        };
    }

    // ── Colours ───────────────────────────────────────────────────────────────
    private static final int C_TEXT = 0xFF111111;
    private static final int C_DIM = 0xFF333344;
    private static final int C_GOLD = 0xFF8B6914;
    private static final int C_ACTIVE = 0xFF8B6914;
    private static final int C_GREEN = 0xFF1B6B1B;
    private static final int C_RED = 0xFFB71C1C;

    // ── Adaptive sizing methods ───────────────────────────────────────────────
    private int padding() {
        return UIConstants.Layout.padding(Minecraft.getInstance().getWindow().getGuiScaledWidth());
    }

    private int headerHeight() {
        return UIConstants.Layout.colHeaderHeight(font.lineHeight);
    }

    private int entryHeight() {
        return (int) (UIConstants.Layout.rowHeight(font.lineHeight) * 0.75);
    }

    private static final int GAP = 2;

    // ── Currency / Item entry ─────────────────────────────────────────────────
    record IconEntry(String key, String label, ItemStack icon, int count) {
    }

    // ── Filter state ──────────────────────────────────────────────────────────
    private final Set<String> activeModes = new LinkedHashSet<>();
    private final Set<String> activeTypes = new LinkedHashSet<>();
    private final Set<String> activeCurrencies = new LinkedHashSet<>(); // "coins" or item registry key
    private final Set<String> activeItems = new LinkedHashSet<>(); // item registry key
    private final Set<String> activeOwners = new LinkedHashSet<>();
    private final Set<String> activeDims = new LinkedHashSet<>();

    // Available values
    private Map<String, Integer> modeCounts = new LinkedHashMap<>();
    private Map<String, Integer> typeCounts = new LinkedHashMap<>();
    private List<IconEntry> currencies = new ArrayList<>();
    private List<IconEntry> items = new ArrayList<>();
    private List<String> owners = new ArrayList<>();
    private Map<String, Integer> ownerCounts = new LinkedHashMap<>();
    private List<String> dims = new ArrayList<>();
    private Map<String, Integer> dimCounts = new LinkedHashMap<>();

    // Collapse state — Currency and Items collapsed by default
    private boolean modeCollapsed = false;
    private boolean typeCollapsed = false;
    private boolean currencyCollapsed = true;
    private boolean itemsCollapsed = true;
    private boolean ownerCollapsed = false;
    private boolean dimCollapsed = true;

    private int currencyScroll = 0;
    private int itemsScroll = 0;
    /**
     * Vertical scroll offset for the whole sidebar panel
     */
    private int scrollY = 0;
    /**
     * Total rendered content height (updated each frame)
     */
    private int totalContentH = 0;

    private int x, y, w, h;
    private Font font;

    // ── Init ──────────────────────────────────────────────────────────────────

    public void init(int px, int py, int pw, int ph) {
        this.x = px;
        this.y = py;
        this.w = pw;
        this.h = ph;
        this.font = Minecraft.getInstance().font;
    }

    public void rebuild(List<ShopEntry> all) {
        modeCounts.clear();
        typeCounts.clear();
        ownerCounts.clear();
        dimCounts.clear();
        Map<String, IconEntry> currMap = new LinkedHashMap<>();
        Map<String, IconEntry> itemMap = new LinkedHashMap<>();

        for (ShopEntry e : all) {
            modeCounts.merge(e.mode(), 1, Integer::sum);
            typeCounts.merge(e.shopType(), 1, Integer::sum);
            ownerCounts.merge(e.ownerName(), 1, Integer::sum);
            String dim = e.dimensionId().replace("minecraft:", "");
            dimCounts.merge(dim, 1, Integer::sum);

            // Currency
            String currKey;
            String currLabel;
            ItemStack currIcon;
            if (e.usesItemPrice() && !e.priceItem().isEmpty()) {
                currKey = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(e.priceItem().getItem()).toString();
                currLabel = e.priceItem().getHoverName().getString();
                currIcon = e.priceItem().copyWithCount(1);
            } else if (!e.usesItemPrice() && e.totalPriceInSpurs() > 0) {
                // Numismatics Vendor — show the dominant coin denomination
                currKey = UIConstants.Coins.dominantKey(e.totalPriceInSpurs());
                currLabel = UIConstants.Coins.dominantLabel(e.totalPriceInSpurs());
                currIcon = UIConstants.Coins.icon(currKey);
            } else {
                // Free / no price
                currKey = "free";
                currLabel = I18n.get("screen.stockmarket.price_free");
                currIcon = ItemStack.EMPTY;
            }
            currMap.merge(currKey,
                    new IconEntry(currKey, currLabel, currIcon, 1),
                    (a, b) -> new IconEntry(a.key(), a.label(), a.icon(), a.count() + 1));

            // Items (what's being sold)
            String itemKey = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(e.sellingItem().getItem()).toString();
            String itemLabel = e.sellingItem().getHoverName().getString();
            ItemStack itemIcon = e.sellingItem().copyWithCount(1);
            itemMap.merge(itemKey,
                    new IconEntry(itemKey, itemLabel, itemIcon, 1),
                    (a, b) -> new IconEntry(a.key(), a.label(), a.icon(), a.count() + 1));
        }

        currencies = currMap.values().stream()
                .sorted(Comparator.comparingInt(IconEntry::count).reversed())
                .collect(Collectors.toList());
        items = itemMap.values().stream()
                .sorted(Comparator.comparingInt(IconEntry::count).reversed())
                .collect(Collectors.toList());
        owners = ownerCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey).collect(Collectors.toList());
        dims = dimCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey).collect(Collectors.toList());

        // Prune stale active filters
        activeOwners.retainAll(ownerCounts.keySet());
        activeModes.retainAll(modeCounts.keySet());
        activeTypes.retainAll(typeCounts.keySet());
        Set<String> currKeys = currencies.stream().map(IconEntry::key).collect(Collectors.toSet());
        activeCurrencies.retainAll(currKeys);
        Set<String> itemKeys = items.stream().map(IconEntry::key).collect(Collectors.toSet());
        activeItems.retainAll(itemKeys);
    }

    // ── Filter API ────────────────────────────────────────────────────────────

    public boolean matches(ShopEntry e) {
        if (!activeModes.isEmpty() && !activeModes.contains(e.mode())) return false;
        if (!activeTypes.isEmpty() && !activeTypes.contains(e.shopType())) return false;
        if (!activeOwners.isEmpty() && !activeOwners.contains(e.ownerName())) return false;
        if (!activeDims.isEmpty()) {
            if (!activeDims.contains(e.dimensionId().replace("minecraft:", ""))) return false;
        }
        if (!activeCurrencies.isEmpty()) {
            String currKey;
            if (e.usesItemPrice() && !e.priceItem().isEmpty()) {
                currKey = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(e.priceItem().getItem()).toString();
            } else if (!e.usesItemPrice() && e.totalPriceInSpurs() > 0) {
                currKey = UIConstants.Coins.dominantKey(e.totalPriceInSpurs());
            } else {
                currKey = "free";
            }
            if (!activeCurrencies.contains(currKey)) return false;
        }
        if (!activeItems.isEmpty()) {
            String itemKey = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(e.sellingItem().getItem()).toString();
            if (!activeItems.contains(itemKey)) return false;
        }
        return true;
    }

    // ── Render ────────────────────────────────────────────────────────────────

    public void render(GuiGraphics gfx, int mx, int my) {
        UIHelper.blitScaled(gfx, GuiTextures.SIDEBAR_BG, x, y, w, h,
                GuiTextures.Dimensions.SIDEBAR_BG_W, GuiTextures.Dimensions.SIDEBAR_BG_H);
        UIHelper.blitTiled(gfx, GuiTextures.PANEL_BORDER, x + w - 1, y, 2, h, 2, 1);

        // Scissor to panel bounds so scrolled content doesn't bleed outside
        gfx.enableScissor(x, y, x + w, y + h);

        int cy = y + 2 - scrollY;

        cy = drawSimpleSection(gfx, mx, my, cy, I18n.get("screen.stockmarket.filter_mode"),
                List.of("SELL", "BUY"), modeCounts, activeModes, modeCollapsed,
                k -> k.equals("SELL") ? C_RED : C_GREEN, ShopFilterSidePanel::modeDisplayLabel);
        cy += GAP;

        cy = drawTypeSection(gfx, mx, my, cy);
        cy += GAP;

        cy = drawIconSection(gfx, mx, my, cy, I18n.get("screen.stockmarket.filter_currency"),
                currencies, activeCurrencies, currencyCollapsed, currencyScroll);
        cy += GAP;

        cy = drawIconSection(gfx, mx, my, cy, I18n.get("screen.stockmarket.filter_items"),
                items, activeItems, itemsCollapsed, itemsScroll);
        cy += GAP;

        cy = drawOwnerSection(gfx, mx, my, cy);
        cy += GAP;

        cy = drawSimpleSection(gfx, mx, my, cy, I18n.get("screen.stockmarket.filter_dimension"),
                dims, dimCounts, activeDims, dimCollapsed, k -> C_DIM, k -> k);

        gfx.disableScissor();

        // Total content height = everything rendered (without scroll offset)
        totalContentH = (cy + scrollY) - (y + 2);

        // Scrollbar indicator if content overflows
        if (totalContentH > h) {
            int trackH = h - 4;
            int thumbH = Math.max(16, trackH * h / totalContentH);
            int thumbY = y + 2 + (scrollY * (trackH - thumbH)) / Math.max(1, totalContentH - h);
            gfx.fill(x + w - 3, y + 2, x + w - 1, y + h - 2, 0x30000000);
            gfx.fill(x + w - 3, thumbY, x + w - 1, thumbY + thumbH, 0x80000000);
        }
    }

    // ── Section renderers ─────────────────────────────────────────────────────

    private int drawSimpleSection(GuiGraphics gfx, int mx, int my, int startY,
                                  String title, List<String> keys,
                                  Map<String, Integer> counts, Set<String> active,
                                  boolean collapsed,
                                  java.util.function.Function<String, Integer> colorFn,
                                  java.util.function.Function<String, String> labelFn) {
        int entryH = entryHeight();
        int pad = padding();
        startY = drawSectionHeader(gfx, mx, my, startY, title, active.size(), collapsed);
        if (collapsed) return startY;
        for (String key : keys) {
            int count = counts.getOrDefault(key, 0);
            if (count == 0) continue;
            boolean isActive = active.contains(key);
            boolean hov = hit(mx, my, startY, entryH);
            drawRowBg(gfx, startY, entryH, isActive, hov);
            String label = truncate(labelFn.apply(key), w - pad * 2 - font.width("99") - 4);
            gfx.drawString(font, label, x + pad, startY + (entryH - 8) / 2,
                    isActive ? C_ACTIVE : colorFn.apply(key), false);
            drawCount(gfx, count, startY, entryH);
            startY += entryH;
        }
        return startY;
    }

    /**
     * Section with item icons (Currency / Items).
     */
    private int drawIconSection(GuiGraphics gfx, int mx, int my, int startY,
                                String title, List<IconEntry> entries,
                                Set<String> active, boolean collapsed, int scroll) {
        int entryH = entryHeight();
        int pad = padding();
        startY = drawSectionHeader(gfx, mx, my, startY, title, active.size(), collapsed);
        if (collapsed) return startY;

        // Render all entries — scissor handles clipping
        for (int i = 0; i < entries.size(); i++) {
            IconEntry fe = entries.get(i);
            boolean isActive = active.contains(fe.key());
            boolean hov = hit(mx, my, startY, entryH);
            drawRowBg(gfx, startY, entryH, isActive, hov);

            int iconX = x + pad;
            if (!fe.icon().isEmpty()) {
                gfx.renderItem(fe.icon(), iconX, startY + (entryH - 16) / 2);
                iconX += 18;
            } else {
                gfx.drawString(font, "©", iconX + 2, startY + (entryH - 8) / 2, C_GOLD, false);
                iconX += 14;
            }

            int maxLabelW = w - (iconX - x) - pad - font.width("99") - 4;
            String label = truncate(fe.label(), maxLabelW);
            gfx.drawString(font, label, iconX, startY + (entryH - 8) / 2,
                    isActive ? C_ACTIVE : C_TEXT, false);
            drawCount(gfx, fe.count(), startY, entryH);
            startY += entryH;
        }
        return startY;
    }

    /**
     * Type section — icons for Vendor, Table Cloth, Tradeworks
     */
    private int drawTypeSection(GuiGraphics gfx, int mx, int my, int startY) {
        int entryH = entryHeight();
        int pad = padding();
        startY = drawSectionHeader(gfx, mx, my, startY, I18n.get("screen.stockmarket.filter_type"),
                activeTypes.size(), typeCollapsed);
        if (typeCollapsed) return startY;
        for (String key : SHOP_TYPE_KEYS) {
            int count = typeCounts.getOrDefault(key, 0);
            if (count == 0) continue;
            boolean isActive = activeTypes.contains(key);
            boolean hov = hit(mx, my, startY, entryH);
            drawRowBg(gfx, startY, entryH, isActive, hov);

            int iconX = x + pad;
            if (key.equals("TABLECLOTH")) {
                ItemStack tcIcon = tableClothTypeIcon();
                if (!tcIcon.isEmpty()) {
                    gfx.renderItem(tcIcon, iconX, startY + (entryH - 16) / 2);
                    iconX += 18;
                }
            } else if (key.equals("TRADEWORKS")) {
                ItemStack twIcon = getItemStack("tradeworks:andesite_shelf");
                if (!twIcon.isEmpty()) {
                    gfx.renderItem(twIcon, iconX, startY + (entryH - 16) / 2);
                    iconX += 18;
                }
            } else {
                ItemStack vendorIcon = getItemStack("numismatics:spur");
                if (!vendorIcon.isEmpty()) {
                    gfx.renderItem(vendorIcon, iconX, startY + (entryH - 16) / 2);
                    iconX += 18;
                }
            }
            String label = truncate(typeDisplayLabel(key), w - (iconX - x) - pad - font.width("99") - 4);
            gfx.drawString(font, label, iconX, startY + (entryH - 8) / 2,
                    isActive ? C_ACTIVE : C_TEXT, false);
            drawCount(gfx, count, startY, entryH);
            startY += entryH;
        }
        return startY;
    }

    /**
     * Owner section — shows player head icon before name, all owners rendered (scissor clips)
     */
    private int drawOwnerSection(GuiGraphics gfx, int mx, int my, int startY) {
        int entryH = entryHeight();
        int pad = padding();
        startY = drawSectionHeader(gfx, mx, my, startY, I18n.get("screen.stockmarket.filter_owner"),
                activeOwners.size(), ownerCollapsed);
        if (ownerCollapsed) return startY;
        for (String key : owners) {
            boolean isActive = activeOwners.contains(key);
            boolean hov = hit(mx, my, startY, entryH);
            drawRowBg(gfx, startY, entryH, isActive, hov);

            int iconSize = Math.max(8, Math.min(entryH - 2, 12));
            UIHelper.drawPlayerHead(gfx, key, x + pad, startY + (entryH - iconSize) / 2, iconSize);
            int textX = x + pad + iconSize + 2;
            int maxLabelW = w - (textX - x) - pad - font.width("99") - 4;
            String label = truncate(key, maxLabelW);
            gfx.drawString(font, label, textX, startY + (entryH - 8) / 2,
                    isActive ? C_ACTIVE : C_TEXT, false);
            drawCount(gfx, ownerCounts.getOrDefault(key, 0), startY, entryH);
            startY += entryH;
        }
        return startY;
    }

    /**
     * Safely gets an ItemStack by registry key, returns EMPTY if not found
     */
    private static ItemStack getItemStack(String registryKey) {
        try {
            var loc = net.minecraft.resources.ResourceLocation.parse(registryKey);
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(loc);
            if (item != null && item != net.minecraft.world.item.Items.AIR)
                return new ItemStack(item);
        } catch (Exception ignored) {
        }
        return ItemStack.EMPTY;
    }

    /**
     * Create table cloth item — fallbacks because registry ids vary by version
     */
    private static ItemStack tableClothTypeIcon() {
        for (String id : List.of(
                "create:red_tablecloth",
                "create:white_tablecloth",
                "create:red_table_cloth",
                "minecraft:red_carpet")) {
            ItemStack s = getItemStack(id);
            if (!s.isEmpty()) return s;
        }
        return ItemStack.EMPTY;
    }

    private int drawSectionHeader(GuiGraphics gfx, int mx, int my, int startY,
                                  String title, int activeCount, boolean collapsed) {
        int hdrH = headerHeight();
        int pad = padding();
        UIHelper.blitScaled(gfx, GuiTextures.SIDEBAR_HDR, x, startY, w, hdrH,
                GuiTextures.Dimensions.SIDEBAR_HDR_W, GuiTextures.Dimensions.SIDEBAR_HDR_H);
        UIHelper.blitTiled(gfx, GuiTextures.SEPARATOR, x, startY + hdrH - 1, w, 1,
                GuiTextures.Dimensions.SEPARATOR_W, GuiTextures.Dimensions.SEPARATOR_H);
        gfx.drawString(font, (collapsed ? "▶ " : "▼ ") + title,
                x + pad, startY + (hdrH - 8) / 2, C_GOLD, false);
        if (activeCount > 0) {
            String badge = String.valueOf(activeCount);
            int bw = font.width(badge) + 6;
            int bx = x + w - bw - pad;
            UIHelper.blitScaled(gfx, GuiTextures.BADGE_COUNT,
                    bx, startY + 2, bw, hdrH - 4,
                    GuiTextures.Dimensions.BADGE_COUNT_W, GuiTextures.Dimensions.BADGE_COUNT_H);
            gfx.drawString(font, badge, bx + 3, startY + (hdrH - 8) / 2, 0xFF111111, false);
        }
        return startY + hdrH;
    }

    private void drawRowBg(GuiGraphics gfx, int startY, int rowH, boolean isActive, boolean hov) {
        if (isActive) {
            UIHelper.blitScaled(gfx, GuiTextures.SIDEBAR_ACTIVE, x, startY, w, rowH,
                    GuiTextures.Dimensions.SIDEBAR_ROW_W, GuiTextures.Dimensions.SIDEBAR_ROW_H);
            UIHelper.blitTiled(gfx, GuiTextures.PANEL_BORDER, x, startY, 2, rowH, 1, 1);
        } else if (hov) {
            UIHelper.blitScaled(gfx, GuiTextures.SIDEBAR_HOVER, x, startY, w, rowH,
                    GuiTextures.Dimensions.SIDEBAR_ROW_W, GuiTextures.Dimensions.SIDEBAR_ROW_H);
        }
    }

    private void drawCount(GuiGraphics gfx, int count, int startY, int rowH) {
        String cnt = String.valueOf(count);
        gfx.drawString(font, cnt, x + w - padding() - font.width(cnt),
                startY + (rowH - 8) / 2, C_DIM, false);
    }

    private String truncate(String s, int maxW) {
        if (font.width(s) <= maxW) return s;
        return font.plainSubstrByWidth(s, maxW - 4) + "…";
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    public boolean mouseClicked(double mx, double my, int button) {
        int hdrH = headerHeight();
        int entryH = entryHeight();
        int cy = y + 2 - scrollY;

        // Mode
        if (hitHeader(mx, my, cy)) {
            modeCollapsed = !modeCollapsed;
            return true;
        }
        cy += hdrH;
        if (!modeCollapsed) {
            for (String key : List.of("SELL", "BUY")) {
                if (modeCounts.getOrDefault(key, 0) == 0) continue;
                if (hit(mx, my, cy, entryH)) {
                    toggle(activeModes, key);
                    return true;
                }
                cy += entryH;
            }
        }
        cy += GAP;

        // Type
        if (hitHeader(mx, my, cy)) {
            typeCollapsed = !typeCollapsed;
            return true;
        }
        cy += hdrH;
        if (!typeCollapsed) {
            for (String key : SHOP_TYPE_KEYS) {
                if (typeCounts.getOrDefault(key, 0) == 0) continue;
                if (hit(mx, my, cy, entryH)) {
                    toggle(activeTypes, key);
                    return true;
                }
                cy += entryH;
            }
        }
        cy += GAP;

        // Currency
        if (hitHeader(mx, my, cy)) {
            currencyCollapsed = !currencyCollapsed;
            return true;
        }
        // Every list below is laid out exactly as render() draws it — all rows, offset only
        // by the panel-wide scrollY. Clicks used to walk a per-section window instead, so
        // with a long list expanded, everything after it was hit-tested at the wrong height.
        cy += hdrH;
        if (!currencyCollapsed) {
            for (IconEntry entry : currencies) {
                if (hitRow(mx, my, cy, entryH)) {
                    toggle(activeCurrencies, entry.key());
                    return true;
                }
                cy += entryH;
            }
        }
        cy += GAP;

        // Items
        if (hitHeader(mx, my, cy)) {
            itemsCollapsed = !itemsCollapsed;
            return true;
        }
        cy += hdrH;
        if (!itemsCollapsed) {
            for (IconEntry entry : items) {
                if (hitRow(mx, my, cy, entryH)) {
                    toggle(activeItems, entry.key());
                    return true;
                }
                cy += entryH;
            }
        }
        cy += GAP;

        // Owner
        if (hitHeader(mx, my, cy)) {
            ownerCollapsed = !ownerCollapsed;
            return true;
        }
        cy += hdrH;
        if (!ownerCollapsed) {
            for (String owner : owners) {
                if (hitRow(mx, my, cy, entryH)) {
                    toggle(activeOwners, owner);
                    return true;
                }
                cy += entryH;
            }
        }
        cy += GAP;

        // Dimension
        if (hitHeader(mx, my, cy)) {
            dimCollapsed = !dimCollapsed;
            return true;
        }
        cy += hdrH;
        if (!dimCollapsed) {
            for (String key : dims) {
                if (dimCounts.getOrDefault(key, 0) == 0) continue;
                if (hitRow(mx, my, cy, entryH)) {
                    toggle(activeDims, key);
                    return true;
                }
                cy += entryH;
            }
        }
        return false;
    }

    /** Row hit-test that also rejects rows scrolled outside the panel (they are scissored away). */
    private boolean hitRow(double mx, double my, int cy, int rowH) {
        return my >= y && my < y + h && hit(mx, my, cy, rowH);
    }

    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (mx < x || mx >= x + w) return false;
        int step = entryHeight();
        int maxScroll = Math.max(0, totalContentH - h);
        scrollY = Math.max(0, Math.min(scrollY + (dy < 0 ? step : -step), maxScroll));
        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean hitHeader(double mx, double my, int cy) {
        return mx >= x && mx < x + w && my >= cy && my < cy + headerHeight();
    }

    private boolean hit(double mx, double my, int cy, int rowH) {
        return mx >= x && mx < x + w && my >= cy && my < cy + rowH;
    }

    private void toggle(Set<String> set, String key) {
        if (set.contains(key)) set.remove(key);
        else set.add(key);
    }
}
