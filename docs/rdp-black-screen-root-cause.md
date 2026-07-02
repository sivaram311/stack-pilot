# RDP Black Screen — Root Cause Analysis

**Host:** WD103118183185C (VMware VPS)  
**OS:** Windows Server 2025 Standard 24H2 — build `26100.1742`  
**Investigation date:** 2026-07-02  
**Symptom:** RDP connects but shows a black screen with "Please wait". Mouse cursor moves; background tasks keep running. Full reboot clears the issue.

---

## Executive summary

The black "Please wait" screen is **not a network or firewall problem**. It is caused by a **corrupted RDP user session** that cannot recover after the **Remote Desktop Services process crashes** in `rdpcorets.dll`. Registry settings prevent automatic session reset, and single-session-per-user forces reconnections back into the broken session.

A reboot works because it destroys all session state and allows a clean login.

---

## Environment

| Item | Value |
|------|-------|
| Platform | VMware Virtual Platform |
| RAM | 24 GB (ample free at time of investigation) |
| GPU | VMware SVGA 3D + Microsoft Remote Display Adapter |
| RDP port | 3389 |
| `fSingleSessionPerUser` | `1` (one session per user) |
| `fResetBroken` | `0` (broken sessions not auto-reset) |
| `fReconnectSame` | `0` |
| Stack Pilot | Running on `:8091`, boot tasks registered 2026-07-02 |

---

## Primary root cause: `rdpcorets.dll` crash

The Remote Desktop Services host process (`svchost.exe_TermService`) has crashed **21+ times** since 2026-06-17. Every crash shares the same signature:

| Field | Value |
|-------|-------|
| Faulting process | `svchost.exe_TermService` |
| Faulting module | `rdpcorets.dll` |
| Module version | `10.0.26100.1455` |
| Exception | `0xc0000005` (access violation) |
| Fault offset | `0x00000000000613d4` (identical every time) |

After each crash:

1. Event **7031** — "Remote Desktop Services service terminated unexpectedly"
2. Windows restarts TermService after ~60 seconds
3. The existing RDP session is left in a **broken intermediate state**
4. Reconnection attempts fail to restore the desktop shell

This is a **recurring Windows RDP core bug** on Server 2025 build 26100, not a one-off corruption.

---

## Why the symptom matches exactly

### Black screen + movable mouse

| Layer | State after crash |
|-------|-------------------|
| RDP transport (network, input) | **Alive** — cursor moves |
| TermService / session broker | **Crashed then restarted** — session state inconsistent |
| Winlogon / Explorer shell | **Failed to load** — black "Please wait" |
| User applications / background jobs | **Still running** in the orphaned session |

### Event log evidence (example: 2026-07-02 09:28)

| Time | Event | Meaning |
|------|-------|---------|
| 09:28:50 | Application Error — `rdpcorets.dll` crash | TermService dies |
| 09:29:00 | SCM 7031 — TermService terminated | Service auto-restart scheduled |
| 09:34:22 | LocalSessionManager 36 — `DisconnectedLoggedDesktopLocked` → `EvConnected` failed (`0x80004005`) | Session cannot transition back to active desktop |
| 09:34:22 | Winlogon 4008 — "logon process has failed to connect the user session" | Shell never loads |

Multiple reconnect attempts (sessions 3, 4) were created and immediately torn down while session 2 remained stuck.

---

## Contributing configuration (prevents self-healing)

| Registry value | Current | Effect |
|----------------|---------|--------|
| `fResetBroken` | `0` | After TermService crash, broken sessions are **not** reset |
| `fSingleSessionPerUser` | `1` | User **cannot** open a fresh session while the broken one exists |
| `fReconnectSame` | `0` | Reconnect does not cleanly replace the session |

Together these create a **deadlock**: crash → broken session → reconnect → same broken session → black screen until reboot.

---

## Secondary factors

### 1. Desktop heap allocation failures

- **Win32k Event 243:** "A desktop heap allocation failed" (2 occurrences in recent logs)
- Default `SharedSection=1024,20480,768`
- Can prevent Explorer from rendering under sustained GUI load

### 2. High Explorer handle count

- `explorer.exe` observed with **~2,950 handles** during investigation
- Not the primary cause, but increases desktop subsystem stress over long sessions

### 3. Console + RDP sessions simultaneously

- Session 1 (console): `Conn`
- Session 2 (RDP): `Active`
- With `fSingleSessionPerUser=1`, console activity can contribute to session arbitration conflicts

### 4. Unexpected VM reboots

- Kernel-Power Event 41 on 2026-07-01 and 2026-07-02 (unclean shutdown)
- Hard resets also leave sessions in inconsistent states

### 5. DWM crash (single occurrence)

- `dwm.exe` fault in `dwmredir.dll` on 2026-06-23
- Related to remote display redirection; secondary to `rdpcorets.dll` pattern

---

## System file health (ruled out)

Checks run 2026-07-02:

| Check | Result |
|-------|--------|
| `sfc /verifyonly` | No integrity violations |
| `DISM /Online /Cleanup-Image /CheckHealth` | No component store corruption |

File integrity is OK. The issue is **runtime session recovery**, not missing or corrupted system files. Note: `rdpcorets.dll` file version (`1455`) is older than the OS image (`1742`) — updates may still deliver a newer patched build.

---

## Failure chain diagram

```text
User connected via RDP (Session 2)
        │
        ▼
rdpcorets.dll access violation
        │
        ▼
TermService crashes (Event 7031)
        │
        ▼
Service restarts (~60s) — session state orphaned
        │
        ▼
User reconnects ──► DisconnectedLoggedDesktopLocked
        │
        ▼
Winlogon cannot attach shell (Event 4008)
        │
        ▼
Black "Please wait" — mouse works, desktop dead
        │
        ▼
Reboot ──► fresh session ──► works until next crash
```

---

## What this is NOT

| Ruled out | Evidence |
|-----------|----------|
| Network / firewall | RDP connects; cursor and transport work |
| Low memory | ~19 GB free RAM |
| Corrupt system files | SFC + DISM clean |
| Wrong RDP port | 3389 listening, sessions log on successfully initially |
| Stack Pilot service failure | grok_dev processes unaffected; issue predates Stack Pilot boot setup |

---

## Related Stack Pilot context

Stack Pilot manages grok_dev services and host restart/shutdown. It does **not** currently monitor or remediate RDP session health. When RDP is stuck:

- Background services (Python, backend, frontend) **keep running**
- Stack Pilot dashboard at `http://control.delena.buzz/` may still be reachable via browser if NGINX is up
- Host restart via Stack Pilot **does** fix the issue (same as manual reboot) but is a heavy-handed workaround

See [rdp-black-screen-fix-plan.md](rdp-black-screen-fix-plan.md) for remediation and Stack Pilot integration.
