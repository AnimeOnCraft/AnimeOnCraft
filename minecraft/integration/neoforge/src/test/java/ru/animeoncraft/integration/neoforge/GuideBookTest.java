package ru.animeoncraft.integration.neoforge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class GuideBookTest {
    @Test void bookFitsMinecraftLimitsAndMentionsKeyCommands(){
        assertTrue(GuideBook.TITLE.length()<=32,"written book title limit");
        assertTrue(GuideBook.PAGES.size()>=6&&GuideBook.PAGES.size()<=20);
        for(var page:GuideBook.PAGES){
            assertFalse(page.title().isBlank());
            // A book page shows about 14 lines; keep each page short enough to fit without scrolling.
            assertTrue((page.title()+"\n\n"+page.body()).length()<=260,"page too long: "+page.title());
        }
        var all=GuideBook.PAGES.toString();
        assertTrue(all.contains("/spawn"));assertTrue(all.contains("/guidebook"));assertTrue(all.contains("Tab"));
        assertTrue(all.contains("движ")||all.contains("Стой на месте"),"spawn page explains that moving cancels");
    }
}
