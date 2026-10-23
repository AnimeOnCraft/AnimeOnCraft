# Данные и транзакции

Целевая спецификация0.1.0-architecture. Текущий development backend0.6.0-admin-dev:26 models/10 migrations, auth + atomic clan workflows/idempotency_keys/individual assignment capability/audit/outbox source ([CLANS](../operations/CLANS.md), [AUTH](../operations/AUTHENTICATION.md)). Stage3 baseline14 mandatory+6 support. Admission/delivery tables/workers — впереди10; schema/business policy не означает игровой admission.

## Общие правила

PK — backend-generated UUID; Minecraft UUID — отдельное поле `uuid`. Dates — `timestamptz`/UTC; expiry по DB/backend clock. Counters и version — `bigint`, JSON decimal strings. Mutable aggregates имеют optimistic `version`, writes требуют expected version. Foreign/unique/CHECK constraints обязательны независимо от ORM.

Email key: trim + Unicode normalization + lower-case как documented product policy; SMTP dots/plus не удалять. Nickname — изменяемое display field, не identity. CLAN_MANAGER вычисляется по assignments, не хранится как глобальная роль. Исторические данные не удаляются cascade.

## Обязательные сущности

| Таблица | Существенные поля | Constraints / indexes |
| --- | --- | --- |
| `users` | id,email,email_key,email_verified_at,password_hash,system_role OWNER/PLAYER,state ACTIVE/SUSPENDED,auth_epoch,access_epoch,version,created_at | UNIQUE email_key; partial UNIQUE system_role WHERE OWNER; unverified email не даёт game/admin access |
| `minecraft_accounts` | id,user_id,minecraft_uuid,nickname,state VERIFIED/REVOKED,verified_at,revoked_at,version | UNIQUE minecraft_uuid навсегда; partial UNIQUE user_id WHERE VERIFIED; timestamps CHECK; RESTRICT user delete |
| `sessions` | id,user_id,client_kind LAUNCHER/WEB_ADMIN,created_at,absolute_expires_at,idle_expires_at,revoked_at,auth_epoch,mfa_verified_at,last_step_up_at | Index user/revoked; parent token family; без plaintext secrets |
| `clans` | id,name,short_code,code_key,description,logo_media_id,discord_url,enabled,archived_at,version,timestamps | UNIQUE code_key даже после archive; code 2–12 ASCII alnum, name 2–64, description ≤4000 |
| `clan_members` | id,clan_id,user_id,joined_at,ended_at,end_reason,accepted_application_id,version | partial UNIQUE user WHERE ended_at NULL; UNIQUE accepted_application_id; UNIQUE(id,user_id); FK user/clan/application |
| `clan_roles` | id,clan_id,code MANAGER,label,can_remove_members,version | UNIQUE(clan_id,code), UNIQUE(id,clan_id); никакого OWNER или произвольного permissions JSON |
| `clan_applications` | id,user_id,clan_id,message,status PENDING/ACCEPTED/REJECTED/WITHDRAWN/CANCELLED,decided_by,decided_at,reason,version,created_at | partial UNIQUE user WHERE PENDING; UNIQUE(id,user_id,clan_id); index(clan,status,created,id); status/timestamps CHECK; message 0–4000 |
| `whitelist` | id,user_id,kind MEMBERSHIP/OWNER,membership_id,granted_by,reason,expires_at,revoked_at,version | MEMBERSHIP iff membership_id set; composite FK(membership_id,user_id)→clan_members(id,user_id); partial UNIQUE membership_id not revoked; partial UNIQUE user not-revoked OWNER grant |
| `bans` | id,user_id,scope GAME/ACCOUNT,reason_private,message_public,issued_by,expires_at,revoked_at,version | Index(user,revoked,expires); active = not revoked and not expired; overlapping bans разрешены, каждый снимается явно |
| `news` | id,slug,title,body_markdown,published_at,archived_at,author_id,version | UNIQUE slug; raw HTML запрещён; title ≤160/body ≤20000; drafts не выдаются игрокам |
| `launcher_versions` | id,channel,platform,version_label,release_sequence,min_api_major,artifact_target,sha256,size,published_at,revoked_at | UNIQUE(channel,platform,release_sequence); published immutable; metadata не даёт signing privilege |
| `server_events` | event_id,server_id,boot_id,kind,observed_at,received_at,schema_version,payload,processing_status | UNIQUE(server,boot,event_id); bounded allowlisted payload; dead-letter не теряется молча |
| `player_statistics` | user_id,server_id,playtime_seconds,last_seen_at,version,updated_at | PK(user,server); nonnegative CHECK; verified UUID binding; reported counters отделены от backend playtime |
| `audit_logs` | id,actor_user_id/actor_service_id,actor_kind,action,target_type/id,before_redacted,after_redacted,reason,request_id,time | Append-only runtime privilege; indexes(actor,time)/(target,time); CLI/system attribution; без tokens/passwords/полных анкет |

