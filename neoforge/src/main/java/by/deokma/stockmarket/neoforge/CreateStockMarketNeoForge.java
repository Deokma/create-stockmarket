package by.deokma.stockmarket.neoforge;

import by.deokma.stockmarket.CommonInit;
import by.deokma.stockmarket.CreateStockMarket;
import by.deokma.stockmarket.block.MarketTerminalBlock;
import by.deokma.stockmarket.block.MarketTerminalBlockEntity;
import by.deokma.stockmarket.command.ShopListCommand;
import by.deokma.stockmarket.item.MarketRemoteItem;
import by.deokma.stockmarket.neoforge.compat.NumismaticsCompat;
import by.deokma.stockmarket.neoforge.display.ModDisplaySources;
import by.deokma.stockmarket.neoforge.compat.TradeworksCompat;
import by.deokma.stockmarket.neoforge.market.MarketEvents;
import by.deokma.stockmarket.neoforge.network.NetworkHandler;
import by.deokma.stockmarket.neoforge.network.OpenShopListPacket;
import by.deokma.stockmarket.neoforge.network.OpenStockMarketPacket;
import by.deokma.stockmarket.neoforge.shop.NeoForgeVendorHelper;
import by.deokma.stockmarket.neoforge.shop.VendorEvents;
import by.deokma.stockmarket.platform.PlatformHelper;
import by.deokma.stockmarket.shop.VendorRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(CreateStockMarket.MOD_ID)
public final class CreateStockMarketNeoForge {

    private static final Logger LOGGER = LogManager.getLogger(CreateStockMarket.MOD_ID);

    /** Create's main creative tab ({@code create:base}) — where our block is listed so JEI/EMI/REI pick it up. */
    private static final ResourceKey<CreativeModeTab> CREATE_BASE_TAB = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB,
            ResourceLocation.fromNamespaceAndPath("create", "base"));

    private final IEventBus modEventBus;

    public CreateStockMarketNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        this.modEventBus = modEventBus;
        LOGGER.info("[{}] NeoForge entrypoint initialising", CreateStockMarket.MOD_ID);

        CommonInit.init();

        // ── Platform service injection ─────────────────────────────────────────
        // Inject platform-specific vendor helper (chunk iteration + Numismatics indexing)
        VendorRegistry.setPlatformHelper(new NeoForgeVendorHelper());

        // Inject mod-presence checkers so common code can query without NeoForge imports
        PlatformHelper.setNumismaticsChecker(NumismaticsCompat::isPresent);
        PlatformHelper.setTradeworksChecker(TradeworksCompat::isPresent);

        // Inject network action for the /shoplist command
        ShopListCommand.setOpenShopListSender(player ->
                NetworkHandler.sendToPlayer(player, new OpenShopListPacket())
        );

        // ── Network, events, registration ─────────────────────────────────────
        NetworkHandler.register(modEventBus);
        VendorEvents.register();
        MarketEvents.register();

        ModBlocks.BLOCKS.register(modEventBus);
        ModBlocks.ITEMS.register(modEventBus);
        ModBlocks.BLOCK_ENTITIES.register(modEventBus);
        ModDisplaySources.register(modEventBus);

        // Wire the BlockEntityType supplier so MarketTerminalBlockEntity can use it
        MarketTerminalBlockEntity.setTypeSupplier(ModBlocks.MARKET_TERMINAL_BE);

        // Hook: when player right-clicks MarketTerminalBlock, send OpenStockMarketPacket
        MarketTerminalBlock.setOpenScreenHandler(player ->
                NetworkHandler.sendToPlayer(player, new OpenStockMarketPacket())
        );

        // Same hook for the handheld Market Remote item (remote access from anywhere)
        MarketRemoteItem.setOpenScreenHandler(player ->
                NetworkHandler.sendToPlayer(player, new OpenStockMarketPacket())
        );

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);
        modEventBus.addListener(this::onBuildCreativeTabs);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // Bind Display Link sources to the Market Terminal once all registries are populated.
        event.enqueueWork(ModDisplaySources::bindToBlock);
    }

    private void clientSetup(FMLClientSetupEvent event) {
        ClientSetup.init(modEventBus);
    }

    /** Adds the Market Terminal to Create's creative tab so it appears in the inventory and in JEI/EMI/REI. */
    private void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CREATE_BASE_TAB)) {
            event.accept(ModBlocks.MARKET_TERMINAL_ITEM.get());
            event.accept(ModBlocks.MARKET_REMOTE.get());
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        ShopListCommand.register(event.getDispatcher());
    }
}
