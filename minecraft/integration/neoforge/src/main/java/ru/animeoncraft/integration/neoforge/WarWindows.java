package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonArray;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;

/** Only fresh server-authoritative windows may open raids. An API outage closes them. */
final class WarWindows {
    record Window(String id, String attacker, String defender, Instant starts, Instant ends) {}
    private static volatile List<Window> windows = List.of();
    private static volatile Instant expires = Instant.EPOCH;
    private WarWindows() {}
    static void load() { windows = List.of(); expires = Instant.EPOCH; }
    static void replace(JsonArray rows) {
        var result = new ArrayList<Window>();
        if (rows.size() > 100) throw new IllegalArgumentException("Too many war windows");
        for (var value : rows) {
            var row = value.getAsJsonObject();
            String id = row.get("id").getAsString(), attacker = row.get("attacker").getAsString(), defender = row.get("defender").getAsString();
            java.util.UUID.fromString(id);
            if (!ClanCodes.known(attacker) || !ClanCodes.known(defender) || attacker.equals(defender)) throw new IllegalArgumentException("Invalid clan pair");
            Instant starts = Instant.parse(row.get("starts_at").getAsString()), ends = Instant.parse(row.get("ends_at").getAsString());
            if (!ends.isAfter(starts)) throw new IllegalArgumentException("Invalid war window");
            result.add(new Window(id,attacker,defender,starts,ends));
        }
        windows = List.copyOf(result); expires = Instant.now().plusSeconds(60);
    }
    static Window active(String defender, String attacker) {
        return activeAt(defender,attacker,Instant.now());
    }
    static Window activeAt(String defender, String attacker, Instant now) {
        if (attacker == null || defender == null || !now.isBefore(expires)) return null;
        return windows.stream().filter(window -> window.attacker.equals(attacker) && window.defender.equals(defender)
            && !now.isBefore(window.starts) && now.isBefore(window.ends)).findFirst().orElse(null);
    }
    static boolean canRaid(String defender, String attacker) { return active(defender,attacker) != null; }
}
