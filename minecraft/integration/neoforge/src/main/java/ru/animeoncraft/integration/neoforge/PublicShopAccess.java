package ru.animeoncraft.integration.neoforge;

import io.github.lightman314.lightmanscurrency.api.traders.TraderState;
import io.github.lightman314.lightmanscurrency.api.traders.blockentity.TraderBlockEntity;
import io.github.lightman314.lightmanscurrency.api.traders.blocks.TraderBlockBase;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Only the normal customer interaction, never storage, machines, ownership or block edits. */
final class PublicShopAccess {
    private PublicShopAccess(){}
    static boolean allowed(PlayerInteractEvent.RightClickBlock event){
        if(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer p&&p.server.isDedicatedServer()&&!GameIdentity.PLAYERS.containsKey(p.getUUID()))return false;
        if(!event.getItemStack().isEmpty()||event.getEntity().isShiftKeyDown())return false;
        var state=event.getLevel().getBlockState(event.getPos());if(!(state.getBlock() instanceof TraderBlockBase block))return false;
        var be=block.getBlockEntity(state,event.getLevel(),event.getPos());if(!(be instanceof TraderBlockEntity<?> trader))return false;
        var data=trader.getTraderData();return data!=null&&data.getState()==TraderState.NORMAL;
    }
}
