package ru.animeoncraft.integration.neoforge;

/** Bounds are half-open block coordinates, aligned to chunk edges. */
record Territory(String clan, int minX, int minZ, int maxX, int maxZ, int color, String owner) {
    Territory(String clan,int minX,int minZ,int maxX,int maxZ,int color){this(clan,minX,minZ,maxX,maxZ,color,clan);}

    Territory {
        if (!ClanCodes.valid(owner) || !ClanCodes.valid(clan) || color < 0 || color > 0xFFFFFF
                || minX % 16 != 0 || minZ % 16 != 0 || maxX % 16 != 0 || maxZ % 16 != 0
                || minX < -104096 || minZ < -104096 || maxX > 104096 || maxZ > 104096
                || maxX - (long) minX < 64 || maxX - (long) minX > 4096
                || maxZ - (long) minZ != maxX - (long) minX) {
            throw new IllegalArgumentException("Invalid clan territory");
        }
    }

    boolean contains(int x, int z) {
        return x >= minX && x < maxX && z >= minZ && z < maxZ;
    }

    boolean overlaps(Territory other) {
        return minX < other.maxX && maxX > other.minX && minZ < other.maxZ && maxZ > other.minZ;
    }
}
