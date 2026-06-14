package by.deokma.stockmarket.neoforge.client;

import by.deokma.stockmarket.market.MarketData;
import by.deokma.stockmarket.market.MarketEntry;
import by.deokma.stockmarket.market.TradeStatsData;
import by.deokma.stockmarket.neoforge.network.NetworkHandler;
import by.deokma.stockmarket.neoforge.network.RequestMarketPacket;
import by.deokma.stockmarket.shop.ShopListData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.*;
import java.util.stream.Collectors;

import static by.deokma.stockmarket.neoforge.client.UIConstants.Colors;

/**
 * Stock Exchange panel — two sections:
 * - Top:    Vendor shops (coin prices, % change, sparkline, volume)
 * - Bottom: TableCloth barter shops (item payment)
 */
public class MarketMonitorScreen {

    // ══════════════════════════════════════════════════════════════════════════
    // CONSTANTS
    // ══════════════════════════════════════════════════════════════════════════

    // Screen-specific colors (not shared with other screens)
    private static final int C_SPARK_UP = 0xFF1B6B1B;
    private static final int C_SPARK_DOWN = 0xFFB71C1C;
    private static final int C_SPARK_FLAT = 0xFF555566;

    // ── Thresholds ────────────────────────────────────────────────────────────
    // Sparkline dimensions (inline in row)
    private static final int SPARK_W = 40;
    private static final int SPARK_H = 12;
    // "Hot" threshold — rows with volume >= this get highlighted
    private static final int HOT_VOLUME = 5;


    private List<MarketEntry> vendorRows = new ArrayList<>();
    private List<MarketEntry> barterRows = new ArrayList<>();

    private int scrollOffset = 0;
    private boolean scrollDragging = false;
    private double dragStartY = 0;
    private int dragStartOffset = 0;
    private EditBox searchBox;
    // sortCol: 0=name 1=price 2=change% 3=volume 4=trend
    private int sortCol = 1;
    private boolean sortAsc = true;
    private long lastUpdateMs = 0;

    /**
     * Top traders from real sales stats: owner name → sales count, sorted desc
     */
    private List<Map.Entry<String, Long>> topTraders = new ArrayList<>();

    private int x, y, w, h;
    /**
     * Full panel origin and width (including sidebar)
     */
    private int panelX, panelW;
    /**
     * Cached sidebar width — computed once in init() from panelW
     */
    private int sidebarW;
    private Font font;

    private int refreshBtnX, refreshBtnY;

    // ── Adaptive sizing methods ───────────────────────────────────────────────
    private int padding() {
        return UIConstants.Layout.padding(Minecraft.getInstance().getWindow().getGuiScaledWidth());
    }

    private int sidebarWidth() {
        return sidebarW;
    }

    private int toolbarHeight() {
        return UIConstants.Layout.toolbarHeight(font.lineHeight);
    }

    private int colHdrHeight() {
        return UIConstants.Layout.colHeaderHeight(font.lineHeight);
    }

    private int rowHeight() {
        return UIConstants.Layout.rowHeight(font.lineHeight);
    }

    private int footerHeight() {
        return UIConstants.Layout.footerHeight(font.lineHeight);
    }

    private int buttonSize() {
        return UIConstants.Layout.buttonSize(font.lineHeight);
    }

    // ── Adaptive vendor column widths ─────────────────────────────────────────
    private int colItem() {
        return Math.max(100, (int) ((w - padding() * 2 - 10) * 0.36f));
    }

    private int colPrice() {
        return Math.max(60, (int) ((w - padding() * 2 - 10) * 0.20f));
    }

    private int colChange() {
        return Math.max(50, (int) ((w - padding() * 2 - 10) * 0.14f));
    }

    private int colVol() {
        return Math.max(30, (int) ((w - padding() * 2 - 10) * 0.10f));
    }

    private int colSpark() {
        return Math.max(40, (int) ((w - padding() * 2 - 10) * 0.13f));
    }

    private int colTrend() {
        return Math.max(24, (int) ((w - padding() * 2 - 10) * 0.07f));
    }

    // ── Adaptive barter column widths ─────────────────────────────────────────
    private int bcolItem() {
        return Math.max(120, (int) ((w - padding() * 2 - 10) * 0.40f));
    }

    private int bcolPayment() {
        return Math.max(80, (int) ((w - padding() * 2 - 10) * 0.27f));
    }

    private int bcolSell() {
        return Math.max(40, (int) ((w - padding() * 2 - 10) * 0.15f));
    }

    // ── Sidebar ───────────────────────────────────────────────────────────────
    private FilterSidePanel sidebar;


