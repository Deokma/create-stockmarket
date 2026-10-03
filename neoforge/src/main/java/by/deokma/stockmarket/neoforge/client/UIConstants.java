package by.deokma.stockmarket.neoforge.client;

import net.minecraft.util.Mth;

/**
 * Shared UI constants - only truly common values across ALL screens.
 * Screen-specific constants should remain in their respective classes.
 */
public final class UIConstants {

    private UIConstants() {}

    // ── Common Colors (used by multiple screens) ──────────────────────────────
    public static final class Colors {
        // Core palette - used everywhere
        public static final int ACCENT      = 0xFFD4AF37;
        public static final int TEXT        = 0xFF111111;  // dark for light UI
        public static final int TEXT_DIM    = 0xFF333344;  // dark dim for light UI

        // Status colors - semantic meaning
        public static final int GREEN       = 0xFF1B6B1B;
        public static final int RED         = 0xFFB71C1C;
        public static final int BLUE        = 0xFF1565C0;
        public static final int GOLD        = 0xFF8B6914;
    }

    // ── Common Layout (adaptive sizing methods) ───────────────────────────────
    public static final class Layout {
        /**
         * Returns adaptive padding based on screen width.
         * Larger screens get more padding for better visual spacing.
         */
        public static int padding(int screenWidth) {
            if (screenWidth >= 1920) return 12;
            if (screenWidth >= 1280) return 10;
            return 8;
        }
        
        /**
         * Returns adaptive sidebar width based on panel width.
         * Sidebar takes 15% of panel width, clamped between 100-150px.
         */
        public static int sidebarWidth(int panelWidth) {
            return Mth.clamp((int)(panelWidth * 0.15f), 100, 150);
        }
        
        /**
         * Returns adaptive button size based on font height.
         * Ensures buttons are always large enough to be clickable.
         */
        public static int buttonSize(int fontHeight) {
            return Math.max(16, fontHeight + 4);
        }
        
        /**
         * Returns adaptive row height based on font height.
         * Accounts for both text height and item icon height (16x16).
         */
        public static int rowHeight(int fontHeight) {
            int textHeight = fontHeight + 8;  // font + padding
            int iconHeight = 16 + 8;          // icon + padding
            return Math.max(20, Math.max(textHeight, iconHeight));
        }
        
        /**
         * Returns adaptive header height based on font height.
         */
        public static int headerHeight(int fontHeight) {
            return Math.max(18, fontHeight + 10);
        }
        
        /**
         * Returns adaptive toolbar height based on font height.
         */
        public static int toolbarHeight(int fontHeight) {
            return Math.max(24, fontHeight + 14);
        }
        
        /**
         * Returns adaptive column header height based on font height.
         */
        public static int colHeaderHeight(int fontHeight) {
            return Math.max(14, fontHeight + 6);
        }
        
        /**
         * Returns adaptive footer height based on font height.
         */
        public static int footerHeight(int fontHeight) {
            return Math.max(20, fontHeight + 12);
        }
    }

    // ── Numismatics Coin System ──────────────────────────────────────────────
    /**
     * Coin denominations in spurs, mirroring Numismatics' {@code Coin} enum:
     * Spur 1, Bevel 8, Sprocket 16, Cog 64, Crown 512, Sun 4096.
     *
     * <p>These were once shifted one step (Crown = 4096, Cog = 512, …), so a price of
     * 1 Crown displayed as "1 Cog" and every vendor price looked wrong.
     */
    public static final class Coins {
        public static final int[] VALUES = { 4096, 512, 64, 16, 8, 1 };
        private static final String[] NAMES = { "sun", "crown", "cog", "sprocket", "bevel", "spur" };

        /** Registry id of the coin item, e.g. {@code numismatics:crown}. */
        public static String key(int index) {
            return "numismatics:" + NAMES[index];
        }

        /** Localised coin name. */
        public static String label(int index) {
            return net.minecraft.client.resources.language.I18n.get("screen.stockmarket.coin." + NAMES[index]);
        }

        /** Index of the highest denomination that fits in the price (the spur for anything below 8). */
        public static int dominantIndex(int spurs) {
            for (int i = 0; i < VALUES.length; i++) {
                if (spurs >= VALUES[i]) return i;
            }
            return VALUES.length - 1;
        }

        /** Returns the key of the highest denomination coin in the price. */
        public static String dominantKey(int spurs) {
            return key(dominantIndex(spurs));
        }

        /** Returns the localised label of the highest denomination coin in the price. */
        public static String dominantLabel(int spurs) {
            return label(dominantIndex(spurs));
        }

        /** The coin item for a registry key, or {@link net.minecraft.world.item.ItemStack#EMPTY} without Numismatics. */
        public static net.minecraft.world.item.ItemStack icon(String key) {
            var loc = net.minecraft.resources.ResourceLocation.tryParse(key);
            if (loc == null) return net.minecraft.world.item.ItemStack.EMPTY;
            return net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(loc)
                    .map(net.minecraft.world.item.ItemStack::new)
                    .orElse(net.minecraft.world.item.ItemStack.EMPTY);
        }
    }
}
