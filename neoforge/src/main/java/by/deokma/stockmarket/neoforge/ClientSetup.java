package by.deokma.stockmarket.neoforge;

import by.deokma.stockmarket.neoforge.client.CoinPriceTooltip;
import by.deokma.stockmarket.neoforge.client.MarketTerminalRenderer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;

public final class ClientSetup {
    private ClientSetup() {
    }

    /**
     * Client listeners that must exist before {@code FMLClientSetupEvent}: tooltip component
     * factories are collected while the game window is still being created.
     */
    public static void initEarly(IEventBus modBus) {
        modBus.addListener(ClientSetup::onRegisterTooltipComponents);
    }

    public static void init(IEventBus modBus) {
        modBus.addListener(ClientSetup::onRegisterRenderers);
    }

    private static void onRegisterTooltipComponents(RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(CoinPriceTooltip.class, CoinPriceTooltip.Renderer::new);
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlocks.MARKET_TERMINAL_BE.get(),
                MarketTerminalRenderer::new);
    }

}
