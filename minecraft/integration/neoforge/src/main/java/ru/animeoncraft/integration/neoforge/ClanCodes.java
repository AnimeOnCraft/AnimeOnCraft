package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Clan registry. No clan names are compiled in: the server registers the official clans from
 * {@code config/animeoncraft-clan-spawns.json} (section {@code clans}) at startup, and clients only
 * check the code format because the server is the authority for membership and rights.
 */
final class ClanCodes {
    /** Same rule as the backend {@code clans_code_format} constraint. */
    private static final Pattern FORMAT = Pattern.compile("[A-Z0-9]{2,12}");
    static final int MAX = 64;
    private static volatile List<String> registered = List.of();

    private ClanCodes() {}

    /** Official clans of the running server, in configuration order. Empty until registered. */
    static List<String> all() { return registered; }

    /** True only for a clan registered on this server. Use on the server side. */
    static boolean known(String code) { return code != null && registered.contains(code); }

    /** Format-only check for payloads and persisted data that may outlive the current registry. */
    static boolean valid(String code) { return code != null && FORMAT.matcher(code).matches(); }

    static void register(List<String> codes) { registered = checked(codes); }

    /** Clan codes declared by a clan-world configuration, in file order. */
    static List<String> fromConfig(JsonObject root) {
        if (root == null || !root.has("clans") || !root.get("clans").isJsonObject()) throw new IllegalArgumentException("Clan list is missing");
        return checked(new ArrayList<>(root.getAsJsonObject("clans").keySet()));
    }

    private static List<String> checked(List<String> codes) {
        var unique = new LinkedHashSet<String>();
        for (String code : codes) {
            if (!valid(code) || code.equals("COMMON")) throw new IllegalArgumentException("Invalid clan code");
            if (!unique.add(code)) throw new IllegalArgumentException("Duplicate clan code");
        }
        if (unique.size() > MAX) throw new IllegalArgumentException("Too many clans");
        return List.copyOf(unique);
    }
}
