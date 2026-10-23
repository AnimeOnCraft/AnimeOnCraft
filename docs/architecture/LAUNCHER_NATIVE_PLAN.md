# Native launcher — проектирование второй части этапа 7

> На 2026-10-14 текущий объём и команды заданы в [лаунчере для друзей](../operations/FRIENDS_LAUNCHER.md), 0.7.20. Требования ниже к email verification/рассылке и сложному updater сохранены как история, не блокируют этап 7. Обычный игрок входит по логину/паролю, OWNER восстанавливает пароль вручную на сохранённую почту; административная MFA сохраняется.

Historical implementation checkpoint through launcher0.7.13: account/profile/player forms и Windows credential adapter реализованы; actual scope, поля Record и limitations — [LAUNCHER_ACCOUNT.md](../operations/LAUNCHER_ACCOUNT.md). Security screen: [LAUNCHER_SECURITY.md](../operations/LAUNCHER_SECURITY.md); TUF/manifest7D1: [LAUNCHER_PACK_POLICY.md](../operations/LAUNCHER_PACK_POLICY.md); test-only NTFS7D2a: [LAUNCHER_NTFS_SPIKE.md](../operations/LAUNCHER_NTFS_SPIKE.md); ownership/inventory7D2b: [LAUNCHER_LOCAL_STORE.md](../operations/LAUNCHER_LOCAL_STORE.md). [Persistent test TUF bridge/binding/READY recovery7D2c](../operations/LAUNCHER_TUF_STORE.md) добавлен; [partial online retry7D2d](../operations/LAUNCHER_TUF_RECOVERY.md) реализован test-only. [Bounded downloader7D2e](../operations/LAUNCHER_DOWNLOAD.md) реализован test-only; [Install journal/forward recovery7D2f](../operations/LAUNCHER_INSTALL_JOURNAL.md) реализованы test-only; [Preparation recovery/repair7D2g](../operations/LAUNCHER_REPAIR.md) реализованы test-only; [Installer actor/native panel7D2h](../operations/LAUNCHER_INSTALLER_ACTOR.md) подключены с unavailable normal backend, signed worker test-only; [Verified startup/preview/confirmation7D2i](../operations/LAUNCHER_INSTALLER_STARTUP.md) проверены с test-only journal; [Fixed local development provisioning/lifetime7D2j](../operations/LAUNCHER_LOCAL_INSTANCE.md) подключены normal Windows app; production trust/installer integration/signing остаются планом; same-SID process не изолируется private user DACL, trusted OS boundary и create-only policy указаны явно.

Текущий7E2b: [native Windows updater handoff](../operations/LAUNCHER_SELF_UPDATE_HANDOFF.md)/UI/narrow IPC подключены в0.7.18; actual signed HTTPS fixture0.7.18→NSIS0.7.19/old-process exit/manual new-version restart/data preservation и отказные проверки прошли. Ordinary source None/production signing/PLAY закрыты; cleanup/pending recovery/automatic restart/production publishing gates впереди. Предыдущий7E2a: [signed self-update preparation](../operations/LAUNCHER_SELF_UPDATE.md) реализован и проверен на isolated HTTPS; отдельные metadata/artifact signatures, bounded transport, one-use consent и shared pack-worker exclusion. UI/installer handoff пока не подключены; ordinary source None. Следующий7E2b — actual installed upgrade/restart. Предыдущий7E1: [development Windows installer](../operations/LAUNCHER_WINDOWS_INSTALLER.md)0.7.17 подготовлен; isolated NSIS install/installed WebView2/restart/downgrade/running-app refusal/uninstall/data preservation проверены. Production signing/self-update и игровая сборка/PLAY закрыты. Предыдущий7D2k7: [combined HTTPS/native pack acceptance](../operations/LAUNCHER_PACK_ACCEPTANCE.md) прошёл в изолированной feature-сборке0.7.16; ordinary source configuration отсутствует, install/PLAY закрыты. Предыдущий7D2k6: [trusted repository client](../operations/LAUNCHER_REPOSITORY_CLIENT.md) подключён в0.7.15; normal online refresh/source catalog compile, реальная configuration отсутствует, install/PLAY закрыты. Предыдущий7D2k5: [normal development Engine profile](../operations/LAUNCHER_ENGINE_PROFILE.md) подключён в0.7.14; source configuration закрыта, install/PLAY недоступны. Предыдущий7D2k4: [persistent Engine/async actor](../operations/LAUNCHER_ENGINE_ACTOR.md) завершён test-only; normal0.7.13 не менялся. Предыдущий7D2k3: [read-only history/index reconciliation](../operations/LAUNCHER_HISTORY_RECONCILIATION.md) завершён test-only; normal0.7.13 не менялся. Предыдущий7D2k2: [fixed engine mapping/binding](../operations/LAUNCHER_ENGINE_LAYOUT.md) завершён test-only; normal0.7.13/empty LocalAppData schema не менялись. Предыдущий7D2k1: [lease-bound backend preflight](../operations/LAUNCHER_BACKEND_PREFLIGHT.md) завершён test-only, normal executable0.7.13 не менялся. Implementation/tests7D2j готовы; пользователь подтвердил successful normal PowerShell native smoke, свежий Settings screenshot0.7.13/Ready просмотрен. Отдельная проверка второго normal запуска и immutable metadata pending. Codex exec остаётся `blocked` при filesystem redirection; эти failed runs не засчитываются как positive acceptance. Подробности и команда проверки из обычного PowerShell — [LAUNCHER_LOCAL_INSTANCE.md](../operations/LAUNCHER_LOCAL_INSTANCE.md).

