# Minecraft AnimeOnCraft

- [Состав и проверки этапа 8](../docs/operations/MODPACK_MVP.md): Minecraft 1.21.1, NeoForge 21.1.262, Java 21 x64.
- [Зафиксированные версии файлов](modpack/profile.json): 12 клиентских / 10 серверных JAR, прямые исходные URL и SHA512.
- [Локальный запуск и проверки этапа 9](../docs/operations/MODPACK_RUNTIME.md): реальные client join, модовые предметы, EMI и server save/restart; обычная PLAY и визуальные игровые сценарии ещё остаются.
- [Admission runtime](../docs/operations/GAME_ADMISSION_RUNTIME.md): `integration/neoforge/` — загружаемый мод0.3.0-friends-dev для NeoForge21.1.262. Проверка до мира и настоящая сквозная PG/HTTP/client/server acceptance прошли в отдельной локальной fixture. `contract/core` сохранены как независимая прежняя основа, новый мод их не использует. Обычный MVP-сервер/лаунчер пока не подключены.

Проверка состава: `node scripts/check-modpack.mjs`; свежая сверка источников: `node scripts/check-modpack.mjs --online`. Инструкция по проверке скачанных JAR и план реального запуска есть в документе этапа 8.

Оригинальные игровые JAR, Java, миры и runtime-каталоги в Git не включаются. Не переносить все файлы исследовательского кеша `.tools` в рабочую сборку: там также находятся отклонённые кандидаты.
