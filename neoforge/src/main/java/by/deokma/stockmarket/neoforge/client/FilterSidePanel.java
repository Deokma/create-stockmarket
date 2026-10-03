package by.deokma.stockmarket.neoforge.client;

import by.deokma.stockmarket.market.MarketEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/**
 * Sidebar filter panel for the Market tab.
 * Two collapsible sections: Currency (payment) and Items (selling).
 * Each entry shows an item icon, truncated name, and lot count.
 */
public class FilterSidePanel {

    // ── Colours ───────────────────────────────────────────────────────────────
    private static final int C_TEXT      = 0xFF111111;
    private static final int C_DIM       = 0xFF333344;
    private static final int C_GOLD      = 0xFF8B6914;
    private static final int C_ACTIVE    = 0xFF8B6914;

    // ── Adaptive sizing methods ───────────────────────────────────────────────
    private int padding()      { return UIConstants.Layout.padding(Minecraft.getInstance().getWindow().getGuiScaledWidth()); }
    private int headerHeight() { return UIConstants.Layout.colHeaderHeight(font.lineHeight); }
    private int entryHeight()  { return (int)(UIConstants.Layout.rowHeight(font.lineHeight) * 0.75); }

    // ── State ─────────────────────────────────────────────────────────────────
    public record FilterEntry(ResourceLocation itemId, ItemStack icon, String label, int count) {}

    private List<FilterEntry> currencyEntries = new ArrayList<>();
    private List<FilterEntry> itemEntries     = new ArrayList<>();

    /** Active filters — null means "all" */
    private final Set<ResourceLocation> activeCurrencies = new LinkedHashSet<>();
    private final Set<ResourceLocation> activeItems      = new LinkedHashSet<>();

    private boolean currencyCollapsed = false;
    private boolean itemsCollapsed    = false;

    private int currencyScroll = 0;
    private int itemsScroll    = 0;
    /** Vertical scroll offset for the whole sidebar */
    private int scrollY = 0;
    /** Total rendered content height (updated each frame) */
    private int totalContentH = 0;

    private int x, y, w, h;
    private Font font;

    // ── Init ──────────────────────────────────────────────────────────────────

    public void init(int px, int py, int pw, int ph) {
        this.x = px; this.y = py; this.w = pw; this.h = ph;
        this.font = Minecraft.getInstance().font;
    }

    public void rebuild(List<MarketEntry> all) {
        // Use String keys internally to support both ResourceLocation and synthetic keys like coin names
        Map<String, FilterEntry> currMap = new LinkedHashMap<>();
        Map<ResourceLocation, FilterEntry> itemMap = new LinkedHashMap<>();

        for (MarketEntry e : all) {
            // Items section — what's being sold
            ResourceLocation itemId = e.itemId();
            itemMap.merge(itemId,
                    new FilterEntry(itemId, e.displayStack().copyWithCount(1),
                            e.labelText(), e.sellCount()),
                    (a, b) -> new FilterEntry(a.itemId(), a.icon(), a.label(), a.count() + b.count()));

            // Currency section
            if (e.isBarterOnly() && !e.barterItem().isEmpty()) {
                String payKey = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(e.barterItem().getItem()).toString();
                ResourceLocation payId = ResourceLocation.parse(payKey);
                FilterEntry fe = new FilterEntry(payId, e.barterItem().copyWithCount(1),
                        e.barterItem().getHoverName().getString(), e.sellCount());
                currMap.merge(payKey, fe,
                        (a, b) -> new FilterEntry(a.itemId(), a.icon(), a.label(), a.count() + b.count()));
            } else if (!e.isBarterOnly() && e.avgPrice() > 0) {
                // Numismatics Vendor — show dominant coin denomination (e.g. "Bevel")
                String coinKey   = UIConstants.Coins.dominantKey(e.avgPrice());
                String coinLabel = UIConstants.Coins.dominantLabel(e.avgPrice());
                ItemStack coinIc = UIConstants.Coins.icon(coinKey);
                ResourceLocation coinId = ResourceLocation.parse(coinKey);
                FilterEntry fe = new FilterEntry(coinId, coinIc, coinLabel, e.sellCount());
                currMap.merge(coinKey, fe,
                        (a, b) -> new FilterEntry(a.itemId(), a.icon(), a.label(), a.count() + b.count()));
            }
        }

        currencyEntries = new ArrayList<>(currMap.values());
        currencyEntries.sort(Comparator.comparingInt(FilterEntry::count).reversed());

        itemEntries = new ArrayList<>(itemMap.values());
        itemEntries.sort(Comparator.comparingInt(FilterEntry::count).reversed());

        // Prune stale active filters
        Set<ResourceLocation> validCurr = currencyEntries.stream()
                .map(FilterEntry::itemId).collect(java.util.stream.Collectors.toSet());
        activeCurrencies.retainAll(validCurr);
        Set<ResourceLocation> validItems = itemEntries.stream()
                .map(FilterEntry::itemId).collect(java.util.stream.Collectors.toSet());
        activeItems.retainAll(validItems);
    }

