# Authentication и security boundaries

Specification этапа1. Development auth4 и scoped Clan authorization5 реализованы ([AUTHENTICATION.md](../operations/AUTHENTICATION.md), [CLANS.md](../operations/CLANS.md)). Verify email требует token+registration password. SMTP проверен на local TLS server; real provider/inbox/positive HTTPS admin/native auth/emergency recovery остаются gates. Admission —10; ниже целевые требования, не production readiness.

## AnimeOnCraft account

Native implementation checkpoint7B: [LAUNCHER_NATIVE_PLAN.md](LAUNCHER_NATIVE_PLAN.md) уточняет actual API/IPC/session lifecycle, PENDING credential перед strict refresh, generation/cancel/logout и acceptance7C. Windows feasibility probe не заменяет Rust/backend auth integration; tokens пока не используются действующим launcher.

Registration создаёт PLAYER/ACTIVE без email verification/member/grant. Email challenge single use/30 минут. Password 15–128 Unicode code points, ≤1024 UTF-8 bytes, без усечения; разрешены paste/password managers. Argon2id PHC string с library-generated salt; начальный cost 64 MiB/iterations 3/parallelism 1. Capacity измерить до release; не снижать ниже актуальных recommendations. Successful login rehash после изменения cost.

Generic login/register/recovery responses не раскрывают account existence. Wrong email проходит dummy hash того же cost, expensive hashing имеет concurrency limit. GAME ban позволяет войти в launcher и видеть status/appeal; ACCOUNT ban блокирует login/API и отзывает sessions.

## Access/refresh и отзыв

МVP использует opaque tokens, не JWT: независимые CSPRNG 256-bit secrets, type prefixes; DB хранит SHA-256 digest, не plaintext. Быстрый hash подходит для случайного высокоэнтропийного token, не password. Access TTL **10 минут**; каждый request проверяет token expiry/revoked, parent session, auth_epoch, current user state/ACCOUNT ban. Permissions берутся из текущих DB assignments. Game access — другая policy.

| Session | Absolute | Idle от refresh | Дополнительные условия |
| --- | --- | --- | --- |
| LAUNCHER | 30 дней | 7 дней | Refresh в OS store, native single-flight |
| WEB_ADMIN | 12 часов | 30 минут | MFA для OWNER/manager, step-up ≤10 минут для sensitive writes |

Rotation под user/session lock: проверить current refresh и expiry; consumed old + new refresh/access + updated idle expiry одной transaction. Used hash остаётся до absolute expiry. Повтор consumed token отзывает всю family/access, не unrelated sessions. Новый secret нельзя получить через idempotency key/cache; plaintext для grace не хранится.

Строгая reuse detection означает re-login при lost refresh response/двух refresh. Native single-flight и web cross-tab coordination предотвращают штатную гонку; used refresh не retry. Logout/revoke/password reset закрывает family немедленно, не ждёт access TTL. На privileged mutation session/permissions проверяются снова внутри domain transaction.

## Web и native transport

Admin/API same origin `/admin/` и `/api/v1/`. Access/refresh Secure+HttpOnly+SameSite=Strict host-only cookies, `__Host-` prefix, Path=/; token body не возвращается JS. Все unsafe browser methods, включая login/refresh/logout, требуют exact Origin allowlist и synchronizer CSRF header. До login token привязан к short-lived signed pre-session cookie, после — к session. Cookie-auth без Origin отвергается. GET не меняет данные/refresh. Logout очищает cookies и при expired access.

Native HTTP/auth из Rust: access только memory, refresh Windows Credential Manager. React получает domain DTO/events, не raw tokens. Нет secrets в localStorage/config или plaintext fallback. Keyring failure = remembered-login unavailable/re-login. На новых ОС отдельный adapter. Только bundled UI получает минимальные capabilities; remote HTML/native commands запрещены. Rust validation нужна дополнительно к Tauri capability scopes.

CORS exact origins, не wildcard credentials; CORS не authorization. Cookie/web sessions и native bearer sessions имеют разные client kinds/transport и не взаимозаменяемы. Proxy доверяет X-Forwarded-For только от заданных private hops.

## OWNER, managers и MFA

Не больше одного OWNER. Bootstrap — local operator CLI, existing verified ACTIVE user, exclusive governance lock, interactive secret input и MFA proof, audit. Нет FIRST_USER_IS_OWNER/default credentials/role patch. CLI выбирает OWNER только если он отсутствует; transfer/recovery отдельная локальная процедура с отзывом sessions и audit, никогда web route.

