# Implementation Plan: StackPilot

`stack-pilot` is a Spring Boot application designed to manage the lifecycle (start, stop, restart) of the services in `grok-dev` invisibly in the background, aggregate their logs, and expose a REST API and control dashboard.

---

## 1. Project Directory Structure

The project is located at `E:\Source\stack-pilot` with the following structure:

```text
stack-pilot/
├── docs/
│   └── implementation_plan.md          # This implementation plan
├── pom.xml                             # Maven configuration
├── src/
│   └── main/
│       ├── java/
│       │   └── com/
│       │       └── stackpilot/
│       │           └── manager/
│       │               ├── StackPilotApplication.java    # Main application entry point
│       │               ├── controller/
│       │               │   └── ServiceController.java    # REST API for managing services
│       │               ├── model/
│       │               │   ├── ServiceStatus.java        # Enum representing service states
│       │               │   └── ServiceInfo.java          # Model representing service metadata
│       │               └── service/
│       │                   ├── ServiceManager.java       # Process runner and coordinator
│       │                   └── LogStreamConsumer.java    # Asynchronous process log reader
│       └── resources/
│           ├── application.yml         # Application configurations (paths, default ports)
│           └── static/
│               ├── index.html          # Web Control Dashboard UI
│               ├── css/
│               │   └── style.css       # UI styling
│               └── js/
│                   └── app.js          # UI interactivity (start/stop/restart/log polling)
```

---

## 2. Configured Services

The manager will manage the following services from `grok-dev`:

| Service Name | Working Directory | Command | Target Port |
| :--- | :--- | :--- | :--- |
| **python-downloader** | `E:\Source\grok_dev\python` | `python run_data_downloader.py` | N/A |
| **python-order-rsi** | `E:\Source\grok_dev\python` | `python run_order_rsi.py` | N/A |
| **backend** | `E:\Source\grok_dev\backend` | `mvn spring-boot:run` | `8081` |
| **frontend** | `E:\Source\grok_dev\frontend` | `npm run start` (or `ng serve`) | `4200` |

**python-order-rsi** publishes live forming-bar RSI(14) for W1→M1 from MT5 into `grok_dev.live_order_rsi`. Configure in `application.yml` under `stackpilot.services.python-order-rsi.environment`:

| Variable | Default | Description |
| :--- | :--- | :--- |
| `ORDER_RSI_MODE` | `tick` | `tick` (push on price change) or `poll` (fixed interval) |
| `ORDER_RSI_TICK_MS` | `250` | MT5 tick check interval when mode is `tick` |
| `ORDER_RSI_POLL_MS` | `1000` | Minimum push interval / poll mode interval |
| `BROKER_SERVER_ZONE` | `UTC` | Broker wall-time zone; match `grok.market.broker-server-zone` in the backend |

Logs: `logs/order-rsi.log`. Requires MT5 terminal logged in (same as the downloader).

StackPilot dashboard: http://localhost:8091/

---

## 3. Technical Core Designs

### A. Invisible Background Execution
Instead of using shell wrappers or launching new interactive command prompt/terminal windows, we will use Java's `ProcessBuilder` API.
* By default, `ProcessBuilder` runs the processes without creating any OS windows.
* We will redirect standard error streams using `redirectErrorStream(true)` or capture stdout and stderr separately in the `LogStreamConsumer`.

### B. Process Tree Termination (Windows Compatibility)
When a process like `mvn spring-boot:run` or `npm run start` is started, the parent process (Maven/Node CLI) launches the actual server as a child process. Killing only the parent process leaves the server running.
* **Solution:** On service stop/restart, the application will invoke:
  ```cmd
  taskkill /F /T /PID <parent-pid>
  ```
  * `/F`: Forcefully terminate the process(es).
  * `/T`: Terminate the specified process and any child processes started by it.

### C. Log Aggregation & Capture
* For each running service, a dedicated thread pool executor will run a `LogStreamConsumer` task.
* This task reads from the process's input stream line-by-line.
* The logs will be:
  1. Printed to the manager's console log.
  2. Written to dynamic service-specific log files inside a `logs/` directory (e.g., `logs/python.log`, `logs/order-rsi.log`, `logs/backend.log`, `logs/frontend.log`).
  3. Kept in a rolling in-memory buffer (e.g., circular FIFO queue of the last 500 lines) so they can be fetched via API.

---

## 4. REST API Endpoint Design

The application will expose the following endpoints:

