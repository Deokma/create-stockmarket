package by.deokma.stockmarket.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.function.Consumer;

/**
 * Handheld remote that opens the Stock Market screen from anywhere — the portable
 * counterpart to {@link by.deokma.stockmarket.block.MarketTerminalBlock}.
 *
 * <p>Opening the GUI is delegated to a platform-injected handler (the same path the
 * block uses), so this class stays free of loader-specific networking imports.
 */
public class MarketRemoteItem extends Item {

    /** Platform registers this hook during initialization. */
    private static Consumer<ServerPlayer> openScreenHandler = player -> {};

    public static void setOpenScreenHandler(Consumer<ServerPlayer> handler) {
        openScreenHandler = handler;
    }

    public MarketRemoteItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            openScreenHandler.accept(serverPlayer);
        }
        return InteractionResultHolder.sidedSuccess(held, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.stockmarket.market_remote.tooltip")
                .withStyle(ChatFormatting.GRAY));
    }
}
