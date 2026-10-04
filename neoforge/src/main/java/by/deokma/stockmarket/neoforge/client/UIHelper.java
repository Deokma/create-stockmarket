package by.deokma.stockmarket.neoforge.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import com.mojang.datafixers.util.Either;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

/**
 * Shared UI helper methods - only truly reusable utilities.
 * Screen-specific rendering should stay in respective classes.
 */
public final class UIHelper {

    private UIHelper() {}

    // ── Texture Rendering ─────────────────────────────────────────────────────

    /**
     * Tiles a texture to fill the destination rectangle.
     * Repeats the texture both horizontally and vertically as needed.
     * Use this for textures that are meant to tile (backgrounds, scrollbars, row highlights).
     */
    public static void blitTiled(GuiGraphics gfx, ResourceLocation tex,
                                  int dx, int dy, int dw, int dh, int texW, int texH) {
        int x = dx;
        while (x < dx + dw) {
            int sw = Math.min(texW, dx + dw - x);
            int y = dy;
            while (y < dy + dh) {
                int sh = Math.min(texH, dy + dh - y);
                gfx.blit(tex, x, y, 0, 0, sw, sh, texW, texH);
                y += sh;
            }
            x += sw;
        }
    }

    /**
     * Stretches a texture to fill the destination rectangle exactly once.
     * Use this for UI strips (toolbars, headers, footers, column headers)
     * that should scale to fit rather than repeat.
     */
    public static void blitScaled(GuiGraphics gfx, ResourceLocation tex,
                                   int dx, int dy, int dw, int dh, int texW, int texH) {
        gfx.blit(tex, dx, dy, dw, dh, 0, 0, texW, texH, texW, texH);
    }

    // ── Price Formatting ──────────────────────────────────────────────────────

