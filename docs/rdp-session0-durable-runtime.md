# RDP recover vs durable PREPROD/PROD (Session 0)

**Updated:** 2026-07-13  
**Incident:** `E:\MyWorkspace\sandbox\vps-incident-2026-07-13\INCIDENT-REPORT.md`

## Problem

`StackPilot-RDP-Health-Check` used to **log off all RDP sessions** after `rdpcorets.dll` crashes. Apps started from RDP died. Postgres (Session 0 service) survived.

## Policy

| Path | Logoff? |
|------|---------|
| Scheduled `rdp-health-check.ps1` | **No** (`-SkipLogoff`) — restart TermService only |
| UI / API recover with confirm phrase `RECOVER RDP` | **Yes** (`-ForceLogoff`) — clears stuck black-screen sessions |

## Start fleet (Session 0)

```powershell
# On-demand (SYSTEM)
schtasks /Run /TN StackPilot-Fleet-Session0

# Per-app AtStartup tasks (registered by register-s0-app-tasks.ps1)
Get-ScheduledTask -TaskName 'StackPilot-S0-*'
```

Scripts:

- `E:\Source\Deployment\scripts\start-fleet-session0.ps1`
- `E:\Source\Deployment\scripts\register-fleet-session0-task.ps1`
- `E:\Source\Deployment\scripts\register-s0-app-tasks.ps1`

## Verify durability

```powershell
# Full fleet incl. side-fleet (css-next :5910, proddeck :5320) — keep this list complete
foreach ($port in 80,4900,5900,5910,4010,5010,4080,5080,4091,5091,4310,5310,4320,5320,5370,5432) {
  $c = Get-NetTCPConnection -State Listen -LocalPort $port -EA SilentlyContinue | Select-Object -First 1
  if ($c) {
    $p = Get-Process -Id $c.OwningProcess
    [PSCustomObject]@{Port=$port; SessionId=$p.SessionId; Name=$p.ProcessName}
  }
}
```

Durable listeners must show **SessionId = 0**.

> **Coverage note (2026-07-21):** the side-fleet apps **css-next PROD `:5910`** (`css-next.delena.buzz` + apex `delena.buzz` `/auth`) and **proddeck PROD `:5320`** (`home.delena.buzz`) were previously **absent** from Session-0 autostart, so they stayed down after reboot (home.delena.buzz → 502). Now covered by `StackPilot-S0-CssNext-Prod` and `StackPilot-S0-pd-prod` (AtStartup) plus entries in `start-fleet-session0.ps1` and `register-s0-app-tasks.ps1`. Verify these two ports whenever confirming fleet durability.

> **Coverage note (2026-08-05):** **production-house PROD `:5370`** (`production-house.delena.buzz`, embedded as a HomeMode WebView in the Foundry/forgecity-launcher Android app) had the exact same gap — it went live via Q2 promote on 2026-07-22 (`H:\releases\production-house-0.1.0`) but was never wired into Session-0 autostart, so it was only up for as long as its interactive `start.ps1` session lasted. Reported by the user as a Cloudflare origin error in the Android app. Fixed with `StackPilot-S0-ProductionHouse-Prod` (AtStartup) via `register-s0-app-tasks.ps1` (bespoke cmd launcher, since `G:\apps\production-house\start.ps1` is a plain `vite preview --port 5370`, not the `-EnvName`-driven Next.js pattern used by agentverse/proddeck) plus a matching `Write-ViteLauncher` entry in `start-fleet-session0.ps1`. Verify `:5370` too whenever confirming fleet durability. **F: PREPROD (`:4370`, `production-house-staging.delena.buzz`) has the same gap and is not yet fixed** — same root cause, lower priority since nothing public depends on it staying up.

## Anti-patterns

- `.\start.ps1` from an interactive RDP desktop for long-lived F:/G: apps  
- Assuming “Hidden” `Start-Process` = Session 0  
- Using UI ForceLogoff recover while apps are still RDP-bound  

## Phase 1 backlog

Stack Pilot as sole Session-0 supervisor for full F:+G: catalog; promote recycle via Pilot API only.