Для accepted_application composite FK(id,user_id,clan_id) гарантирует тот же user/clan; NULL разрешён при direct OWNER admission. Каждый expiration имеет CHECK > creation/issuance time, но time-dependent partial indexes не используются. Создание нового OWNER grant после expired старого сначала помечает старый revoked в той же transaction, сохраняя историю и uniqueness.

## Дополнительные сущности

| Таблица | Назначение / обязательные constraints |
| --- | --- |
| `clan_manager_assignments` | user/clan/role,can_remove_members,ended_at,version; composite FK(role_id,clan_id); partial UNIQUE(user,clan) active. Capability индивидуальна с migration9, прежний role flag — legacy metadata. Несколько назначенных clans допустимы; membership всё равно один |
| `application_history` | Application transitions,actor,reason,time; append-only, включая initial PENDING |
| `access_tokens` | token_hash UNIQUE,session_id,expires/revoked; opaque secrets только в виде hashes |
| `refresh_tokens` | token_hash UNIQUE,session,parent_id UNIQUE nullable,created/expires/consumed/revoked; partial UNIQUE session WHERE not consumed/not revoked; used hashes до family absolute expiry |
| `auth_challenges` | purpose VERIFY_EMAIL/RESET_PASSWORD/MINECRAFT_LINK/MFA_LOGIN,token_hash UNIQUE,user/session binding,expiry,consumed; challenge не session |
| `mfa_credentials`, `mfa_recovery_codes` | Encrypted TOTP secret/key_id + atomic last_used_step; recovery code hash/used_at |
| `minecraft_link_history` | UUID,old/new user,state,actor,proof method,time; сохраняется при identity recovery |
| `service_principals`, `service_credentials` | Server scope,fixed capabilities,credential_hash,expiry/revoked; никогда user role |
| `game_servers`, `server_boots` | Server ID,current boot,protocol/version,heartbeat,maintenance,projection epoch |
| `player_connections` | server,boot,connection_id,UUID/user,joined,last_seen,closed,playtime cursor; UNIQUE(server,boot,connection) |
| `admission_decisions` | decision ID,server/boot/connection/nonce,UUID/user,allow/reason,epochs,issued/expiry; связь JOIN с разрешённой connection, short bounded retention и audit counters |
| `projection_snapshots` | server/snapshot ID,revision,global epoch,generated/expiry,immutable paged payload; все страницы из одного consistent read |
| `outbox_events`, `outbox_deliveries` | Event/aggregate/version/schema,payload; per-consumer pending/attempt/retry/ack; UNIQUE(event,consumer) |
| `idempotency_keys` | principal,route,key UNIQUE,request digest,result resource/status,expiry; без token/secret response bodies |
| `modpack_versions`, `release_channels` | Immutable pack target/hash/sequence; active server compatibility set; только проверенные targets |
| `media_assets` | Controlled object key,format,digest,size,dimensions/status; decoded/re-encoded raster |
| `server_commands` | ID,server,type,allowlisted payload,status,actor,expiry/result; без arbitrary shell/RCON |
| `system_settings` | Typed versioned settings,global access epoch как random UUID,restore hold; не secret store |

## Переходы состояний

| Aggregate | Переходы | Условия |
| --- | --- | --- |
| Application | create→PENDING; PENDING→ACCEPTED/REJECTED/WITHDRAWN/CANCELLED | Terminal не открывается снова; withdraw applicant; cancel archive/suspension; reviewer != applicant |
| Membership | absent→ACTIVE; ACTIVE→ENDED (leave/remove/archive) | User active,clan enabled/not archived,no other active membership |
| Manager | absent→ACTIVE→ENDED | Только OWNER или archive; scope нельзя patch-подменить |
| Grant | absent→ACTIVE→REVOKED/expired | Membership FK; OWNER exception требует reason |
| Ban | issued→ACTIVE→REVOKED/expired | GAME закрывает игру; ACCOUNT отзывает sessions; member history остаётся |
| Clan | enabled↔disabled; active→ARCHIVED; archived→disabled | Disable сохраняет existing rights; archive завершает их; unarchive не восстанавливает старые rows |

