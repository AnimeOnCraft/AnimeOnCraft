# Minecraft ↔ Backend protocol v1

Актуализация 2026-10-15, D-0057: владелец подтвердил смешанный состав друзей, включая игроков без купленной Minecraft Java. Текущий план — [вход через AnimeOnCraft](FRIENDS_GAME_LOGIN.md). Ниже сохранён прежний online-only протокол; его требования Microsoft binding/online-mode не применять как новое продуктовое условие. Работающий код ещё использует прежние контракты; согласованная замена относится к этапу 10, готовой серверной авторизации пока нет.

Loader adapter появится после выбора версии на этапе 8 и реализуется на 10. JDK 21 / `--release 17` core stage 2 содержит transport/state logic без вымышленных loader hooks.

## Доверие и admission

Production online-mode=true, vanilla whitelist=true, enforce-whitelist=true. Никакого публичного offline backend или proxy forwarding без отдельного review. UUID получается из vanilla authenticated GameProfile; service request не принимает client role/clan/access flags как proof. Compromised server/mod или украденный service secret может подделать telemetry/UUID claim в своей области — это остаточный риск host trust, не оправдание выдавать global permissions.

Boot registration: authenticated server ID, новый random boot_id, build/protocol major, current config/runtime, maintenance flag. Только один active boot на server. Replacement требует prior boot expiry ≥30 секунд без heartbeat или операторского replacement, не бесконтрольного last-writer-wins. Новый boot закрывает старые presence/connections/leases; старые packets reject. Server credential не даёт user access.

На каждой новой connection mod после vanilla authentication до входа в мир вызывает POST admissions: `{server_id,boot_id,connection_id,request_nonce,minecraft_uuid,protocol_major}`. Backend блокирует user для consistent policy read и проверяет fresh DB state. Ответ: `{decision_id,allow,reason,access_epoch,global_epoch,clan_summary,issued_at,expires_at,lease_expires_at}` привязан к этому boot/connection/nonce и TLS response. Denial codes USER_INACTIVE/IDENTITY_REQUIRED/NOT_WHITELISTED/BANNED/MAINTENANCE/DEPENDENCY_UNAVAILABLE.

Admission deadline **3 секунды**, decision usable **5 секунд**, connected lease максимум **60 секунд** от backend issuance. Backend сохраняет decision/connection binding для корреляции JOIN и lease; renew неизвестной connection отвергается. Positive admission result не переиспользуется другой connection и не кешируется для новых joins. После задержки commit-to-join или истечения decision — новая authorization. Применение response на правильном connection проверяет nonce/epoch, закрывает obsolete asynchronous completion. Fast deny cache ≤30 секунд допустим, положительный cache для join запрещён.

HTTP/DB никогда не выполняется синхронно на tick thread: bounded executor 4 in-flight/queue 32 initial, future→loader dispatch. Overflow/timeout отказывает конкретному join и оставляет диагностику. Hook, позволяющий отложить login до world admission и не допустить player spawn до проверки, — обязательный loader feasibility test. Если такого поведения нельзя обеспечить на выбранном loader, adapter не считается безопасным и release блокируется.

## Vanilla whitelist projection

Whitelisted profiles отражают текущий effective access, не фиксированный список clan names. Snapshot через Repeatable Read, server/global epoch,revision,snapshot_id,generated_at,profiles(UUID/current nickname). Native whitelist обновляется атомарно без RCON строки от клиента. Полный reconcile при boot и каждые **60 секунд**, deliveries poll каждые **5 секунд**.

Native whitelist и fresh mod admission работают вместе. Отставший snapshot может временно отказать новому разрешённому игроку; launcher показывает `sync pending` и retry, не «вход точно готов». Если adapter позволяет безопасно refresh профиль перед vanilla whitelist check, это оптимизация с отдельным тестом. Stale native entry никогда не заменяет fresh admission. Manual vanilla edits/server OP не создают backend membership/access; mod gate проверяет OP игроков тоже.

Plugin отсутствует/disabled → admission gate не считается исправной. Distribution start check/health smoke и операторский launch guard обязательны; vanilla whitelist одна не обеспечивает эту архитектуру. Production не запускается с integration health missing. Администратор хоста технически может удалить guard — host compromise вне гарантии API.

## Revocation и backend outage

Connected leases renew каждые **15 секунд** отдельным bounded batch: connection IDs,UUID,boot,current epochs. Backend пересчитывает access; deny → disconnect, epoch/clan change → обновить metadata. Пользовательская session не нужна Minecraft service для renew. Изменения access доставляются через outbox, poll 5 секунд; при получении устаревшего event с меньшей version не восстанавливать права, fetch current policy.

