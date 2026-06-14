package by.deokma.stockmarket.neoforge.client;

import by.deokma.stockmarket.market.TradeStatsData;
import by.deokma.stockmarket.neoforge.network.NetworkHandler;
import by.deokma.stockmarket.neoforge.network.RequestTradeStatsPacket;
import by.deokma.stockmarket.shop.ShopListData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.*;
import java.util.stream.Collectors;

import static by.deokma.stockmarket.neoforge.client.UIConstants.Colors.*;

/**
 * Leaderboard: shop owners ranked by completed sales (vendor, table checkout, Stock Keeper).
 */
public class TopSellersScreen {

    private enum LeaderboardTab {
        SALES,
        SHOPS
    }

    private List<Map.Entry<String, Long>> salesEntries = new ArrayList<>();
    private List<Map.Entry<String, Long>> shopEntries = new ArrayList<>();
    private LeaderboardTab activeTab = LeaderboardTab.SALES;
    private int scrollOffset = 0;
    private boolean scrollDragging = false;
    private double dragStartY = 0;
    private int dragStartOffset = 0;
    private int x, y, w, h;
    private Font font;
    /**
     * True when {@link TradeStatsData} had rows for sales tab.
     */
    private boolean hasSalesData = false;
    private long lastRefreshMs = 0;

    private int refreshBtnX, refreshBtnY;
    private int salesTabX, salesTabY, salesTabW, salesTabH;
    private int shopsTabX, shopsTabY, shopsTabW, shopsTabH;

    private int padding() {
        return UIConstants.Layout.padding(Minecraft.getInstance().getWindow().getGuiScaledWidth());
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

    public void init(int px, int py, int pw, int ph) {
        this.x = px;
        this.y = py;
        this.w = pw;
        this.h = ph;
        this.font = Minecraft.getInstance().font;

        int pad = padding();
        int btnSize = buttonSize();
        int toolH = toolbarHeight();
        refreshBtnX = x + w - pad - btnSize - 2;
        refreshBtnY = y + (toolH - btnSize) / 2;

        refresh();
    }

    public void refresh() {
        List<Map.Entry<String, Long>> real = TradeStatsData.getTopSellers();
        salesEntries = new ArrayList<>(real);
        hasSalesData = !real.isEmpty();

        Map<String, Long> counts = new LinkedHashMap<>();
        for (var e : ShopListData.get()) {
            counts.merge(e.ownerName(), 1L, Long::sum);
        }
        shopEntries = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(64)
                .collect(Collectors.toCollection(ArrayList::new));

        scrollOffset = 0;
        lastRefreshMs = System.currentTimeMillis();
    }

    public void render(GuiGraphics gfx, int mx, int my, float delta) {
        UIHelper.blitTiled(gfx, GuiTextures.PANEL_BG, x, y, w, h,
                GuiTextures.Dimensions.PANEL_BG_W, GuiTextures.Dimensions.PANEL_BG_H);

        drawToolbar(gfx, mx, my);
        drawColumnHeaders(gfx, mx, my);

        int listTop = y + toolbarHeight() + colHdrHeight() + 2;
        int listBot = y + h - footerHeight();
        int listH = listBot - listTop;
        int rowH = rowHeight();
        int rowsVis = Math.max(1, listH / rowH);
        List<Map.Entry<String, Long>> entries = getActiveEntries();
        int maxScroll = Math.max(0, entries.size() - rowsVis);
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);

        UIHelper.enableScissor(gfx, x, y + toolbarHeight(), w, h - toolbarHeight());

        if (entries.isEmpty()) {
            String msg = activeTab == LeaderboardTab.SALES
                    ? "No sales data yet"
                    : "No shops indexed yet";
            String sub = activeTab == LeaderboardTab.SALES
                    ? "Click refresh after players complete trades."
                    : "Open Shops once, or place Vendor / Table Cloth blocks.";
            gfx.drawString(font, msg,
                    x + w / 2 - font.width(msg) / 2,
                    listTop + listH / 2 - font.lineHeight, TEXT_DIM, false);
            gfx.drawString(font, sub,
                    x + w / 2 - font.width(sub) / 2,
                    listTop + listH / 2 + 4, TEXT_DIM, false);
        } else {
            drawRows(gfx, entries, listTop, rowsVis, mx, my);
            drawScrollBar(gfx, listTop, listH, rowsVis);
        }

        drawFooter(gfx, rowsVis);

        UIHelper.disableScissor(gfx);
    }

