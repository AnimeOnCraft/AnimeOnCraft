package ru.animeoncraft.integration.neoforge;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class CoinResetTest {
    @Test void onlyRealMoneyCountsAsCoins() {
        for (var metal : new String[] {"copper", "iron", "gold", "emerald", "diamond", "netherite"})
            for (var kind : new String[] {"coin", "coinpile", "coinblock"}) assertTrue(GuildRules.coinId("lightmanscurrency:" + kind + "_" + metal), kind + " " + metal);
        assertTrue(GuildRules.coinId("lightmanscurrency:coin_ancient"));
        for (var id : GuildRules.COIN_IDS) assertTrue(GuildRules.coinId(id), id);
        for (var id : new String[] {"lightmanscurrency:coin_chocolate_gold", "lightmanscurrency:coinpile_chocolate_copper", "lightmanscurrency:wallet_gold", "lightmanscurrency:coinmint",
                "lightmanscurrency:coin_chest", "lightmanscurrency:coinjar_blue", "minecraft:gold_ingot", "othermod:coin_gold"})
            assertFalse(GuildRules.coinId(id), id);
        assertEquals(64, GuildRules.START_COPPER);
    }

    @Test void theEconomyProfileTurnsOffEveryCoinSourceButTheGuild() throws Exception {
        var root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("minecraft/server/economy-profile.json"))) root = root.getParent();
        assertNotNull(root);
        var c = JsonParser.parseString(Files.readString(root.resolve("minecraft/server/economy-profile.json"))).getAsJsonObject().getAsJsonObject("lightmans_common");
        assertFalse(c.getAsJsonObject("loot.entities").get("enabled").getAsBoolean(), "mobs drop no coins");
        assertFalse(c.getAsJsonObject("loot.chests").get("enabled").getAsBoolean(), "loot chests hold no coins");
        assertFalse(c.getAsJsonObject("villagers").get("piglinsBarterCoins").getAsBoolean(), "piglins barter no coins");
        assertFalse(c.getAsJsonObject("crafting.coin_mint").get("canMint").getAsBoolean(), "no minting");
        assertFalse(c.getAsJsonObject("events").get("startingRewards").getAsBoolean(), "the starting purse is ours, 64 copper");
    }
}
