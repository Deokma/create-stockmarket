package by.deokma.stockmarket.neoforge;

import by.deokma.stockmarket.CreateStockMarket;
import by.deokma.stockmarket.block.MarketTerminalBlock;
import by.deokma.stockmarket.block.MarketTerminalBlockEntity;
import by.deokma.stockmarket.item.MarketRemoteItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, CreateStockMarket.MOD_ID);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, CreateStockMarket.MOD_ID);

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CreateStockMarket.MOD_ID);

    public static final Supplier<MarketTerminalBlock> MARKET_TERMINAL = BLOCKS.register(
            "market_terminal",
            () -> new MarketTerminalBlock(
                    BlockBehaviour.Properties.of()
                            .strength(3.5f)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()
                            .noOcclusion()
            )
    );

    public static final Supplier<BlockItem> MARKET_TERMINAL_ITEM = ITEMS.register(
            "market_terminal",
            () -> new BlockItem(MARKET_TERMINAL.get(), new Item.Properties())
    );

    /** Handheld remote — opens the Stock Market screen from anywhere. */
    public static final Supplier<MarketRemoteItem> MARKET_REMOTE = ITEMS.register(
            "market_remote",
            () -> new MarketRemoteItem(new Item.Properties().stacksTo(1))
    );

    public static final Supplier<BlockEntityType<MarketTerminalBlockEntity>> MARKET_TERMINAL_BE =
            BLOCK_ENTITIES.register(
                    "market_terminal",
                    () -> BlockEntityType.Builder
                            .of(MarketTerminalBlockEntity::new, MARKET_TERMINAL.get())
                            .build(null)
            );

    private ModBlocks() {
    }
}