Expired grants/bans проверяются при каждом policy read: correctness не зависит от cron. Clan aggregate.version увеличивается также при transitions members/assignments/applications, чтобы archive preview и его подтверждённая version не игнорировали новых участников. Retention/privacy определяются до production; cleanup не стирает membership/audit history ради кнопки delete.

## Locking и consistency

Domain writes: Read Committed + explicit row locks. Shared advisory lock `owner-governance` для mutations, exclusive для owner bootstrap/recovery. Затем clans по UUID, users actor/targets по UUID, session/resource rows по UUID. Операция без clan пропускает слой и не захватывает clan позже. Archive собирает affected user IDs под clan lock. Все writers соблюдают одинаковый порядок.

После locks повторно проверить actor/session/role/MFA, user ban/state, target version. Guard до transaction недостаточен. Session/assignment/ban changes lock affected users, поэтому уже ожидающая write не использует отозванное право. OWNER governance changes получают exclusive lock. До 3 bounded retries с jitter для deadlock/serialization failures, только идемпотентной command; UNIQUE conflict не маскировать success.

Начальный transaction budget 5 секунд; внешние HTTP/SMTP/Discord вне locks. Большой archive, не укладывающийся в budget, полностью rollback; перед ростом требуется maintenance workflow, не частичный archive.

### Approval одной transaction

1. Определить actor из session и digest/idempotency scope, не payload role.
2. Lock governance/clan/users/application/session; перечитать permissions, clan state, applicant state/ban, application/version.
3. Отвергнуть self-approval, stale version, terminal application, existing member или disabled/archived clan.
4. Записать ACCEPTED/history + membership + MEMBERSHIP grant + access_epoch + audit + outbox events + result reference.
5. После commit вернуть domain success, sync status отдельно. Недоступный Minecraft не откатывает commit и не означает синхронизацию.

Ban/approval lock одного user: ban committed первым блокирует approval; approval committed первым не переживает следующую access policy ban. Network admission race ограничивается lease/revocation protocol, не заявлением о распределённой atomicity.

Другие writes: leave/remove заканчивает member и linked grant; archive заканчивает members/assignments, cancels pending, revokes membership grants, increments epochs и пишет audit/outbox. OWNER grants остаются явными исключениями. Manager assignment независим от membership, обычный leave не расширяет/не снимает его; исключение действующего менеджера требует OWNER. Grant/revoke/ban/unban/identity revoke/suspension — user state+epoch+audit/outbox одной transaction. Suspension отзывает sessions и cancels pending. Manager capability changes increment auth_epoch и revoke его sessions.

## Delivery и migrations

Outbox worker резервирует attempt короткой transaction с SKIP LOCKED; HTTP вне transaction; после result сохраняет ack/retry. Crash между delivery/ack даёт повтор — consumer dedupe обязателен. Не использовать sequence outbox ID как commit-order cursor: commit порядок может отличаться. Integration получает durable per-server deliveries с явным ack и периодический full snapshot. Snapshot — Repeatable Read с epoch/revision и фиксированным snapshot ID для pagination.

Prisma CLI/client одной stable версии; специальные constraints/locks через parameterized SQL. Production без `db push`, auto-sync/destructive reset; migrations применяет отдельный DDL role. Review SQL, clean apply и previous-schema upgrade; rollback через совместимый application rollback/forward repair или согласованный restore, не фиктивный down.sql.

Seed SCAM/NYA/AKUMA/ZXC insert-if-absent по code_key, не затирает OWNER edits/не воскрешает archive. Skeleton seed disabled с явно неполными profile fields; без вымышленных Discord/online, OWNER/default passwords. Test-only fixtures отделены.

Основания: [PostgreSQL locks](https://www.postgresql.org/docs/17/explicit-locking.html), [partial indexes](https://www.postgresql.org/docs/17/indexes-partial.html), [isolation](https://www.postgresql.org/docs/17/transaction-iso.html). Схема/lock order/invariants выше — решения AnimeOnCraft.