    /**
     * Formats a price in Numismatics Spurs using official denominations
     * ({@link UIConstants.Coins#VALUES}) — Sun, Crown, Cog, Sprocket, Bevel, Spur.
     * The spur divides every price, so the breakdown is always exact.
     */
    public static String formatPrice(int spurs) {
        if (spurs <= 0) return I18n.get("screen.stockmarket.price_free");
        int remaining = spurs;
        StringBuilder sb = new StringBuilder();
        int[] values = UIConstants.Coins.VALUES;
        for (int i = 0; i < values.length; i++) {
            int denom = values[i];
            int n = remaining / denom;
            if (n > 0) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(n).append('×').append(UIConstants.Coins.label(i));
                remaining -= n * denom;
            }
        }
        return sb.toString();
    }

    /** Horizontal step per coin: a 16px item icon plus a 1px gap; the count sits on the icon. */
    private static final int COIN_STEP = 17;

    /**
     * Draws a price as Numismatics coin icons, largest denomination first, each with its
     * count in the corner like an inventory stack. Denominations that do not fit in
     * {@code maxWidth} are replaced by "…" — callers keep the exact price in the row tooltip.
     * A text price wider than its column used to spill over the neighbouring columns.
     *
     * <p>Falls back to {@link #formatPrice} text (clipped to the width) when the coin items
     * are not registered.
     *
     * @param suffix drawn after the coins in the dim colour, e.g. {@code "/64"}; "" for none
     * @param iconY  top of the 16px icons
     * @param textY  baseline row for text (the "…", the suffix and the fallback)
     */
    public static void drawCoinPrice(GuiGraphics gfx, Font font, int spurs, String suffix,
                                     int x, int iconY, int textY, int maxWidth, int color) {
        if (spurs <= 0 || !hasCoinIcons()) {
            gfx.drawString(font, clip(font, formatPrice(spurs), maxWidth), x, textY, color, false);
            return;
        }

        int[] counts = coinCounts(spurs);
        int[] coinIndex = new int[counts.length];
        int[] coinCount = new int[counts.length];
        int coins = 0;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == 0) continue;
            coinIndex[coins] = i;
            coinCount[coins] = counts[i];
            coins++;
        }

        String more = "…";
        int suffixW = suffix.isEmpty() ? 0 : font.width(suffix) + 2;
        int budget = maxWidth - suffixW;
        int shown = Math.min(coins, budget / COIN_STEP);
        if (shown < coins) {
            shown = Math.max(0, (budget - font.width(more) - 2) / COIN_STEP);
        }

        int cx = x;
        for (int k = 0; k < shown; k++) {
            ItemStack icon = UIConstants.Coins.icon(coinIndex[k]);
            gfx.renderItem(icon, cx, iconY);
            gfx.renderItemDecorations(font, icon, cx, iconY,
                    coinCount[k] == 1 ? null : String.valueOf(coinCount[k]));
            cx += COIN_STEP;
        }
        if (shown < coins) {
            gfx.drawString(font, more, cx + 1, textY, color, false);
            cx += font.width(more) + 2;
        }
        if (!suffix.isEmpty()) {
            gfx.drawString(font, suffix, cx + 1, textY, UIConstants.Colors.TEXT_DIM, false);
        }
    }

    /** Gap between one coin's "×N" and the next coin in {@link #drawCoinBreakdown}. */
    private static final int BREAKDOWN_GAP = 4;

    /**
     * Width of {@link #drawCoinBreakdown} for this price, or of the plain text price when the
     * coin items are missing.
     */
    public static int coinBreakdownWidth(Font font, int spurs) {
        if (spurs <= 0 || !hasCoinIcons()) return font.width(formatPrice(spurs));
        int width = 0;
        for (int n : coinCounts(spurs)) {
            if (n == 0) continue;
            if (width > 0) width += BREAKDOWN_GAP;
            width += COIN_STEP + font.width("×" + n);
        }
        return width;
    }

    /**
     * Draws the whole price as coin icons, each followed by "×N" — no truncation, for
     * tooltips. Falls back to {@link #formatPrice} text when the coin items are missing.
     *
     * @param y top of the 16px icons; text is centred on them
     */
    public static void drawCoinBreakdown(GuiGraphics gfx, Font font, int spurs, int x, int y, int textColor) {
        if (spurs <= 0 || !hasCoinIcons()) {
            gfx.drawString(font, formatPrice(spurs), x, y + 4, textColor, true);
            return;
        }
        int[] counts = coinCounts(spurs);
        int cx = x;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == 0) continue;
            String count = "×" + counts[i];
            gfx.renderItem(UIConstants.Coins.icon(i), cx, y);
            gfx.drawString(font, count, cx + COIN_STEP, y + 4, textColor, true);
            cx += COIN_STEP + font.width(count) + BREAKDOWN_GAP;
        }
    }

    /**
     * A tooltip line for a coin price: the translated label (e.g. "Price: ") followed by the
     * coins as icons, or the plain text line when the price is free or the coins are missing.
     *
     * @param labelKey lang key with one {@code %s} for the price, e.g. {@code screen.stockmarket.tt_price}
     */
    public static Either<FormattedText, TooltipComponent> priceTooltipLine(String labelKey, int spurs) {
        if (spurs > 0 && hasCoinIcons()) {
            return Either.right(new CoinPriceTooltip(I18n.get(labelKey, ""), spurs));
        }
        return Either.left(net.minecraft.network.chat.Component.literal(I18n.get(labelKey, formatPrice(spurs))));
    }

    /** True when every Numismatics coin item is registered, so prices can be drawn as icons. */
    public static boolean hasCoinIcons() {
        for (int i = 0; i < UIConstants.Coins.VALUES.length; i++) {
            if (UIConstants.Coins.icon(i).isEmpty()) return false;
        }
        return true;
    }

    /** Coins per denomination (indexed like {@link UIConstants.Coins#VALUES}), largest first. */
    private static int[] coinCounts(int spurs) {
        int[] values = UIConstants.Coins.VALUES;
        int[] counts = new int[values.length];
        int remaining = Math.max(0, spurs);
        for (int i = 0; i < values.length; i++) {
            counts[i] = remaining / values[i];
            remaining -= counts[i] * values[i];
        }
        return counts;
    }

    private static String clip(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("…"))) + "…";
    }

    /**
     * Formats a percentage change with sign.
     * Returns "—" for changes < 0.05%.
     */
    public static String formatChangePct(double pct) {
        if (Math.abs(pct) < 0.05) return "—";
        return String.format("%+.1f%%", pct);
    }

    // ── Player Head Rendering ─────────────────────────────────────────────────

    /**
     * Renders a player head icon at (x, y) with the given pixel size.
     * Resolves the skin via {@link SkinFetcher}; shows the Steve fallback while loading.
     *
     * @param size desired icon size in pixels; clamped to [8, 16]
     */
    public static void drawPlayerHead(GuiGraphics gfx, String playerName,
                                      int x, int y, int size) {
        drawPlayerHead(gfx, null, playerName, x, y, size);
    }

    /**
     * Renders a player head, resolving the skin by the real account {@code uuid}
     * when available (which yields the player's actual skin rather than Steve).
     */
    public static void drawPlayerHead(GuiGraphics gfx, java.util.UUID uuid, String playerName,
                                      int x, int y, int size) {
        int clampedSize = Math.max(8, Math.min(16, size));
        ResourceLocation texture = SkinFetcher.INSTANCE.getTexture(uuid, playerName);
        if (texture == null) return;
        PlayerHeadRenderer.render(gfx, texture, x, y, clampedSize,
                SkinFetcher.INSTANCE.isLegacy(uuid, playerName));
    }

    /**
     * Returns the icon size to use for a given row height.
     * Delegates to {@link PlayerHeadRenderer#iconSizeForRow(int)}.
     */
    public static int playerHeadIconSize(int rowHeight) {
        return PlayerHeadRenderer.iconSizeForRow(rowHeight);
    }

    // ── History Normalization ─────────────────────────────────────────────────

    /**
     * Normalizes a price history to 0.0–1.0 range for sparkline rendering.
     * Returns 0.5 for all values if min == max.
     */
    public static float[] normalizeHistory(java.util.List<Integer> history) {
        if (history.isEmpty()) return new float[0];
        int min = history.stream().mapToInt(i -> i).min().orElse(0);
        int max = history.stream().mapToInt(i -> i).max().orElse(0);
        float[] result = new float[history.size()];
        if (min == max) {
            java.util.Arrays.fill(result, 0.5f);
            return result;
        }
        for (int i = 0; i < history.size(); i++) {
            result[i] = (float)(history.get(i) - min) / (max - min);
        }
        return result;
    }

    // ── Scissor / Clipping ────────────────────────────────────────────────────

    /**
     * Enables scissor clipping to the given rectangle.
     * All rendering outside this area will be discarded.
     * Must be paired with {@link #disableScissor(GuiGraphics)}.
     */
    public static void enableScissor(GuiGraphics gfx, int x, int y, int w, int h) {
        gfx.enableScissor(x, y, x + w, y + h);
    }

    /**
     * Disables the scissor clipping set by {@link #enableScissor}.
     */
    public static void disableScissor(GuiGraphics gfx) {
        gfx.disableScissor();
    }
}
