# NGINX deployment assets for Stack Pilot

Version-controlled reverse-proxy configs and Windows scripts. Source of truth for `delena.buzz` and `control.delena.buzz`.

## Layout

```text
deployment/
├── conf/
│   ├── delena.buzz.conf          # :4200 + /api → :8081
│   └── control.delena.buzz.conf  # → Stack Pilot :8091
└── scripts/
    ├── sync-nginx-config.ps1     # copy conf → nginx install + update includes
    ├── start-nginx.ps1
    ├── stop-nginx.ps1
    ├── reload-nginx.ps1
    └── status-nginx.ps1
```

## Sync configs to NGINX (recommended workflow)

Edit files under `deployment/conf/`, then:

```powershell
# Administrator — copy, fix nginx.conf includes, test
E:\Source\stack-pilot\deployment\scripts\sync-nginx-config.ps1

# Apply on running nginx
E:\Source\stack-pilot\deployment\scripts\sync-nginx-config.ps1 -Reload
```

### Control panel access (`control.delena.buzz`)

**Current (public):** NGINX basic auth and Stack Pilot API key are **disabled** while `stackpilot.auth.enabled: false` in `application.yml`. `control.delena.buzz` and `:8091` work without credentials.

To re-enable protection:

1. Set `stackpilot.auth.enabled: true` in `application.yml` and restart Stack Pilot.
2. Uncomment `auth_basic` lines in `deployment/conf/control.delena.buzz.conf`, run `setup-control-auth.ps1`, then `sync-nginx-config.ps1 -Reload`.

Previously, `control.delena.buzz.conf` used `conf/.htpasswd-control`:

```powershell
# Creates C:\nginx-1.30.3\conf\.htpasswd-control (prompts for password)
E:\Source\stack-pilot\deployment\scripts\setup-control-auth.ps1 -Username admin

E:\Source\stack-pilot\deployment\scripts\sync-nginx-config.ps1 -Reload
```

Direct access to `:8091` from non-localhost used Stack Pilot **API key** (`stackpilot.auth.api-key` or `STACKPILOT_AUTH_API_KEY`) when auth was enabled.

Defaults when auth is enabled:

| Variable | Default |
|----------|---------|
| `STACK_PILOT_HOME` | `E:\Source\stack-pilot` |
| `NGINX_HOME` | `C:\nginx-1.30.3` |

## Boot tasks

```powershell
E:\Source\stack-pilot\scripts\setup-boot-tasks.ps1
```

Uses `deployment/scripts/start-nginx.ps1` by default.

## Legacy path

`E:\Source\Deployment\` may still exist on this machine. Prefer `stack-pilot/deployment/` for new changes; run `sync-nginx-config.ps1` after edits.