| Состояние | Новые joins | Уже подключённые |
| --- | --- | --- |
| API/DB healthy | Fresh authorize | Renew leases; ban/revoke применяется после current decision |
| API timeout/DB failure | Отказ с retriable reason | До последнего lease deadline; затем disconnect, никакого бесконечного grace |
| Service credential revoked / unsupported protocol | Отказ | Immediate gate closure/disconnect при reject; как минимум lease expiry |
| Mod/server restart | Нет cached allow | Новый boot, authenticated reconnect + fresh decision |
| Epoch changed / restore | Отказ до reconciliation | Старые leases invalid; reconnect policy после maintenance |

Цель healthy revocation **≤20 секунд**: poll/renew interval + 3-second request + scheduled application; end-to-end измеряется на этапах 10/13. Outage продолжение не дольше **60 секунд после последнего разрешённого renew**, кроме задержки frozen tick/host. Tick stall не позволяет обещать real-time deadline; это fault с alert, не «тест пройден». Monotonic local deadline рассчитывается консервативно от request start и server lease duration; rollback wall clock не продлевает lease. Server clock skew >30 секунд — diagnostic/gate denial.

Одновременный ban после admission read может кратко допустить connection до следующего revoke: нет distributed atomic join+DB transaction. Deadline/lease/deny checks ограничивают окно; replay response с другой connection не разрешён. Если нужна zero-window бан гарантия, потребуется иной protocol и соответствующее продуктовое требование.

## Delivery/reconciliation

GET deliveries получает unacked events конкретного server с event_id/schema/aggregate/version. ACK после apply/current-policy fetch, не до него. Crash → replay; consumer dedupe/version guard. Это не sequence commit cursor. Retention gap/epoch mismatch → full snapshot, stale changes не применяются. Outbox payload минимален; snapshot/admission authoritative. Consumer failure retry 1/2/4/...до30 секунд+jitter, bounded; auth failures не infinite retry; no auto-allow.

## Онлайн, статистика и moderation

Clan ID/code/name/role — backend-derived display/permission metadata с current version. Manager clan role не выдаёт vanilla OP, command bypass, world edit или global admin. Gameplay permission bridge к выбранному mod API появится после исследования; разрешения scoped clan/territory actions не являются backend membership authority.

Heartbeat/presence каждые **10 секунд**, server OFFLINE/stale после **30 секунд**. Presence содержит boot,monotonic sequence и complete current connection list; limits/batch count bounds. Out-of-order boot/sequence не принимается. `online` показывается с updated_at/stale; backend unavailable → UNKNOWN, не fake 0/ONLINE.

JOIN/LEAVE/MODERATION event ID unique per server/boot. JOIN содержит decision_id/connection nonce и совпадает с сохранённой allowed admission для того же UUID/boot; user_id вычисляется backend. HTTP retry сохраняет event ID; ACK после DB durable insert. Duplicate event не начисляет playtime второй раз. Payload allowlist: no arbitrary user/role/admin command or reward. Service events не являются правом автоматически банить user; moderation recommendation сохраняется для OWNER, либо future explicitly scoped policy.

Playtime backend считает по accepted live connection intervals и heartbeat cursors: только ACTIVE admitted connections, подтверждённый интервал clipped ≤30 секунд/heartbeat; gap не начисляется. Persisted last accounted cursor + event dedupe защищают restart/replay. Totals могут быть неполными при network loss и помечаются quality/freshness; client не заявляет точное число из собственного счётчика. Mod counters (kills/deaths и другие) отдельно bounded/trusted-host reported, без влияния на access/economy.

Durable local telemetry spool в ограниченном каталоге ≤64 MiB, без tokens. При overflow сначала терять optional stats с loss metric; latest presence заменяет предыдущую, а не блокирует tick. Access не зависит от успешного stats upload. Clear/restore spool с новым epoch/boot не replay в другой мир.

## Команды OWNER

В MVP фиксированные MAINTENANCE_SET, WORLD_SAVE, WHITELIST_RECONCILE, STOP_GRACEFUL. Command привязана server/boot,TTL≤60 секунд, version/request ID,audited actor. Processor dedupe persistent command IDs; effects идемпотентны, результат отправляется после apply. Повтор после crash не запускает shell и не перезапускает старый STOP на новом boot. Server start/host restart/restore — local operator tooling, backend не получает Docker socket. ANNOUNCE/произвольные команды не добавляются без отдельной схемы.