Исходный design checkpoint2026-10-11, D-0026: на момент проектирования launcher был `0.7.0-launcher-ui-dev` / native `0.7.0` без account/install/update/PLAY. Текущая реализация указана выше и в PROJECT_STATE. MC/loader/сборка выбираются на этапе8. Этот документ уточняет [AUTH_SECURITY.md](AUTH_SECURITY.md), [UPDATES.md](UPDATES.md) и существующие backend contracts, а не заменяет их.

## 1. Границы и последовательность реализации

| Часть этапа 7 | Результат | Условие перехода |
| --- | --- | --- |
| 7A, уже выполнено | Public UI, native public reads, settings, бренд | Существующие TS/Rust/WebView2 проверки |
| 7B, этот checkpoint | Команды, lifecycle сессии, credential protocol, install recovery, выбор кандидатов | Проверка actual API/source, первичных источников и изолированный Windows probe |
| 7C, следующая задача | Native AnimeOnCraft login/MFA/register/recovery/logout, настоящий профиль и player forms | Real test PG/Valkey + Windows Credential Manager + WebView2, secret leakage/replay/race tests |
| 7D | TUF client/publisher spike, manifest parser, Windows managed filesystem adapter, downloads/journal/repair | Test-only signed files, negative trust/path tests, actual process interruption/recovery |
| 7E | Installer/self-update и официальный provider feasibility | Собственные release keys/approved client, signing/installer/provider acceptance; PLAY всё ещё зависит от 8–10 |

7C не должен ждать выбора модов. 7D использует небольшие собственные тестовые файлы, а не Minecraft jar. Нельзя сообщать о завершении всего этапа 7 только после login или макета прогресса. Не создавать аккаунт/OWNER в рабочей DB для демонстрации; fixtures — в собственной временной базе.

## 2. Native core и IPC

Планируемые модули `apps/launcher/src-tauri/src/`:

| Модуль | Ответственность | Что не передаётся в React |
| --- | --- | --- |
| `native_policy.rs` | Compile-time environment/endpoints/limits, capability/window checks | Arbitrary URL/headers/method/executable |
| `account_client.rs` | Fixed API routes, DTO validation, bounded request/response | Access/refresh, HTTP body/debug dump |
| `auth.rs` | Serialized transitions, generation, refresh, MFA challenges | TokenPair/challenge bearer |
| `credential_store.rs` | Windows generic credential READY/PENDING/LOGGED_OUT record | Raw credential blob, secret identifiers |
| `player_client.rs` | Own profile/applications/clan/access, intentional write retry | Management API, role grants |
| `trust.rs`, `manifest.rs` | TUF adapter, signed manifest policy, monotonic release | Unverified metadata/download URLs |
| `managed_fs.rs`, `install.rs` | Owned handles, journal, staging/rollback, recovery | Generic read/write/delete/path traversal |
| `downloads.rs` | Bounded streaming/hash/cache/cancel | Arbitrary HTTP proxy |
| `self_update.rs` | Separate launcher release trust + installer orchestration | Plugin configuration override |
| `game.rs` | Future verified Java/loader lifecycle | Shell command/AnimeOnCraft tokens |

