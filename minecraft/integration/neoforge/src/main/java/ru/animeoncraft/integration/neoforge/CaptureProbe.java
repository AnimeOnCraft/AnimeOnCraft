package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.nio.file.Files;
import java.util.UUID;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Runs only from the existing port/world-gated disposable probe. */
final class CaptureProbe {
    private CaptureProbe() {}
    static void verify(net.minecraft.server.MinecraftServer server)throws Exception{
        var attacker=new FakePlayer(server.overworld(),new GameProfile(UUID.randomUUID(),"AocAttacker"));
        var defender=new FakePlayer(server.overworld(),new GameProfile(UUID.randomUUID(),"AocDefender"));
        var pos=ClanSpawns.read(server).get("NYA").offset(48,0,0);
        int y=server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,pos.getX(),pos.getZ());
        attacker.setPos(pos.getX()+0.5,y,pos.getZ()+0.5);defender.setPos(pos.getX()+0.5,y,pos.getZ()+0.5);
        GameIdentity.PLAYERS.put(attacker.getUUID(),new GameIdentity("SCAM",new byte[0],false,"",UUID.randomUUID(),UUID.randomUUID()));
        GameIdentity.PLAYERS.put(defender.getUUID(),new GameIdentity("NYA",new byte[0],false,"",UUID.randomUUID(),UUID.randomUUID()));
        var war=new JsonObject();String warId=UUID.randomUUID().toString();
        war.addProperty("id",warId);war.addProperty("attacker","SCAM");war.addProperty("defender","NYA");
        war.addProperty("starts_at",java.time.Instant.now().minusSeconds(60).toString());war.addProperty("ends_at",java.time.Instant.now().plusSeconds(600).toString());
        var windows=new JsonArray();windows.add(war);WarWindows.replace(windows);
        var eventId=UUID.nameUUIDFromBytes((warId+":NYA").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var saved=server.getWorldPath(LevelResource.ROOT).resolve("animeoncraft-gameplay-outbox").resolve(eventId+".json");
        try{
            for(int i=0;i<120;i++)TerritoryCapture.sample(server,java.util.List.of(attacker));
            if(Files.exists(saved))throw new IllegalStateException("Capture completed too early");
            TerritoryCapture.sample(server,java.util.List.of(attacker,defender));
            for(int i=0;i<299;i++)TerritoryCapture.sample(server,java.util.List.of(attacker));
            if(Files.exists(saved))throw new IllegalStateException("Defender did not reset capture progress");
            TerritoryCapture.sample(server,java.util.List.of(attacker));
            if(!Files.isRegularFile(saved))throw new IllegalStateException("Completed capture was not persisted");
            System.out.println("AOC_CAPTURE_PROBE_PASS hold=300 contested_reset=true persisted=true");
        }finally{
            GameIdentity.PLAYERS.remove(attacker.getUUID());GameIdentity.PLAYERS.remove(defender.getUUID());WarWindows.load();
        }
    }
}
