package ru.animeoncraft.loading;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class LoadingProgressTest {
    @Test void unknownTotalNeverFabricatesAPercentage() {
        var stage=new LoadingProgress("Loading bootstrap resources",99,0);
        assertFalse(stage.determinate());assertEquals(0,stage.fraction());assertFalse(stage.counter().contains("%"));
        assertEquals("Подготовка основных ресурсов",stage.translated());
    }
    @Test void displayedFractionTracksTheActualStageAndStaysBounded() {
        assertEquals(.25f,new LoadingProgress("Minecraft Progress",25,100).fraction());
        assertTrue(new LoadingProgress("Minecraft Progress",25,100).counter().contains("25%"));
        assertEquals(1,new LoadingProgress("Mods",120,100).fraction());
        assertEquals(0,new LoadingProgress("Mods",-3,100).fraction());
    }
    @Test void unknownLabelsAndModNamesRemainVisible() {
        assertEquals("Create: recipes",new LoadingProgress("Create: recipes",2,8).translated());
        assertEquals("Ожидание данных загрузчика",new LoadingProgress(null,0,0).translated());
    }
}
