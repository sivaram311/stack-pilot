# RDP Black Screen — Fix Plan

**Related:** [rdp-black-screen-root-cause.md](rdp-black-screen-root-cause.md)  
**Host:** WD103118183185C — Windows Server 2025 `26100.1742` on VMware  
**Last updated:** 2026-07-02

---

## Goals

1. **Stop** the black-screen deadlock after `rdpcorets.dll` crashes
2. **Recover** without full reboot when RDP shell is stuck
3. **Detect** RDP/session health from Stack Pilot
4. **Reduce** crash frequency via OS updates and RDP tuning

---

## Phase 1 — Immediate mitigation (no code changes)

Apply these first. Low risk; reversible.

### 1.1 Enable broken-session auto-reset

Forces Windows to discard corrupted sessions after TermService failure instead of trapping reconnects.

```powershell
# Run as Administrator
Set-ItemProperty `
  -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\Terminal Server\WinStations\RDP-Tcp' `
  -Name fResetBroken -Value 1 -Type DWord
```

| Before | After |
|--------|-------|
| `fResetBroken = 0` | `fResetBroken = 1` |

No reboot required. Takes effect on next TermService recovery or new connection.

### 1.2 Manual recovery when stuck (without reboot)

From VPS provider console, another admin session, or WinRM:

```powershell
# List sessions
query session

# Log off stuck RDP session (replace 2 with session ID from query)
logoff 2

# If TermService is unhealthy, restart it
Restart-Service TermService -Force
```

### 1.3 RDP client-side tuning

In the `.rdp` file or Remote Desktop client settings:

| Setting | Recommendation |
|---------|----------------|
| Persistent bitmap caching | **Off** |
| UDP transport | Try **Off** (TCP only) for stability testing |
| Color depth | 16-bit (temporary test) |
| Reconnect on disconnect | Keep enabled, but rely on `fResetBroken` server-side |

---

## Phase 2 — OS hardening (scheduled maintenance)

### 2.1 Windows Update

Install latest cumulative updates for Windows Server 2025 / 26100. Microsoft has shipped RDP session fixes in recent builds.

```powershell
# Option A: Settings → Windows Update → Check for updates
# Option B: PowerShell (if PSWindowsUpdate module installed)
Install-Module PSWindowsUpdate -Force -Scope AllUsers
Get-WindowsUpdate -AcceptAll -Install -AutoReboot
```

**Note:** Last visible hotfix metadata shows 2024 dates; image build is `26100.1742`. Verify update channel with hosting provider.

### 2.2 Full component repair (if updates fail or crashes continue)

```powershell
DISM /Online /Cleanup-Image /RestoreHealth
sfc /scannow
```

Prior `/verifyonly` and `/CheckHealth` passed clean (2026-07-02). Run full repair only if crashes persist after updates.

### 2.3 VMware Tools update

Ensure VMware Tools is current for Server 2025 / 24H2. Running version should match host ESXi compatibility matrix.

### 2.4 Desktop heap increase (optional)

Only if Win32k Event 243 recurs:

```text
HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\SubSystems
Windows = ... SharedSection=1024,20480,2048
```

Change third value `768` → `2048`. **Requires reboot.**

---

## Phase 3 — Stack Pilot integration (recommended)

**Status: IMPLEMENTED (2026-07-02)**

Merge RDP health into Stack Pilot as a new **Host Infrastructure** capability alongside NGINX and host restart/shutdown.

### Why integrate with Stack Pilot

| Benefit | Detail |
|---------|--------|
| Single control plane | RDP health visible next to grok_dev services and NGINX |
| Remote recovery | Fix stuck sessions via `control.delena.buzz` when desktop RDP is black |
| Boot chain awareness | Stack Pilot already owns post-reboot recovery; RDP fixes complement that |
| Existing patterns | `HostController`, `HostControlService`, PowerShell scripts, dashboard Infrastructure section |

### Recommended architecture

```text
stack-pilot/
├── docs/
│   ├── rdp-black-screen-root-cause.md      # This investigation
│   └── rdp-black-screen-fix-plan.md        # This plan
├── scripts/
│   ├── rdp-apply-mitigations.ps1           # Phase 1 registry + verify
│   ├── rdp-status.ps1                      # Sessions, TermService, recent crashes
│   └── rdp-recover-session.ps1             # logoff + TermService restart
└── src/.../
    ├── controller/RdpHealthController.java
    └── service/RdpHealthService.java
```

### Proposed API endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/infrastructure/rdp/status` | `GET` | TermService state, active sessions, last `rdpcorets` crash, `fResetBroken` value |
| `/api/infrastructure/rdp/recover` | `POST` | Log off stuck sessions + restart TermService (requires confirm phrase) |
| `/api/infrastructure/rdp/apply-mitigations` | `POST` | Set `fResetBroken=1` if not already set (requires confirm phrase) |

Follow the same safety model as host restart: typed confirmation phrase in `application.yml`.

### Proposed `application.yml` section

