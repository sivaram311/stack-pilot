# AI-DLC Inception Baseline - stack-pilot

**Captured:** 2026-08-04 (as-is snapshot, not a target design)

## Purpose

stack-pilot is a Spring Boot **machine control panel** (service orchestration dashboard) for this host. It starts/stops/restarts registered processes, surfaces nginx and RDP health, host power controls, and a mobile-first OLED Task Manager UI. Public hostnames: **PROD** https://control.delena.buzz · **PREPROD** https://control-staging.delena.buzz. Release label in UI/code is **0.2.0-a (Phase A)**; Maven `pom.xml` remains `0.0.1-SNAPSHOT`.

## Tech stack

| Piece | As stated in repo |
|---|---|
| App / release label | UI + `PlatformSummaryService.VERSION_LABEL` = `0.2.0-a`; README “Release note: 0.2.0-a (Phase A)” |
| Maven coordinates | `com.stackpilot:stack-pilot:0.0.1-SNAPSHOT` (`pom.xml`) |
| Runtime | Java `21` (`pom.xml` `java.version`); release `scripts/start.ps1` prefers Eclipse Adoptium JDK 21 path |
| Framework | Spring Boot `3.3.1` (`spring-boot-starter-parent`) |
| Web | `spring-boot-starter-web` (REST + static UI) |
| Code gen | Lombok (optional) |
| Tests dependency | `spring-boot-starter-test` (test scope); **no** `src/test` tree present |
| UI | Static SPA under `src/main/resources/static/` (`index.html`, `css/style.css`, `js/app.js`) — no Node/`package.json` |
| Build | Maven jar; `mvn spring-boot:run` for local; promote copies jar + `start.ps1` to F:/G: |
| Auth / DB | Optional API-key filter (`stackpilot.auth`); currently `enabled: false`. No application database — process/file oriented |
| Profiles | `dev` (default) and `prod` via `application-dev.yml` / `application-prod.yml` |

## Current features (as-built)

**UI shell** (`src/main/resources/static/index.html` + `app.js`):

- Single-page OLED-dark Task Manager; assets cache-busted as `?v=0.2.0-a`
- Bottom / side nav panels: **Fleet**, **Edge**, **Machine**, **Promote**, **Logs**, **AI-DLC**
- Env strip + drives panel via `GET /api/platform/summary` (Phase A)
- Fleet: managed service list (start/stop/restart/takeover), nginx controls, RDP status/recover
- Machine: drive roles (E/F/G/H) from platform summary; host restart/shutdown/cancel with confirm phrases
- Edge / Promote: stub notes (Phase C / Phase D — not fully built)
- Logs: manager + per-service / nginx log views
- AI-DLC: read-only status panel from `GET /api/aidlc/summary`
- Optional unlock / API-key gate in UI when auth is enabled (currently off)

**REST API** (controllers under `com.stackpilot.manager.controller`):

| Prefix | Endpoints (as-built) |
|---|---|
| `/api/services` | `GET /`, `GET /{name}`, `POST /{name}/start\|stop\|restart\|takeover`, `GET /{name}/logs` |
| `/api/services/bulk` | `POST /stop-all`, `/start-all`, `/restart-all` |
| `/api/manager` | `GET /logs` |
| `/api/infrastructure/nginx` | `GET /status`, `POST /start\|stop\|reload`, `GET /logs/error\|access` |
| `/api/infrastructure/rdp` | `GET /status`, `POST /recover`, `POST /apply-mitigations` |
| `/api/host` | `GET /status`, `POST /restart\|shutdown\|cancel` |
| `/api/auth` | `GET /status` |
| `/api/platform` | `GET /summary` |
| `/api/aidlc` | `GET /summary` |

**Managed services (by profile):**

- **`dev`** (`application-dev.yml`): grok_dev stack — `python-downloader`, `python-order-rsi`, `backend` (:8081), `frontend` (:4200)
- **`prod`** (`application-prod.yml`): `css` (:5900), `agent-portal` (:5080), `h-drive-server` (:5010) under `G:\apps\…`

**Ops / scripts:**

- `scripts/start.ps1` — release launcher (`preprod` → F: `:4091` profile `dev`; `prod` → G: `:5091` profile `prod`)
- `scripts/run-app-foreground.ps1`, `start-stack-pilot.ps1`, `setup-boot-tasks.ps1`
- RDP helpers: `rdp-health-check.ps1`, `rdp-recover-session.ps1`, `rdp-apply-mitigations.ps1`, `rdp-status.ps1`
- `deployment/` — nginx confs + sync/start/stop/reload scripts for `control*.delena.buzz`
- Boot runner: auto-start services / nginx / RDP mitigations (prod on; preprod start.ps1 forces auto-start off)

