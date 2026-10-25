package ru.animeoncraft.loading;

import java.util.Locale;

/** The displayed fraction always belongs to the real named stage; unknown totals stay indeterminate. */
record LoadingProgress(String label, int current, int total) {
    boolean determinate() { return total > 0; }
    float fraction() { return determinate() ? Math.max(0, Math.min(1, current / (float) total)) : 0; }
    String counter() { return determinate() ? Math.max(0, Math.min(current,total)) + " / " + total + "  ·  " + Math.round(fraction()*100) + "%" : "В процессе"; }
    String translated() {
        String value=label==null?"":label.strip();
        String lower=value.toLowerCase(Locale.ROOT);
        if(lower.contains("bootstrap resources")) return "Подготовка основных ресурсов";
        if(lower.contains("scanning mod") || lower.contains("discovering mod")) return "Проверка файлов модов";
        if(lower.contains("mod construction")) return "Инициализация модов";
        if(lower.contains("registry")) return "Регистрация игровых объектов";
        if(lower.contains("config loading")) return "Загрузка настроек";
        if(lower.contains("sided setup")) return "Подготовка клиента";
        if(lower.contains("common setup")) return "Настройка игровых систем";
        if(lower.contains("complete loading")) return "Завершение загрузки";
        if(lower.contains("minecraft progress")) return "Загрузка ресурсов Minecraft";
        if(lower.contains("loading mods")) return "Загрузка модов";
        if(lower.contains("launching minecraft") || lower.contains("bootstrapping minecraft")) return "Запуск Minecraft";
        if(lower.contains("neoforge loading")) return "Подготовка NeoForge";
        return value.isEmpty()?"Ожидание данных загрузчика":value;
    }
}