| Endpoint | Method | Description |
| :--- | :--- | :--- |
| `/api/services` | `GET` | List all services, their configurations, and current status |
| `/api/services/{name}/start` | `POST` | Start a specific service |
| `/api/services/{name}/stop` | `POST` | Stop a specific service |
| `/api/services/{name}/restart` | `POST` | Restart a specific service |
| `/api/services/{name}/logs` | `GET` | Get recent logs for a service (supports query param `tail` for line limit) |
| `/api/infrastructure/nginx/status` | `GET` | NGINX process, config test, health checks |
| `/api/infrastructure/nginx/start` | `POST` | Start NGINX |
| `/api/infrastructure/nginx/stop` | `POST` | Stop NGINX gracefully |
| `/api/infrastructure/nginx/reload` | `POST` | Test config and reload NGINX |
| `/api/infrastructure/nginx/logs/error` | `GET` | Tail NGINX error log |
| `/api/infrastructure/nginx/logs/access` | `GET` | Tail NGINX access log |
| `/api/host/status` | `GET` | Host control config and pending shutdown state |
| `/api/host/restart` | `POST` | Schedule server restart (requires `confirmPhrase` in body) |
| `/api/host/shutdown` | `POST` | Schedule server shutdown (requires `confirmPhrase` in body) |
| `/api/host/cancel` | `POST` | Cancel a pending restart/shutdown |

---

## 5. Web Control Dashboard UI

A lightweight, modern, responsive single-page dashboard will be hosted directly by the manager at `http://localhost:8091/`.
* **Features:**
  * Status indicator cards for each service (running, stopped, starting, error).
  * Quick-action buttons (Start, Stop, Restart) per service.
  * Real-time rolling log terminal viewer that updates dynamically using polling or Server-Sent Events.
  * A button to start all or stop all services at once.

---

## 6. Infrastructure — NGINX (Deployment integration)

Stack Pilot wraps the PowerShell tooling in `E:\Source\Deployment\scripts\` and exposes nginx lifecycle control from the dashboard **Infrastructure** section.

| Action | API | Equivalent script |
| :--- | :--- | :--- |
| Status + health | `GET /api/infrastructure/nginx/status` | `status-nginx.ps1` |
| Start | `POST /api/infrastructure/nginx/start` | `start-nginx.ps1` |
| Stop | `POST /api/infrastructure/nginx/stop` | `stop-nginx.ps1` |
| Reload config | `POST /api/infrastructure/nginx/reload` | `reload-nginx.ps1` |
| Error log tail | `GET /api/infrastructure/nginx/logs/error?tail=200` | `error.log` |
| Access log tail | `GET /api/infrastructure/nginx/logs/access?tail=200` | `access.log` |

Configuration in `application.yml` under `stackpilot.nginx`:

| Key | Default | Description |
| :--- | :--- | :--- |
| `home` | `C:/nginx-1.30.3` | NGINX install directory |
| `port` | `80` | Listener port for status checks |
| `error-log` | `logs/error.log` | Relative to `home` |
| `access-log` | `logs/access.log` | Relative to `home` |
| `health-checks` | localhost, delena.buzz, control.delena.buzz | HTTP probes from status endpoint |

Status includes upstream port checks for frontend (`4200`), backend (`8081`), and Stack Pilot (`8091`).

**Note:** Application service bulk actions (Start All / Stop All) do **not** include nginx — manage it separately in Infrastructure.

---

## 7. Host Controls — Server restart / shutdown

The **Host Controls** section schedules Windows `shutdown` commands with a configurable delay (default **60 seconds**).

| Action | API | Windows command |
| :--- | :--- | :--- |
| Status | `GET /api/host/status` | — |
| Restart server | `POST /api/host/restart` | `shutdown /r /t 60` |
| Shutdown server | `POST /api/host/shutdown` | `shutdown /s /t 60` |
| Cancel pending | `POST /api/host/cancel` | `shutdown /a` |

Request body for restart/shutdown:

```json
{ "confirmPhrase": "RESTART SERVER" }
```

Confirmation phrases are configured in `application.yml` under `stackpilot.host`:

| Key | Default |
| :--- | :--- |
| `enabled` | `true` |
| `shutdown-delay-seconds` | `60` |
| `confirm-phrase-restart` | `RESTART SERVER` |
| `confirm-phrase-shutdown` | `SHUTDOWN SERVER` |

**Safety:** The dashboard requires typing the exact phrase plus a final browser confirm dialog. Host actions need **Administrator** privileges on Windows.

**Public exposure:** `control.delena.buzz` proxies to Stack Pilot — restrict access (firewall, VPN, or auth) if host controls should not be reachable from the internet.