    public void init(int px, int py, int pw, int ph) {
        this.panelX = px;
        this.panelW = pw;
        this.sidebarW = UIConstants.Layout.sidebarWidth(pw); // compute once from panelW
        this.x = px + sidebarW; // table starts after sidebar
        this.y = py;
        this.w = pw - sidebarW;
        this.h = ph;
        this.font = Minecraft.getInstance().font;

        // Sidebar — starts BELOW the toolbar (same as ShopListScreen)
        int toolH = toolbarHeight();
        sidebar = new FilterSidePanel();
        sidebar.init(px, py + toolH, sidebarWidth(), ph - toolH);

        int pad = padding();
        int btnSize = buttonSize();

        // Refresh button — far right of full panel, vertically centred
        refreshBtnX = panelX + panelW - pad - btnSize - 2;
        refreshBtnY = this.y + (toolH - btnSize) / 2;

        // Search box — from right edge of sidebar to just before refresh button
        int searchY = this.y + (toolH - btnSize) / 2;
        int searchX = this.x + pad;
        int searchW = refreshBtnX - searchX - 4;
        searchBox = new EditBox(font,
                searchX + 4, searchY + (btnSize - font.lineHeight) / 2,
                searchW - 8, font.lineHeight + 2,
                Component.empty());
        searchBox.setHint(Component.literal("Search..."));
        searchBox.setBordered(false);
        searchBox.setTextColor(0xFF888888);
        searchBox.setResponder(s -> {
            scrollOffset = 0;
            applyFilter();
        });

        refreshEntries();
    }


    public void refreshEntries() {
        lastUpdateMs = System.currentTimeMillis();
        if (sidebar != null) sidebar.rebuild(MarketData.get());
        rebuildTopTraders();
        applyFilter();
    }

    private void rebuildTopTraders() {
        // Use real sales statistics from the server (TradeStatsData)
        topTraders = TradeStatsData.getTopSellers();
        // Fallback to shop count if no sales data yet
        if (topTraders.isEmpty()) {
            Map<String, Long> counts = new LinkedHashMap<>();
            for (var e : ShopListData.get()) {
                counts.merge(e.ownerName(), 1L, Long::sum);
            }
            topTraders = counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(5)
                    .collect(Collectors.toList());
        }
    }

    private void applyFilter() {
        String q = searchBox != null ? searchBox.getValue().toLowerCase(Locale.ROOT) : "";

        List<MarketEntry> all = MarketData.get().stream()
                .filter(e -> q.isEmpty()
                        || e.displayStack().getHoverName().getString().toLowerCase(Locale.ROOT).contains(q)
                        || (!e.barterItem().isEmpty() && e.barterItem().getHoverName().getString()
                        .toLowerCase(Locale.ROOT).contains(q)))
                .filter(e -> sidebar == null || (sidebar.matchesCurrency(e) && sidebar.matchesItem(e)))
                .collect(Collectors.toList());

        vendorRows = all.stream()
                .filter(e -> !e.isBarterOnly())
                .sorted((a, b) -> {
                    int cmp = switch (sortCol) {
                        case 1 -> Integer.compare(b.avgPrice(), a.avgPrice()); // default: highest price first
                        case 2 -> Double.compare(priceChangePct(b), priceChangePct(a));
                        case 3 -> Integer.compare(b.sellCount() + b.buyCount(), a.sellCount() + a.buyCount());
                        case 4 -> a.trend().compareTo(b.trend());
                        default -> a.displayStack().getHoverName().getString()
                                .compareToIgnoreCase(b.displayStack().getHoverName().getString());
                    };
                    return sortAsc ? cmp : -cmp;
                })
                .collect(Collectors.toCollection(ArrayList::new));

        barterRows = all.stream()
                .filter(MarketEntry::isBarterOnly)
                .sorted(Comparator.comparing(e -> e.displayStack().getHoverName().getString(),
                        String::compareToIgnoreCase))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * % change between oldest and newest snapshot. Returns 0 if < 2 snapshots.
     */
    private static double priceChangePct(MarketEntry e) {
        List<Integer> h = e.priceHistory();
        if (h.size() < 2) return 0.0;
        int first = h.get(0), last = h.get(h.size() - 1);
        if (first == 0) return 0.0;
        return (last - first) * 100.0 / first;
    }

    /**
     * Total virtual rows including section headers
     */
    private int totalRows() {
        int total = vendorRows.size();
        if (!barterRows.isEmpty()) total += 1 + barterRows.size(); // +1 for barter section header
        if (!vendorRows.isEmpty() && !barterRows.isEmpty()) total += 1; // separator
        return total;
    }


    public void render(GuiGraphics gfx, int mx, int my, float delta) {
        // Panel background — full width including sidebar area
        UIHelper.blitTiled(gfx, GuiTextures.PANEL_BG,
                panelX, y, panelW, h,
                GuiTextures.Dimensions.PANEL_BG_W, GuiTextures.Dimensions.PANEL_BG_H);

        // Sidebar (renders on top of bg, has its own scissor)
        if (sidebar != null) sidebar.render(gfx, mx, my);
//        UIHelper.blitTiled(gfx, GuiTextures.PANEL_BORDER,
//                panelW, y, 1, h,
//                1, GuiTextures.Dimensions.PANEL_BG_H);

        drawToolbar(gfx, mx, my);
        if (searchBox != null) searchBox.render(gfx, mx, my, delta);
        drawVendorColumnHeaders(gfx, mx + panelW, my);

        int listTop = y + toolbarHeight() + colHdrHeight() + 2;
        int listBot = y + h - footerHeight();
        int listH = listBot - listTop;
        int rowsVis = Math.max(1, listH / rowHeight());

        int maxScroll = Math.max(0, totalRows() - rowsVis);
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);

        // Scissor table area so rows/footer don't bleed onto the sidebar
        UIHelper.enableScissor(gfx, x, y + toolbarHeight(), w, h - toolbarHeight());

        if (MarketData.isLoading()) {
            gfx.drawString(font, "Loading...", x + w / 2 - font.width("Loading...") / 2, listTop + listH / 2, Colors.TEXT_DIM, false);
        } else if (vendorRows.isEmpty() && barterRows.isEmpty()) {
            gfx.drawString(font, "No market data", x + w / 2 - font.width("No market data") / 2, listTop + listH / 2, Colors.TEXT_DIM, false);
        } else {
            drawAllRows(gfx, listTop, rowsVis, mx, my);
            drawScrollBar(gfx, listTop, listH, rowsVis);
        }

        drawFooter(gfx, rowsVis);

        UIHelper.disableScissor(gfx);
    }

