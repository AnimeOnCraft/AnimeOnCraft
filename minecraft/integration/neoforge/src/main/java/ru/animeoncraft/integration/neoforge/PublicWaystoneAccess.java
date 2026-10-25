package ru.animeoncraft.integration.neoforge;

import java.util.Set;

/** Public activation only in the protected neutral core, never a blanket container permission. */
final class PublicWaystoneAccess {
    private static final Set<String> BLOCKS=Set.of("waystones:waystone","waystones:mossy_waystone","waystones:sandy_waystone");
    private PublicWaystoneAccess() {}
    static boolean allowed(TerritoryPermissions policy,int x,int z,String block,boolean admitted,boolean sneaking) {
        if(!admitted||sneaking||!BLOCKS.contains(block)||policy==null||!policy.states()||policy.regions()==null)return false;
        var regions=policy.regions();
        if(regions.marketAt(x,z)!=null||!RegionPolicy.NOBODY.equals(regions.ownerAt(x,z)))return false;
        return regions.regions().stream().anyMatch(r->"CORE".equals(r.kind())&&r.clan()==null&&RegionPolicy.NOBODY.equals(r.owner())&&r.contains(x,z));
    }
}
