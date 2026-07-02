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

Defaults:

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