    // ── Toolbar ───────────────────────────────────────────────────────────────

    private void drawToolbar(GuiGraphics gfx, int mx, int my) {
        int toolH = toolbarHeight();
        int pad = padding();
        int btnSize = buttonSize();

        // Toolbar background — full panel width (covers sidebar too)
        UIHelper.blitScaled(gfx, GuiTextures.TOOLBAR, panelX, y, panelW, toolH,
                GuiTextures.Dimensions.TOOLBAR_W, GuiTextures.Dimensions.TOOLBAR_H);

        // Search box bg — from right edge of sidebar to just before refresh button
        int btnY = y + (toolH - btnSize) / 2;
        int searchX = x + pad;
        int searchW = refreshBtnX - searchX - 4;
        UIHelper.blitScaled(gfx, GuiTextures.SEARCH_BG,
                searchX, btnY, searchW, btnSize,
                GuiTextures.Dimensions.SEARCH_BG_W, GuiTextures.Dimensions.SEARCH_BG_H);

        // Refresh button — texture
        boolean hov = mx >= refreshBtnX && mx < refreshBtnX + btnSize
                && my >= refreshBtnY && my < refreshBtnY + btnSize;
        UIHelper.blitScaled(gfx, hov ? GuiTextures.BTN_REFRESH_HOV : GuiTextures.BTN_REFRESH,
                refreshBtnX, refreshBtnY, btnSize, btnSize,
                GuiTextures.Dimensions.BTN_W, GuiTextures.Dimensions.BTN_H);

        // Last update timestamp — left of refresh button
        if (lastUpdateMs > 0) {
            long secAgo = (System.currentTimeMillis() - lastUpdateMs) / 1000;
            String ts = secAgo < 60 ? secAgo + "s ago" : (secAgo / 60) + "m ago";
            gfx.drawString(font, ts, refreshBtnX - font.width(ts) - 4,
                    y + (toolH - 8) / 2, 0xFF888888, false);
        }
    }

    // ── Column Headers ────────────────────────────────────────────────────────

    private void drawVendorColumnHeaders(GuiGraphics gfx, int mx, int my) {
        int top = y + toolbarHeight();
        int colH = colHdrHeight();
        int pad = padding();
        // Column header background — stretch to fit
        UIHelper.blitScaled(gfx, GuiTextures.COL_HEADER, x, top, w, colH,
                GuiTextures.Dimensions.COL_HEADER_W, GuiTextures.Dimensions.COL_HEADER_H);

        // Column headers: Item | Price | Change | Vol | Chart | Trend
        int[] widths = {colItem(), colPrice(), colChange(), colVol(), colSpark(), colTrend()};
        String[] names = {"Item", "Price", "Change", "Vol", "Chart", "Trend"};
        int cx = x + pad;
        for (int i = 0; i < names.length; i++) {
            // Vertical divider before each column (skip first)
            if (i > 0) {
                gfx.fill(cx - 1, top + 3, cx, top + colH - 3, 0x60000000);
                gfx.fill(cx, top + 3, cx + 1, top + colH - 3, 0x40FFFFFF);
            }
            String label = names[i] + (i == sortCol ? (sortAsc ? " ▲" : " ▼") : "");
            boolean hov = mx >= cx && mx < cx + widths[i] && my >= top && my < top + colH;
            gfx.drawString(font, label, cx + 2, top + 3, hov ? Colors.GOLD : 0xFF5C4A00, false);
            cx += widths[i];
        }
        // Separator line at bottom
        UIHelper.blitTiled(gfx, GuiTextures.SEPARATOR, x + pad, top + colH - 1,
                w - pad * 2, 1,
                GuiTextures.Dimensions.SEPARATOR_W, GuiTextures.Dimensions.SEPARATOR_H);
    }