Для OWNER/CLAN_MANAGER web admin нужен TOTP через проверенную library. Secret encrypted AEAD с key ID вне DB; recovery code hashes single use. Atomic last_used_step предотвращает TOTP replay. Pending login challenge ≤5 минут не даёт полноценной session. Dangerous writes (assignments/archive/bans/grants/settings/identity transfer/server commands) требуют password+MFA step-up ≤10 минут.

OWNER может назначить manager заранее, но management API до MFA enrollment возвращает MFA_REQUIRED. Assignment/capability changes increment auth_epoch и revoke affected sessions; manager не меняет собственную роль. Password/MFA change также отзывает sessions. Player profile не может менять system_role/auth_epoch.

Reset: generic 202, 256-bit challenge hash, TTL 15 минут, single use; fixed configured HTTPS link origin, не Host header. Reset отзывает sessions/outstanding challenges, не выдаёт login и не сбрасывает MFA. Email change в MVP только audited recovery. OWNER MFA loss: offline recovery codes либо local operator break-glass/отдельный offline recovery material, exclusive lock, revoke sessions, pending re-enrollment, audit+уведомление старому email. SMTP account не может назначить OWNER.

## Rate limits и отказ storage

Valkey 8.1 atomic counters/expiry, private ACL, AOF persistence. Initial budgets: login IP 30/15 минут + account-key 10/15 минут; register IP 5/час; recovery IP 5/час + email-key 3/час; MFA 5/challenge; binding 5/user/час; authenticated API 120/min/user плюс bounds маршрутов. Email-key HMAC для privacy. Bounded penalties вместо permanent lockout; tune этап 4.

Storage недоступен → auth/challenge endpoints 503, не unlimited fallback. Existing session reads и integration admission/renewal при healthy DB могут работать с process concurrency и proxy coarse limits. Valkey не источник session/permissions/access, restart не делает запрещённый доступ разрешённым.

## Minecraft identity

Server `online-mode=true`, без offline fallback. Microsoft login в external system browser, собственный registered public-client ID; PKCE/state для code flow либо разрешённый device code. Нет embedded password form/client_secret/заимствованного client ID. Microsoft success ещё не подтверждает Minecraft Java ownership/profile.

Binding workflow:

1. Verified AnimeOnCraft user + recent password reauth получает one-time user/session-bound challenge ≤5 минут.
2. Native отправляет challenge и короткоживущий **Minecraft access token** по HTTPS с body tracing отключённым. Microsoft password/refresh не отправляются.
3. Backend обращается только к фиксированным official service origins, получает profile/ownership server-side. Client UUID/nickname не являются proof. Token/provider responses не хранятся в DB/outbox/logs, только request memory.
4. Внешний network request вне transaction; proof result ≤60 секунд. Transaction перечитывает active session/challenge, блокирует user/account/UUID conflict, consumes challenge, сохраняет VERIFIED UUID+audit+epoch/outbox.
5. UUID другого user → conflict, не takeover. Public revoke закрывает game access; reverify прежним user. Transfer — OWNER audited recovery с fresh proof, locks обоих users, revoke sessions/access, link history.

Поведение profile/entitlement шагов наблюдалось в исходниках поддерживаемого launcher; это не официальный grant разрешений AnimeOnCraft. Endpoint paths/response schemas/provider approval/SDK нужно проверить на 4/7. Если собственный client ID не получает разрешённый доступ, production launch остаётся blocked; offline-mode не обход.

Minecraft join UUID приходит из vanilla online-authenticated GameProfile, а не custom packet. Binding proof не заменяет authentication каждого соединения. Microsoft/Minecraft refresh хранит native keyring; backend его не получает.

## Service auth и контент

Service credentials — CSPRNG 256-bit, DB digest, server scope и fixed capabilities `admission.read`, `projection.read`, `telemetry.write`, `commands.consume`, expiry 90 дней; operator rotation/revocation. Private HTTPS/VPN + secret file mount, mTLS можно добавить позднее. User sessions не принимаются private listener; service bearer не принимается public/admin routes.

News Markdown без raw HTML/dangerous links. Logos decoded/re-encoded PNG/JPEG/WebP ≤2 MiB/1024×1024, header dimensions проверяются до full decode, pixel/allocation budget ограничен; без SVG. Discord URL только HTTPS discord.gg/... или discord.com/invite/... без credentials. Backend не fetch произвольный logo URL/path. DTO reject unknown privileged fields, parameterized SQL, bounds request/body.

Основания: [OWASP authentication](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html), [sessions](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html), [CSRF](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html), [native OAuth](https://datatracker.ietf.org/doc/html/rfc8252), [Microsoft public clients](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow), [Windows Credential Manager](https://learn.microsoft.com/en-us/windows/win32/secauthn/credentials-management). TTL/budgets/workflows — решения проекта, не defaults источников.