    private void drawToolbar(GuiGraphics gfx, int mx, int my) {
        int toolH = toolbarHeight();
        int pad = padding();
        int btnSize = buttonSize();

        UIHelper.blitScaled(gfx, GuiTextures.TOOLBAR, x, y, w, toolH,
                GuiTextures.Dimensions.TOOLBAR_W, GuiTextures.Dimensions.TOOLBAR_H);

        String title = "Top sellers";
        gfx.drawString(font, title, x + pad, y + (toolH - font.lineHeight) / 2, TEXT, false);

        int tabPadX = 6;
        int tabW = Math.max(70, font.width("By sales") + tabPadX * 2);
        int tabH = Math.min(16, toolH - 4);
        int tabsTotalW = tabW * 2 + 4;
        int tabsX = x + w / 2 - tabsTotalW / 2;
        // Add small offset from toolbar for visual separation
        int tabsY = y + (toolH - tabH) / 2 + 1;

        salesTabX = tabsX;
        salesTabY = tabsY;
        salesTabW = tabW;
        salesTabH = tabH;
        shopsTabX = tabsX + tabW + 4;
        shopsTabY = tabsY;
        shopsTabW = tabW;
        shopsTabH = tabH;

        drawTab(gfx, salesTabX, salesTabY, salesTabW, salesTabH,
                "By sales", activeTab == LeaderboardTab.SALES);
        drawTab(gfx, shopsTabX, shopsTabY, shopsTabW, shopsTabH,
                "By shops", activeTab == LeaderboardTab.SHOPS);

        if (lastRefreshMs > 0) {
            long secAgo = (System.currentTimeMillis() - lastRefreshMs) / 1000;
            String ts = secAgo < 60 ? secAgo + "s ago" : (secAgo / 60) + "m ago";
            gfx.drawString(font, ts, refreshBtnX - font.width(ts) - 4,
                    y + (toolH - 8) / 2, 0xFF888888, false);
        }

        boolean hov = mx >= refreshBtnX && mx < refreshBtnX + btnSize
                && my >= refreshBtnY && my < refreshBtnY + btnSize;
        UIHelper.blitScaled(gfx, hov ? GuiTextures.BTN_REFRESH_HOV : GuiTextures.BTN_REFRESH,
                refreshBtnX, refreshBtnY, btnSize, btnSize,
                GuiTextures.Dimensions.BTN_W, GuiTextures.Dimensions.BTN_H);
    }

    private void drawColumnHeaders(GuiGraphics gfx, int mx, int my) {
        int top = y + toolbarHeight();
        int colH = colHdrHeight();
        int pad = padding();

        UIHelper.blitScaled(gfx, GuiTextures.COL_HEADER, x, top, w, colH,
                GuiTextures.Dimensions.COL_HEADER_W, GuiTextures.Dimensions.COL_HEADER_H);

        int colRank = 36;
        String valueHeader = activeTab == LeaderboardTab.SALES ? "Sales" : "Shops";
        int colSales = Math.max(72, font.width("9,999,999 " + valueHeader.toLowerCase(Locale.ROOT)) + 8);
        int ty = top + 3;

        gfx.drawString(font, "#", x + pad + 2, ty, 0xFF5C4A00, false);
        gfx.drawString(font, "Seller",
                x + pad + colRank + 2, ty, 0xFF5C4A00, false);
        gfx.drawString(font, valueHeader,
                x + w - pad - colSales + 2, ty, 0xFF5C4A00, false);

        UIHelper.blitTiled(gfx, GuiTextures.SEPARATOR, x + pad, top + colH - 1,
                w - pad * 2, 1,
                GuiTextures.Dimensions.SEPARATOR_W, GuiTextures.Dimensions.SEPARATOR_H);
    }

