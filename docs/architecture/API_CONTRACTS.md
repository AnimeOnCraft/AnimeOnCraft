# API и contracts

Версия external/integration API: **v1**. Реализованы public reads/local CRUD, default-off auth, community25 operations и administration14 operations ([CLANS.md](../operations/CLANS.md), [ADMIN_PANEL.md](../operations/ADMIN_PANEL.md)). Public/development/auth/community/administration OpenAPI разделены. OWNER users/state/news/audit/bans/grants/server reads реализованы; media/settings/integration commands/admission ниже остаются проектом. External provider/inbox/positive trusted-browser gates не закрыты.

## Общий contract

REST JSON UTF-8; public `/api/v1`, private `/integration/v1` на другом listener. `additionalProperties=false` для request DTO; только allowlisted поля. UUID canonical string, даты RFC3339 UTC, bigint/counters/version decimal strings. Server-generated request_id в response/logs; client correlation не становится log fragment без validation.

Errors: `{error:{code,message,request_id,details?}}`. Details содержат безопасные field errors, не SQL/stack/provider body/token. HTTP: 400 validation, 401 invalid session, 403 forbidden/MFA_REQUIRED, 404 missing или inaccessible scoped resource, 409 domain conflict/idempotency mismatch, 412 stale version, 428 missing precondition, 429 с Retry-After, 503 dependency/maintenance. DTO различает application_status, effective_access и sync_status; не один `approved` boolean.

Pagination: cursor(keyset created_at/id), limit default 20/max 100; cursor type/resource/filter-bound и подписан. Stable sorting allowlist, не SQL field от клиента. Lists не раскрывают email, bans/private reasons, service secrets или audit player payload.

## Public/user routes

| Methods / route | Authorization | Результат / важные границы |
| --- | --- | --- |
| POST `/auth/register`, `/auth/login` | Rate-limited anonymous; browser CSRF/origin | PLAYER либо opaque tokens native / cookies web; login может вернуть MFA challenge |
| GET `/auth/csrf` | Bounded same-origin browser request | Pre-session/session-bound CSRF token; не выдаёт access/role |
| POST `/auth/verify-email`, `/auth/recovery/request`, `/auth/recovery/complete` | One-time challenge + limits | Generic responses, reset без auto-login |
| POST `/auth/mfa/complete`, `/auth/mfa/enroll`, `/auth/mfa/confirm`, `/auth/step-up` | Pending challenge или current session как определено AUTH_SECURITY | MFA enrollment/step-up; одноразовый replay prevention |
| POST `/auth/refresh`, `/auth/logout` | Refresh/current session, correct transport | Secret rotation; auth исключён из обычного idempotency response cache |
| GET `/me`, `/me/sessions`; DELETE `/me/sessions/{id}` | Own session only | Profile и sanitized device/session metadata; revoke own family |
| POST `/me/password` | Password reauth/MFA if enabled | Revoke sessions; никак не role patch |
| POST `/me/minecraft-links/challenge`, `/me/minecraft-links/verify`; DELETE `/me/minecraft-links/{id}` | Verified own user + recent reauth | One-use proof, ownership conflict; DELETE только revoke, не чужой transfer |
| GET `/clans`, `/clans/{id}` | Anonymous safe summary или authenticated richer view | Discovery только enabled active; disabled detail доступен своим members/assigned managers/OWNER; archived только history/management |
| GET `/clans/{id}/members` | Authenticated player | Только nickname, clan role, join date, freshness online; без email/UUID/private stats; setting может закрыть roster |
| POST `/clans/{id}/applications` | Verified email ACTIVE player, no active GAME/ACCOUNT ban | message only; user_id/status/reviewer вычисляются backend |
| GET `/me/applications`; POST `/me/applications/{id}/withdraw` | Own applicant | History/status; terminal не отменяется |
| POST `/me/clan/leave` | Own ACTIVE member | Atomic revoke membership grant; manager assignment OWNER отдельно |
| GET `/me/access`, `/me/statistics` | Own session | Reason codes и freshness, не admission token |
| GET `/news`, `/news/{slug}` | Public published content | Sanitized Markdown, drafts закрыты |
| GET `/server/status`, `/launcher/compatibility` | Public bounded metadata | Status ONLINE/OFFLINE/UNKNOWN/MAINTENANCE, last_updated, compatible releases/protocol |
| GET `/launcher/releases/{channel}/{platform}` | Public metadata | Только references подписанных releases; не arbitrary URL/command |

## Management routes

Browser WEB_ADMIN session с MFA обязательна. Sensitive mutations дополнительно step-up. OWNER role не извлекается из request. Manager access действует только для route clan scope, даже если body содержит другой ID.

