package ru.animeoncraft.integration.neoforge;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;

/** Client display of the server-supplied clan identity. */
final class ClientNameTags {
 private ClientNameTags() {}
 static void render(RenderNameTagEvent event) {
  if (!(event.getEntity() instanceof Player player)) return;
  String clan=ClientSkins.clan(player.getUUID());
  if (clan==null || clan.isEmpty()) return;
  int color=switch (clan) {
   case "SCAM" -> 0xE84A5F;
   case "NYA" -> 0xF47DCB;
   case "AKUMA" -> 0xA970FF;
   case "ZXC" -> 0x4DA8FF;
   case "YAKUZA" -> 0xFFB347;
   default -> 0xFFFFFF;
  };
  Component tag=Component.literal("["+clan+"] ").withStyle(style->style.withColor(color).withBold(true));
  event.setContent(tag.copy().append(event.getOriginalContent()));
 }
}