    // ── Filter API ────────────────────────────────────────────────────────────

    public boolean matchesCurrency(MarketEntry e) {
        if (activeCurrencies.isEmpty()) return true;
        if (e.isBarterOnly() && !e.barterItem().isEmpty()) {
            ResourceLocation payId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(e.barterItem().getItem());
            return activeCurrencies.contains(payId);
        }
        // Vendor — match by dominant coin key
        if (!e.isBarterOnly() && e.avgPrice() > 0) {
            ResourceLocation coinId = ResourceLocation.parse(UIConstants.Coins.dominantKey(e.avgPrice()));
            return activeCurrencies.contains(coinId);
        }
        return false;
    }

    public boolean matchesItem(MarketEntry e) {
        if (activeItems.isEmpty()) return true;
        return activeItems.contains(e.itemId());
    }

    // ── Render ────────────────────────────────────────────────────────────────

    public void render(GuiGraphics gfx, int mx, int my) {
        UIHelper.blitScaled(gfx, GuiTextures.SIDEBAR_BG, x, y, w, h,
                GuiTextures.Dimensions.SIDEBAR_BG_W, GuiTextures.Dimensions.SIDEBAR_BG_H);
        UIHelper.blitTiled(gfx, GuiTextures.PANEL_BORDER, x + w - 1, y, 1, h, 1, 1);

        gfx.enableScissor(x, y, x + w, y + h);

        int cy = y + 2 - scrollY;

        cy = drawSection(gfx, mx, my, cy, I18n.get("screen.stockmarket.filter_currency"), currencyEntries,
                activeCurrencies, currencyCollapsed, currencyScroll);
        cy += 4;
        cy = drawSection(gfx, mx, my, cy, I18n.get("screen.stockmarket.filter_items"), itemEntries,
                activeItems, itemsCollapsed, itemsScroll);

        gfx.disableScissor();

        totalContentH = (cy + scrollY) - (y + 2);

        // Scrollbar indicator
        if (totalContentH > h) {
            int trackH = h - 4;
            int thumbH = Math.max(16, trackH * h / totalContentH);
            int thumbY = y + 2 + (scrollY * (trackH - thumbH)) / Math.max(1, totalContentH - h);
            gfx.fill(x + w - 3, y + 2, x + w - 1, y + h - 2, 0x30000000);
            gfx.fill(x + w - 3, thumbY, x + w - 1, thumbY + thumbH, 0x80000000);
        }
    }