    /**
     * Renders all rows in a unified virtual list:
     * [vendor rows...] [separator] [barter header] [barter rows...]
     */
    private void drawAllRows(GuiGraphics gfx, int listTop, int rowsVis, int mx, int my) {
        int scrollBarW = 5;
        int pad = padding();
        int rowH = rowHeight();
        int usableW = w - pad * 2 - scrollBarW - 2;

        // Build virtual row list
        // Each entry: type 0=vendor, 1=separator, 2=barterHeader, 3=barter
        record VRow(int type, int dataIdx) {
        }
        List<VRow> vrows = new ArrayList<>();
        for (int i = 0; i < vendorRows.size(); i++) vrows.add(new VRow(0, i));
        if (!barterRows.isEmpty()) {
            if (!vendorRows.isEmpty()) vrows.add(new VRow(1, -1)); // separator
            vrows.add(new VRow(2, -1)); // barter section header
            for (int i = 0; i < barterRows.size(); i++) vrows.add(new VRow(3, i));
        }

        int rendered = 0;
        MarketEntry hoveredVendor = null;
        MarketEntry hoveredBarter = null;
        for (int vi = scrollOffset; vi < vrows.size() && rendered < rowsVis; vi++, rendered++) {
            VRow vr = vrows.get(vi);
            int ry = listTop + rendered * rowH;

            switch (vr.type()) {
                case 1 -> { // separator
                    UIHelper.blitTiled(gfx, GuiTextures.SEPARATOR, x + pad, ry + rowH / 2 - 1,
                            w - pad * 2, 1,
                            GuiTextures.Dimensions.SEPARATOR_W, GuiTextures.Dimensions.SEPARATOR_H);
                }
                case 2 -> { // barter section header
                    UIHelper.blitScaled(gfx, GuiTextures.COL_HEADER, x, ry, w, rowH,
                            GuiTextures.Dimensions.COL_HEADER_W, GuiTextures.Dimensions.COL_HEADER_H);
                    int cx = x + pad;
                    gfx.drawString(font, "Item", cx + 2, ry + (rowH - 8) / 2, 0xFF5C4A00, false);
                    cx += bcolItem();
                    gfx.fill(cx - 1, ry + 3, cx, ry + rowH - 3, 0x60000000);
                    gfx.fill(cx, ry + 3, cx + 1, ry + rowH - 3, 0x40FFFFFF);
                    gfx.drawString(font, "Payment", cx + 2, ry + (rowH - 8) / 2, 0xFF5C4A00, false);
                    cx += bcolPayment();
                    gfx.fill(cx - 1, ry + 3, cx, ry + rowH - 3, 0x60000000);
                    gfx.fill(cx, ry + 3, cx + 1, ry + rowH - 3, 0x40FFFFFF);
                    gfx.drawString(font, "Sell", cx + 2, ry + (rowH - 8) / 2, 0xFF5C4A00, false);
                    cx += bcolSell();
                    gfx.fill(cx - 1, ry + 3, cx, ry + rowH - 3, 0x60000000);
                    gfx.fill(cx, ry + 3, cx + 1, ry + rowH - 3, 0x40FFFFFF);
                    gfx.drawString(font, "Buy", cx + 2, ry + (rowH - 8) / 2, 0xFF5C4A00, false);
                    String label = "⇄ Barter";
                    gfx.drawString(font, label, x + w - pad - font.width(label) - scrollBarW - 4,
                            ry + (rowH - 8) / 2, Colors.GREEN, false);
                }
                case 0 -> {
                    MarketEntry hovered = drawVendorRow(gfx, vendorRows.get(vr.dataIdx()), vi, ry, usableW + 40, mx, my);
                    if (hovered != null) hoveredVendor = hovered;
                }
                case 3 -> {
                    MarketEntry hovered = drawBarterRow(gfx, barterRows.get(vr.dataIdx()), vi, ry, usableW + 40, mx, my);
                    if (hovered != null) hoveredBarter = hovered;
                }
            }
        }
        if (hoveredVendor != null) {
            drawVendorTooltip(gfx, hoveredVendor, mx, my);
        } else if (hoveredBarter != null) {
            drawBarterTooltip(gfx, hoveredBarter, mx, my);
        }
    }

