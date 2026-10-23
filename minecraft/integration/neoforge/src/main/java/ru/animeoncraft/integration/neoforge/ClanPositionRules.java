package ru.animeoncraft.integration.neoforge;
import java.util.UUID;
final class ClanPositionRules {
 private ClanPositionRules(){}
 static boolean visible(UUID viewer,String clan,UUID target,String targetClan){return !viewer.equals(target)&&clan!=null&&ClanCodes.known(clan)&&clan.equals(targetClan);}
}
