# Эксплуатационные границы

Архитектура, не готовые Compose/scripts. Dev setup — этап 2, production review/configuration — этап 16. Нельзя заявлять capacity/restore readiness до испытаний.

## Окружения и privileges

Dev/test/staging/prod имеют раздельные DB/credentials/roots/artifacts. Dev API bind localhost; до auth нет publicly reachable CRUD. Test user/provider doubles только test profile; production startup запрещает AUTH_BYPASS/test provider/default secrets.

Linux single-host MVP: Caddy public HTTPS для API/admin; API non-root; PostgreSQL/Valkey private; Minecraft отдельный service/volume; integration listener private HTTPS/VPN/firewall. Artifact hosting отдельно, может сначала быть static server. Admin same origin; direct backend/DB/Valkey порты не публичны. Открытый Minecraft port не открывает integration listener.

Backend runtime DB role: DML разрешённых tables, INSERT/SELECT audit без UPDATE/DELETE, no DDL/superuser. Migrations отдельной operator identity. Outbox worker в том же modular application; можно запустить другой процесс той же codebase без создания нового доменного сервиса. Background processing не зависит от одного ephemeral timer.

Нет Docker socket, host filesystem mounts, shell/RCON string API у backend. Minecraft command processor принимает фиксированные commands; host start/restart/backup/restore оператор выполняет tools/runbook. Container memory/CPU limits, log rotation, graceful stop и достаточный shutdown timeout обязательны; значения hardware-dependent.

## Secrets и keys

Env references/secret mounts/secret manager; только пустые `.env.example`, no real secrets. Отдельные DB credentials, Valkey ACL, service credentials, SMTP, CSRF signing/HMAC privacy keys, MFA AEAD keys, release/TUF signing keys. Root/targets keys вне runtime/CI общего доступа. Initial installer signing material отдельно. DB backup с зашифрованными MFA secrets бесполезен без защищённого key backup, но keys не кладутся рядом в plaintext.

Key rotation имеет key_id и overlap для read старых ciphertext только до re-encryption; service credentials bounded overlap, revoke old. Runbooks для leak и signing compromise до production. Request dumps/HTTP traces не хранят credentials. Actor CLI secret вводится интерактивно, не args/history.

## Health, logs и metrics

`/health/live` отвечает process alive без DB probe. `/health/ready` checks DB/schema и mandatory dependencies: Valkey failure degraded/auth closed, readiness not healthy для нового auth traffic; не автоматически restarting healthy process в бесконечном loop. Public response минимален, private diagnostics содержит dependency error codes без connection strings.

JSON logs: service/version/environment,request_id,event_id,actor ID,action,target ID,result,duration,error code. Redact Authorization/Cookie/Set-Cookie/password/Minecraft token/refresh/CSRF/SMTP key/анкеты. Allowlist serializer, не blanket body logging; provider body не logs. Admin logs — bounded redacted diagnostics из controlled source, не произвольные paths.

Метрики: auth failures/rate limit/hash concurrency, API p95/error rate/DB lock conflicts/pool, outbox pending age/retries, admission deadline/revocation lag, lease expiry/presence stale, projection age, telemetry loss; MC TPS/MSPT/heap/GC/entities/chunks; native launcher idle CPU/RAM/download/verification errors. Default readiness/status UNKNOWN при missing heartbeat; не выдумывать online.

До capacity test numeric performance SLO — кандидаты: API p95<300 ms для ordinary reads, admission network budget3s, revocation≤20s healthy, lease60s outage. TPS/MSPT targets и view/simulation distance назначаются после hardware+pack profiling. Alerts требуют measured sustained threshold, секреты не попадают в уведомления.

## Backup и consistent restore

Backup охватывает PostgreSQL, мир/player data, server/modpack configs, release indexes/trusted metadata, operator config и отдельно encrypted key backups. Off-host encrypted copies; retention предложено daily14 + weekly8, RPO24h/RTO4h как **неутверждённые operational defaults**, не гарантия. До открытия сервера владелец согласует их, staging restore измеряет сроки.

Для MVP coordinated cold backup:

1. Maintenance закрывает new joins/domain writes; сохранить/flush и корректно остановить Minecraft; bounded shutdown, не копировать пишущийся world.
2. Drain outbox/domain workers и зафиксировать control-plane checkpoint. Dump PostgreSQL consistent transaction и snapshot world/configs. Общий backup ID, dump timestamp,schema/app/pack versions,checksums,global epoch; encryption и off-host transfer.
3. Проверить completeness/digests, вернуть services, smoke readiness. Не считать copy завершённым backup до проверки destination.

Restore в **отдельной** среде: остановить traffic/workers, проверить decrypt/hash/version, восстановить DB/world/configs одной согласованной точки, совместимый app/pack, migration compatibility. Restore не является способом автоматически отменить bans.

После restore всегда: global access epoch = новый random UUID, `restored_access_hold=true`; revoke все sessions/challenges/old service credentials, invalidate boot/leases/projections и telemetry spool. До сверки никаких game joins и manager writes. OWNER/operator сверяет текущий off-host audit/security журнал, bans/grants/assignments/identity с восстановленным checkpoint. Если новейшие revocations неизвестны — deny остаётся и требуется явное повторное разрешение, не автоматически whitelist из старого dump. Затем new service creds/boot, full reconciliation, smoke denial/allow/ban и только явный release maintenance/hold.

Audit export off-host снижает потери, но asynchronous export не гарантирует отсутствие RPO gap; restore hold закрывает его по безопасности. Старые one-time challenges не оживают благодаря обязательному revoke. Если MFA key material потеряно — operator recovery, не отключение MFA в production.

Restore acceptance: login reauth, correct UUID/member/ban, spawn/player inventories/world config, connection/online freshness, version compatibility и безопасный отказ при API loss; старый server boot/token/command не replay. Процедура пока не выполнена; автоматизация и timestamps доказательств этапы 15/16.

## Release и rollback

Build/test/lint/secret/dependency checks → immutable signed artifacts → staging migration/pack startup/update → owner approval для production environment → promotion. CI tests с этапа 2, полноценное packaging позже; production keys недоступны PR jobs. Runtime migration не производится каждым API container автоматически.

Application rollback возможен только пока schema обратно совместима. Expand/contract migrations, destructive DDL после separate backup/staging check. Minecraft/world rollback не делать обычной frontend кнопкой: версия world/mods может быть несовместима. Modpack rollback — новый signed sequence, maintenance и world compatibility check. Security signoff повторить после изменения ключей/identity/admission.