| Methods / route | Допуск | Contract |
| --- | --- | --- |
| GET `/management/dashboard` | OWNER или manager | OWNER totals; manager только assigned clans |
| POST `/management/clans`; PATCH `/management/clans/{id}` | OWNER | Create/edit name/code/description/Discord/logo/enabled; unknown privileged fields reject |
| POST `/management/clans/{id}/archive`, `/unarchive` | OWNER | Effects/access exceptions preview + confirmed version; history сохраняется |
| GET `/management/clans/{id}/archive-preview` | OWNER | Counts affected members/managers/applications и independent OWNER grants, aggregate version; не mutation |
| PUT/DELETE `/management/clans/{id}/managers/{userId}` | OWNER | Fixed MANAGER role; reason + individual can_remove_members (PUT), reason (DELETE); clan If-Match; revoke affected sessions |
| GET `/management/clans/{id}/applications`, `/members` | OWNER или assigned manager | Scope проверяется и в query, и при resource access |
| POST `/management/applications/{id}/approve`, `/reject` | OWNER или manager application.clan | reason, If-Match; self-review forbidden |
| POST `/management/clans/{id}/members` | OWNER | Direct admission с reason; atomic membership/grant/audit |
| DELETE `/management/clans/{id}/members/{userId}` | OWNER или permitted manager | Manager не исключает себя/действующего manager; reason обязательно |
| GET `/management/users`, `/users/{id}`; PATCH `/users/{id}` | OWNER | Account state и bounded display fields; system_role/password_hash/epochs запрещены |
| POST `/management/users/{id}/whitelist-grants`; DELETE `/management/whitelist-grants/{id}` | OWNER | Только OWNER exception grants, expires_at/reason; membership grants нельзя произвольно оторвать от membership |
| POST `/management/users/{id}/bans`; POST `/management/bans/{id}/revoke` | OWNER | GAME/ACCOUNT,expiry,public/private reason; event/audit |
| GET/POST `/management/news`; GET/PATCH `/management/news/{id}`; POST `/management/news/{id}/publish`, `/archive` | OWNER | Draft/edit/publish/archive versioned; нет raw HTML |
| POST `/management/media` | OWNER | Bounded decoded raster upload, controlled media ID; path/remote URL не принимается |
| GET `/management/audit`, `/statistics`, `/server`, `/diagnostics` | OWNER | Bounded redacted records, no arbitrary filesystem logs/query |
| PATCH `/management/settings`; POST `/management/server-commands` | OWNER + step-up | Typed settings / fixed command enum, no Docker socket/RCON/shell |
| POST `/management/releases/promote` | OWNER + step-up | Только заранее validated target/sequence/platform; не uploading signing keys |

OWNER bootstrap/transfer и Minecraft UUID transfer recovery остаются local operator commands; отсутствуют обычные `/role=OWNER` или `/self/approve` endpoints. Admin server start/host restore требует operator runbook; mod может обрабатывать лишь описанные фиксированные commands.

## Mutation preconditions и retries

Resource writes требуют `If-Match: "version"`; create не имеет existing version. Sensitive create/decision/command использует `Idempotency-Key` UUID. Scope key=(authenticated principal,route,key), хранение 24 часа. Fingerprint включает method,route,target,validated body,precondition; отличается →409. Same key и request возвращает prior outcome/resource reference после повторной текущей auth/scope проверки, без повторного domain write. Retry ранее успешного решения не упирается в новый version: key lookup выполняется до ordinary optimistic precondition внутри transaction.

Не кешировать secrets/auth response body; stateless retries из expired session запрещены. DELETE/revoke повторяемы по resource version/state, но первоначальный action audit не дублируется. Ошибка network после commit решается повтором с тем же key, не новой заявкой.

## Private routes и версионирование

Private: POST `/boots`, `/admissions`, `/leases/renew`, `/presence`, `/events`, `/deliveries/ack`, `/commands/{id}/result`; GET `/projections/whitelist`, `/deliveries`, `/commands`. Требуют service principal scope/server ID, boot validity и bounded protocol payload. Exact contracts и policies: [MINECRAFT_PROTOCOL.md](MINECRAFT_PROTOCOL.md).

Additive optional response fields допустимы в v1; request unknown fields остаются ошибкой. Обязательные/semantic changes требуют нового API/schema major и compatibility window. Backend предоставляет health/compatibility versions; integration boot с unsupported major DENY. Outbox event envelope `{event_id,type,schema_version,aggregate_id,aggregate_version,occurred_at,payload}`; потребитель rejects unsupported schema в dead-letter, а не применяет частично.

Этап 2 создаёт OpenAPI skeleton и golden fixtures health/compatibility/пустой clan list, не описывает несуществующие routes как реализованные. Новые контракты добавляются вместе с функциональностью. Public OpenAPI не включает operational secrets/diagnostics; raw provider errors не публикуются.
