# Этап 2 — задание skeleton

Модель этапа: **GPT-5.6 Sol**, reasoning **MEDIUM**. Этап2 завершён2026-10-10; этот документ сохраняет его scope и exit checks. Созданные исходники перечислены в PROJECT_MAP, фактические результаты — в PROJECT_STATE. Следующий этап3 выполняется только после новой команды владельца.

## Объём

Создать Git monorepo, build/config manifests и минимальные запускаемые components. Не реализовывать регистрационный flow, clan CRUD, реальные approvals/whitelist, модовый server adapter, полноценный updater или deployment. Routes ещё отсутствующих функций не возвращают fake success/данные. При broken build сначала исправить, затем продолжать.

| Путь / планируемые файлы | Назначение и элементы | Проверяемый результат |
| --- | --- | --- |
| `package.json`, `pnpm-workspace.yaml`, lockfile, `.gitignore`, `.editorconfig`, env examples | Engine24,pnpm12,dev/build/lint/test orchestration; secrets/world/binaries/caches ignored | Frozen install, no secret fixtures tracked |
| `apps/backend/src/{main,app.module}.ts` | Nest12/Fastify5, public/private bind config, strict validation, controlled errors/redacted logs | Реальный process start, no debug dump |
| `apps/backend/src/modules/health/` | HealthController, HealthService | live/ready с real DB/Valkey states |
| `apps/backend/src/modules/clans/` | Public read-only list; пока disabled seeds не показываются | Empty discovery list honest response, no fake counts |
| `apps/backend/db/` | Prisma7.10 CLI/client+adapter same stable version; minimal users/clans migration и SQL constraints; disabled four-clan seeds | Real PG clean apply, idempotent seed, no OWNER/default credentials |
| `packages/contracts/` | OpenAPI3.1 health/compatibility/clans + JSON Schema protocol fixtures | Generated TS client/models; DTO/schema validation golden tests |
| `packages/ui/`, `packages/tooling/` | Common tokens/components, strict TS/lint | Компоненты доступны UI без backend/ORM imports |
| `apps/admin/src/` | React app shell, protected routes planned, setup/unavailable state | Browser build; отсутствующий auth явно blocks management |
| `apps/launcher/src/`, `src-tauri/` | React shell,Rust typed settings/health command, bounded logs,minimal capabilities/CSP | Native build/window открывается; secrets/update/PLAY ещё disabled |
| `minecraft/integration/{core,contract}/` | Gradle Wrapper,JDK21,release17; transport interfaces, schema DTO, config/timeouts | JUnit + jar; никакого loader/vanilla API dependency |
| `infra/compose/` | Dev PostgreSQL17/Valkey8.1, optional API profile, localhost ports, healthchecks | DB/storage ready; migrations и API readiness реально проверены |
| `tests/`, dev scripts, docs | Contract/DB HTTP/skeleton smoke, Windows/native and Java instructions | Проверки вызывают реальные build tools, exit failure propagated |

Другие modules/contracts появятся с реализацией этапов 3/4/5/7/10. Данные model spec — target, не обязанность сделать все таблицы в skeleton. CI минимальный lint/test/build с отдельными Windows native/Java checks и Linux API job; release signing/publishing пока отсутствует. Remote repository/PR создаётся только по поручению; локальный Git допустим в этапе 2.

## Dependency baseline и prerequisites

Metadata на 2026-10-09: Nest core/common/platform-fastify12.1.2, Fastify5.12.5; Prisma CLI/client7.10.0; React19.3.0,Vite8.3.4,pnpm12.10.1,Tauri JS API2.12.2 — кандидаты pin. Проверить associated plugins/driver versions и warnings перед install; не ставить CLI Prisma8 RC по `latest`. Rust crates/MSRV/Gradle wrapper/Java distribution — stable compatible versions, отдельные lockfiles/verified wrappers. Не предполагать version parity npm Tauri package и Rust crates.

Для Windows понадобятся Rust stable-msvc, C++ Build Tools, WebView2, JDK21 и рабочая Docker engine/Compose (обычно WSL2). Локально подтверждены только Node24/npm/Git; отсутствие Rust/Cargo/Docker в PATH не доказательство полной установки. Не менять системные env variables/Java глобально без необходимости: prefer workspace toolchains и explicit paths. Server Java runtime остаётся этапом 8.

Секреты generated локально, не defaults committed. Dev DB password только local env; runtime DDL отдельно от migration role даже в documented production plan. Env validation fails closed на missing secret/prod test profile. Этап 2 не требует auth provider credentials, Minecraft binaries или signing keys.

## UI shell и будущие data states

Единый тёмный стиль: tokens background/surface/border/text/accent, readable typography, spacing scale, keyboard/focus, reduced motion. Без random placeholder images/fake news/online. Launcher navigation: home,clans/detail,profile,news,settings; skeleton создаёт shell и реальные loading/error/unavailable states, detail/forms появятся позже. Settings scaffolding отмечает unsupported fields, не сохраняет неработающую настройку как применённую.

Этапы 6/7 завершают product screens: PLAY показывает отдельные auth/access/install/server statuses, byte progress/cancel/retry; clan cards/detail отображают API data и own application status; profile — identity/clan/role/access/playtime/date; news sanitized; settings RAM/Java/resolution/fullscreen/install/JVM/repair/reinstall/cache/logs. Admin managers видят только assigned scope, OWNER — create-clan form и preview effects archive/access exceptions. Backend authorization обязательна независимо от скрытых controls.

## Exit checks

1. Frozen workspace install, TS lint/typecheck/build/tests без ignored failures.
2. Cargo fmt/check/test + actual Tauri Windows build; window/settings/error smoke и capabilities review.
3. Gradle clean test/jar с JDK21 и release17; wrapper integrity; jar не выдавать за installed server mod.
4. Real PostgreSQL migration/seed/HTTP read; owner uniqueness constraint; Valkey up/down/readiness; clean shutdown.
5. Contract response/schema/generation consistency, no ORM/shared server secrets in UI bundle.
6. Compose config/start health; documentation commands tested; no .env/world/generated binary secrets tracked.
7. PROJECT_MAP/STATE/README обновлены с конкретными файлами, tools, commands/results и оставшимися TODO.

Если native/Java/Docker toolchain недоступен, установка не удалась или prerequisite требует внешнего действия, зафиксировать точную проблему и оставить этап 2 незавершённым. Passing web build не заменяет native build. Remote CI результат принимать только при фактическом запуске, не по наличию YAML. После завершения остановиться перед этапом 3; та же модель/MEDIUM может продолжить только по следующей команде владельца.
