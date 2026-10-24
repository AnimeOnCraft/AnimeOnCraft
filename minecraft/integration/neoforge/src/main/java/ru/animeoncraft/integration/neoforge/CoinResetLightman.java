package ru.animeoncraft.integration.neoforge;

import io.github.lightman314.lightmanscurrency.api.money.bank.BankAPI;
import io.github.lightman314.lightmanscurrency.api.traders.TraderAPI;
import io.github.lightman314.lightmanscurrency.common.attachments.WalletHandler;
import io.github.lightman314.lightmanscurrency.common.items.WalletItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** The Lightman's Currency side of the coin reset; only called when the mod is loaded. */
final class CoinResetLightman {
    private CoinResetLightman() {}
    /** Coins inside a wallet stack (changed in place). */
    static int wallet(ItemStack s) {
        if (!WalletItem.isWallet(s)) return 0;
        var data = WalletItem.getDataWrapper(s); var contents = data.getContents(); int removed = CoinReset.clean(contents);
        if (removed > 0) data.setContents(contents, null);
        return removed;
    }
    /** The wallet in the wallet slot. */
    static int equippedWallet(ServerPlayer p) {
        var handler = WalletHandler.get(p); if (handler == null) return 0;
        var w = handler.getWallet().copy(); int removed = wallet(w);
        if (removed > 0) handler.setWallet(w);
        return removed;
    }
    /** Every bank account (players and teams) and the cash stored in every shop. */
    static String wipeAccounts() {
        int accounts = 0, traders = 0;
        for (var account : BankAPI.getApi().GetAllBankAccounts(false)) if (!account.getMoneyStorage().isEmpty()) { account.getMoneyStorage().clear(); account.markDirty(); accounts++; }
        for (var trader : TraderAPI.getApi().GetAllTraders(false)) if (!trader.getInternalStoredMoney().isEmpty()) { trader.getInternalStoredMoney().clear(); traders++; } // clear() marks the shop dirty
        return "bank_accounts=" + accounts + " shops=" + traders;
    }
}
