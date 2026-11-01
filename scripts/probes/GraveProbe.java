package aoc.graveprobe;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import de.maxhenkel.gravestone.Main;
import de.maxhenkel.gravestone.GraveUtils;
import de.maxhenkel.gravestone.blocks.GraveStoneBlock;
import de.maxhenkel.gravestone.tileentity.GraveStoneTileEntity;
import de.maxhenkel.gravestone.corelib.death.Death;
import de.maxhenkel.gravestone.corelib.death.PlayerDeathEvent;
import com.orangemiracle.cleanmaid.config.ConfigModule;
import com.orangemiracle.cleanmaid.cleanup.CleanupModule;
import com.orangemiracle.cleanmaid.filter.*;
import com.orangemiracle.cleanmaid.protection.*;
import com.orangemiracle.cleanmaid.data.CleanOptions;
@Mod("aoc_grave_probe")
public class GraveProbe {
 int ticks;
 public GraveProbe(){NeoForge.EVENT_BUS.addListener(this::start);NeoForge.EVENT_BUS.addListener(this::tick);}
 void start(ServerStartedEvent e){e.getServer().overworld().setChunkForced(0,0,true);}
 static void check(boolean b,String s){if(!b)throw new IllegalStateException(s);}
 void tick(ServerTickEvent.Post e){if(++ticks!=100)return;try{verify(e.getServer());System.out.println("AOC_GRAVESTONE_PROBE_PASS");}catch(Throwable t){t.printStackTrace();System.out.println("AOC_GRAVESTONE_PROBE_FAIL");}}
 void verify(net.minecraft.server.MinecraftServer server){
  var world=server.overworld();world.getChunk(0,0);var pos=new BlockPos(0,110,0);world.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());world.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
  var owner=new FakePlayer(world,new GameProfile(UUID.randomUUID(),"GraveOwner"));var stranger=new FakePlayer(world,new GameProfile(UUID.randomUUID(),"GraveGuest"));
  var c1=new io.netty.channel.embedded.EmbeddedChannel(owner.connection.getConnection());var c2=new io.netty.channel.embedded.EmbeddedChannel(stranger.connection.getConnection());
  try{
   owner.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);stranger.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);owner.setPos(0.5,110,0.5);stranger.setPos(0.5,110,0.5);
   owner.getInventory().items.set(0,new ItemStack(Items.DIAMOND,7));owner.getInventory().armor.set(3,new ItemStack(Items.DIAMOND_HELMET));owner.getInventory().offhand.set(0,new ItemStack(Items.SHIELD));
   var backpack=new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse("sophisticatedbackpacks:gold_backpack")));check(!backpack.isEmpty(),"Backpack missing");owner.getInventory().items.set(9,backpack);
   var death=Death.fromPlayer(owner);owner.getInventory().clearContent();NeoForge.EVENT_BUS.post(new PlayerDeathEvent(death,owner,world.damageSources().generic()));
   check(world.getBlockEntity(pos) instanceof GraveStoneTileEntity,"Death did not create grave");var grave=(GraveStoneTileEntity)world.getBlockEntity(pos);
   check(!Main.SERVER_CONFIG.onlyOwnersCanBreak.get()&&Main.SERVER_CONFIG.sneakPickup.get()&&!Main.SERVER_CONFIG.strictPlacement.get(),"Public grave config missing");check(GraveUtils.canBreakGrave(world,stranger,pos),"Stranger cannot loot");
   var serialized=grave.saveWithFullMetadata(world.registryAccess());grave.loadWithComponents(serialized,world.registryAccess());check(grave.getDeath().getMainInventory().get(0).getCount()==7,"Grave NBT lost items");
   var config=new ConfigModule(LogUtils.getLogger());config.initialize();check(config.config().general().intervalSeconds()==1800,"Cleanup interval differs");
   var cleaner=new CleanupModule(new DimensionFilterModule(),new ItemFilterModule(),new ProtectionModule(new ItemFilterModule(),new DeathDropProtectionModule()));check(cleaner.startCleanup(server,CleanOptions.automatic(),config.config()),"Cleaner failed");for(int i=0;i<1000&&cleaner.isCleaning();i++)cleaner.tickCleanup(server);
   check(world.getBlockEntity(pos)==grave&&grave.getDeath().getMainInventory().get(0).getCount()==7,"Cleanup removed grave contents");
   stranger.setShiftKeyDown(true);((GraveStoneBlock)world.getBlockState(pos).getBlock()).entityInside(world.getBlockState(pos),world,pos,stranger);
   check(!(world.getBlockEntity(pos) instanceof GraveStoneTileEntity),"Public sneak pickup failed");check(stranger.getInventory().items.get(0).getCount()==7&&stranger.getInventory().armor.get(3).is(Items.DIAMOND_HELMET)&&stranger.getInventory().offhand.get(0).is(Items.SHIELD)&&stranger.getInventory().items.get(9).getItem()==backpack.getItem(),"Slots lost");
   check(owner.getInventory().items.stream().noneMatch(s->s.is(Items.DIAMOND)||s.getItem()==backpack.getItem())&&owner.getInventory().armor.get(3).isEmpty()&&owner.getInventory().offhand.get(0).isEmpty(),"Owner received duplicate loot");
   System.out.println("AOC_GRAVE_CHECKS death=true public_looting=true sneak=true cleanup_safe=true nbt=true slots_and_backpack=true");
  }finally{c1.finishAndReleaseAll();c2.finishAndReleaseAll();}
 }
}
