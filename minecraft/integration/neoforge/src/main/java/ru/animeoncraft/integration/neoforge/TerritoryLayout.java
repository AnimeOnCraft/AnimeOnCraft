package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

final class TerritoryLayout {
    private TerritoryLayout() {}

    static List<Territory> read(JsonObject root, long seed) {
        if (root.get("format_version").getAsInt() != 1 || Long.parseLong(root.get("seed").getAsString()) != seed) {
            throw new IllegalArgumentException("Territories belong to a different world");
        }
        if (!root.has("territories")) return List.of();
        var config = root.getAsJsonObject("territories");
        int width = config.get("width_blocks").getAsInt();
        if (width < 64 || width > 4096 || width % 32 != 0) throw new IllegalArgumentException("Invalid territory size");
        var result = new ArrayList<Territory>();
        for (String clan : ClanCodes.fromConfig(root)) {
            var spawn = root.getAsJsonObject("clans").getAsJsonObject(clan);
            int x = spawn.get("x").getAsInt(), z = spawn.get("z").getAsInt();
            if (Math.abs((long) x) > 100000 || Math.abs((long) z) > 100000) throw new IllegalArgumentException("Invalid clan spawn");
            String hex = config.getAsJsonObject("colors").get(clan).getAsString();
            if (!hex.matches("[0-9A-Fa-f]{6}")) throw new IllegalArgumentException("Invalid territory color");
            int minX = Math.floorDiv(x, 16) * 16 - width / 2;
            int minZ = Math.floorDiv(z, 16) * 16 - width / 2;
            var region = new Territory(clan, minX, minZ, minX + width, minZ + width, Integer.parseInt(hex, 16));
            if (result.stream().anyMatch(region::overlaps)) throw new IllegalArgumentException("Clan territories overlap");
            if (root.has("common")) {
                var common = root.getAsJsonObject("common");
                if (region.contains(common.get("x").getAsInt(), common.get("z").getAsInt())) {
                    throw new IllegalArgumentException("Common spawn must remain neutral");
                }
            }
            result.add(region);
        }
        return List.copyOf(result);
    }
}
