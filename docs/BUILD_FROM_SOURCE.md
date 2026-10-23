# Сборка из исходников

Публичный код: https://github.com/AnimeOnCraft/AnimeOnCraft.
Основные части: React/Tauri — лаунчер, NestJS/PostgreSQL — API,
Java/NeoForge — игровой мод, apps/download-site — сайт скачивания.

Требования: Node.js 24, pnpm 12.10.1 (Corepack), Rust (не ниже rust-version в
Cargo.toml), MSVC Build Tools и Windows SDK, WebView2 Runtime, JDK 21.
Для локального API нужны PostgreSQL и Valkey (Compose в infra/compose).

После клонирования:

```powershell
corepack pnpm@12.10.1 install --frozen-lockfile
corepack pnpm --filter @animeoncraft/contracts build
corepack pnpm --filter @animeoncraft/launcher typecheck
corepack pnpm --filter @animeoncraft/backend test
$env:CI='true'
$env:AOC_SKIP_GAME_BOOTSTRAP='1'
corepack pnpm --filter @animeoncraft/launcher tauri:build --no-bundle
Remove-Item Env:AOC_SKIP_GAME_BOOTSTRAP
Remove-Item Env:CI
```

Это проверочная сборка оболочки без игрового комплекта. EXE появится в
apps/launcher/src-tauri/target/release. Для локального API
скопируйте .env.example в .env и задайте собственные секреты и локальные адреса.
Некоторые игровые проверки содержат абсолютные F:-пути окружения разработчика;
при запуске на другом компьютере адаптируйте их к своему каталогу.

Игровой мод (из minecraft/integration, JAVA_HOME указывает на JDK 21):

```powershell
./gradlew.bat :neoforge:test :neoforge:jar
```

Первый запуск Gradle скачает NeoForge и зависимости. Собственный JAR появится
в neoforge/build/libs. Для комплектного установщика сначала выполните
скрипты prepare/bootstrap из scripts: Java и модпак не хранятся в Git.
Состав и точные источники находятся в minecraft/modpack/profile.json и конфигурации
src-tauri. Упаковка описана в scripts/build-launcher-installer.ps1.
Закрытый ключ подписи не публикуется. Для своего канала обновлений используйте
собственные адреса и пару ключей.

Для исходников выпуска 0.8.8 используйте тег `v0.8.8`; публичная ветка может
продолжать разработку. `RELEASE_BUILD.json` содержит контрольные суммы выпуска.
Побитовое совпадение самостоятельной сборки не гарантируется. Сверяйте контрольную
сумму конкретного установщика (Get-FileHash -Algorithm SHA256). Подпись и версия
проверяются в simple_update.rs; открытый код сам по себе не доказывает
соответствие произвольного скачанного бинарного файла.
