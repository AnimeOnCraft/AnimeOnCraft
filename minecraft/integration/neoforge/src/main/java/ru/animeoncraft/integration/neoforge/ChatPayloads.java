package ru.animeoncraft.integration.neoforge;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Structured chat lines for the tabbed client chat. Clients without the channel keep getting plain system messages. */
final class ChatPayloads {
    private ChatPayloads(){}
    static final int TEXT_MAX=256;
    /** Channel ordinal matches ChatChannels.Channel: 0 LOCAL, 1 GLOBAL, 2 CLAN. 3 = INFO (notices only). */
    record Line(int channel,UUID sender,String name,String clan,int rank,String title,String betaTitle,boolean staff,String text) implements CustomPacketPayload {
        Line{
            if(channel<0||channel>2||name.isEmpty()||name.length()>16||!name.matches("[A-Za-z0-9_]{1,16}")||clan.length()>12||!clan.isEmpty()&&!ClanCodes.valid(clan)||rank<-1||rank>6||title.length()>40||betaTitle.length()>40||text.isEmpty()||text.length()>TEXT_MAX||!safe(text))throw new IllegalArgumentException("Invalid chat line");
        }
        static final Type<Line> TYPE=new Type<>(id("chat_line_v1"));
        static final StreamCodec<FriendlyByteBuf,Line> CODEC=StreamCodec.of((b,p)->{b.writeVarInt(p.channel);b.writeUUID(p.sender);b.writeUtf(p.name,16);b.writeUtf(p.clan,12);b.writeVarInt(p.rank);b.writeUtf(p.title,40);b.writeUtf(p.betaTitle,40);b.writeBoolean(p.staff);b.writeUtf(p.text,TEXT_MAX);},
            b->new Line(b.readVarInt(),b.readUUID(),b.readUtf(16),b.readUtf(12),b.readVarInt(),b.readUtf(40),b.readUtf(40),b.readBoolean(),b.readUtf(TEXT_MAX)));
        @Override public Type<Line> type(){return TYPE;}
    }
    /** Server hints that belong to a chat tab (for example "nobody hears you nearby"). Severity: 0 hint, 1 warning, 2 error. */
    record Notice(int channel,int severity,String text) implements CustomPacketPayload {
        Notice{if(channel<0||channel>3||severity<0||severity>2||text.isEmpty()||text.length()>TEXT_MAX||!safe(text))throw new IllegalArgumentException("Invalid chat notice");}
        static final Type<Notice> TYPE=new Type<>(id("chat_notice_v1"));
        static final StreamCodec<FriendlyByteBuf,Notice> CODEC=StreamCodec.of((b,p)->{b.writeVarInt(p.channel);b.writeVarInt(p.severity);b.writeUtf(p.text,TEXT_MAX);},b->new Notice(b.readVarInt(),b.readVarInt(),b.readUtf(TEXT_MAX)));
        @Override public Type<Notice> type(){return TYPE;}
    }
    /** No control characters and no legacy section-sign formatting in player text. */
    static boolean safe(String text){return text.codePoints().noneMatch(c->Character.isISOControl(c)||c==167);}
    private static ResourceLocation id(String name){return ResourceLocation.fromNamespaceAndPath("animeoncraft",name);}
}