    private MarketEntry drawVendorRow(GuiGraphics gfx, MarketEntry e, int vi, int ry, int usableW, int mx, int my) {
        int rx = x + padding();
        int rowH = rowHeight();
        boolean hov = mx >= rx && mx < rx + usableW && my >= ry && my < ry + rowH;
        int volume = e.sellCount() + e.buyCount();
        boolean hot = volume >= HOT_VOLUME;

        // Row background — use textures
        if (hov) {
            UIHelper.blitTiled(gfx, GuiTextures.ROW_HOVER, rx, ry, usableW, rowH,
                    GuiTextures.Dimensions.ROW_W, GuiTextures.Dimensions.ROW_H);
        } else if (hot) {
            UIHelper.blitTiled(gfx, GuiTextures.ROW_HOT, rx, ry, usableW, rowH,
                    GuiTextures.Dimensions.ROW_W, GuiTextures.Dimensions.ROW_H);
        } else if (vi % 2 == 0) {
            UIHelper.blitTiled(gfx, GuiTextures.ROW_ODD, rx, ry, usableW, rowH,
                    GuiTextures.Dimensions.ROW_W, GuiTextures.Dimensions.ROW_H);
        }

        // Hot indicator — left edge stripe
        if (hot) {
            UIHelper.blitTiled(gfx, GuiTextures.HOT_STRIPE, rx, ry, 2, rowH,
                    GuiTextures.Dimensions.HOT_STRIPE_W, GuiTextures.Dimensions.HOT_STRIPE_H);
        }

        int cx = rx, ty = ry + (rowH - 8) / 2;

        // ── Item icon + name ──────────────────────────────────────────────────
        gfx.renderItem(e.displayStack(), cx, ry + (rowH - 16) / 2);
        String name = e.displayStack().getHoverName().getString();
        if (font.width(name) > colItem() - 22) name = font.plainSubstrByWidth(name, colItem() - 26) + "…";
        gfx.drawString(font, name, cx + 18, ty, Colors.TEXT, false);
        cx += colItem();

        // ── Price (avg) ───────────────────────────────────────────────────────
        gfx.drawString(font, UIHelper.formatPrice(e.avgPrice()), cx + 2, ty, Colors.GOLD, false);
        cx += colPrice();

        // ── % Change ─────────────────────────────────────────────────────────
        double pct = priceChangePct(e);
        String pctStr = UIHelper.formatChangePct(pct);
        int pctColor = pct > 0.05 ? Colors.GREEN : pct < -0.05 ? Colors.RED : Colors.TEXT_DIM;
        gfx.drawString(font, pctStr, cx + 2, ty, pctColor, false);
        cx += colChange();

        // ── Volume ────────────────────────────────────────────────────────────
        gfx.drawString(font, String.valueOf(volume), cx + 2, ty, hot ? Colors.GOLD : Colors.TEXT_DIM, false);
        cx += colVol();

        // ── Inline sparkline ──────────────────────────────────────────────────
        List<Integer> hist = e.priceHistory();
        if (hist.size() >= 2) {
            int sparkColor = pct > 0.05 ? C_SPARK_UP : pct < -0.05 ? C_SPARK_DOWN : C_SPARK_FLAT;
            drawInlineSparkline(gfx, hist, cx + 2, ry + (rowH - SPARK_H) / 2, SPARK_W, SPARK_H, sparkColor);
        }
        cx += colSpark();

        // ── Trend symbol ─────────────────────────────────────────────────────
        String ts;
        int tc;
        switch (e.trend()) {
            case RISING -> {
                ts = "▲";
                tc = Colors.GREEN;
            }
            case FALLING -> {
                ts = "▼";
                tc = Colors.RED;
            }
            default -> {
                ts = "—";
                tc = Colors.TEXT_DIM;
            }
        }
        gfx.drawString(font, ts, cx + 2, ty, tc, false);

        // ── Vertical column dividers ──────────────────────────────────────────
        {
            int divX = rx + colItem();
            gfx.fill(divX - 1, ry + 2, divX, ry + rowH - 2, 0x50000000);
            gfx.fill(divX, ry + 2, divX + 1, ry + rowH - 2, 0x30FFFFFF);
            divX += colPrice();
            gfx.fill(divX - 1, ry + 2, divX, ry + rowH - 2, 0x50000000);
            gfx.fill(divX, ry + 2, divX + 1, ry + rowH - 2, 0x30FFFFFF);
            divX += colChange();
            gfx.fill(divX - 1, ry + 2, divX, ry + rowH - 2, 0x50000000);
            gfx.fill(divX, ry + 2, divX + 1, ry + rowH - 2, 0x30FFFFFF);
            divX += colVol();
            gfx.fill(divX - 1, ry + 2, divX, ry + rowH - 2, 0x50000000);
            gfx.fill(divX, ry + 2, divX + 1, ry + rowH - 2, 0x30FFFFFF);
            divX += colSpark();
            gfx.fill(divX - 1, ry + 2, divX, ry + rowH - 2, 0x50000000);
            gfx.fill(divX, ry + 2, divX + 1, ry + rowH - 2, 0x30FFFFFF);
        }

        return hov ? e : null;
    }

    /**
     * Draws a small line-chart sparkline directly in the row.
     */
    private void drawInlineSparkline(GuiGraphics gfx, List<Integer> history,
                                     int sx, int sy, int sw, int sh, int color) {
        float[] norm = UIHelper.normalizeHistory(history);
        if (norm.length < 2) return;
        // Draw as connected line segments (pixel-by-pixel)
        float stepX = (float) sw / (norm.length - 1);
        for (int i = 0; i < norm.length - 1; i++) {
            int x1 = sx + Math.round(i * stepX);
            int y1 = sy + sh - 1 - Math.round(norm[i] * (sh - 1));
            int x2 = sx + Math.round((i + 1) * stepX);
            int y2 = sy + sh - 1 - Math.round(norm[i + 1] * (sh - 1));
            // Draw a 1px thick line between (x1,y1) and (x2,y2)
            int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
            if (steps == 0) {
                gfx.fill(x1, y1, x1 + 1, y1 + 1, color);
                continue;
            }
            for (int s = 0; s <= steps; s++) {
                int px = x1 + (x2 - x1) * s / steps;
                int py = y1 + (y2 - y1) * s / steps;
                gfx.fill(px, py, px + 1, py + 1, color);
            }
        }
    }

