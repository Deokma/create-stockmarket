package by.deokma.stockmarket.neoforge.client;

import by.deokma.stockmarket.neoforge.network.NetworkHandler;
import by.deokma.stockmarket.neoforge.network.RequestShopListPacket;
import by.deokma.stockmarket.shop.ShopEntry;
import by.deokma.stockmarket.shop.ShopListData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.*;
import java.util.stream.Collectors;

import static by.deokma.stockmarket.neoforge.client.UIConstants.Colors;

public class ShopListScreen extends Screen {

    // ══════════════════════════════════════════════════════════════════════════
    // CONSTANTS
    // ══════════════════════════════════════════════════════════════════════════

    // Screen-specific colors (not shared with other screens)
    private static final int C_STAR_ON = 0xFF8B6914;  // dark gold star
    private static final int C_STAR_OFF = 0xFF999999;  // grey star
    private static final int COL_FAV = 16;

    // ══════════════════════════════════════════════════════════════════════════
    // ADAPTIVE SIZING METHODS
    // ══════════════════════════════════════════════════════════════════════════

    // Adaptive layout dimensions based on font and screen size
    private int padding() {
        return UIConstants.Layout.padding(width);
    }

    private int sidebarWidth() {
        return UIConstants.Layout.sidebarWidth(panelW());
    }

