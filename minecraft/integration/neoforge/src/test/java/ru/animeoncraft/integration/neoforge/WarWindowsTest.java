package ru.animeoncraft.integration.neoforge;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WarWindowsTest {
    @Test void restrictsDirectionAndTimeAndClosesRaidsWhenSnapshotExpires() {
        ClanCodes.register(java.util.List.of("SCAM", "NYA", "AKUMA", "ZXC", "YAKUZA"));
        WarWindows.load();
        assertFalse(WarWindows.canRaid("NYA","SCAM"));
        Instant now=Instant.now();
        WarWindows.replace(JsonParser.parseString("[{\"id\":\"11111111-1111-4111-8111-111111111111\",\"attacker\":\"SCAM\",\"defender\":\"NYA\",\"starts_at\":\""+now.minusSeconds(10)+"\",\"ends_at\":\""+now.plusSeconds(120)+"\"}]").getAsJsonArray());
        assertNotNull(WarWindows.activeAt("NYA","SCAM",now));
        assertNull(WarWindows.activeAt("SCAM","NYA",now));
        assertNull(WarWindows.activeAt("NYA","SCAM",now.minusSeconds(20)));
        assertNull(WarWindows.activeAt("NYA","SCAM",now.plusSeconds(61)));
        assertNull(WarWindows.activeAt(null,"SCAM",now));
        WarWindows.load();
    }
}
