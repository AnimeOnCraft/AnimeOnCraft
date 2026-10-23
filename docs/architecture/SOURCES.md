# Источники архитектуры и границы доказательств

Дата проверки: 2026-10-09. Только primary documentation/project source/registry metadata. Read-only исследования; пакеты/toolchains не устанавливались.

| Источник | Основание / ограничение |
| --- | --- |
| [PostgreSQL row locks](https://www.postgresql.org/docs/17/explicit-locking.html), [partial indexes](https://www.postgresql.org/docs/17/indexes-partial.html), [isolation](https://www.postgresql.org/docs/17/transaction-iso.html) | Доступные DB primitives; конкретный lock order/invariants — наши design choices |
| [Prisma transactions](https://www.prisma.io/docs/orm/fundamentals/transactions), [migration documentation](https://www.prisma.io/docs/orm/migrations/the-migration-graph) | ORM transaction/migration facilities; major/API меняются, CLI/client pin обязателен |
| [Nest Fastify](https://docs.nestjs.com/techniques/performance) | Официальный adapter; не benchmark проекта |
| [OWASP authentication](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html), [sessions](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html), [CSRF](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html) | Защитные механизмы; наши TTL/MFA/transport/budgets не являются defaults источника |
| [RFC8252 native OAuth](https://datatracker.ietf.org/doc/html/rfc8252), [Microsoft authorization code](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow) | External browser/public-client/PKCE boundary; Microsoft grant не равен Minecraft entitlement |
| [Prism AuthFlow](https://github.com/PrismLauncher/PrismLauncher/blob/develop/launcher/minecraft/auth/AuthFlow.cpp), [Profile step](https://github.com/PrismLauncher/PrismLauncher/blob/develop/launcher/minecraft/auth/steps/MinecraftProfileStep.cpp), [Entitlement step](https://github.com/PrismLauncher/PrismLauncher/blob/develop/launcher/minecraft/auth/steps/EntitlementsStep.cpp), [Login step](https://github.com/PrismLauncher/PrismLauncher/blob/develop/launcher/minecraft/auth/steps/LauncherLoginStep.cpp) | Primary source наблюдаемой цепочки/profile/ownership; не копировать код/ID/credentials и не считать официальным approval AnimeOnCraft |
| [Prism API settings](https://www.prismlauncher.org/wiki/help-pages/apis/) | Собственный client ID отдельно конфигурируется; production permissions надо получить самому |
| [Windows Credential Manager](https://learn.microsoft.com/en-us/windows/win32/secauthn/credentials-management) | OS store candidate; native Rust integration пока не проверена |
| [Tauri capabilities](https://v2.tauri.app/security/capabilities/), [updater](https://v2.tauri.app/plugin/updater/) | Frontend/native boundary и обязательная executable update signature; не safe modpack installer |
| [TUF overview](https://theupdateframework.io/docs/overview/), [specification](https://theupdateframework.github.io/specification/latest/), [Rust tough](https://github.com/awslabs/tough) | Root/roles/version/expiry/trusted targets; installer filesystem и initial trust остаются нашей ответственностью |
| [Valkey](https://valkey.io/), [security](https://valkey.io/topics/security/) | Поддерживаемый storage8.1 и private ACL model; rate-limit client/atomic implementation tests ещё впереди |
| [Gradle Java compatibility](https://docs.gradle.org/current/userguide/compatibility.html) | Проверить wrapper/JDK21 compatibility; release17 core не фиксирует MC Java runtime |

## Registry observation

Read-only `npm.cmd view` на packages/versions/engines/peerDependencies, без npm install:

| Пакет / запись | Наблюдение |
| --- | --- |
| `@nestjs/core`, `@nestjs/platform-fastify` | Latest12.1.2; core Node>=20; adapter peers core/common^12 |
| `@nestjs/common@12.1.2` | Peers rxjs^7.1,validator>=0.13.2,transformer^0.4/0.5,reflect-metadata^0.1/0.2 |
| `@nestjs/platform-fastify@12.1.2` | Declared Fastify5.12.5 |
| `prisma` latest / `@prisma/client` latest | CLI8.0.0-rc.22 versus client7.10.0 — несовпадающие tags; latest pair непригодна как автоматический pin |
| `prisma@7.10.0`, `@prisma/client@7.10.0` | Одинаковая stable version, Node^20.19/^22.12/>=24; выбрана baseline |
| `@tauri-apps/api` | 2.12.2; Rust/plugin peer/MSRV ещё нужно проверить |
| `react`, `vite`, `pnpm` | 19.3.0 /8.3.4/12.10.1; Vite Node^20.19 or >=22.12; pnpm Node>=18 |

Metadata совместима с локальным Node24.20.0, но не доказывает native builds или все peer/transitive relationships. Следующий этап закрепляет exact dependencies/lockfiles после реального install/build. Технологический выбор Discovery и ссылки по Node/PostgreSQL/usage guidelines также сохранены в [Discovery sources](../discovery/SOURCES.md).

Не выполнены: provider approval/login, selected loader early admission hook, TUF conformance/native path tests, real DB concurrency, capacity profiling, coordinated restore, public deployment. Все они остаются future gates, не успешными проверками этапа 1.

## Native design7B — источники проверены 2026-10-11

Исторические ограничения этапа1 выше сохранены; актуальные implementation proofs — в PROJECT_STATE. Новая спецификация: [LAUNCHER_NATIVE_PLAN.md](LAUNCHER_NATIVE_PLAN.md).

| Первичный источник | Что подтверждает для design, не implementation proof |
| --- | --- |
| [Tauri capabilities](https://v2.tauri.app/security/capabilities/) | App commands default доступны окнам приложения; AppManifest::commands нужен для явного app ACL |
| [CredWriteW](https://learn.microsoft.com/en-us/windows/win32/api/wincred/nf-wincred-credwritew), [CREDENTIALW](https://learn.microsoft.com/en-us/windows/win32/api/wincred/ns-wincred-credentialw) | Generic credentials replacement, byte blob max2560, local-machine versus roaming persistence; наш bound2048 — решение проекта |
| [CreateMutexW](https://learn.microsoft.com/en-us/windows/win32/api/synchapi/nf-synchapi-createmutexw) | Named Windows mutex; production SID namespace/ACL/ownership ещё требуют adapter acceptance |
| [TUF specification](https://theupdateframework.github.io/specification/latest/), [tough RepositoryLoader](https://docs.rs/tough/0.24.0/tough/struct.RepositoryLoader.html) | Trusted initial root/metadata verification, explicit persistent datastore и Safe expiration; default temporary datastore не сохраняет rollback history |
| [tough advisory](https://github.com/awslabs/tough/security/advisories/GHSA-4v58-8p28-2rq3), [release0.24.0](https://github.com/awslabs/tough/releases/tag/tough-v0.24.0), [tuftool0.17.0](https://github.com/awslabs/tough/releases/tag/tuftool-v0.17.0) | Старые delegated-validation и expiration serialization исправления; candidates0.24.0/0.17.0, не Cargo adoption/conformance proof |
| [CreateFileW](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-createfilew), [reparse operations](https://learn.microsoft.com/en-us/windows/win32/fileio/reparse-points-and-file-operations) | Final no-follow flag и cached write/flush различаются; retained parent/no-follow/rename protocol должен быть проверен отдельно |
| [Tauri updater](https://v2.tauri.app/plugin/updater/) | Отдельные signed updater artifacts/embedded public key/Windows installer modes; подпись не заменяет initial Authenticode или modpack TUF |

Actual isolated Windows proof7B: credential replacement/read-back из другого процесса/local persistence field/Global mutex exclusion/own cleanup. Power-loss, forced-kill/backend refresh, multi-logon ACL, Rust credentials, TUF client/publisher, NTFS transaction и signed installer в этом checkpoint не тестировались.

## Native implementation7C dependencies

- [Zeroizing1.9.0](https://docs.rs/zeroize/1.9.0/zeroize/struct.Zeroizing.html): zeroization on drop собственных buffers, без обещания очистить копии во внутренних HTTP/WebView/OS allocations.
- [windows-sys0.61.2 CredWriteW](https://docs.rs/windows-sys/0.61.2/windows_sys/Win32/Security/Credentials/fn.CredWriteW.html): native Windows bindings, Cargo pinned.
- [reqwest0.13.5 ClientBuilder](https://docs.rs/reqwest/0.13.5/reqwest/struct.ClientBuilder.html): no_proxy/redirect/timeouts, explicit rustls feature; actual TLS fixture proof в PROJECT_STATE.