    private MarketEntry drawBarterRow(GuiGraphics gfx, MarketEntry e, int vi, int ry, int usableW, int mx, int my) {
        int rx = x + padding();
        int rowH = rowHeight();
        boolean hov = mx >= rx && mx < rx + usableW && my >= ry && my < ry + rowH;

        // Row background — use textures
        if (hov) {
            UIHelper.blitTiled(gfx, GuiTextures.ROW_HOVER, rx, ry, usableW, rowH,
                    GuiTextures.Dimensions.ROW_W, GuiTextures.Dimensions.ROW_H);
        } else if (vi % 2 == 0) {
            UIHelper.blitTiled(gfx, GuiTextures.ROW_ODD, rx, ry, usableW, rowH,
                    GuiTextures.Dimensions.ROW_W, GuiTextures.Dimensions.ROW_H);
        }

        int cx = rx, ty = ry + (rowH - 8) / 2;

        // Selling item
        gfx.renderItem(e.displayStack(), cx, ry + (rowH - 16) / 2);
        String name = e.displayStack().getHoverName().getString();
        if (font.width(name) > bcolItem() - 22) name = font.plainSubstrByWidth(name, bcolItem() - 26) + "…";
        gfx.drawString(font, name, cx + 18, ty, Colors.TEXT, false);
        cx += bcolItem();

        // Payment item (icon + name)
        if (!e.barterItem().isEmpty()) {
            gfx.renderItem(e.barterItem(), cx, ry + (rowH - 16) / 2);
            String payName = e.barterItem().getHoverName().getString();
            if (e.barterItem().getCount() > 1) payName = "x" + e.barterItem().getCount() + " " + payName;
            int maxPay = bcolPayment() - 22;
            if (font.width(payName) > maxPay) payName = font.plainSubstrByWidth(payName, maxPay - 4) + "…";
            gfx.drawString(font, payName, cx + 18, ty, Colors.TEXT_DIM, false);
        } else {
            gfx.drawString(font, "?", cx + 2, ty, Colors.TEXT_DIM, false);
        }
        cx += bcolPayment();

        gfx.drawString(font, String.valueOf(e.sellCount()), cx + 2, ty, Colors.GREEN, false);
        cx += bcolSell();
        gfx.drawString(font, String.valueOf(e.buyCount()), cx + 2, ty, Colors.BLUE, false);

        // ── Vertical column dividers ──────────────────────────────────────────
        {
            int divX = rx + bcolItem();
            gfx.fill(divX - 1, ry + 2, divX, ry + rowH - 2, 0x50000000);
            gfx.fill(divX, ry + 2, divX + 1, ry + rowH - 2, 0x30FFFFFF);
            divX += bcolPayment();
            gfx.fill(divX - 1, ry + 2, divX, ry + rowH - 2, 0x50000000);
            gfx.fill(divX, ry + 2, divX + 1, ry + rowH - 2, 0x30FFFFFF);
            divX += bcolSell();
            gfx.fill(divX - 1, ry + 2, divX, ry + rowH - 2, 0x50000000);
            gfx.fill(divX, ry + 2, divX + 1, ry + rowH - 2, 0x30FFFFFF);
        }

        return hov ? e : null;
    }

    // ── Tooltips ──────────────────────────────────────────────────────────────

    private void drawVendorTooltip(GuiGraphics gfx, MarketEntry e, int mx, int my) {
        List<Component> tt = new ArrayList<>();
        tt.add(e.displayStack().getHoverName().copy().withStyle(s -> s.withColor(Colors.GOLD)));

        // Price info
        tt.add(Component.literal("§7Avg Price: §f" + UIHelper.formatPrice(e.avgPrice())));
        tt.add(Component.literal("§7Min Price: §f" + UIHelper.formatPrice(e.minPrice())));

        // % change
        double pct = priceChangePct(e);
        String pctStr = UIHelper.formatChangePct(pct);
        String pctColored = pct > 0.05 ? "§a" + pctStr : pct < -0.05 ? "§c" + pctStr : "§7" + pctStr;
        tt.add(Component.literal("§724h Change: " + pctColored));

        // Volume
        int vol = e.sellCount() + e.buyCount();
        tt.add(Component.literal("§7Volume: §f" + vol
                + " §8(§a" + e.sellCount() + " sell §8/ §b" + e.buyCount() + " buy§8)"));

        // Trend
        String ts = switch (e.trend()) {
            case RISING -> "§a▲ Rising";
            case FALLING -> "§c▼ Falling";
            default -> "§7— Stable";
        };
        tt.add(Component.literal("§7Trend: " + ts));

        if (vol >= HOT_VOLUME) tt.add(Component.literal("§6🔥 High activity"));

        gfx.renderTooltip(font, tt.stream().map(Component::getVisualOrderText).collect(Collectors.toList()), mx, my);

        // Full-size chart in tooltip
        if (e.priceHistory().size() >= 2) {
            int chartX = Math.min(mx + 4, Minecraft.getInstance().getWindow().getGuiScaledWidth() - 92);
            int chartY = Math.min(my + tt.size() * 10 + 6, Minecraft.getInstance().getWindow().getGuiScaledHeight() - 38);
            drawMiniChart(gfx, e.priceHistory(), chartX, chartY, 88, 34);
        }
    }

