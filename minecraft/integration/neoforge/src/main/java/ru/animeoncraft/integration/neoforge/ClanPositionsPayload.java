package ru.animeoncraft.integration.neoforge;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

record ClanPositionsPayload(String clan,List<Position> players) implements CustomPacketPayload {
 record Position(UUID id,ResourceKey<Level> dimension,double x,double y,double z){
  Position {if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||Math.abs(x)>30000000||Math.abs(z)>30000000||Math.abs(y)>32768)throw new IllegalArgumentException("Invalid clan position");}
 }
 static final Type<ClanPositionsPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("animeoncraft","clan_positions_v1"));
 static final StreamCodec<FriendlyByteBuf,ClanPositionsPayload> CODEC=StreamCodec.of((b,p)->{
  b.writeUtf(p.clan(),12);b.writeVarInt(p.players().size());
  for(var v:p.players()){b.writeUUID(v.id());b.writeResourceLocation(v.dimension().location());b.writeDouble(v.x());b.writeDouble(v.y());b.writeDouble(v.z());}
 },b->{String clan=b.readUtf(12);int count=b.readVarInt();if(count<0||count>256)throw new IllegalArgumentException("Too many clan markers");var list=new java.util.ArrayList<Position>();for(int i=0;i<count;i++)list.add(new Position(b.readUUID(),ResourceKey.create(Registries.DIMENSION,b.readResourceLocation()),b.readDouble(),b.readDouble(),b.readDouble()));return new ClanPositionsPayload(clan,list);});
 ClanPositionsPayload {players=List.copyOf(players);if(players.size()>256||(!clan.isEmpty()&&!ClanCodes.valid(clan))||(clan.isEmpty()&&!players.isEmpty()))throw new IllegalArgumentException("Invalid clan markers");}
 @Override public Type<ClanPositionsPayload> type(){return TYPE;}
}