Это **планируемые файлы**, их сейчас нет. `public_api.rs`/`settings.rs` сохраняют свою действующую роль. Secret wrappers не реализуют `Serialize`/`Debug`; минимизировать копии, обнулять native buffers при освобождении выбранным проверенным средством. Это не обещание устранить все копии из памяти ОС/WebView.

React пароль принимает через форму и передаёт Rust на время команды; очищает форму после отправки/закрытия, не пишет в storage/logs/URL/telemetry. Rust хранит access в памяти, refresh только в памяти и Windows credential record. AnimeOnCraft и Microsoft credentials имеют разные namespaces. Не экспортировать TokenPair даже для тестового режима.

Существующий `build.rs` вызывает `tauri_build::build()`; зарегистрированные app commands по умолчанию доступны окнам приложения. Нынешний `core:default` не является готовой защитой будущих auth/install commands. В 7C перечислить **все** app commands через `AppManifest::commands`, создать явные permissions, capability только `main`/local bundled frontend, убрать ненужные core permissions. Проверить denial для второго окна и remote navigation настоящим IPC. Запретить frontend создание окон/webviews и shell/fs/http/keyring/updater plugins. External provider — системный браузер через узкий native flow, не webview с IPC. Основание: [Tauri capabilities](https://v2.tauri.app/security/capabilities/).

### Предлагаемый allowlist команд

| Команды | Input из UI | Result/event |
| --- | --- | --- |
| `account_snapshot` | Нет | State, validated profile или null, persistence status, safe error |
| `account_login` | email, password, remember | Signed-in / MFA prompt / generic failure; без challenge token |
| `account_complete_mfa`, `account_cancel_mfa` | operation_id + proof / operation_id | State; challenge хранится native, TTL до 5 минут |
| `account_register`, `account_recover` | email + password / email | Generic accepted; не подтверждение существования аккаунта |
| `account_verify_email`, `account_reset_password` | Вручную введённый mail token + password | Completed; no auto-login |
| `account_logout` | Нет | Local signed-out, remote-revocation status |
| `player_read` | Resource enum + validated cursor/limit | Own profile/access/clan/applications; без ORM/private tokens |
| `player_prepare_action` | Typed apply/withdraw/leave payload | Native operation_id + preview, no HTTP yet |
| `player_submit_action`, `player_retry_action` | operation_id | Result или UNKNOWN_OUTCOME; frozen payload/key сохранён native |
| `pack_check`, `pack_prepare`, `pack_start` | Fixed channel / native plan_id | Verified plan, operation_id, sanitized progress |
| `pack_cancel`, `pack_repair`, `cache_clear` | Own operation_id / own instance_id | State; no arbitrary directory |
| `launcher_update_check`, `launcher_update_install` | Нет / native plan_id | Separate launcher update state |

Имена — proposed контракт, не существующие invoke handlers. UUID/cursor/action constraints остаются backend constraints; неизвестные поля/неверные типы отклоняются. Events — только enum states, operation_id, bytes done/total, file count, safe errors; без paths пользователя/URL/tokens. Progress snapshot по запросу нужен при reload UI; events не единственный источник состояния. Events <= 10/сек, числа означают фактические bytes, total неизвестен до validated plan.

Browser preview сохраняет только public reads. Auth/player/install controls в нём недоступны: никакого bearer/body fetch из браузера. DTO sharing/generated contracts не превращает private endpoint в разрешённый native route.

## 3. AnimeOnCraft session lifecycle

Actual backend: `auth.controller.ts`, `auth-http.ts`, `auth.service.ts`. Native всегда ставит `x-aoc-client: launcher`, не отправляет Origin/Cookie/Sec-Fetch-Site; access — Bearer, refresh/logout — body `{token}`. WEB_ADMIN transport и administrative operations не включаются, даже если profile.system_role=OWNER. Profile — id/email/email_verified/system_role/clan_management/session_id/client_kind; MC UUID/nickname/statistics отсутствуют в этом DTO, их не придумывать.

TokenPair имеет access_token, refresh_token, expires_in, session_id, absolute_expires_at. Проверять prefixes `aa_`/`ar_` + 43 base64url chars, UUID, UTC expiry и положительный bounded expires_in; session_id после refresh обязан совпадать. Внутреннее время access expiry считать monotonic с запасом 30 секунд; absolute expiry проверять отдельно. Limits/password policy брать из backend, не сокращать допустимый Unicode password своим frontend regex.

Production origin — fixed HTTPS из trusted build policy, не UI/env/ответа сервера; hostnames ещё не назначены. Development-only loopback HTTP отдельного build/profile допускает только локальную проверку и **не** production credentials. Origin/credential namespaces для dev/staging/prod различаются. Clients без redirect/cookie jar/proxy; TLS errors не обходятся. Account metadata <= 512 KiB, request <= 16 KiB, connect 2s/total 15s; очередь auth bounded1, throttling показывать по Retry-After с cap3600. Это client policy, не defaults библиотек. Password/token body tracing запрещён.

Текущий reqwest0.13.5 включён с default-features=false и только query: HTTPS backend для account ещё не добавлен. При7C explicitly включить TLS feature `rustls` (название проверено по локальному source Cargo.toml0.13.5), build/lock и actual HTTPS verified certificate/invalid cert/hostname tests обязательны; no blanket disable verification. Public loopback client не является доказательством credential HTTPS transport.

| Состояние | Допустимое поведение |
| --- | --- |
| SIGNED_OUT | Public reads, register/recover/login |
| AUTHENTICATING | Один login, cancel не публикует поздний результат |
| MFA_REQUIRED | Один native challenge/operation/TTL, proof retry только пока challenge действителен |
| SIGNED_IN | Profile только после authenticated GET me; own player reads/actions |
| REFRESHING | One flight; остальные reads ожидают тот же результат, без второго refresh |
| REAUTH_REQUIRED | Access/challenge/profile очищены; старый refresh не повторять |
| SIGNING_OUT | Generation increment, stop accepting authenticated work, persist logout tombstone |
| STORAGE_UNAVAILABLE | Remembered session недоступна; никаких plaintext/localStorage fallback |

Состояние и generation принадлежат одному native actor. Locks не держать на async network await; actor сериализует transitions и проверяет generation перед публикацией. Cancel/login replacement/logout увеличивают generation; поздняя TokenPair не восстанавливает UI, family отзывается best-effort новым полученным refresh, затем secret очищается. On app start credential store читается только после singleton lock; READY даёт один refresh, не cached authenticated profile.

**401 не означает только истёкший access.** Backend возвращает общий AUTHENTICATION_REQUIRED также при revoke/state/ban. Proactive refresh до expiry; при 401 read разрешён максимум один serialized refresh и один replay read. При repeat401 — reauth, не цикл. Refresh/login/MFA/password/reset/verify **не** автоматически повторять после network timeout: сервер мог уже выполнить переход. Любая неоднозначность refresh закрывает family локально и требует login. 429/503 не считать permission denied и не показывать фиктивный профиль.

### Credential protocol и crash boundary

Один `CRED_TYPE_GENERIC` target на environment/user-machine launcher slot, фиксированная версия/namespace. Например prefix `AnimeOnCraft/<environment>/account-v1`; actual environment_id фиксирует build. Target нельзя получить из email/пользовательского пути. Blob UTF-8 <= 2048 bytes, strict schema/duplicate-key rejection:

- READY: format, environment_id, generation, session_id, absolute_expires_at, refresh_token.
- PENDING: format, environment_id, generation, operation_id; **без старого refresh**.
- LOGGED_OUT: format, environment_id, generation; **без tokens**.

Выбрать local-machine persistence (та же Windows учётная запись/машина), не roaming Enterprise. Production adapter кандидат — `windows-sys` поверх CredWriteW/CredReadW/CredDeleteW/CredFree, точный crate pin после Rust build proof; безопасная обёртка изолирует unsafe. Keyring wrapper допустим только если явно выбирает нужный store, target, persistence и не меняет fallback. Не использовать автоматически весь CLI/multi-platform store набор. [Windows CREDENTIALW](https://learn.microsoft.com/en-us/windows/win32/api/wincred/ns-wincred-credentialw), [CredWriteW](https://learn.microsoft.com/en-us/windows/win32/api/wincred/nf-wincred-credwritew).

Refresh для remember=true:

1. Singleton/account actor исключает второй процесс/refresh. Прочитать READY, validate namespace/schema/expiry, сохранить старый refresh только в memory secret.
2. Заменить credential **целиком** на PENDING и проверить read-back operation_id. Если запись/read-back не подтверждены — HTTP refresh не отправлять, reauth/storage error.
3. Единожды отправить старый refresh. Не передавать его downloader/provider/self-updater.
4. Valid TokenPair того же session заменить на новый READY и read-back проверить; только затем публиковать SIGNED_IN/access. Ошибка сохранения — отозвать полученную новую family best-effort, очистить memory, оставить безопасный tombstone/PENDING и требовать login.
5. На timeout/malformed/rejection/crash новый процесс видит PENDING: не знает, использован ли прежний refresh, поэтому **не восстанавливает его и не делает retry**. Предложить повторный вход. Остаточная remote family при потерянном response истечёт либо отзывается через список сессий после нового входа.

Для remember=false refresh только в памяти, один раз на flight; crash=login. Login с remember=true публикуется только после успешного credential READY/read-back; если storage недоступно, можно выбрать отдельный login без запоминания, не скрытый fallback. Logout сначала блокирует публикацию и пишет LOGGED_OUT/read-back, потом remote revoke с refresh, очистка памяти; tombstone можно удалить после подтверждения. Ошибка keyring invalidation означает STORAGE_UNAVAILABLE и неполный logout persistence, это явно показывается; не сообщать об успешном запоминании/выходе.

Global Windows named mutex derived from Windows user SID + environment_id, DACL только этому SID/SYSTEM, owned весь lifecycle native process одним живым OS thread (Windows mutex thread-affine: не переносить ownership между Tokio tasks). Он должен охватывать другие logon sessions той же учётной записи: локальный per-session mutex недостаточен при local-machine credential. Внутри процесса actor/mutex, не rely on cross-thread credential operation ordering. Второй процесс без lock не читает/пишет credential и не начинает работу; lock failure/abandonment проверяется до restore. Same-user malware/OS-admin остаются вне локальной trust boundary. Global namespace/ACL/name-squatting и actual adapter требуют отдельных acceptance tests.

Credential Manager API и read-back не дают нам доказательство power-loss atomicity/отката OS profile. Пока её нет, протокол гарантирует fail-closed восстановление при наблюдаемом PENDING и обычном process crash; нельзя заявлять абсолютную защиту при storage snapshot rollback. Backend strict reuse остаётся дополнительной защитой. Не добавлять grace/idempotent refresh на backend ради UX.

## 4. Profile, recovery и player mutations

7C включает login с MFA уже включённого аккаунта, register/email verify/recover/reset и own profile/applications/access/clan. Exact reads: `GET /api/v1/auth/me`, `/api/v1/me/applications`, `/api/v1/me/access`, `/api/v1/me/clan`; roster при необходимости `GET /api/v1/clans/:id/members` с backend authorization. Manage sessions/MFA enrollment/password security screen можно реализовать отдельной частью 7C, не выдавать отсутствие этой части за полную готовность профиля. Mail token пользователь вводит в форме native; он не попадает в URL launcher/window. Сейчас mail links ведут на HTTPS fragment URL без готовой account landing page: UI не обещает автоматический переход/подтверждение. Deep-link handler не добавлять до отдельного one-time verified bridge дизайна и тестов.

Apply: `POST /api/v1/clans/:id/applications`, message50..4000, If-Match сейчас не требуется. Withdraw `/api/v1/me/applications/:id/withdraw` требует quoted application.version, leave `/api/v1/me/clan/leave` — quoted membership.version из own clan/access; reason1..1000. Rust генерирует idempotency UUID, хранит immutable action+key+expected version; UI видит только operation_id/preview. На сетевом сбое write=UNKNOWN_OUTCOME; deliberate retry того же action/key после проверки current auth, не новый key. На412 обновить snapshot и получить новое намерение, на409 показать conflict. После перезапуска memory pending actions не replay автоматически: сперва own history/state. Нельзя automatically refresh+replay мутацию после401.

Actual apply eligibility включает ACTIVE/verified email/VERIFIED Minecraft identity/no active ban/no membership/open clan. Поэтому login/email verification не позволяют автоматически отправить заявку, пока provider gate закрыт. UI объясняет actual access reason; positive apply acceptance7C возможна на явно synthetic verified test DB fixture, а не изменением identity основной базы. Такое доказательство не закрывает real Microsoft approval/licensed proof.

Access screen показывает actual API reason/state/grants, не локальный boolean allow; cached profile не включает PLAY. Minecraft binding и его UI требуют own approved provider flow отдельно. Existing bind challenge+Minecraft access backend proof не является game login/admission. Privileged manager/OWNER buttons в native не появляются.

## 5. TUF и manifest trust

Кандидат spike: **tough=0.24.0**, publisher **tuftool=0.17.0**; точные release metadata и Cargo build/lock/audit надо подтвердить до adoption. Причина не брать старую ветку: upstream delegated metadata advisory исправлена в0.22.0;0.24.0 исправляет expiration serialization issue0.23.0. Не считать release notes доказательством conformance нашей интеграции. [Advisory](https://github.com/awslabs/tough/security/advisories/GHSA-4v58-8p28-2rq3), [release0.24.0](https://github.com/awslabs/tough/releases/tag/tough-v0.24.0), [releases](https://github.com/awslabs/tough/releases).

RepositoryLoader: embedded env root; explicit persistent datastore для rollback detection, Safe expiration, explicit limits/custom transport. Default temporary datastore недостаточен. Root update chain/persist проверяется spike: не заново доверять API root на каждом старте. Первая версия publisher без delegations; suite всё равно проверяет rejection delegated metadata/unsupported features. [RepositoryLoader](https://docs.rs/tough/0.24.0/tough/struct.RepositoryLoader.html), [TUF spec](https://theupdateframework.github.io/specification/latest/).

Production roots/keys сейчас не существуют. Test-only roots в fixture namespace, одноразовые keys только ignored temp directory; fixture root не принимается release build. Прежние threshold/expiry/consistent snapshot policies [UPDATES.md](UPDATES.md) сохраняются. Own trusted state corruption=TRUST_STATE_INVALID, не silent reset to embedded version. Cache clear удаляет content cache, **не** trust datastore/release high-water mark.

Manifest schema1 уточняется перед parser implementation: все поля из UPDATES required, unknown fields/duplicate keys запрещены на всех уровнях. Platform добавить как explicit `{os,arch}` в schema1 до первого выпуска (пока опубликованного schema1 нет), сверять channel/platform/pack/release path с trusted target и compatibility. Positive JSON integer release_sequence>=1/length>=0 в u64 с checked sum; max10k files/2MiB/20GiB. Hash exactly64 lowercase hex, managed=true, supported side. URI не часть free-form file entry: proposed `source_ref:{source_id,object_key}`; source_id только compile-time allowlist, key отдельный portable URL-key validator, URL escaping once. Production allowlisted origins/redirect chains ещё предстоит назначить, default deny.

Portable path v1: nonempty `/`-separated components из ASCII letters/digits/`._-`, component<=100 bytes, path<=240 bytes; no empty/`.`/`..`, leading dot in component, trailing dot, colon/backslash/percent/space, drive/UNC/device prefixes. Reject Windows CON/PRN/AUX/NUL/COM1..9/LPT1..9 и CONIN$/CONOUT$ с расширениями; `$` уже вне alphabet. Case-fold ASCII duplicate и file/ancestor conflict (`mods/x` + `mods/x/a.jar`) reject. Сравнение paths не URL decoding. Managed allowlist: `mods/`, `libraries/`, `assets/objects/`, `versions/`, `natives/` и `runtime/` только после отдельного format/extraction adapter. `config/` не managed до политики этапа9; world/saves/options/screenshots/logs/secrets/cache/staging/journal запрещены независимо от подписанного manifest. Не принимать managed .exe/scripts вне runtime allowlist. Проверять полный manifest до side filtering.

Compatibility сейчас UNAVAILABLE/SKELETON_ONLY; actual release resolver отсутствует. Plan может проверять test fixtures, но production installation пока не получит release. Manifest signatures/hashes не доказывают license/redistribution; source/license matrix этапа8 обязательна.

## 6. Download, filesystem и transaction recovery

Download client отдельный от auth, без bearer/cookies/proxy. Все URL/каждый redirect — known HTTPS origins и bounded object keys; redirects максимум3, без downgrade/credentials. Начальная 7D версия **без resume**: отменённый `.part` удаляется из own staging, retry скачивает заново. HTTP Range/ETag добавляется позже только с verified offset/content-range/identity encoding/hash proof. Это допустимый restart вариант UPDATES, не обещание resume.

<=4 streams, bounded32..256KiB buffers, connect5s, idle15s, total file deadline10min, finite retries только downloads до apply. Accept-Encoding identity; expected length из signed manifest, content-length только hint; bytes превышают expected=abort, EOF length/hash mismatch=reject. Zero length permitted only with exact empty-file hash. Cache object key=SHA256, rehash перед reuse; создание `.part` exclusive, flush перед promote. Hash streaming, concurrent counters checked u64. Cancellation cooperative; UI closing/reload не отменяет native install автоматически.

Первый managed install target **local NTFS dedicated default user instance**, без custom path/network/removable volumes до proof. Settings RAM/resolution schema1 остаётся прежней; chosen root не добавлять обычным текстовым settings field. `instance_id` и random owner marker связывают root/volume identity/file IDs; marker не даёт право удалить произвольную папку. Journal, staging, rollback рядом на том же volume, отдельные own directories. Не касаться `F:\AnimeOnCraft`, Docker, downloads пользователя или credential store очисткой pack cache.

Windows adapter открывает и удерживает handles каждого directory component с no-follow/reparse inspection и запретом rename/delete sharing на parents, проверяет file IDs/volume/containment, для final file reject reparse/hardlinks>1. Операции relative to retained trusted parent handles либо verified handle-based rename, никаких canonicalize-then-path delete/write. CreateFileW OPEN_REPARSE_POINT для финального компонента сам по себе не решает intermediate junction race. Checked mkdir/open/create/rename/delete primitives и RAII handles проектируются/доказываются isolated NTFS spike; если это не получается — filesystem gate остаётся закрыт, не fallback std::fs recursion. [CreateFileW](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-createfilew), [reparse operations](https://learn.microsoft.com/en-us/windows/win32/fileio/reparse-points-and-file-operations).

Installer: bounded journal<=4MiB, <=10k entries, immutable plan hash/instance_id/old index/new manifest/old+new hashes/relative owned paths/op phases. Parsing journal не разрешает arbitrary path и не заменяет подписанный manifest: recovery повторно validates plan/trust/ownership. Free space на target volume >= uncached staging + replacement/rollback bytes + journal/index reserve64MiB, checked sum; также реагировать на disk-full после предварительной проверки. Foreign files never scheduled delete. Untracked mods jar=FOREIGN_MANAGED_FILE и blocked launch; modified formerly managed bytes сохранить в conflict/rollback, не уничтожать пользовательские изменения.

| Фаза | Crash/cancel recovery |
| --- | --- |
| PREPARING/DOWNLOADING | Managed tree не изменён; удалить только подтверждённые own partials, повторить check/plan |
| PREPARED | All new bytes verified, old files/index verified, persisted journal до изменения; можно отменить |
| APPLYING | Перед каждой rename/backup persisted intent; после операции record+flush. Cancel откладывается до recovery-safe boundary; запрещён PLAY |
| INDEX_COMMITTED | Новый index привязан manifest/hash/plan, rehash managed set и required trust; до commit marker instance dirty |
| COMMITTED | Новый набор подтверждён; cleanup только owned obsolete/rollback/cache; interruption cleanup идемпотентен |
| RECOVERY_REQUIRED | Rollback старого набора только при trusted old index+bytes; если неоднозначно/повреждено — repair/reinstall managed set, пользовательские файлы сохранены |

После смерти процесса или перезапуска ОС любое in-flight/unknown journal recover **до** PLAY/install. Если новый file уже installed, а phase не записан, сравнить retained ownership и old/new hashes; не blind repeat rename/delete. Journal cannot alone authorize external files. Same-volume rename не означает durability всей транзакции. File data/journal/index flush, replacement/rename failures и Windows sharing/AV interference нуждаются в fault injection; read-back не доказательство power loss. Initial acceptance claims process-crash recovery на NTFS, OS/power-loss остаётся отдельно до доказательства. Self-update не стартует при APPLYING/recovery/game running; game launch не стартует при dirty instance.

## 7. Self-update и Java/provider gates

Launcher updater отдельный signing key и compiled public key/HTTPS endpoint policy; TUF root не подписывает exe. Выбрать per-user NSIS installer candidate, passive mode; first installer Authenticode отдельно. Через narrow Rust commands, без JS updater API/config override. Explicit install click, verified plan expiry/version/platform, no semver downgrade; возвращение к старым bytes выпускается с новой большей версией. Сейчас bundle.active=false, plugin отсутствует. [Tauri updater](https://v2.tauri.app/plugin/updater/).

Official Minecraft identity: own approved Microsoft public-client registration, external browser PKCE/state или разрешённый device flow; не borrowed client ID и не password form. Microsoft refresh namespace/keyring отдельно от AnimeOnCraft. До approval/licensed proof UI показывает недоступность binding, не identity stub. Production host/client ID/keys — внешние prerequisites, не повод переключить security flag на true.

Java/runtime artifact источник+license+hash и exact major определяются после8. Launch spike в7 не запускает произвольную command строку: typed verified executable + argv allowlist, без agent/bootclasspath/user shell; no AnimeOnCraft bearer Java. Real loader/online-mode/admission/PLAY acceptance остаётся8–10; этап7 может завершить download/repair своих тестовых files без игрового сервера.

## 8. Проверки и пределы доказательств

Выполнено в этом checkpoint: source review native/backend contracts; primary source verification; `scripts/credential-store-probe.ps1` на Windows PowerShell5.1. Probe создаёт уникальный `AnimeOnCraft/probe/<GUID>` credential, пишет/читает/replaces READY→PENDING→READY с synthetic strings, другой процесс видит новый blob, другой процесс не получает held Global mutex. Local-machine persistence field проверен через actual CredReadW. Finally удаляет только свою запись/outputs, отсутствие credential подтверждено. Probe **не** обращается к backend/реальным аккаунтам, не перечисляет хранилище; это C# Win32 interop внутри PowerShell, не готовый Rust adapter.

Не доказаны: реальный backend login через Rust, forced-kill refresh recovery, MFA UI/player writes, production mutex ACL и multi-logon, credential power loss, TUF integration/conformance/publisher fixture, NTFS no-follow/crash journal, installer signatures/provider/Java/MC. Проверка synthetic PENDING visibility не симулирует настоящий HTTP refresh и не выдаётся за его acceptance.

Перед завершением7C: existing TS/Rust tests/build/lint; own test DB real login/refresh/reuse/family/logout/recovery/MFA; keyring unavailable/wrong namespace/corrupt/oversize/duplicate keys; second process/parallel401/logout-vs-refresh/cancel-vs-login; forced termination до/после PENDING/HTTP/newREADY; WebView2 forms/profile/write errors/unknown outcome; serialized IPC/events/storage/logs не содержат access/refresh/challenge/password. Provider остаётся закрыт если внешнего proof нет.

Перед adoption7D: exact Cargo/publisher lock+Windows build, trusted root rotation/old+new thresholds, rollback after restart, expiry/freeze/wrong clock/mix-and-match, tampered metadata/target length/hash, trailing-zero expiry regression, duplicates/path/bounds/source mismatch, zero-byte/too-long/slow stream/cancel/cache corruption. NTFS adversarial junction/symlink/hardlink/parent-swap, foreign files preservation, free-space/disk-full/sharing failures, actual kill после каждого journal phase, repeated recovery and repair, dirty instance no PLAY. Crash tests только на своих маленьких fixture roots; secrets/мир пользователя не трогать.

Команда proof:

```powershell
Set-Location F:\AnimeOnCraft
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\credential-store-probe.ps1
```

Следующая самостоятельная часть — **7C native account auth + player screens**, GPT-5.6 Sol/MEDIUM по исходному ТЗ после design. Реализация security adapters проходит проверки выше; при появлении нового архитектурного риска возвращаться к указанному в ТЗ security reasoning checkpoint. Модель инструментами не переключалась.
