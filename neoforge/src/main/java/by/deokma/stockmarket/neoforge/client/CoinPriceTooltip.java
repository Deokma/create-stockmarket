package by.deokma.stockmarket.neoforge.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.inventory.tooltip.TooltipComponent;

/**
 * Tooltip line showing a label followed by the full price as Numismatics coin icons
 * ("Price: [Sun]×2 [Crown]×2 …"). Used in place of the text price, which for large prices
 * was a long run of coin names that was hard to read.
 *
 * @param label text before the coins, may carry § formatting (e.g. "§7Price: §f")
 */
public record CoinPriceTooltip(String label, int spurs) implements TooltipComponent {

    /** Client renderer, registered in {@code ClientSetup}. */
    public static final class Renderer implements ClientTooltipComponent {

        private static final int HEIGHT = 18; // a 16px icon plus the usual line gap

        private final CoinPriceTooltip data;

        public Renderer(CoinPriceTooltip data) {
            this.data = data;
        }

        @Override
        public int getHeight() {
            return HEIGHT;
        }

        @Override
        public int getWidth(Font font) {
            return font.width(data.label()) + UIHelper.coinBreakdownWidth(font, data.spurs());
        }

        @Override
        public void renderImage(Font font, int x, int y, GuiGraphics gfx) {
            gfx.drawString(font, data.label(), x, y + 4, 0xFFFFFFFF, true);
            UIHelper.drawCoinBreakdown(gfx, font, data.spurs(), x + font.width(data.label()), y, 0xFFFFFFFF);
        }
    }
}