    private int drawSection(GuiGraphics gfx, int mx, int my, int startY,
                             String title, List<FilterEntry> entries,
                             Set<ResourceLocation> active, boolean collapsed, int scroll) {
        int hdrH = headerHeight();
        int entryH = entryHeight();
        int pad = padding();
        UIHelper.blitScaled(gfx, GuiTextures.SIDEBAR_HDR, x, startY, w, hdrH,
                GuiTextures.Dimensions.SIDEBAR_HDR_W, GuiTextures.Dimensions.SIDEBAR_HDR_H);
        UIHelper.blitTiled(gfx, GuiTextures.SEPARATOR, x, startY + hdrH - 1, w, 1,
                GuiTextures.Dimensions.SEPARATOR_W, GuiTextures.Dimensions.SEPARATOR_H);

        String arrow = collapsed ? "▶" : "▼";
        gfx.drawString(font, arrow + " " + title, x + pad, startY + (hdrH - 8) / 2, C_GOLD, false);

        // Active count badge
        if (!active.isEmpty()) {
            String badge = String.valueOf(active.size());
            int bw = font.width(badge) + 6;
            int bx = x + w - bw - pad;
            UIHelper.blitScaled(gfx, GuiTextures.BADGE_COUNT,
                    bx, startY + 2, bw, hdrH - 4,
                    GuiTextures.Dimensions.BADGE_COUNT_W, GuiTextures.Dimensions.BADGE_COUNT_H);
            gfx.drawString(font, badge, bx + 3, startY + (hdrH - 8) / 2, 0xFF111111, false);
        }

        int cy = startY + hdrH;
        if (collapsed) return cy;

        // Render all entries — scissor handles clipping
        for (FilterEntry fe : entries) {
            boolean isActive = active.contains(fe.itemId());
            boolean hov = mx >= x && mx < x + w && my >= cy && my < cy + entryH;

            if (isActive) {
                UIHelper.blitTiled(gfx, GuiTextures.SIDEBAR_ACTIVE, x, cy, w, entryH,
                        GuiTextures.Dimensions.SIDEBAR_ROW_W, GuiTextures.Dimensions.SIDEBAR_ROW_H);
                UIHelper.blitTiled(gfx, GuiTextures.PANEL_BORDER, x, cy, 2, entryH, 1, 1);
            } else if (hov) {
                UIHelper.blitTiled(gfx, GuiTextures.SIDEBAR_HOVER, x, cy, w, entryH,
                        GuiTextures.Dimensions.SIDEBAR_ROW_W, GuiTextures.Dimensions.SIDEBAR_ROW_H);
            }

            if (!fe.icon().isEmpty()) {
                gfx.renderItem(fe.icon(), x + pad, cy + (entryH - 16) / 2);
            } else {
                gfx.drawString(font, "©", x + pad + 4, cy + (entryH - 8) / 2, C_GOLD, false);
            }

            String label = fe.label();
            int maxLabelW = w - pad * 2 - 20 - font.width("99") - 4;
            if (font.width(label) > maxLabelW)
                label = font.plainSubstrByWidth(label, maxLabelW - 4) + "…";
            gfx.drawString(font, label, x + pad + 18, cy + (entryH - 8) / 2,
                    isActive ? C_ACTIVE : C_TEXT, false);

            String cnt = String.valueOf(fe.count());
            gfx.drawString(font, cnt, x + w - pad - font.width(cnt),
                    cy + (entryH - 8) / 2, C_DIM, false);

            cy += entryH;
        }

        return cy;
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    public boolean mouseClicked(double mx, double my, int button) {
        int hdrH = headerHeight();
        int entryH = entryHeight();
        int cy = y + 2 - scrollY;

        // Currency header
        if (mx >= x && mx < x + w && my >= cy && my < cy + hdrH) {
            currencyCollapsed = !currencyCollapsed;
            return true;
        }
        cy += hdrH;
        if (!currencyCollapsed) {
            for (FilterEntry fe : currencyEntries) {
                if (mx >= x && mx < x + w && my >= cy && my < cy + entryH) {
                    toggleFilter(activeCurrencies, fe.itemId());
                    return true;
                }
                cy += entryH;
            }
        }
        cy += 4;

        // Items header
        if (mx >= x && mx < x + w && my >= cy && my < cy + hdrH) {
            itemsCollapsed = !itemsCollapsed;
            return true;
        }
        cy += hdrH;
        if (!itemsCollapsed) {
            for (FilterEntry fe : itemEntries) {
                if (mx >= x && mx < x + w && my >= cy && my < cy + entryH) {
                    toggleFilter(activeItems, fe.itemId());
                    return true;
                }
                cy += entryH;
            }
        }
        return false;
    }

    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (mx < x || mx >= x + w) return false;
        int step = entryHeight();
        int maxScroll = Math.max(0, totalContentH - h);
        scrollY = Math.max(0, Math.min(scrollY + (dy < 0 ? step : -step), maxScroll));
        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void toggleFilter(Set<ResourceLocation> set, ResourceLocation id) {
        if (set.contains(id)) set.remove(id);
        else set.add(id);
    }
}
