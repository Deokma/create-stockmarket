package by.deokma.stockmarket.market;

import by.deokma.stockmarket.config.MarketConfig;

import java.util.List;

/** Holds the latest market data received from the server (client-side singleton). */
public final class MarketData {

    private MarketData() {}

    private static List<MarketEntry> entries = List.of();
    private static boolean loading = false;

    /**
     * "Hot" volume threshold, sent by the server alongside the entries — the config
     * lives on the server, so a client cannot read it from its own files.
     */
    private static int hotVolume = MarketConfig.hotVolume();

    public static void set(List<MarketEntry> list) { entries = List.copyOf(list); }
    public static List<MarketEntry> get()          { return entries; }
    public static void setLoading(boolean v)       { loading = v; }
    public static boolean isLoading()              { return loading; }

    public static void setHotVolume(int value)     { hotVolume = Math.max(1, value); }
    public static int hotVolume()                  { return hotVolume; }
}