    private void drawRows(GuiGraphics gfx, List<Map.Entry<String, Long>> entries, int listTop, int rowsVis, int mx, int my) {
        int pad = padding();
        int rowH = rowHeight();
        int scrollW = 5;
        int usableW = w - pad * 2 - scrollW - 2;
        int colRank = 36;
        long maxVal = entries.isEmpty() ? 1 : Math.max(1, entries.get(0).getValue());

        for (int i = scrollOffset; i < Math.min(entries.size(), scrollOffset + rowsVis); i++) {
            var entry = entries.get(i);
            int rank = i + 1;
            int ry = listTop + (i - scrollOffset) * rowH;
            int rx = x + pad;
            boolean hov = mx >= rx && mx < rx + usableW && my >= ry && my < ry + rowH;

            if (hov) {
                UIHelper.blitTiled(gfx, GuiTextures.ROW_HOVER, rx, ry, usableW, rowH,
                        GuiTextures.Dimensions.ROW_W, GuiTextures.Dimensions.ROW_H);
            } else if (rank <= 3) {
                UIHelper.blitTiled(gfx, GuiTextures.ROW_HOT, rx, ry, usableW, rowH,
                        GuiTextures.Dimensions.ROW_W, GuiTextures.Dimensions.ROW_H);
            } else if (i % 2 == 0) {
                UIHelper.blitTiled(gfx, GuiTextures.ROW_ODD, rx, ry, usableW, rowH,
                        GuiTextures.Dimensions.ROW_W, GuiTextures.Dimensions.ROW_H);
            }

            int rankColor = switch (rank) {
                case 1 -> GOLD;
                case 2 -> 0xFF666666;
                case 3 -> 0xFF7A4A1A;
                default -> TEXT_DIM;
            };

            if (rank <= 3) {
                UIHelper.blitTiled(gfx, GuiTextures.HOT_STRIPE,
                        rx - pad, ry, 3, rowH,
                        GuiTextures.Dimensions.HOT_STRIPE_W, GuiTextures.Dimensions.HOT_STRIPE_H);
            }

            int ty = ry + (rowH - 8) / 2;
            String rankStr = rank + ".";
            gfx.drawString(font, rankStr, rx + 2, ty, rankColor, false);

            int nameColor = switch (rank) {
                case 1 -> GOLD;
                case 2 -> 0xFF666666;
                case 3 -> 0xFF7A4A1A;
                default -> TEXT;
            };
            int iconSize = UIHelper.playerHeadIconSize(rowH);
            int nameStartX = rx + colRank;
            UIHelper.drawPlayerHead(gfx, entry.getKey(),
                    nameStartX, ry + (rowH - iconSize) / 2, iconSize);
            nameStartX += iconSize + 2;

            String name = entry.getKey();
            int maxNameW = usableW - colRank - iconSize - 2 - 100;
            if (font.width(name) > maxNameW)
                name = font.plainSubstrByWidth(name, maxNameW - 4) + "\u2026";
            gfx.drawString(font, name, nameStartX, ty, nameColor, false);

            float ratio = (float) entry.getValue() / maxVal;
            int barMaxW = w / 5;
            int barW = Math.max(2, (int) (ratio * barMaxW));
            String countLabel = formatCount(entry.getValue());
            int countStrW = font.width(countLabel);
            int barX = x + w - pad - countStrW - 6 - barW - 4;
            int barAlpha = rank <= 3 ? 0x60 : 0x28;
            int barColor = (barAlpha << 24) | (rankColor & 0x00FFFFFF);
            gfx.fill(barX, ry + 4, barX + barW, ry + rowH - 4, barColor);

            int countColor = rank == 1 ? GOLD : GREEN;
            gfx.drawString(font, countLabel,
                    x + w - pad - countStrW - 6, ty, countColor, false);
        }
    }

    private void drawScrollBar(GuiGraphics gfx, int listTop, int listH, int rowsVis) {
        List<Map.Entry<String, Long>> entries = getActiveEntries();
        if (entries.size() <= rowsVis) return;
        int bx = x + w - 5;
        int thumbH = Math.max(16, listH * rowsVis / entries.size());
        int maxS = entries.size() - rowsVis;
        int thumbY = listTop + (scrollOffset * (listH - thumbH)) / Math.max(1, maxS);
        UIHelper.blitTiled(gfx, GuiTextures.SCROLL_TRACK, bx, listTop, 4, listH,
                GuiTextures.Dimensions.SCROLL_TRACK_W, GuiTextures.Dimensions.SCROLL_TRACK_H);
        UIHelper.blitTiled(gfx, GuiTextures.SCROLL_THUMB, bx, thumbY, 4, thumbH,
                GuiTextures.Dimensions.SCROLL_THUMB_W, GuiTextures.Dimensions.SCROLL_THUMB_H);
    }

