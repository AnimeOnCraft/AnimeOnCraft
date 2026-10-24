package ru.animeoncraft.integration.neoforge;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;

/** Client display of the server-supplied clan identity and guild rank. */
final class ClientNameTags {
 private ClientNameTags() {}
 static void render(RenderNameTagEvent event) {
  if (!(event.getEntity() instanceof Player player)) return;
  String clan=ClientSkins.clan(player.getUUID());int rank=ClientGuild.rank(player.getUUID());
  boolean hasClan=clan!=null && !clan.isEmpty();
  if (!hasClan && rank<0) return;
  Component content=event.getOriginalContent();
  if (rank>=0) content=content.copy().append(Component.literal(" · ").withStyle(style->style.withColor(0x9a8aae))).append(Component.literal(GuildRules.Rank.of(rank).name()).withStyle(style->style.withColor(GuildScreen.RANK_COLORS[rank]&0xffffff).withBold(true)));
  if (!hasClan) { event.setContent(content); return; }
  int color=switch (clan) {
   case "SCAM" -> 0xE84A5F;
   case "NYA" -> 0xF47DCB;
   case "AKUMA" -> 0xA970FF;
   case "ZXC" -> 0x4DA8FF;
   case "YAKUZA" -> 0xFFB347;
   default -> 0xFFFFFF;
  };
  Component tag=Component.literal("["+clan+"] ").withStyle(style->style.withColor(color).withBold(true));
  event.setContent(tag.copy().append(content));
 }
}