```yaml
stackpilot:
  rdp:
    enabled: true
    confirm-phrase-recover: "RECOVER RDP"
    confirm-phrase-apply-mitigations: "APPLY RDP FIX"
    monitor:
      enabled: true
      poll-interval-ms: 60000          # Check event log for rdpcorets crashes
      alert-after-crashes-in-hours: 2  # Surface warning on dashboard
```

### Dashboard UI (Infrastructure section)

Add an **RDP Health** card below NGINX:

| Field | Source |
|-------|--------|
| TermService status | `Get-Service TermService` |
| Active RDP sessions | `query session` |
| `fResetBroken` | Registry read |
| Last `rdpcorets` crash | Event log Application Error |
| Actions | **Recover Session**, **Apply Mitigations** |

### Boot-time one-shot (optional)

Extend `setup-boot-tasks.ps1` or `BootStartupRunner` to call `rdp-apply-mitigations.ps1` once at boot:

- Ensures `fResetBroken=1` survives imaging / manual registry resets
- Idempotent — safe to run every boot

**Do not** auto-`logoff` sessions at boot — that would kill interactive work. Only enforce registry mitigations.

### Scheduled health check (optional Phase 3b)

Windows Scheduled Task: `StackPilot-RDP-Health-Check` every 15 minutes

- Runs `rdp-status.ps1`
- If TermService stopped or crash in last 5 minutes → restart TermService
- Log to `stack-pilot/logs/rdp-health.log`
- Stack Pilot reads log tail for dashboard

---

## Phase 4 — Monitoring and validation

### Success criteria

| Metric | Target |
|--------|--------|
| Black-screen incidents | Zero without manual reboot |
| `rdpcorets` crashes | Logged but session recovers automatically |
| Recovery time when stuck | < 2 minutes via Stack Pilot recover action |
| grok_dev uptime | Unaffected by RDP session recovery |

### Validation checklist

```powershell
# 1. Confirm mitigation applied
reg query "HKLM\SYSTEM\CurrentControlSet\Control\Terminal Server\WinStations\RDP-Tcp" /v fResetBroken
# Expected: 0x1

# 2. Confirm TermService running
Get-Service TermService

# 3. Simulate recovery path (during maintenance window)
# Disconnect RDP abruptly, reconnect — desktop should load or get fresh session

# 4. Stack Pilot endpoint (after Phase 3 implemented)
curl http://localhost:8091/api/infrastructure/rdp/status
```

### Ongoing monitoring

Watch Event Viewer:

| Log | Provider | Event IDs |
|-----|----------|-----------|
| Application | Application Error | 1000 (`rdpcorets.dll`) |
| System | Service Control Manager | 7031 (TermService) |
| Microsoft-Windows-TerminalServices-LocalSessionManager/Operational | — | 36, 40 |
| System | Win32k | 243 (desktop heap) |

---

## Implementation priority

| Priority | Item | Effort | Impact |
|----------|------|--------|--------|
| **P0** | Set `fResetBroken=1` | 1 min | High — breaks the deadlock | **Done** |
| **P1** | Windows Update + VMware Tools | 30 min + reboot | High — addresses crash root | Manual / maintenance window |
| **P2** | PowerShell scripts (`rdp-status`, `rdp-recover`) | 2 hours | Medium — manual/API recovery | **Done** |
| **P3** | Stack Pilot `RdpHealthController` + dashboard card | 1 day | High — remote ops when RDP black | **Done** |
| **P4** | Boot-time mitigation script | 1 hour | Low — drift prevention | **Done** (`boot.auto-apply-rdp-mitigations`) |
| **P5** | Desktop heap increase | 15 min + reboot | Low — only if Event 243 recurs | Not needed yet |

---

## Rollback

### Registry

```powershell
Set-ItemProperty `
  -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\Terminal Server\WinStations\RDP-Tcp' `
  -Name fResetBroken -Value 0 -Type DWord
```

### Stack Pilot (after Phase 3)

Set `stackpilot.rdp.enabled: false` in `application.yml` and restart Stack Pilot.

---

## Merge summary — Stack Pilot + RDP fix plan

| Layer | Responsibility |
|-------|----------------|
| **Windows registry** (`fResetBroken`) | OS-level session recovery — apply once, Phase 1 |
| **PowerShell scripts** | Operational runbook automation — `stack-pilot/scripts/` |
| **Stack Pilot API + dashboard** | Discovery, status, one-click recover — Phase 3 |
| **Existing host restart** | Last resort — already in Stack Pilot Host Controls |

**Recommendation:** Do **not** fold RDP recovery into host restart/shutdown. Keep them separate:

- **Recover RDP** = light touch (logoff session, restart TermService) — safe during trading hours
- **Restart server** = heavy — use only when recover fails or for updates

Stack Pilot becomes the **remote hands** when the desktop is unusable but `control.delena.buzz` still works.

---

## Next steps

1. Apply Phase 1.1 (`fResetBroken=1`) on the VPS
2. Schedule Phase 2.1 (Windows Update) in a maintenance window
3. Implement Phase 3 scripts + API (can be done in stack-pilot repo)
4. Update [implementation_plan.md](implementation_plan.md) with RDP infrastructure section after Phase 3 ships
