package by.deokma.stockmarket.neoforge.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
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
     */
    public static String formatPrice(int spurs) {
        if (spurs <= 0) return "Free";
        int remaining = spurs;
        StringBuilder sb = new StringBuilder();
        int[] values = UIConstants.Coins.VALUES;
        String[] labels = UIConstants.Coins.LABELS;
        for (int i = 0; i < values.length; i++) {
            int denom = values[i];
            int n = remaining / denom;
            if (n > 0) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(n).append('×').append(labels[i]);
                remaining -= n * denom;
            }
        }
        if (remaining != 0) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(remaining).append("×?");
        }
        return sb.toString();
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
