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
| **backend** | `E:\Source\grok_dev\backend` | `mvn spring-boot:run` | `8080` (default) |
| **frontend** | `E:\Source\grok_dev\frontend` | `npm run start` (or `ng serve`) | `4200` |

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
  2. Written to dynamic service-specific log files inside a `logs/` directory (e.g., `logs/python.log`, `logs/backend.log`, `logs/frontend.log`).
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

---

## 5. Web Control Dashboard UI

A lightweight, modern, responsive single-page dashboard will be hosted directly by the manager at `http://localhost:8091/`.
* **Features:**
  * Status indicator cards for each service (running, stopped, starting, error).
  * Quick-action buttons (Start, Stop, Restart) per service.
  * Real-time rolling log terminal viewer that updates dynamically using polling or Server-Sent Events.
  * A button to start all or stop all services at once.