    private void drawBarterTooltip(GuiGraphics gfx, MarketEntry e, int mx, int my) {
        List<Component> tt = new ArrayList<>();
        tt.add(e.displayStack().getHoverName().copy().withStyle(s -> s.withColor(Colors.GOLD)));
        tt.add(Component.literal("§8Create TableCloth Shop"));
        if (!e.barterItem().isEmpty()) {
            String pay = e.barterItem().getHoverName().getString()
                    + (e.barterItem().getCount() > 1 ? " x" + e.barterItem().getCount() : "");
            tt.add(Component.literal("§7Payment: §f" + pay));
        }
        tt.add(Component.literal("§7Sellers: §a" + e.sellCount() + "  §7Buyers: §b" + e.buyCount()));
        gfx.renderTooltip(font, tt.stream().map(Component::getVisualOrderText).collect(Collectors.toList()), mx, my);
    }

    private void drawMiniChart(GuiGraphics gfx, List<Integer> history, int cx, int cy, int cw, int ch) {
        float[] norm = UIHelper.normalizeHistory(history);
        int barW = Math.max(1, cw / norm.length);
        gfx.fill(cx - 1, cy - 1, cx + cw + 1, cy + ch + 1, 0xAA000000);
        for (int i = 0; i < norm.length; i++) {
            int barH = Math.max(1, (int) (norm[i] * ch));
            gfx.fill(cx + i * barW, cy + ch - barH, cx + i * barW + barW - 1, cy + ch, Colors.ACCENT);
        }
    }

    // ── Scrollbar ─────────────────────────────────────────────────────────────

    private void drawScrollBar(GuiGraphics gfx, int listTop, int listH, int rowsVis) {
        int total = totalRows();
        if (total <= rowsVis) return;
        // bx+4 must not exceed x+w-1 (right border is 1px at panelX+panelW-1)
        int bx = x + w - 5;
        int thumbH = Math.max(16, listH * rowsVis / total);
        int maxS = total - rowsVis;
        int thumbY = listTop + (scrollOffset * (listH - thumbH)) / Math.max(1, maxS);

        // Fill gap between scrollbar and right wall
        gfx.fill(bx + 4, listTop, x + w - 1, listTop + listH, 0xFFC6C6C6);
        // Scrollbar track
        UIHelper.blitTiled(gfx, GuiTextures.SCROLL_TRACK, bx, listTop, 4, listH,
                GuiTextures.Dimensions.SCROLL_TRACK_W, GuiTextures.Dimensions.SCROLL_TRACK_H);
        // Scrollbar thumb
        UIHelper.blitTiled(gfx, GuiTextures.SCROLL_THUMB, bx, thumbY, 4, thumbH,
                GuiTextures.Dimensions.SCROLL_THUMB_W, GuiTextures.Dimensions.SCROLL_THUMB_H);
    }