**Docs already in-repo:** `docs/implementation_plan.md`, RDP black-screen / session0 docs, `deployment/README.md`, `agents/pre-work/` (Phase A vision).

## Deploy topology (known facts, do not invent anything not given here or found in-repo)

From `E:\MyAgent\workflow\ports\REGISTRY.md`:

- **DEV:** port **3091**, path `E:\Source\stack-pilot`, status **reserved** — note: “Preferred DEV offset (migrate from legacy :8091)”
- **PREPROD:** port **4091**, path `F:\apps\stack-pilot`, status **active** — https://control-staging.delena.buzz → :4091
- **PROD:** port **5091**, path `G:\apps\stack-pilot`, status **active** — https://control.delena.buzz → :5091
- **Legacy:** port **8091**, env dev, status **legacy** — “Stopped 2026-07-11 sole cutover; prod :5091 owns control.delena.buzz”

**Cross-check vs in-repo docs/config:**

- README, `scripts/start.ps1`, `deployment/README.md`, and nginx confs agree on **PREPROD :4091** (`control-staging.delena.buzz`) and **PROD :5091** (`control.delena.buzz` → `127.0.0.1:5091`). Matches registry.
- Local / default Spring port in `application.yml` is **`server.port: 8091`**; README “Local run” documents `mvn spring-boot:run` → **:8091**. Matches registry **legacy** row, not the preferred DEV **:3091**.
- **Discrepancy:** registry reserves DEV **:3091** as preferred offset; **no in-repo reference to 3091** was found (grep across the tree). Source tree still binds/documents **:8091** for local/dev default.
- **Auth:** `stackpilot.auth.enabled: false` in `application.yml`; deployment README states NGINX basic auth and API key are disabled for public access. Registry does not assign a CSS-auth row to stack-pilot (unlike some other apps).
- Preprod uses Spring profile **`dev`** (grok_dev services) on :4091; prod uses profile **`prod`** on :5091 — documented in README and `start.ps1`.

## Known debt / gaps (as-is, factual)

- Maven version `0.0.1-SNAPSHOT` vs shipped UI/release label `0.2.0-a` (README notes pom may remain snapshot).
- Preferred DEV port **3091** registered but unused in-repo; local default remains legacy **8091**.
- Edge and Promote UI panels are stubs (Phase C / Phase D notes in `index.html`); deploy-from-UI explicitly off until approved.
- No `src/test` sources; `spring-boot-starter-test` present but unused in-tree.
- No `TODO`/`FIXME` markers under `src/`; debt is mostly documented as phase stubs and RDP/ops docs.
- `stackpilot.auth.enabled: false` — control plane is publicly reachable via nginx hostnames while API-key / basic-auth paths exist but are off (`deployment/README.md`, commented `auth_basic` in `control.delena.buzz.conf`).
- Default API-key placeholder exists in `application.yml` (overridable via `STACKPILOT_AUTH_API_KEY`); secrets for managed apps are expected in each app’s on-disk `.env`, not YAML.
- `docs/implementation_plan.md` “Current ship” still cites **0.1.2** / older proxy-to-:8091 narrative in places — partially stale relative to README 0.2.0-a and prod :5091.
- `AGENTS.md` is Agent Portal pack metadata only (not project architecture guidance); no `CLAUDE.md`.
- Prior to this baseline, no `docs/aidlc/` tree existed.

## Sources consulted

- `README.md`
- `AGENTS.md` (Agent Portal packs; no CLAUDE.md)
- `pom.xml`
- `src/main/resources/application.yml`
- `src/main/resources/application-dev.yml`
- `src/main/resources/application-prod.yml`
- `src/main/resources/static/index.html`
- `src/main/resources/static/js/app.js` (spot-check for auth/port copy)
- Controllers: `ServiceController`, `ServiceBulkController`, `ManagerLogController`, `NginxController`, `RdpHealthController`, `HostController`, `AuthController`, `PlatformController`, `AiDlcStatusController`
- `src/main/java/com/stackpilot/manager/service/PlatformSummaryService.java` (`VERSION_LABEL`)
- `scripts/start.ps1`
- `deployment/README.md`
- `deployment/conf/control.delena.buzz.conf`
- `deployment/conf/control-staging.delena.buzz.conf`
- `docs/implementation_plan.md` (header / port narrative)
- `git log --oneline` (recent history through AI-DLC panel / platform summary / 0.1.2 ship)
- `E:\MyAgent\workflow\ports\REGISTRY.md` (deploy topology cross-check only)
