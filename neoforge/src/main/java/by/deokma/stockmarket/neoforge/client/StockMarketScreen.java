package by.deokma.stockmarket.neoforge.client;

import by.deokma.stockmarket.market.MarketData;
import by.deokma.stockmarket.neoforge.network.NetworkHandler;
import by.deokma.stockmarket.neoforge.network.RequestMarketPacket;
import by.deokma.stockmarket.neoforge.network.RequestShopListPacket;
import by.deokma.stockmarket.neoforge.network.RequestTradeStatsPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Container screen with three tabs: Shops, Market, Top Sellers.
 * Layout is fully responsive — adapts to any GUI scale / screen size.
 */
public class StockMarketScreen extends Screen {

    private static int lastTab = 0; // 0=Shops, 1=Market, 2=Top Sellers

    // ── Responsive layout ─────────────────────────────────────────────────────

    // Panel takes 90% of screen width, clamped between 380 and 900
    private int panelW() {
        return Mth.clamp((int) (width * 0.90f), 380, 900);
    }

    // Panel takes 85% of screen height, clamped between 200 and 500
    private int panelH() {
        return Mth.clamp((int) (height * 0.85f), 200, 500);
    }

    private int panelX() {
        return (width - panelW()) / 2;
    }

    private int panelY() {
        return (height - panelH()) / 2;
    }

    private int tabHeight() {
        return UIConstants.Layout.headerHeight(font.lineHeight);
    }

    // ── Colours ───────────────────────────────────────────────────────────────
    private static final int C_TEXT = 0xFF111111;
    private static final int C_GOLD = 0xFF8B6914;

    // ── State ─────────────────────────────────────────────────────────────────
    private int activeTab;
    private ShopListScreen shopScreen;
    private MarketMonitorScreen marketScreen;
    private TopSellersScreen topSellersScreen;