    private void drawFooter(GuiGraphics gfx, int rowsVis) {
        List<Map.Entry<String, Long>> entries = getActiveEntries();
        int footH = footerHeight();
        int pad = padding();
        int fy = y + h - footH;

        UIHelper.blitTiled(gfx, GuiTextures.SEPARATOR, x, fy, w, 1,
                GuiTextures.Dimensions.SEPARATOR_W, GuiTextures.Dimensions.SEPARATOR_H);
        UIHelper.blitScaled(gfx, GuiTextures.FOOTER, x, fy + 1, w, footH - 1,
                GuiTextures.Dimensions.FOOTER_W, GuiTextures.Dimensions.FOOTER_H);

        String info = entries.size() + (entries.size() == 1 ? " seller" : " sellers");
        gfx.drawString(font, info, x + pad, fy + (footH - 8) / 2, TEXT_DIM, false);

        String note = activeTab == LeaderboardTab.SALES
                ? (hasSalesData ? "Vendor, barter & Stock Keeper sales" : "No sales have been logged yet")
                : "Ranked by indexed shops per seller";
        gfx.drawString(font, note,
                x + w - pad - font.width(note),
                fy + (footH - 8) / 2,
                activeTab == LeaderboardTab.SALES && hasSalesData ? GREEN : TEXT_DIM, false);
    }

    private String formatCount(long value) {
        String suffix = activeTab == LeaderboardTab.SALES ? " sales" : " shops";
        return String.format("%,d", value) + suffix;
    }

    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        List<Map.Entry<String, Long>> entries = getActiveEntries();
        int listTop = y + toolbarHeight() + colHdrHeight() + 2;
        int listBot = y + h - footerHeight();
        int listH = listBot - listTop;
        int rowsVis = Math.max(1, listH / rowHeight());
        int maxScroll = Math.max(0, entries.size() - rowsVis);
        scrollOffset = Mth.clamp(scrollOffset - (int) Math.signum(dy), 0, maxScroll);
        return true;
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (isInside(mx, my, salesTabX, salesTabY, salesTabW, salesTabH)) {
            if (activeTab != LeaderboardTab.SALES) {
                activeTab = LeaderboardTab.SALES;
                scrollOffset = 0;
            }
            return true;
        }
        if (isInside(mx, my, shopsTabX, shopsTabY, shopsTabW, shopsTabH)) {
            if (activeTab != LeaderboardTab.SHOPS) {
                activeTab = LeaderboardTab.SHOPS;
                scrollOffset = 0;
            }
            return true;
        }
        if (mx >= refreshBtnX && mx < refreshBtnX + buttonSize()
                && my >= refreshBtnY && my < refreshBtnY + buttonSize()) {
            NetworkHandler.sendToServer(new RequestTradeStatsPacket());
            refresh();
            return true;
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
            List<Map.Entry<String, Long>> entries = getActiveEntries();
            int listTop = y + toolbarHeight() + colHdrHeight() + 2;
            int listBot = y + h - footerHeight();
            int listH = listBot - listTop;
            int rowsVis = Math.max(1, listH / rowHeight());
            int maxScroll = Math.max(0, entries.size() - rowsVis);
            int thumbH = Math.max(16, entries.isEmpty() ? listH : listH * rowsVis / entries.size());
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

    private List<Map.Entry<String, Long>> getActiveEntries() {
        return activeTab == LeaderboardTab.SALES ? salesEntries : shopEntries;
    }

    private boolean isInside(double mx, double my, int px, int py, int pw, int ph) {
        return mx >= px && mx < px + pw && my >= py && my < py + ph;
    }

    private void drawTab(GuiGraphics gfx, int tx, int ty, int tw, int th, String label, boolean active) {
        ResourceLocation tabTex = active ? GuiTextures.TAB_ACTIVE : GuiTextures.TAB_INACTIVE;

        // Draw tab texture
        UIHelper.blitScaled(gfx, tabTex,
                tx, ty, tw, th,
                GuiTextures.Dimensions.TAB_W, GuiTextures.Dimensions.TAB_H);

        // Draw text
        int textColor = active ? 0xFF111111 : TEXT_DIM;
        gfx.drawString(font, label,
                tx + (tw - font.width(label)) / 2,
                ty + (th - font.lineHeight) / 2,
                textColor, false);

        // Add bottom shadow for inactive tabs (creates raised effect)
        if (!active) {
            gfx.fill(tx, ty + th - 1, tx + tw, ty + th, 0x40000000);
        }
    }
}