    private int headerHeight() {
        return UIConstants.Layout.headerHeight(font.lineHeight);
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

    // Responsive — matches StockMarketScreen exactly
    private int panelW() {
        return Mth.clamp((int) (width * 0.90f), 380, 900);
    }

    private int panelH() {
        return Mth.clamp((int) (height * 0.85f), 200, 500);
    }

    private int panelX() {
        return (width - panelW()) / 2;
    }

    private int panelY() {
        return (height - panelH()) / 2;
    }

    private int tableX() {
        return panelX() + sidebarWidth();
    }

    private int tableW() {
        return panelW() - sidebarWidth();
    }

    // ── Column Widths ─────────────────────────────────────────────────────────
    // Columns — proportional to available table width
    // Total fixed: FAV(16) + scrollbar(5) + padding*2 = ~41
    // Remaining distributed: Item 35%, Price 18%, Owner 22%, Mode 12%, Dim 13%
    private int colItem() {
        int available = tableW() - padding() * 2 - 5 - COL_FAV;
        return Math.max(100, (int) (available * 0.35f));
    }

    private int colPrice() {
        int available = tableW() - padding() * 2 - 5 - COL_FAV;
        return Math.max(60, (int) (available * 0.18f));
    }

    private int colOwner() {
        int available = tableW() - padding() * 2 - 5 - COL_FAV;
        return Math.max(70, (int) (available * 0.22f));
    }

    private int colMode() {
        int available = tableW() - padding() * 2 - 5 - COL_FAV;
        return Math.max(44, (int) (available * 0.12f));
    }

    private int colDim() {
        int available = tableW() - padding() * 2 - 5 - COL_FAV;
        return Math.max(50, (int) (available * 0.13f));
    }


    /**
     * One UI row: Vendor = single offer; TableCloth/Tradeworks = merged stall
     */
    private List<ShopDisplayRow> displayRows = new ArrayList<>();
    private int scrollOffset = 0;
    private boolean scrollDragging = false;
    private double dragStartY = 0;
    private int dragStartOffset = 0;
    private EditBox searchBox;
    private boolean loading = true;

    // Sorting: 0=item 1=price 2=owner 3=mode 4=dim
    private int sortCol = 0;
    private boolean sortAsc = true;

    // Favourites — persisted in memory for the session
    private static final Set<String> favourites = new LinkedHashSet<>();

    // Filter tabs: "all" | "fav"
    private String activeTab = "all";
    // playerTabs removed — owner filter is in the sidebar

    // Sidebar filter panel
    private ShopFilterSidePanel sidebar;
    private boolean xaeroAvailable;
    private boolean mapMenuVisible;
    private ShopEntry mapMenuEntry;
    private int mapMenuX;
    private int mapMenuY;

    private static final class ShopDisplayRow {
        final List<ShopEntry> offers;

        ShopDisplayRow(List<ShopEntry> offers) {
            this.offers = List.copyOf(offers);
        }

        ShopEntry anchor() {
            return offers.get(0);
        }

        boolean isStall() {
            return anchor().usesItemPrice();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ══════════════════════════════════════════════════════════════════════════

    public ShopListScreen() {
        super(Component.translatable("screen.stockmarket.shop_list"));
    }

    @Override
    protected void init() {
        super.init();
        int px = panelX(), py = panelY();
        int pw = panelW();
        int pad = padding();
        int btnSize = buttonSize();

        // Toolbar row spans the FULL panel width (sidebar + table)
        int toolbarY = py + headerHeight();
        int toolH = toolbarHeight();

        // Sidebar starts BELOW the toolbar, extends to the bottom of the panel
        sidebar = new ShopFilterSidePanel();
        sidebar.init(px, toolbarY + toolH, sidebarWidth(), panelH() - headerHeight() - toolH);

        xaeroAvailable = XaeroWaypointCompat.isPresent();

        // Button positions — vertically centred in toolbar
        int btnY = toolbarY + (toolH - btnSize) / 2;
        int btnRefX = px + pw - pad - btnSize * 2 - 4;

        // Search box — from sidebar edge to just before buttons, vertically centred
        int searchX = px + sidebarWidth() + pad;
        int searchH = btnSize; // same height as buttons
        int searchW = btnRefX - searchX - 4;
        int searchY = btnY;
        searchBox = new EditBox(font,
                searchX + 4, searchY + (searchH - font.lineHeight) / 2,
                searchW - 8, font.lineHeight + 2,
                Component.empty());
        searchBox.setHint(Component.translatable("screen.stockmarket.search_hint"));
        searchBox.setBordered(false);
        searchBox.setTextColor(0xFF888888);
        searchBox.setResponder(s -> {
            scrollOffset = 0;
            applyFilter();
        });
        addRenderableWidget(searchBox);

        requestData();
    }


    private void requestData() {
        loading = true;
        displayRows.clear();
        NetworkHandler.sendToServer(new RequestShopListPacket());
    }

    public void refreshEntries() {
        loading = false;
        if (sidebar != null) sidebar.rebuild(ShopListData.get());
        applyFilter();
    }

    /**
     * Vendor / single cloth slot — один ключ на строку в избранном
     */
    private static String favKey(ShopEntry e) {
        var itemKey = BuiltInRegistries.ITEM.getKey(e.sellingItem().getItem());
        return e.dimensionId() + "|" + e.pos().toShortString() + "|" + e.offerIndex() + "|" + itemKey;
    }

    /**
     * Избранное целиком для скатерти / Tradeworks (одна звезда на блок)
     */
    private static String stallFavouriteKey(ShopEntry anchor) {
        return "stall|" + clothBlockKey(anchor);
    }

    /**
     * Matches server {@code VendorRegistry} cloth keys without {@code #slot} suffix
     */
    private static String clothBlockKey(ShopEntry e) {
        if (!e.usesItemPrice()) return "";
        var p = e.pos();
        return e.dimensionId() + "|" + p.getX() + "," + p.getY() + "," + p.getZ();
    }

    private boolean rowFavourite(ShopDisplayRow row) {
        ShopEntry a = row.anchor();
        if (row.isStall()) return favourites.contains(stallFavouriteKey(a));
        return favourites.contains(favKey(a));
    }

    private static boolean entryMatchesSearch(ShopEntry e, String q) {
        if (q.isEmpty()) return true;
        return e.sellingItem().getHoverName().getString().toLowerCase(Locale.ROOT).contains(q)
                || e.ownerName().toLowerCase(Locale.ROOT).contains(q);
    }

    private boolean entryPassesSidebarAndTab(ShopEntry e, String q) {
        if (sidebar != null && !sidebar.matches(e)) return false;
        if ("fav".equals(activeTab)) {
            if (e.usesItemPrice()) {
                if (!favourites.contains(stallFavouriteKey(e))) return false;
            } else {
                if (!favourites.contains(favKey(e))) return false;
            }
        }
        return entryMatchesSearch(e, q);
    }

    /**
     * Весь прилавок виден, если хотя бы одна позиция проходит фильтры и поиск
     */
    private boolean stallGroupMatches(List<ShopEntry> group, String q) {
        return group.stream().anyMatch(e -> entryPassesSidebarAndTab(e, q));
    }

    private static String primaryItemLabel(ShopDisplayRow row) {
        return row.offers.stream()
                .map(e -> e.sellingItem().getHoverName().getString())
                .min(String.CASE_INSENSITIVE_ORDER)
                .orElse("");
    }

    private void applyFilter() {
        String q = searchBox != null ? searchBox.getValue().toLowerCase(Locale.ROOT) : "";
        List<ShopEntry> all = ShopListData.get();

        Map<String, List<ShopEntry>> clothStalls = new LinkedHashMap<>();
        List<ShopEntry> vendors = new ArrayList<>();
        for (ShopEntry e : all) {
            if (e.usesItemPrice()) {
                clothStalls.computeIfAbsent(clothBlockKey(e), k -> new ArrayList<>()).add(e);
            } else {
                vendors.add(e);
            }
        }
        for (List<ShopEntry> g : clothStalls.values()) {
            g.sort(Comparator.comparingInt(ShopEntry::offerIndex));
        }

        List<ShopDisplayRow> rows = new ArrayList<>();
        for (List<ShopEntry> group : clothStalls.values()) {
            if (!stallGroupMatches(group, q)) continue;
            rows.add(new ShopDisplayRow(group));
        }
        for (ShopEntry e : vendors) {
            if (!entryPassesSidebarAndTab(e, q)) continue;
            rows.add(new ShopDisplayRow(List.of(e)));
        }

        Comparator<ShopDisplayRow> cmp = (ra, rb) -> {
            boolean af = rowFavourite(ra);
            boolean bf = rowFavourite(rb);
            if (af != bf) return af ? -1 : 1;
            ShopEntry a = ra.anchor();
            ShopEntry b = rb.anchor();
            int c = switch (sortCol) {
                case 1 -> {
                    int pa = a.totalPriceInSpurs(), pb = b.totalPriceInSpurs();
                    if (pa == 0 && pb == 0) yield 0;
                    if (pa == 0) yield 1;
                    if (pb == 0) yield -1;
                    yield Integer.compare(pa, pb);
                }
                case 2 -> a.ownerName().compareToIgnoreCase(b.ownerName());
                case 3 -> a.mode().compareTo(b.mode());
                case 4 -> a.dimensionId().compareTo(b.dimensionId());
                default -> primaryItemLabel(ra).compareToIgnoreCase(primaryItemLabel(rb));
            };
            int dir = sortAsc ? c : -c;
            if (dir != 0) return dir;
            if (ra.isStall() && rb.isStall())
                return clothBlockKey(a).compareTo(clothBlockKey(b));
            return BuiltInRegistries.ITEM.getKey(a.sellingItem().getItem())
                    .compareTo(BuiltInRegistries.ITEM.getKey(b.sellingItem().getItem()));
        };
        rows.sort(cmp);
        displayRows = rows;
    }


    @Override
    public void renderBackground(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        // No blur — transparent, world stays visible
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        int px = panelX(), py = panelY();
        int tx = tableX(), tw = tableW();

        lastMouseX = mouseX;
        lastMouseY = mouseY;

        // Toolbar spans full panel width (above sidebar + table)
        drawToolbar(gfx, px, panelW(), py);
        drawPlayerTabs(gfx, tx, tw, py, mouseX, mouseY);

        // Sidebar — starts below toolbar
        if (sidebar != null) sidebar.render(gfx, mouseX, mouseY);

        super.render(gfx, mouseX, mouseY, delta); // widgets on top

        int tabsH = 18; // All / Fav tabs always visible
        int listTop = py + headerHeight() + toolbarHeight() + tabsH + colHdrHeight();
        int listBot = py + panelH() - footerHeight();
        int listH = listBot - listTop;
        int rowsVis = Math.max(1, listH / rowHeight());

        // Scissor the table area so nothing bleeds onto the sidebar
        UIHelper.enableScissor(gfx, tx, py + headerHeight() + toolbarHeight(), tw, panelH() - headerHeight() - toolbarHeight());

        drawColumnHeaders(gfx, tx, tw, py + headerHeight() + toolbarHeight() + tabsH, mouseX, mouseY);

        int maxScroll = Math.max(0, displayRows.size() - rowsVis);
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);

        if (loading) {
            String loadingMsg = I18n.get("screen.stockmarket.loading");
            gfx.drawString(font, loadingMsg, tx + tw / 2 - font.width(loadingMsg) / 2, listTop + listH / 2, Colors.TEXT_DIM, false);
        } else if (displayRows.isEmpty()) {
            String emptyMsg = I18n.get("screen.stockmarket.no_shops");
            gfx.drawString(font, emptyMsg, tx + tw / 2 - font.width(emptyMsg) / 2, listTop + listH / 2, Colors.TEXT_DIM, false);
        } else {
            drawRows(gfx, tx, tw, listTop, rowsVis, mouseX, mouseY);
            drawScrollBar(gfx, tx, tw, listTop, listH, rowsVis);
        }

        drawFooter(gfx, px, py, tx, tw, rowsVis);

        UIHelper.disableScissor(gfx);
        drawMapMenu(gfx, mouseX, mouseY);
    }

    // ── Toolbar ───────────────────────────────────────────────────────────────

    private void drawToolbar(GuiGraphics gfx, int px, int pw, int py) {
        int ty = py + headerHeight();
        int toolH = toolbarHeight();
        int pad = padding();
        int btnSize = buttonSize();

        // Toolbar background — full panel width
        UIHelper.blitScaled(gfx, GuiTextures.TOOLBAR, px, ty, pw, toolH,
                GuiTextures.Dimensions.TOOLBAR_W, GuiTextures.Dimensions.TOOLBAR_H);

        int btnY = ty + (toolH - btnSize) / 2;
        int btnRefX = px + pw - pad - btnSize * 2 - 4;
        int btnClsX = px + pw - pad - btnSize;

        // Search box bg — from sidebar edge to just before buttons
        int bx = px + sidebarWidth() + pad;
        int bw = btnRefX - bx - 4;
        int by = btnY;
        UIHelper.blitScaled(gfx, GuiTextures.SEARCH_BG,
                bx, by, bw, btnSize,
                GuiTextures.Dimensions.SEARCH_BG_W, GuiTextures.Dimensions.SEARCH_BG_H);

        // Refresh button — texture
        boolean hovRef = isMouseOverBtn(btnRefX, btnY, btnSize);
        UIHelper.blitScaled(gfx,
                hovRef ? GuiTextures.BTN_REFRESH_HOV : GuiTextures.BTN_REFRESH,
                btnRefX, btnY, btnSize, btnSize,
                GuiTextures.Dimensions.BTN_W, GuiTextures.Dimensions.BTN_H);

        // Close button — texture
        boolean hovCls = isMouseOverBtn(btnClsX, btnY, btnSize);
        UIHelper.blitScaled(gfx,
                hovCls ? GuiTextures.BTN_CLOSE_HOV : GuiTextures.BTN_CLOSE,
                btnClsX, btnY, btnSize, btnSize,
                GuiTextures.Dimensions.BTN_W, GuiTextures.Dimensions.BTN_H);
    }

    /**
     * Stores last known mouse position for hover detection in drawToolbar.
     */
    private int lastMouseX, lastMouseY;

    private boolean isMouseOverBtn(int bx, int by, int size) {
        return lastMouseX >= bx && lastMouseX < bx + size
                && lastMouseY >= by && lastMouseY < by + size;
    }

    // ── All / Fav Tabs ────────────────────────────────────────────────────────

    private void drawPlayerTabs(GuiGraphics gfx, int tx, int tw, int py, int mouseX, int mouseY) {
        int ty = py + headerHeight() + toolbarHeight();
        int tabH = 18;
        int pad = padding();

        // Tab strip background (the "paper" area below tabs)
        UIHelper.blitScaled(gfx, GuiTextures.TAB_STRIP, tx, ty, tw, tabH,
                GuiTextures.Dimensions.TAB_STRIP_W, GuiTextures.Dimensions.TAB_STRIP_H);

        int tabX = tx + pad;
        for (String tab : List.of("all", "fav")) {
            String label = tab.equals("all")
                    ? I18n.get("screen.stockmarket.tab_all")
                    : I18n.get("screen.stockmarket.tab_fav");
            int tabW = font.width(label) + 12;
            boolean active = tab.equals(activeTab);

            if (active) {
                // Active: sits flush with the strip, no bottom border — blends in
                UIHelper.blitScaled(gfx, GuiTextures.TAB_ACTIVE,
                        tabX, ty + 2, tabW, tabH - 2,
                        GuiTextures.Dimensions.TAB_W, GuiTextures.Dimensions.TAB_H);
                gfx.drawString(font, label, tabX + 6, ty + (tabH - 8) / 2, 0xFF111111, false);
            } else {
                // Inactive: raised above strip by 2px, has bottom shadow line
                UIHelper.blitScaled(gfx, GuiTextures.TAB_INACTIVE,
                        tabX, ty + 2, tabW, tabH - 2,
                        GuiTextures.Dimensions.TAB_W, GuiTextures.Dimensions.TAB_H);
                // Bottom shadow line to separate from strip
                gfx.fill(tabX, ty + tabH - 1, tabX + tabW, ty + tabH, 0x40000000);
                int fg = tab.equals("fav") ? C_STAR_ON : Colors.TEXT;
                gfx.drawString(font, label, tabX + 6, ty + 2 + (tabH - 2 - 8) / 2, fg, false);
            }
            tabX += tabW + 2;
        }
        // Bottom separator line under the whole tab row
        UIHelper.blitTiled(gfx, GuiTextures.SEPARATOR, tx, ty + tabH, tw, 1,
                GuiTextures.Dimensions.SEPARATOR_W, GuiTextures.Dimensions.SEPARATOR_H);
    }


    private void drawColumnHeaders(GuiGraphics gfx, int tx, int tw, int top, int mouseX, int mouseY) {
        int colH = colHdrHeight();
        int pad = padding();

        // Column header background — stretch to fit
        UIHelper.blitScaled(gfx, GuiTextures.COL_HEADER, tx + 1, top, tw - 1, colH,
                GuiTextures.Dimensions.COL_HEADER_W, GuiTextures.Dimensions.COL_HEADER_H);

        int[] widths = {COL_FAV, colItem(), colPrice(), colOwner(), colMode(), colDim()};
        String[] names = {
                "",
                I18n.get("screen.stockmarket.col_item"),
                I18n.get("screen.stockmarket.col_price"),
                I18n.get("screen.stockmarket.col_owner"),
                I18n.get("screen.stockmarket.col_mode"),
                I18n.get("screen.stockmarket.col_dim")
        };
        int[] sortIdx = {-1, 0, 1, 2, 3, 4};
        int cx = tx + pad;
        for (int i = 0; i < names.length; i++) {
            // Vertical divider before each named column (skip FAV and first named col)
            if (i > 1) {
                gfx.fill(cx - 1, top + 3, cx, top + colH - 3, 0x60000000);  // dark line
                gfx.fill(cx, top + 3, cx + 1, top + colH - 3, 0x40FFFFFF); // light highlight
            }
            if (!names[i].isEmpty()) {
                String label = names[i];
                if (sortIdx[i] == sortCol) label += sortAsc ? " ▲" : " ▼";
                boolean hov = mouseX >= cx && mouseX < cx + widths[i]
                        && mouseY >= top && mouseY < top + colH;
                gfx.drawString(font, label, cx + 2, top + (colH - 8) / 2, hov ? Colors.GOLD : 0xFF5C4A00, false);
            }
            cx += widths[i];
        }

        // Bottom separator under the whole header row
        UIHelper.blitTiled(gfx, GuiTextures.SEPARATOR, tx, top + colH - 1, tw, 1,
                GuiTextures.Dimensions.SEPARATOR_W, GuiTextures.Dimensions.SEPARATOR_H);
    }


    /** True when every offer in the row sells the same item (e.g. a single product with several price tiers). */
    private static boolean isUniformItem(ShopDisplayRow row) {
        ItemStack a = row.anchor().sellingItem();
        return row.offers.stream().allMatch(o -> ItemStack.isSameItem(o.sellingItem(), a));
    }

    private static String formatRowItemNames(ShopDisplayRow row) {
        // Uniform rows show the name once; the quantity is rendered separately (right-aligned)
        // so it stays visible even when the name is clipped.
        if (isUniformItem(row)) {
            return row.anchor().sellingItem().getHoverName().getString();
        }
        // Mixed stall — list each product with its inline count.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.offers.size(); i++) {
            if (i > 0) sb.append(" · ");
            ItemStack s = row.offers.get(i).sellingItem();
            sb.append(s.getHoverName().getString());
            if (s.getCount() > 1) sb.append(" ×").append(s.getCount());
        }
        return sb.toString();
    }

    private void drawRows(GuiGraphics gfx, int tx, int tw, int listTop, int rowsVis, int mouseX, int mouseY) {
        int scrollBarW = 5;
        int pad = padding();
        int rowH = rowHeight();
        int usableW = tw - pad * 2 - scrollBarW - 2;

        for (int i = scrollOffset; i < Math.min(displayRows.size(), scrollOffset + rowsVis); i++) {
            ShopDisplayRow row = displayRows.get(i);
            ShopEntry e = row.anchor();
            boolean fav = rowFavourite(row);
            int ry = listTop + (i - scrollOffset) * rowH;
            int rx = tx + pad;

            boolean hov = mouseX >= rx && mouseX < rx + usableW
                    && mouseY >= ry && mouseY < ry + rowH;

            // Row bg — use textures
            if (hov) {
                UIHelper.blitTiled(gfx, GuiTextures.ROW_HOVER, rx, ry, usableW, rowH,
                        GuiTextures.Dimensions.ROW_W, GuiTextures.Dimensions.ROW_H);
            } else if (fav) {
                UIHelper.blitTiled(gfx, GuiTextures.ROW_HOT, rx, ry, usableW, rowH,
                        GuiTextures.Dimensions.ROW_W, GuiTextures.Dimensions.ROW_H);
            } else if (i % 2 == 0) {
                UIHelper.blitTiled(gfx, GuiTextures.ROW_ODD, rx, ry, usableW, rowH,
                        GuiTextures.Dimensions.ROW_W, GuiTextures.Dimensions.ROW_H);
            }

            int cx = rx;
            int ty = ry + (rowH - 8) / 2;

            // ★ Favourite star
            gfx.drawString(font, "★", cx + 2, ty, fav ? C_STAR_ON : C_STAR_OFF, false);
            cx += COL_FAV;

            String tag = switch (e.shopType()) {
                case "TABLECLOTH" -> "§8[TC]§r ";
                case "TRADEWORKS" -> "§8[TW]§r ";
                default -> "§8[V]§r ";
            };

            int iconStep = 13;
            int iconY = ry + (rowH - 16) / 2;
            int maxIcons = Math.min(row.offers.size(), Math.max(1, (colItem() - 48) / iconStep));
            maxIcons = Math.min(maxIcons, 6);
            int ix = cx;
            for (int j = 0; j < maxIcons; j++) {
                gfx.renderItem(row.offers.get(j).sellingItem(), ix, iconY);
                ix += iconStep;
            }
            int iconsEnd = ix;
            if (row.offers.size() > maxIcons) {
                String more = "+" + (row.offers.size() - maxIcons);
                gfx.drawString(font, more, ix + 2, ty, Colors.TEXT_DIM, false);
                iconsEnd = ix + 2 + font.width(more) + 6;
            }

            // Selling quantity (×N) — for uniform rows take the largest count among the stall's
            // offers and draw it right-aligned, so a long item name can never push it out of view
            // (it survives name clipping). Mixed stalls keep their per-item counts inline instead.
            boolean uniform = isUniformItem(row);
            int sellCount = uniform
                    ? row.offers.stream().mapToInt(o -> o.sellingItem().getCount()).max().orElse(1)
                    : 1;
            String qty = (uniform && sellCount > 1) ? "×" + sellCount : "";
            int colRight = cx + colItem();
            int qtyW = qty.isEmpty() ? 0 : font.width(qty) + 6;

            String names = formatRowItemNames(row);
            int textX = iconsEnd + 4;
            String line = tag + names;
            int nameMax = colRight - textX - 6 - qtyW;
            nameMax = Math.max(24, nameMax);
            if (font.width(line) > nameMax)
                line = font.plainSubstrByWidth(line, nameMax - 4) + "…";
            gfx.drawString(font, line, textX, ty, Colors.TEXT, false);
            if (!qty.isEmpty()) {
                gfx.drawString(font, qty, colRight - font.width(qty) - 6, ty, Colors.GOLD, false);
            }
            cx += colItem();

            // Price
            if (e.usesItemPrice() && !e.priceItem().isEmpty()) {
                gfx.renderItem(e.priceItem(), cx, iconY);
                if (e.priceItem().getCount() > 1)
                    gfx.drawString(font, "x" + e.priceItem().getCount(), cx + 18, ty, Colors.GOLD, false);
            } else {
                gfx.drawString(font, UIHelper.formatPrice(e.totalPriceInSpurs()), cx + 2, ty, Colors.GOLD, false);
            }
            cx += colPrice();

            // Owner — with skin head icon (resolved by the real account UUID)
            String owner = e.ownerName();
            int iconSize = UIHelper.playerHeadIconSize(rowH);
            UIHelper.drawPlayerHead(gfx, e.ownerUuid(), e.ownerName(), cx + 2, ry + (rowH - iconSize) / 2, iconSize);
            int ownerTextX = cx + 2 + iconSize + 2;
            int ownerMaxW = colOwner() - 4 - iconSize - 2;
            if (font.width(owner) > ownerMaxW)
                owner = font.plainSubstrByWidth(owner, ownerMaxW - 4) + "…";
            gfx.drawString(font, owner, ownerTextX, ty, Colors.BLUE, false);
            cx += colOwner();

            // Mode badge
            boolean sell = e.mode().equals("SELL");
            int modeBadgeFg = 0xFFFFFFFF; // white reads better on the coloured badge
            int mw = font.width(e.mode()) + 6;
            ResourceLocation badgeTex = sell ? GuiTextures.BADGE_SELL : GuiTextures.BADGE_BUY;
            int badgeTexW = sell ? GuiTextures.Dimensions.BADGE_SELL_W : GuiTextures.Dimensions.BADGE_BUY_W;
            int badgeTexH = sell ? GuiTextures.Dimensions.BADGE_SELL_H : GuiTextures.Dimensions.BADGE_BUY_H;
            UIHelper.blitScaled(gfx, badgeTex,
                    cx + 2, ry + 4, mw, rowH - 8,
                    badgeTexW, badgeTexH);
            gfx.drawString(font, e.mode(), cx + 5, ty, modeBadgeFg, false);
            cx += colMode();

            // Dim
            String dim = e.dimensionId().replace("minecraft:", "");
            if (font.width(dim) > colDim() - 4)
                dim = font.plainSubstrByWidth(dim, colDim() - 8) + "…";
            gfx.drawString(font, dim, cx + 2, ty, Colors.TEXT_DIM, false);

            // Vertical column dividers (drawn last so they appear on top of row bg)
            {
                int divX = rx + COL_FAV + colItem();
                gfx.fill(divX - 1, ry + 2, divX, ry + rowH - 2, 0x50000000);
                gfx.fill(divX, ry + 2, divX + 1, ry + rowH - 2, 0x30FFFFFF);
                divX += colPrice();
                gfx.fill(divX - 1, ry + 2, divX, ry + rowH - 2, 0x50000000);
                gfx.fill(divX, ry + 2, divX + 1, ry + rowH - 2, 0x30FFFFFF);
                divX += colOwner();
                gfx.fill(divX - 1, ry + 2, divX, ry + rowH - 2, 0x50000000);
                gfx.fill(divX, ry + 2, divX + 1, ry + rowH - 2, 0x30FFFFFF);
                divX += colMode();
                gfx.fill(divX - 1, ry + 2, divX, ry + rowH - 2, 0x50000000);
                gfx.fill(divX, ry + 2, divX + 1, ry + rowH - 2, 0x30FFFFFF);
            }

            // Tooltip
            if (hov) {
                List<Component> tt = new ArrayList<>();
                if (row.offers.size() == 1) {
                    tt.add(row.offers.get(0).sellingItem().getHoverName().copy()
                            .withStyle(s -> s.withColor(Colors.GOLD)));
                } else {
                    tt.add(Component.literal(I18n.get("screen.stockmarket.tt_products", row.offers.size())));
                    for (ShopEntry off : row.offers) {
                        ItemStack s = off.sellingItem();
                        String lineT = "§f• §r" + s.getHoverName().getString()
                                + (s.getCount() > 1 ? " §7×§f" + s.getCount() : "");
                        tt.add(Component.literal(lineT));
                    }
                }
                if (e.usesItemPrice()) {
                    String ps = e.priceItem().isEmpty() ? "?"
                            : e.priceItem().getHoverName().getString()
                            + (e.priceItem().getCount() > 1 ? " x" + e.priceItem().getCount() : "");
                    tt.add(Component.literal(I18n.get("screen.stockmarket.tt_price", ps)));
                    tt.add(Component.literal(e.isTradeworks()
                            ? I18n.get("screen.stockmarket.tt_tradeworks")
                            : I18n.get("screen.stockmarket.tt_tablecloth")));
                } else {
                    tt.add(Component.literal(I18n.get("screen.stockmarket.tt_price", UIHelper.formatPrice(e.totalPriceInSpurs()))));
                    tt.add(Component.literal(I18n.get("screen.stockmarket.tt_vendor")));
                }
                tt.add(Component.literal(I18n.get("screen.stockmarket.tt_owner", e.ownerName())));
                tt.add(Component.literal(I18n.get("screen.stockmarket.tt_mode", sell ? "§cSELL" : "§aBUY")));
                tt.add(Component.literal(I18n.get("screen.stockmarket.tt_pos", e.pos().toShortString())));
                tt.add(Component.literal(I18n.get("screen.stockmarket.tt_dim", e.dimensionId())));
                if (fav) tt.add(Component.literal(I18n.get("screen.stockmarket.tt_favourited")));
                gfx.renderTooltip(font,
                        tt.stream().map(Component::getVisualOrderText).collect(Collectors.toList()),
                        mouseX, mouseY);
            }
        }
    }


    private void drawScrollBar(GuiGraphics gfx, int tx, int tw, int listTop, int listH, int rowsVis) {
        if (displayRows.size() <= rowsVis) return;
        int pad = padding();
        int bx = tx + tw - pad - 4;
        int thumbH = Math.max(16, listH * rowsVis / displayRows.size());
        int maxS = displayRows.size() - rowsVis;
        int thumbY = listTop + (scrollOffset * (listH - thumbH)) / Math.max(1, maxS);
        // Fill gap between scrollbar and right wall
        gfx.fill(bx + 4, listTop, tx + tw - 1, listTop + listH, 0xFFC6C6C6);
        // Track
        UIHelper.blitTiled(gfx, GuiTextures.SCROLL_TRACK, bx, listTop, 4, listH,
                GuiTextures.Dimensions.SCROLL_TRACK_W, GuiTextures.Dimensions.SCROLL_TRACK_H);
        // Thumb
        UIHelper.blitTiled(gfx, GuiTextures.SCROLL_THUMB, bx, thumbY, 4, thumbH,
                GuiTextures.Dimensions.SCROLL_THUMB_W, GuiTextures.Dimensions.SCROLL_THUMB_H);
    }

    // ── Footer ────────────────────────────────────────────────────────────────

    private void drawFooter(GuiGraphics gfx, int px, int py, int tx, int tw, int rowsVis) {
        int footH = footerHeight();
        int pad = padding();
        int fy = py + panelH() - footH;

        // Footer background — only over the table area (not the sidebar)
        UIHelper.blitScaled(gfx, GuiTextures.FOOTER, tx, fy, tw, footH,
                GuiTextures.Dimensions.FOOTER_W, GuiTextures.Dimensions.FOOTER_H);

        if (!displayRows.isEmpty()) {
            int end = Math.min(displayRows.size(), scrollOffset + rowsVis);
            gfx.drawString(font, (scrollOffset + 1) + "–" + end + " / " + displayRows.size(),
                    tx + pad, fy + (footH - 8) / 2, Colors.TEXT_DIM, false);
        }
        // Compact hint — centred over the table area
        String hint = I18n.get("screen.stockmarket.footer_hint");
        gfx.drawString(font, hint,
                tx + tw / 2 - font.width(hint) / 2,
                fy + (footH - 8) / 2, Colors.TEXT_DIM, false);
    }

    private void drawMapMenu(GuiGraphics gfx, int mouseX, int mouseY) {
        if (!mapMenuVisible || mapMenuEntry == null || !xaeroAvailable) return;

        int menuW = 108;
        int menuH = 18;
        int x = Math.min(mapMenuX, width - menuW - 2);
        int y = Math.min(mapMenuY, height - menuH - 2);
        x = Math.max(2, x);
        y = Math.max(2, y);
        mapMenuX = x;
        mapMenuY = y;

        boolean hover = mouseX >= x && mouseX < x + menuW && mouseY >= y && mouseY < y + menuH;
        gfx.fill(x - 1, y - 1, x + menuW + 1, y + menuH + 1, 0xCC000000);
        gfx.fill(x, y, x + menuW, y + menuH, hover ? 0xFFF0E5B5 : 0xFFE0D3A2);
        gfx.drawString(font, I18n.get("screen.stockmarket.add_to_map"), x + 8, y + (menuH - 8) / 2, 0xFF2C2410, false);
    }

    private boolean isMapMenuButton(double mx, double my) {
        if (!mapMenuVisible || mapMenuEntry == null || !xaeroAvailable) return false;
        int menuW = 108;
        int menuH = 18;
        return mx >= mapMenuX && mx < mapMenuX + menuW && my >= mapMenuY && my < mapMenuY + menuH;
    }

    private int rowIndexAt(double mx, double my, int tx, int tw, int listTop, int rowsVis) {
        int pad = padding();
        int rowH = rowHeight();
        int scrollBarW = 5;
        int usableW = tw - pad * 2 - scrollBarW - 2;
        int rowX = tx + pad;
        if (mx < rowX || mx >= rowX + usableW) return -1;
        if (my < listTop || my >= listTop + rowsVis * rowH) return -1;
        int idx = scrollOffset + (int) ((my - listTop) / rowH);
        return (idx >= 0 && idx < displayRows.size()) ? idx : -1;
    }


    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (sidebar != null && sidebar.mouseScrolled(mx, my, dx, dy)) return true;
        scrollOffset = Math.max(0, scrollOffset - (int) Math.signum(dy));
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (scrollDragging && button == 0 && !displayRows.isEmpty()) {
            int tabsH = 18;
            int py = panelY();
            int listTop = py + headerHeight() + toolbarHeight() + tabsH + colHdrHeight();
            int listBot = py + panelH() - footerHeight();
            int listH = listBot - listTop;
            int rowsVis = Math.max(1, listH / rowHeight());
            int maxScroll = Math.max(0, displayRows.size() - rowsVis);
            int thumbH = Math.max(16, listH * rowsVis / displayRows.size());
            int trackH = listH - thumbH;
            if (trackH > 0) {
                int delta = (int) Math.round((my - dragStartY) * maxScroll / (double) trackH);
                scrollOffset = Mth.clamp(dragStartOffset + delta, 0, maxScroll);
            }
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (scrollDragging && button == 0) {
            scrollDragging = false;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int px = panelX(), py = panelY();
        int pw = panelW();
        int tx = tableX(), tw = tableW();
        int pad = padding();
        int btnSize = buttonSize();
        int toolbarY = py + headerHeight();
        int toolH = toolbarHeight();
        int btnY = toolbarY + (toolH - btnSize) / 2;
        int btnRefX = px + pw - pad - btnSize * 2 - 4;
        int btnClsX = px + pw - pad - btnSize;
        int tabsH = 18;
        int colTop = py + headerHeight() + toolbarHeight() + tabsH;
        int colH = colHdrHeight();
        int listTop = colTop + colH;
        int listBot = py + panelH() - footerHeight();
        int rowH = rowHeight();
        int rowsVis = Math.max(1, (listBot - listTop) / rowH);

        if (button == 0 && isMapMenuButton(mx, my)) {
            if (mapMenuEntry != null) {
                XaeroWaypointCompat.addShopWaypoint(mapMenuEntry);
            }
            mapMenuVisible = false;
            return true;
        }

        if (mapMenuVisible && button == 0 && !isMapMenuButton(mx, my)) {
            mapMenuVisible = false;
        }

        // ── Texture buttons ───────────────────────────────────────────────────
        if (mx >= btnRefX && mx < btnRefX + btnSize && my >= btnY && my < btnY + btnSize) {
            requestData();
            return true;
        }
        if (mx >= btnClsX && mx < btnClsX + btnSize && my >= btnY && my < btnY + btnSize) {
            onClose();
            return true;
        }

        // Sidebar
        if (sidebar != null && sidebar.mouseClicked(mx, my, button)) {
            applyFilter();
            return true;
        }

        // ── All / Fav tabs ────────────────────────────────────────────────────
        int tabY = py + headerHeight() + toolbarHeight();
        if (my >= tabY + 2 && my < tabY + 16) {
            int tabX = tx + pad;
            for (String tab : List.of("all", "fav")) {
                String label = tab.equals("all")
                    ? I18n.get("screen.stockmarket.tab_all")
                    : I18n.get("screen.stockmarket.tab_fav");
                int tabW = font.width(label) + 8;
                if (mx >= tabX && mx < tabX + tabW) {
                    activeTab = tab;
                    scrollOffset = 0;
                    applyFilter();
                    return true;
                }
                tabX += tabW + 3;
            }
        }

        // ── Column headers ────────────────────────────────────────────────────
        if (my >= colTop && my < colTop + colH) {
            int[] widths = {COL_FAV, colItem(), colPrice(), colOwner(), colMode(), colDim()};
            int[] sortIdx = {-1, 0, 1, 2, 3, 4};
            int cx = tx + pad;
            for (int i = 0; i < widths.length; i++) {
                if (mx >= cx && mx < cx + widths[i] && sortIdx[i] >= 0) {
                    if (sortCol == sortIdx[i]) sortAsc = !sortAsc;
                    else {
                        sortCol = sortIdx[i];
                        sortAsc = true;
                    }
                    applyFilter();
                    return true;
                }
                cx += widths[i];
            }
        }

        // ── Star click (favourite toggle) ─────────────────────────────────────
        int rowIdx = rowIndexAt(mx, my, tx, tw, listTop, rowsVis);
        int dataStartX = tx + pad + COL_FAV;
        int dataEndX = tx + tw - pad - 6;
        if (button == 1 && xaeroAvailable && rowIdx >= 0) {
            mapMenuEntry = displayRows.get(rowIdx).anchor();
            mapMenuVisible = true;
            mapMenuX = (int) mx + 4;
            mapMenuY = (int) my + 4;
            return true;
        }

        if (mx >= tx + pad && mx < tx + pad + COL_FAV
                && my >= listTop && my < listTop + rowsVis * rowH) {
            int idx = scrollOffset + (int) ((my - listTop) / rowH);
            if (idx >= 0 && idx < displayRows.size()) {
                ShopDisplayRow row = displayRows.get(idx);
                String key = row.isStall() ? stallFavouriteKey(row.anchor()) : favKey(row.anchor());
                if (favourites.contains(key)) favourites.remove(key);
                else favourites.add(key);
                applyFilter();
                return true;
            }
        }

        // Scrollbar drag start
        if (button == 0 && !displayRows.isEmpty()) {
            int listH = listBot - listTop;
            int scrollBarX = tx + tw - pad - 4;
            if (mx >= scrollBarX && mx < scrollBarX + 4 && my >= listTop && my < listTop + listH) {
                scrollDragging = true;
                dragStartY = my;
                dragStartOffset = scrollOffset;
                return true;
            }
        }

        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (searchBox != null && searchBox.isFocused())
            return searchBox.keyPressed(key, scan, mods);
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (searchBox != null && searchBox.isFocused())
            return searchBox.charTyped(c, mods);
        return super.charTyped(c, mods);
    }
}