    public StockMarketScreen() {
        super(Component.translatable("screen.stockmarket.stock_market"));
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        super.init();
        activeTab = lastTab;

        int px = panelX(), py = panelY();
        int tabH = tabHeight();
        // contentY is just below the tab bar — each sub-screen draws its own toolbar
        int contentY = py + tabH;
        int contentH = panelH() - tabH;

        shopScreen = new ShopListScreen();
        shopScreen.init(minecraft, width, height);

        marketScreen = new MarketMonitorScreen();
        marketScreen.init(px, contentY, panelW(), contentH);

        topSellersScreen = new TopSellersScreen();
        topSellersScreen.init(px, contentY, panelW(), contentH);

        if (activeTab == 0) {
            NetworkHandler.sendToServer(new RequestShopListPacket());
        } else if (activeTab == 1) {
            MarketData.setLoading(true);
            NetworkHandler.sendToServer(new RequestMarketPacket());
        } else if (activeTab == 2) {
            NetworkHandler.sendToServer(new RequestTradeStatsPacket());
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void renderBackground(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        int px = panelX(), py = panelY();
        int pw = panelW(), ph = panelH();

        drawPanel(gfx, px, py);
        drawTabs(gfx, px, py, mouseX, mouseY);

        super.render(gfx, mouseX, mouseY, delta);

        // Scissor to panel content area (inside 1px black border)
        int tabH = tabHeight();
        UIHelper.enableScissor(gfx, px + 1, py + tabH + 1, pw - 2, ph - tabH - 2);
        switch (activeTab) {
            case 0 -> shopScreen.render(gfx, mouseX, mouseY, delta);
            case 1 -> marketScreen.render(gfx, mouseX, mouseY, delta);
            case 2 -> topSellersScreen.render(gfx, mouseX, mouseY, delta);
        }
        UIHelper.disableScissor(gfx);
    }

    private void drawPanel(GuiGraphics gfx, int px, int py) {
        int pw = panelW(), ph = panelH();
        int tabH = tabHeight();

        // Panel background (starts below tab area)
        UIHelper.blitTiled(gfx, GuiTextures.PANEL_BG,
                px + 1, py + tabH, pw - 2, ph - tabH - 1,
                GuiTextures.Dimensions.PANEL_BG_W, GuiTextures.Dimensions.PANEL_BG_H);

        // Black outline — full perimeter (1px)
        int border = 0xFF000000;
        // Top (flush with bottom of tabs)
        gfx.fill(px, py + tabH, px + pw, py + tabH + 1, border);
        // Bottom
        gfx.fill(px, py + ph - 1, px + pw, py + ph, border);
        // Left
        gfx.fill(px, py + tabH, px + 1, py + ph, border);
        // Right
        gfx.fill(px + pw - 1, py + tabH, px + pw, py + ph, border);
    }

    private void drawTabs(GuiGraphics gfx, int px, int py, int mx, int my) {
        int tabH = tabHeight();
        // Bottom accent line — black, flush with panel top border
        gfx.fill(px, py + tabH - 1, px + panelW(), py + tabH, 0xFF000000);

        String[] labels = {"Shops", "Market", "Top Sellers"};
        ResourceLocation[] icons = {
                GuiTextures.ICON_SHOPS,
                GuiTextures.ICON_MARKET,
                GuiTextures.ICON_TOP_SELLERS
        };
        int iconSize = Math.min(GuiTextures.Dimensions.ICON_W, tabH - 6);
        int tx = px + 8;
        for (int i = 0; i < labels.length; i++) {
            int iconW = iconSize;
            int textW = font.width(labels[i]);
            int tabW = iconW + 4 + textW + 10; // icon + gap + text + padding
            boolean active = (i == activeTab);
            boolean hovered = mx >= tx && mx < tx + tabW && my >= py + 5 && my < py + tabH - 1;

            // Tab pill
            ResourceLocation tabTex = active ? GuiTextures.TAB_ACTIVE : GuiTextures.TAB_INACTIVE;
            UIHelper.blitScaled(gfx, tabTex,
                    tx, py + 5, tabW, tabH - 4,
                    GuiTextures.Dimensions.TAB_W, GuiTextures.Dimensions.TAB_H);

            // Icon
            int iconY = py + 5 + (tabH - 4 - iconSize) / 2;
            UIHelper.blitScaled(gfx, icons[i],
                    tx + 5, iconY, iconSize, iconSize,
                    GuiTextures.Dimensions.ICON_W, GuiTextures.Dimensions.ICON_H);

            // Label
            int fg = active ? 0xFF111111 : C_TEXT;
            gfx.drawString(font, labels[i],
                    tx + 5 + iconW + 4,
                    py + 5 + (tabH - 4 - 8) / 2,
                    fg, false);
            tx += tabW + 4;
        }
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int px = panelX(), py = panelY();

        String[] labels = {"Shops", "Market", "Top Sellers"};
        int iconSize = Math.min(GuiTextures.Dimensions.ICON_W, tabHeight() - 6);
        int tx = px + 8;
        for (int i = 0; i < labels.length; i++) {
            int tabW = iconSize + 4 + font.width(labels[i]) + 10;
            if (mx >= tx && mx < tx + tabW && my >= py + 5 && my < py + tabHeight() - 1) {
                switchTab(i);
                return true;
            }
            tx += tabW + 4;
        }

        return switch (activeTab) {
            case 0 ->
                    shopScreen != null && shopScreen.mouseClicked(mx, my, button) || super.mouseClicked(mx, my, button);
            case 1 -> marketScreen.mouseClicked(mx, my, button) || super.mouseClicked(mx, my, button);
            case 2 -> topSellersScreen.mouseClicked(mx, my, button) || super.mouseClicked(mx, my, button);
            default -> super.mouseClicked(mx, my, button);
        };
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        return switch (activeTab) {
            case 0 -> shopScreen != null && shopScreen.mouseScrolled(mx, my, dx, dy);
            case 1 -> marketScreen.mouseScrolled(mx, my, dx, dy);
            case 2 -> topSellersScreen.mouseScrolled(mx, my, dx, dy);
            default -> super.mouseScrolled(mx, my, dx, dy);
        };
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        boolean handled = switch (activeTab) {
            case 0 -> shopScreen != null && shopScreen.mouseDragged(mx, my, button, dx, dy);
            case 1 -> marketScreen.mouseDragged(mx, my, button, dx, dy);
            case 2 -> topSellersScreen.mouseDragged(mx, my, button, dx, dy);
            default -> false;
        };
        return handled || super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        boolean handled = switch (activeTab) {
            case 0 -> shopScreen != null && shopScreen.mouseReleased(mx, my, button);
            case 1 -> marketScreen.mouseReleased(mx, my, button);
            case 2 -> topSellersScreen.mouseReleased(mx, my, button);
            default -> false;
        };
        return handled || super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (activeTab == 0 && shopScreen != null && shopScreen.keyPressed(key, scan, mods)) return true;
        if (activeTab == 1 && marketScreen.keyPressed(key, scan, mods)) return true;
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (activeTab == 0 && shopScreen != null && shopScreen.charTyped(c, mods)) return true;
        if (activeTab == 1 && marketScreen.charTyped(c, mods)) return true;
        return super.charTyped(c, mods);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void refreshEntries() {
        marketScreen.refreshEntries();
    }

    public void refreshShopEntries() {
        if (shopScreen != null) shopScreen.refreshEntries();
    }

    public void refreshTopSellers() {
        topSellersScreen.refresh();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void switchTab(int tab) {
        if (tab == activeTab) return;
        activeTab = tab;
        lastTab = tab;
        if (tab == 1 && MarketData.get().isEmpty()) {
            MarketData.setLoading(true);
            NetworkHandler.sendToServer(new RequestMarketPacket());
        } else if (tab == 2) {
            NetworkHandler.sendToServer(new RequestTradeStatsPacket());
        }
    }
}