    private void drawFooter(GuiGraphics gfx, int rowsVis) {
        int footH = footerHeight();
        int pad = padding();
        int fy = y + h - footH;
        // Footer separator
        UIHelper.blitTiled(gfx, GuiTextures.SEPARATOR, x, fy, w, 1,
                GuiTextures.Dimensions.SEPARATOR_W, GuiTextures.Dimensions.SEPARATOR_H);
        // Footer background — stretch to fit
        UIHelper.blitScaled(gfx, GuiTextures.FOOTER, x, fy + 1, w, footH - 1,
                GuiTextures.Dimensions.FOOTER_W, GuiTextures.Dimensions.FOOTER_H);

        int vCount = vendorRows.size(), bCount = barterRows.size();
        int textY = fy + (footH - font.lineHeight) / 2;

        // Left: item counts
        String info = vCount + " listed  " + bCount + " barter";
        gfx.drawString(font, info, x + pad, textY, Colors.TEXT_DIM, false);
        int leftEdge = x + pad + font.width(info) + 6;

        // Right: sort hint
        String hint = "↑↓ sort";
        int rightEdge = x + w - pad - font.width(hint) - 4;
        gfx.drawString(font, hint, rightEdge, textY, Colors.TEXT_DIM, false);

        // Centre: top traders — fits between leftEdge and rightEdge
        if (!topTraders.isEmpty()) {
            boolean hasRealData = !TradeStatsData.getTopSellers().isEmpty();
            String labelPlain = hasRealData ? "🏆 " : "Top Seller ";
            // No §-colour code on the text label so the Colors.TEXT_DIM param applies (matches the
            // neighbouring footer labels). The trophy keeps its gold §6 code.
            String labelStyled = hasRealData ? "§6🏆 " : "Top Seller ";
            int iconSize = UIHelper.playerHeadIconSize(footH);
            int tx = leftEdge;

            gfx.drawString(font, labelStyled, tx, textY, Colors.TEXT_DIM, false);
            tx += font.width(labelPlain);

            for (int i = 0; i < topTraders.size(); i++) {
                var entry = topTraders.get(i);
                String countStr = " (" + entry.getValue() + ")";
                int entryW = iconSize + 2 + font.width(entry.getKey()) + font.width(countStr) + 4;
                if (tx + entryW > rightEdge - 4) break;

                int nameColor = switch (i) {
                    case 0 -> 0xFF8B6914;
                    case 1 -> 0xFF666666;
                    case 2 -> 0xFF7A4A1A;
                    default -> Colors.TEXT_DIM;
                };
                UIHelper.drawPlayerHead(gfx, entry.getKey(),
                        tx, fy + (footH - iconSize) / 2, iconSize);
                tx += iconSize + 2;
                gfx.drawString(font, entry.getKey(), tx, textY, nameColor, false);
                tx += font.width(entry.getKey());
                gfx.drawString(font, countStr, tx, textY, Colors.TEXT_DIM, false);
                tx += font.width(countStr) + 4;
            }
        }
    }


    public boolean mouseClicked(double mx, double my, int button) {
        // Sidebar first
        if (sidebar != null && sidebar.mouseClicked(mx, my, button)) {
            applyFilter(); // re-filter when sidebar selection changes
            return true;
        }

        if (mx >= refreshBtnX && mx < refreshBtnX + buttonSize()
                && my >= refreshBtnY && my < refreshBtnY + buttonSize()) {
            MarketData.setLoading(true);
            NetworkHandler.sendToServer(new RequestMarketPacket());
            return true;
        }

        // Search box — explicitly set focus based on click position
        if (searchBox != null) {
            boolean inBox = mx >= searchBox.getX() && mx < searchBox.getX() + searchBox.getWidth()
                    && my >= searchBox.getY() && my < searchBox.getY() + searchBox.getHeight();
            searchBox.setFocused(inBox);
            if (inBox) {
                searchBox.mouseClicked(mx, my, button);
                return true;
            }
        }

        int colTop = y + toolbarHeight();
        if (my >= colTop && my < colTop + colHdrHeight()) {
            int[] widths = {colItem(), colPrice(), colChange(), colVol(), colSpark(), colTrend()};
            int cx = x + padding();
            for (int i = 0; i < widths.length; i++) {
                if (mx >= cx && mx < cx + widths[i]) {
                    if (i == 4) {
                        cx += widths[i];
                        continue;
                    } // chart column not sortable
                    if (sortCol == i) sortAsc = !sortAsc;
                    else {
                        sortCol = i;
                        sortAsc = true;
                    }
                    applyFilter();
                    return true;
                }
                cx += widths[i];
            }
        }
        // Scrollbar drag start
        if (button == 0) {
            int listTop = y + toolbarHeight() + colHdrHeight() + 2;
            int listBot = y + h - footerHeight();
            int listH = listBot - listTop;
            int bx = x + w - 5;
            if (mx >= bx && mx < bx + 4 && my >= listTop && my < listTop + listH) {
                scrollDragging = true;
                dragStartY = my;
                dragStartOffset = scrollOffset;
                return true;
            }
        }

        return false;
    }

    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (scrollDragging && button == 0) {
            int listTop = y + toolbarHeight() + colHdrHeight() + 2;
            int listBot = y + h - footerHeight();
            int listH = listBot - listTop;
            int rowsVis = Math.max(1, listH / rowHeight());
            int total = totalRows();
            int maxScroll = Math.max(0, total - rowsVis);
            int thumbH = Math.max(16, total > 0 ? listH * rowsVis / total : listH);
            int trackH = listH - thumbH;
            if (trackH > 0) {
                int delta = (int) Math.round((my - dragStartY) * maxScroll / (double) trackH);
                scrollOffset = Mth.clamp(dragStartOffset + delta, 0, maxScroll);
            }
            return true;
        }
        return false;
    }

    public boolean mouseReleased(double mx, double my, int button) {
        if (scrollDragging && button == 0) {
            scrollDragging = false;
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (sidebar != null && sidebar.mouseScrolled(mx, my, dx, dy)) return true;
        scrollOffset = Math.max(0, scrollOffset - (int) Math.signum(dy));
        return true;
    }

    public boolean keyPressed(int key, int scan, int mods) {
        if (searchBox != null && searchBox.isFocused()) return searchBox.keyPressed(key, scan, mods);
        return false;
    }

    public boolean charTyped(char c, int mods) {
        if (searchBox != null && searchBox.isFocused()) return searchBox.charTyped(c, mods);
        return false;
    }
}
