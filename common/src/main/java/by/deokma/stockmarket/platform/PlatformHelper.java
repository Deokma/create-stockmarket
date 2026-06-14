package by.deokma.stockmarket.platform;

import java.util.function.BooleanSupplier;

/**
 * Loader-agnostic access to platform-specific feature checks.
 *
 * Each loader injects its own checkers at startup:
 * <ul>
 *   <li>NeoForge: via {@code ModList.get().isLoaded(...)}</li>
 *   <li>Fabric: via {@code FabricLoader.getInstance().isModLoaded(...)}</li>
 * </ul>
 *
 * Defaults to {@code false} for all checks so common code is safe before injection.
 */
public final class PlatformHelper {

    private static BooleanSupplier numismaticsPresent = () -> false;
    private static BooleanSupplier tradeworksPresent  = () -> false;

    private PlatformHelper() {}

    // ── Setters (called once at mod startup by the platform entrypoint) ────────

    public static void setNumismaticsChecker(BooleanSupplier supplier) {
        numismaticsPresent = supplier;
    }

    public static void setTradeworksChecker(BooleanSupplier supplier) {
        tradeworksPresent = supplier;
    }

    // ── Queries (used by common code) ─────────────────────────────────────────

    public static boolean isNumismaticsPresent() {
        return numismaticsPresent.getAsBoolean();
    }

    public static boolean isTradeworksPresent() {
        return tradeworksPresent.getAsBoolean();
    }
}
