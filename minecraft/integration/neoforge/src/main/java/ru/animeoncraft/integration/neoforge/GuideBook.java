package ru.animeoncraft.integration.neoforge;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * A short written guide handed out once per player (first login after this version, including old players),
 * and again on demand with /guidebook. The full guide stays in Tab → Гайд.
 */
final class GuideBook {
    static final String GIVEN_TAG="animeoncraft_guide_book_v1";
    static final String TITLE="Гайд AnimeOnCraft";
    record Page(String title,String body){}
    static final List<Page> PAGES=List.of(
        new Page("Добро пожаловать!","Это памятка новичка. Подробный гайд всегда открыт: Tab → Гайд.\n\nПотерял книгу? Команда /guidebook выдаст новую."),
        new Page("Кланы и земли","Ты играешь за свой клан. На землях клана можно строить, на клановом спавне — пользоваться сундуками.\n\nЧужие территории защищены, кроме окна войны."),
        new Page("Центральная Сора","/spawn — телепорт в Сору через 10 секунд. Стой на месте: шаг отменяет телепорт. Повтор через 5 минут.\n\nПлощадь и кольцо защищены, мобы там не появляются."),
        new Page("Гильдия","Зал гильдии — юг кольца Соры (0, 142). Там выдают карточку авантюриста и доску заданий.\n\nЗадания приносят монеты и опыт ранга F → S."),
        new Page("Монеты и торговля","Монеты зарабатываются в гильдии. У каждого клана в Соре свой торговый участок: /aocmarket.\n\n/trade Ник — прямой обмен с игроком рядом."),
        new Page("Чат","Обычное сообщение слышно в 100 блоках.\n! в начале — весь сервер.\n!! в начале — только клан.\n\nГолосовой чат настраивается в его меню."),
        new Page("Приключения","Данжи, боссы, магия и Create. В опасные походы бери команду, еду и запас снаряжения.\n\nКарта и мини-карта показывают зоны, точки и соклановцев."),
        new Page("Правила","Читы, X-ray и дюпы запрещены: сервер замечает подозрительную активность и сообщает администрации.\n\nУважай других игроков. Вопросы — администрации в чате."));
    private static final Map<UUID,Long> LAST_COMMAND=new HashMap<>();
    private GuideBook() {}

    static ItemStack create(){
        var pages=PAGES.stream().map(page->Filterable.<Component>passThrough(
            Component.literal(page.title()).withStyle(ChatFormatting.DARK_PURPLE,ChatFormatting.BOLD)
                .append(Component.literal("\n\n"+page.body()).withStyle(style->style.withBold(false).withColor(ChatFormatting.BLACK))))).toList();
        var stack=new ItemStack(Items.WRITTEN_BOOK);
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT,new WrittenBookContent(Filterable.passThrough(TITLE),"AnimeOnCraft",0,pages,true));
        return stack;
    }
    static void give(ServerPlayer player){
        var book=create();
        if(!player.getInventory().add(book))player.drop(book,false);
        player.sendSystemMessage(Component.literal("В инвентаре книга «"+TITLE+"». Подробный гайд — Tab → Гайд.").withStyle(ChatFormatting.LIGHT_PURPLE));
    }
    static void login(PlayerEvent.PlayerLoggedInEvent event){
        if(!(event.getEntity() instanceof ServerPlayer player)||player.isSpectator())return;
        var data=player.getPersistentData();
        if(data.getBoolean(GIVEN_TAG))return;
        data.putBoolean(GIVEN_TAG,true);
        give(player);
    }
    /** Persistent data is not always copied on death; keep the mark so the book is not handed out again. */
    static void clone(PlayerEvent.Clone event){
        if(event.getOriginal().getPersistentData().getBoolean(GIVEN_TAG))event.getEntity().getPersistentData().putBoolean(GIVEN_TAG,true);
    }
    static void commands(RegisterCommandsEvent event){
        event.getDispatcher().register(Commands.literal("guidebook").executes(context->{
            var player=context.getSource().getPlayerOrException();long now=System.currentTimeMillis();
            Long last=LAST_COMMAND.get(player.getUUID());
            if(last!=null&&now-last<60_000){context.getSource().sendFailure(Component.literal("Новую книгу можно взять через "+(1+(60_000-(now-last))/1000)+" сек."));return 0;}
            LAST_COMMAND.put(player.getUUID(),now);give(player);return 1;
        }));
    }
}
