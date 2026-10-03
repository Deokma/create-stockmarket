package by.deokma.stockmarket.command;

import by.deokma.stockmarket.config.MarketConfig;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/**
 * Registers the {@code /shoplist} command.
 *
 * Platform-specific action (sending the open-screen packet) is injected via
 * {@link #setOpenShopListSender} at mod startup. This keeps the command logic
 * fully loader-agnostic.
 */
public final class ShopListCommand {

    /** How to send the "open shop list screen" signal to a player — injected by platform. */
    private static Consumer<ServerPlayer> openShopListSender = player -> {};

    private ShopListCommand() {}

    /** Called once at startup by the platform entrypoint to wire in packet sending. */
    public static void setOpenShopListSender(Consumer<ServerPlayer> sender) {
        openShopListSender = sender;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("shoplist")
                // Level 0 by default — everyone. Admins can raise it, because the shop list
                // reveals the coordinates of every indexed shop on the server.
                .requires(source -> source.hasPermission(MarketConfig.shopListPermissionLevel()))
                .executes(ctx -> {
                    if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
                        ctx.getSource().sendFailure(Component.translatable("command.stockmarket.players_only"));
                        return 0;
                    }
                    openShopListSender.accept(player);
                    return 1;
                })
        );
    }
}
