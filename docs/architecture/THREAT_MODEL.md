# Threat model и проверочный план

Этап 1: design review, **не проведённый security audit implementation**. Assets: OWNER permissions, sessions/identity, clan history, whitelist/bans, release signing keys, player world/data, audit integrity. Actors: anonymous attacker, modified launcher/player, assigned manager, compromised CDN, stolen user/service credentials, network failure, trusted operator/host.

Trusted computing base: backend/DB implementation, official online authentication, native OS/keyring, server mod/host, offline signing keys и initial installer provenance. Compromised host/native same-user malware/root signing threshold не исправляется одним permission guard. Не заявлять непробиваемость launcher или anti-cheat до реализации.

| ID / угроза | Мера | Будущий тест / этап |
| --- | --- | --- |
| T01 OWNER через register/patch/mass assignment | Allowlisted DTO, isolated bootstrap, singleton constraint | Попытка role=OWNER/нескольких OWNER, operator races;4/14 |
| T02 Manager чужого clan / IDOR | Current assignment и scoped query/transaction check | Route/body clan/user substitution;5/6 |
| T03 Self-approval / rights escalation | Actor≠applicant, assignment changes only OWNER | Self review, can_remove forge, remove manager;5/14 |
| T04 Concurrent approval/ban/revoke | Locks, constraints, fresh policy, epoch/outbox | Barrier-controlled race tests в real PostgreSQL;3/5/10 |
| T05 Session theft/reuse/revocation gap | Opaque hashes,current session/epoch,strict refresh family reuse detection | Expired/revoked/parallel/lost-response refresh;4 |
| T06 Brute force/account enumeration | Valkey/IP+account budgets,dummy hash,generic response,capacity bound | Storage down,restart,spoof forwarded IP,hash exhaustion;4 |
| T07 CSRF/XSS/admin credential leak | Origin+CSRF,HttpOnly,CSP,sanitized Markdown/media,native minimal capabilities | Cross-origin unsafe requests,raw HTML/SVG/URL payloads;6/7/14 |
| T08 UUID/nickname spoof or profile linking takeover | Official ownership proof,one-use session-bound challenge,online-mode GameProfile | Forged UUID/expired token/unapproved provider/UUID conflict;4/7/10 |
| T09 Alternate client skips access / native stale whitelist | Mandatory fresh mod admission before world,boot health guard | Direct connect,remove guard,late login hook,stale native entry;10 |
| T10 Backend loss opens access / stale leases | No positive join cache,deadline,60-second lease,renewal | DB/API failures,slow tick/clock rollback,mid-join ban;10/13 |
| T11 Service token gives OWNER / fake rewards | Private server scope,fixed capabilities,no role writes,telemetry separated | Service on admin API,wrong server/boot,forged event fields;10/14 |
| T12 Replay/order/crash delivery | IDs/dedupe/version,current policy fetch,full snapshots,boot nonce | Duplicate/out-of-order events,crash-before-ACK,retention gap;10 |
| T13 CDN/metadata rollback/freeze/compromised online key | TUF roles/version/expiry/pinned root,separate offline targets | Tampered target,expired snapshot,older metadata,root rotation;7/14 |
| T14 Path traversal/junction/ADS/cache poison | Native handle containment,portable paths,owned journal,full hashes | `..`,UNC,reserved names,case collision,reparse/TOCTOU,partial download;7 |
| T15 Repair/reinstall destroys userdata / mixed update | Managed catalog,instance lock,rollback journal,launch dirty denial | Crash at each apply boundary,game running,untracked jar;7 |
| T16 Secrets leak via logs/CI/argv | Allowlist redaction,secret mounts,isolated keys,no secrets to UI | Canary secret assertions в HTTP/native logs,PR key access;2/4/7/14 |
| T17 Restore resurrects bans/grants/challenges | Maintenance hold,new epoch,revoke sessions/challenges/creds,manual current reconciliation | Old DB restore with newer revocation,boot/token replay;15/16 |
| T18 Dupes/mod mismatch/tick overload | Exact mod matrix,clean startup,gameplay exploit/profiling | Client-only jar,dependency failure,chunk load/entity/dupe cases;8–13 |
| T19 SQL injection/arbitrary commands/SSRF | Parameterized SQL,typed commands,URL allowlist,no remote logo fetch | SQL sort/filter payloads,shell command fields,internal URL;3/6/10/14 |
| T20 Audit bypass/post-commit-only record | Same transaction audit/outbox,append-only DB role | Audit failure rolls back domain;denied attempts logs;5/14 |

## Design review conclusions

- Нельзя доверять JWT role или launcher access status: выбран opaque+current DB policy.
- Native whitelist projection не является admission authority; zero-window atomic ban/join не обещается. Healthy20s/outage60s — измеряемые цели, не уже выполненные гарантии.
- Strict refresh reuse сознательно требует re-login при lost response; прозрачный retry secret не предусмотрен.
- Archive и manager revocation имеют атомарные effects; owner grants видимы как исключения.
- Полный restore без reconciliation может вернуть права: введён mandatory access/management hold вне обычного player workflow.
- TUF защищает retrieval, не safe filesystem installation и initial installer provenance; для них отдельные меры.

## Проверки, которые нельзя заменить mocks

Real PostgreSQL concurrency/constraints/migration upgrade; native Windows paths/junctions/install crash; actual permitted Microsoft/Minecraft login/profile; real selected-loader login gate/direct connect; API/DB outage while playing; world+DB restore. Mocks полезны для deterministic contracts, но не доказывают перечисленные свойства.

Review stage 1 не обнаружил причины менять концепцию, но выявленные feasibility gates обязательны: provider approval, early loader admission hook, maintained TUF client, toolchain build и actual restore. Эти риски зарегистрированы в PROJECT_STATE; security audit этапа 14 не завершён.
