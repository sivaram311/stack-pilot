document.addEventListener('DOMContentLoaded', () => {
    const API_KEY_STORAGE = 'stackPilotApiKey';
    let authRequired = false;
    let authUnlocked = false;

    let activeTab = null;
    let lastLogHash = '';
    let pollingInterval = null;
    let services = [];
    let renderedServiceKey = '';
    let pendingHostAction = null;
    let activePanel = 'services';
    let logsDrawerOpen = false;
    let hostConfig = {
        confirmPhraseRestart: 'RESTART SERVER',
        confirmPhraseShutdown: 'SHUTDOWN SERVER',
        shutdownDelaySeconds: 60
    };
    let rdpConfig = {
        confirmPhraseRecover: 'RECOVER RDP',
        confirmPhraseApplyMitigations: 'APPLY RDP FIX'
    };

    const LOG_TAB_LABELS = {
        'python-downloader': 'Downloader',
        'python-order-rsi': 'Order RSI',
        'backend': 'Backend',
        'frontend': 'Frontend',
        'agent-portal': 'Agent Portal',
        'css': 'CSS (Auth)',
        'h-drive-server': 'H-Drive',
        'nginx-error': 'NGINX Error',
        'nginx-access': 'NGINX Access',
        'stackpilot-actions': 'Actions'
    };

    const COLOR_OK = '#3dd68c';
    const COLOR_ERR = '#ff6b6b';

    const servicesGrid = document.getElementById('services-grid');
    const logsTabs = document.getElementById('logs-tabs');
    const logsSurface = document.getElementById('logs-surface');
    const logsDrawer = document.getElementById('logs-drawer');
    const logsDrawerMount = document.getElementById('logs-drawer-mount');
    const logsDrawerBackdrop = document.getElementById('logs-drawer-backdrop');
    const logsDrawerCloseBtn = document.getElementById('btn-logs-drawer-close');
    const panelLogs = document.getElementById('panel-logs');
    const terminalBody = document.getElementById('terminal-body');
    const autoScrollCheck = document.getElementById('chk-autoscroll');
    const clearLogsBtn = document.getElementById('btn-clear-logs');
    const startAllBtn = document.getElementById('btn-start-all');
    const restartAllBtn = document.getElementById('btn-restart-all');
    const stopAllBtn = document.getElementById('btn-stop-all');
    const nginxStartBtn = document.getElementById('btn-nginx-start');
    const nginxStopBtn = document.getElementById('btn-nginx-stop');
    const nginxReloadBtn = document.getElementById('btn-nginx-reload');
    const nginxStatusBtn = document.getElementById('btn-nginx-status');
    const hostRestartBtn = document.getElementById('btn-host-restart');
    const hostShutdownBtn = document.getElementById('btn-host-shutdown');
    const hostCancelBtn = document.getElementById('btn-host-cancel');
    const rdpRecoverBtn = document.getElementById('btn-rdp-recover');
    const rdpMitigateBtn = document.getElementById('btn-rdp-mitigate');
    const rdpRefreshBtn = document.getElementById('btn-rdp-refresh');
    const runningCountEl = document.getElementById('running-count');
    const overflowBtn = document.getElementById('btn-overflow');
    const overflowPanel = document.getElementById('overflow-panel');

    const hostModal = document.getElementById('host-modal');
    const hostModalTitle = document.getElementById('host-modal-title');
    const hostModalDesc = document.getElementById('host-modal-desc');
    const hostModalPhrase = document.getElementById('host-modal-phrase');
    const hostModalExpected = document.getElementById('host-modal-expected');
    const hostModalError = document.getElementById('host-modal-error');
    const hostModalConfirm = document.getElementById('host-modal-confirm');
    const hostModalCancel = document.getElementById('host-modal-cancel');
    const hostModalBackdrop = document.getElementById('host-modal-backdrop');
    const authGate = document.getElementById('auth-gate');
    const authApiKeyInput = document.getElementById('auth-api-key');
    const authGateSubmit = document.getElementById('auth-gate-submit');
    const authGateError = document.getElementById('auth-gate-error');

    init();

    function getStoredApiKey() {
        return sessionStorage.getItem(API_KEY_STORAGE) || '';
    }

    function setStoredApiKey(key) {
        if (key) sessionStorage.setItem(API_KEY_STORAGE, key);
        else sessionStorage.removeItem(API_KEY_STORAGE);
    }

    function showAuthGate(message) {
        authGate.classList.remove('hidden');
        if (message) {
            authGateError.textContent = message;
            authGateError.classList.remove('hidden');
        }
        requestAnimationFrame(() => {
            try {
                authApiKeyInput.focus({ preventScroll: false });
                authApiKeyInput.scrollIntoView({ block: 'center', behavior: 'smooth' });
            } catch (_) { /* ignore */ }
        });
    }

    function hideAuthGate() {
        authGate.classList.add('hidden');
        authGateError.classList.add('hidden');
        authUnlocked = true;
        document.body.classList.remove('keyboard-open');
    }

    function setupKeyboardGuards() {
        const onFocusIn = (e) => {
            if (!(e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement)) return;
            document.body.classList.add('keyboard-open');
            setTimeout(() => {
                try {
                    e.target.scrollIntoView({ block: 'center', behavior: 'smooth' });
                } catch (_) { /* ignore */ }
            }, 280);
        };
        const onFocusOut = () => {
            setTimeout(() => {
                const active = document.activeElement;
                if (!(active instanceof HTMLInputElement || active instanceof HTMLTextAreaElement)) {
                    document.body.classList.remove('keyboard-open');
                }
            }, 100);
        };
        document.addEventListener('focusin', onFocusIn);
        document.addEventListener('focusout', onFocusOut);
        if (window.visualViewport) {
            window.visualViewport.addEventListener('resize', () => {
                const vv = window.visualViewport;
                const obscured = (window.innerHeight - vv.height) > 120;
                document.body.classList.toggle('keyboard-open', obscured);
            });
        }
    }

    async function apiFetch(url, options = {}) {
        const headers = { ...(options.headers || {}) };
        const key = getStoredApiKey();
        if (key) headers['X-StackPilot-Api-Key'] = key;

        const response = await fetch(url, { ...options, headers });

        if (response.status === 401) {
            authRequired = true;
            authUnlocked = false;
            showAuthGate('Invalid or missing API key.');
            throw new Error('Unauthorized');
        }
        return response;
    }

    async function checkAuth() {
        try {
            const response = await fetch('/api/auth/status');
            if (!response.ok) return;
            const status = await response.json();
            authRequired = !!status.enabled;
            if (!authRequired) {
                hideAuthGate();
                return;
            }
            const key = getStoredApiKey();
            const headers = key ? { 'X-StackPilot-Api-Key': key } : {};
            const probe = await fetch('/api/services', { headers });
            if (probe.ok) {
                hideAuthGate();
                return;
            }
            if (key) showAuthGate('Stored API key was rejected.');
            else showAuthGate('Use control.delena.buzz (nginx login) or enter API key for direct :8091 access.');
        } catch (e) {
            console.error('Auth check failed', e);
        }
    }

    async function submitAuthGate() {
        const key = authApiKeyInput.value.trim();
        if (!key) return;
        setStoredApiKey(key);
        try {
            const probe = await fetch('/api/services', {
                headers: { 'X-StackPilot-Api-Key': key }
            });
            if (probe.ok) {
                hideAuthGate();
                startDashboardPolling();
            } else {
                setStoredApiKey('');
                showAuthGate('API key rejected. Try again.');
            }
        } catch (e) {
            showAuthGate('Could not verify API key.');
        }
    }

    function init() {
        setupEventListeners();
        setupKeyboardGuards();
        checkAuth().then(() => {
            if (!authRequired || authUnlocked) {
                startDashboardPolling();
            }
        });
    }

    function startDashboardPolling() {
        fetchStatus();
        fetchNginxStatus();
        fetchRdpStatus();
        fetchHostStatus();
        fetchLogs();

        if (pollingInterval) clearInterval(pollingInterval);
        pollingInterval = setInterval(() => {
            if (authRequired && !authUnlocked) return;
            fetchStatus();
            fetchNginxStatus();
            fetchRdpStatus();
            fetchHostStatus();
            fetchLogs();
        }, 1500);
    }

    function isDesktop() {
        return window.matchMedia('(min-width: 768px)').matches;
    }

    function switchPanel(panel) {
        if (!panel) return;
        activePanel = panel;

        document.querySelectorAll('.panel').forEach(el => {
            const match = el.dataset.panel === panel;
            el.classList.toggle('active', match);
            if (match) el.removeAttribute('hidden');
            else el.setAttribute('hidden', '');
        });

        document.querySelectorAll('.nav-item').forEach(btn => {
            const match = btn.dataset.panel === panel;
            btn.classList.toggle('active', match);
            if (match) btn.setAttribute('aria-current', 'page');
            else btn.removeAttribute('aria-current');
        });

        if (panel === 'logs') {
            closeLogsDrawer(true);
            restoreLogsSurfaceToPanel();
        }
    }

    function closeOverflow() {
        overflowPanel.classList.add('hidden');
        overflowBtn.setAttribute('aria-expanded', 'false');
    }

    function toggleOverflow() {
        const open = overflowPanel.classList.contains('hidden');
        if (open) {
            overflowPanel.classList.remove('hidden');
            overflowBtn.setAttribute('aria-expanded', 'true');
        } else {
            closeOverflow();
        }
    }

    function restoreLogsSurfaceToPanel() {
        if (!logsSurface || !panelLogs) return;
        if (logsSurface.parentElement !== panelLogs) {
            panelLogs.appendChild(logsSurface);
        }
    }

    function openLogsDrawer(tab) {
        if (isDesktop()) {
            switchPanel('logs');
            if (tab) switchLogTab(tab);
            return;
        }

        if (tab) switchLogTab(tab);
        if (logsDrawerMount && logsSurface && logsSurface.parentElement !== logsDrawerMount) {
            logsDrawerMount.appendChild(logsSurface);
        }
        logsDrawer.classList.remove('hidden', 'closing');
        logsDrawerOpen = true;
        document.body.style.overflow = 'hidden';
    }

    function closeLogsDrawer(immediate) {
        if (!logsDrawerOpen && logsDrawer.classList.contains('hidden')) {
            restoreLogsSurfaceToPanel();
            return;
        }

        let finished = false;
        const finish = () => {
            if (finished) return;
            finished = true;
            logsDrawer.classList.add('hidden');
            logsDrawer.classList.remove('closing');
            logsDrawerOpen = false;
            document.body.style.overflow = '';
            restoreLogsSurfaceToPanel();
        };

        if (immediate || window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
            finish();
            return;
        }

        logsDrawer.classList.add('closing');
        const sheet = logsDrawer.querySelector('.logs-drawer-sheet');
        const onEnd = (e) => {
            if (e && e.target !== sheet) return;
            sheet.removeEventListener('transitionend', onEnd);
            finish();
        };
        sheet.addEventListener('transitionend', onEnd);
        setTimeout(finish, 300);
    }

    function setupEventListeners() {
        servicesGrid.addEventListener('click', (e) => {
            const logsBtn = e.target.closest('.btn-open-logs');
            if (logsBtn) {
                const tab = logsBtn.getAttribute('data-log-tab');
                openLogsDrawer(tab);
                return;
            }
            const btn = e.target.closest('button[data-service][data-action]');
            if (!btn || btn.disabled) return;
            callServiceAction(btn.dataset.service, btn.dataset.action);
        });

        document.querySelector('.infra-rows')?.addEventListener('click', (e) => {
            const logsBtn = e.target.closest('.btn-open-logs');
            if (!logsBtn) return;
            openLogsDrawer(logsBtn.getAttribute('data-log-tab'));
        });

        logsTabs.addEventListener('click', (e) => {
            const btn = e.target.closest('.tab-btn');
            if (!btn) return;
            switchLogTab(btn.getAttribute('data-tab'));
        });

        document.querySelectorAll('.nav-item').forEach(btn => {
            btn.addEventListener('click', () => switchPanel(btn.dataset.panel));
        });

        overflowBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            toggleOverflow();
        });
        document.addEventListener('click', (e) => {
            if (!overflowPanel.classList.contains('hidden') && !e.target.closest('#overflow-menu')) {
                closeOverflow();
            }
        });

        startAllBtn.addEventListener('click', () => {
            closeOverflow();
            callBulkAction('start-all', 'Starting all services');
        });
        restartAllBtn.addEventListener('click', () => {
            closeOverflow();
            if (confirm('Kill all external/managed grok-dev processes and relaunch under StackPilot?')) {
                callBulkAction('restart-all', 'Restarting all services (take control)');
            }
        });
        stopAllBtn.addEventListener('click', () => {
            closeOverflow();
            callBulkAction('stop-all', 'Stopping all services');
        });

        nginxStartBtn.addEventListener('click', () => callNginxAction('start', 'Starting nginx'));
        nginxStopBtn.addEventListener('click', () => {
            if (confirm('Stop nginx? Public sites will be unreachable until it is started again.')) {
                callNginxAction('stop', 'Stopping nginx');
            }
        });
        nginxReloadBtn.addEventListener('click', () => callNginxAction('reload', 'Reloading nginx config'));
        nginxStatusBtn.addEventListener('click', () => fetchNginxStatus());

        hostRestartBtn.addEventListener('click', () => openHostModal('restart'));
        hostShutdownBtn.addEventListener('click', () => openHostModal('shutdown'));
        hostCancelBtn.addEventListener('click', () => callHostCancel());

        rdpRecoverBtn.addEventListener('click', () => openHostModal('rdp-recover'));
        rdpMitigateBtn.addEventListener('click', () => openHostModal('rdp-apply'));
        rdpRefreshBtn.addEventListener('click', () => fetchRdpStatus());

        hostModalCancel.addEventListener('click', closeHostModal);
        hostModalBackdrop.addEventListener('click', closeHostModal);
        hostModalConfirm.addEventListener('click', submitHostModal);
        hostModalPhrase.addEventListener('input', updateHostModalConfirmState);
        hostModalPhrase.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !hostModalConfirm.disabled) submitHostModal();
            if (e.key === 'Escape') closeHostModal();
        });

        authGateSubmit.addEventListener('click', submitAuthGate);
        authApiKeyInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') submitAuthGate();
        });

        clearLogsBtn.addEventListener('click', () => {
            terminalBody.innerHTML = '<div class="log-line system-msg">Log terminal view cleared.</div>';
            lastLogHash = '';
        });

        logsDrawerBackdrop.addEventListener('click', () => closeLogsDrawer());
        logsDrawerCloseBtn.addEventListener('click', () => closeLogsDrawer());

        document.addEventListener('keydown', (e) => {
            if (e.key !== 'Escape') return;
            if (!hostModal.classList.contains('hidden')) {
                closeHostModal();
                return;
            }
            if (logsDrawerOpen) {
                closeLogsDrawer();
                return;
            }
            closeOverflow();
        });

        window.addEventListener('resize', () => {
            if (isDesktop() && logsDrawerOpen) {
                closeLogsDrawer(true);
            }
        });
    }

    function formatDisplayName(name) {
        const labels = {
            'python-downloader': 'Python Downloader',
            'python-order-rsi': 'Python Order RSI',
            'backend': 'Backend Service',
            'frontend': 'Frontend UI',
            'agent-portal': 'Agent Portal',
            'css': 'CSS (Auth)',
            'h-drive-server': 'H-Drive'
        };
        if (labels[name]) return labels[name];
        return name.split('-').map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
    }

    function shortPath(workingDir) {
        if (!workingDir) return '-';
        const parts = workingDir.replace(/\\/g, '/').split('/').filter(Boolean);
        return parts.length ? parts[parts.length - 1] + '/' : workingDir;
    }

    function escapeHtml(text) {
        return String(text)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    function buildServiceRowHtml(service) {
        const name = service.name;
        const display = formatDisplayName(name);
        const dir = shortPath(service.workingDir);
        const port = service.port != null ? service.port : 'N/A';
        const safe = escapeHtml(name);
        return `
            <article class="service-row" id="card-${safe}" data-service="${safe}" role="listitem">
                <div class="row-main">
                    <div class="row-identity">
                        <span class="row-name">${escapeHtml(display)}</span>
                        <span class="status-badge status-stopped" id="status-${safe}">Stopped</span>
                    </div>
                    <div class="row-meta">
                        <span class="meta-item"><span class="meta-label">Dir</span> <span class="meta-value path" title="${escapeHtml(service.workingDir || '')}">${escapeHtml(dir)}</span></span>
                        <span class="meta-item"><span class="meta-label">Cmd</span> <span class="meta-value path" title="${escapeHtml(service.command || '')}">${escapeHtml((service.command || '-').slice(0, 40))}${(service.command || '').length > 40 ? '...' : ''}</span></span>
                        <span class="meta-item"><span class="meta-label">Port</span> <span class="meta-value" id="port-${safe}">${escapeHtml(String(port))}</span></span>
                        <span class="meta-item"><span class="meta-label">PID</span> <span class="meta-value pid" id="pid-${safe}">-</span></span>
                    </div>
                    <div class="row-error error-row" id="err-row-${safe}" style="display: none;">
                        <span class="error-msg" id="err-${safe}"></span>
                    </div>
                </div>
                <div class="row-actions">
                    <button class="btn btn-sm btn-success start-btn" data-service="${safe}" data-action="start" type="button">Start</button>
                    <button class="btn btn-sm btn-warning restart-btn" data-service="${safe}" data-action="restart" type="button">Restart</button>
                    <button class="btn btn-sm btn-danger stop-btn" data-service="${safe}" data-action="stop" type="button">Stop</button>
                    <button class="btn btn-sm btn-ghost btn-open-logs" type="button" data-log-tab="${safe}">Logs</button>
                </div>
            </article>`;
    }

    function renderServiceCards(servicesData) {
        const key = servicesData.map(s => s.name).join('|');
        if (key !== renderedServiceKey) {
            renderedServiceKey = key;
            servicesGrid.innerHTML = servicesData.map(buildServiceRowHtml).join('');
            renderLogTabs(servicesData.map(s => s.name));
        }
        servicesData.forEach(updateServiceCard);
        updateRunningCount(servicesData);
    }

    function updateRunningCount(servicesData) {
        if (!runningCountEl) return;
        const n = servicesData.filter(s =>
            s.status === 'RUNNING' || s.status === 'RUNNING_EXTERNAL' || s.status === 'STARTING'
        ).length;
        runningCountEl.textContent = String(n);
    }

    function renderLogTabs(serviceNames) {
        const staticTabs = ['nginx-error', 'nginx-access', 'stackpilot-actions'];
        const allTabs = [...serviceNames, ...staticTabs];

        if (!activeTab || !allTabs.includes(activeTab)) {
            activeTab = serviceNames[0] || staticTabs[0];
            lastLogHash = '';
        }

        logsTabs.innerHTML = allTabs.map(tab => {
            const label = LOG_TAB_LABELS[tab] || formatDisplayName(tab);
            const active = tab === activeTab ? ' active' : '';
            return `<button class="tab-btn${active}" type="button" role="tab" data-tab="${escapeHtml(tab)}">${escapeHtml(label)}</button>`;
        }).join('');
    }

    function switchLogTab(tab) {
        if (!tab) return;
        if (tab === activeTab) {
            logsTabs.querySelectorAll('.tab-btn').forEach(btn => {
                btn.classList.toggle('active', btn.getAttribute('data-tab') === tab);
            });
            return;
        }
        activeTab = tab;
        logsTabs.querySelectorAll('.tab-btn').forEach(btn => {
            btn.classList.toggle('active', btn.getAttribute('data-tab') === tab);
        });
        lastLogHash = '';
        terminalBody.innerHTML = `<div class="log-line system-msg">Switched tab to ${escapeHtml(tab)}. Loading logs...</div>`;
        fetchLogs();
    }

    async function fetchStatus() {
        try {
            const response = await apiFetch('/api/services');
            if (!response.ok) throw new Error('API request failed');
            const servicesData = await response.json();
            services = servicesData.map(s => s.name);
            renderServiceCards(servicesData);
        } catch (error) {
            console.error('Error fetching service status:', error);
            if (!renderedServiceKey) {
            if (!renderedServiceKey) {
                servicesGrid.innerHTML = '<div class="row-loading" aria-live="assertive"><p class="error-msg">Failed to load services. Pull to refresh or check network.</p></div>';
            }
        }
    }

    function updateServiceCard(service) {
        const idPrefix = service.name;
        const statusBadge = document.getElementById(`status-${idPrefix}`);
        const portSpan = document.getElementById(`port-${idPrefix}`);
        const pidSpan = document.getElementById(`pid-${idPrefix}`);
        const errorRow = document.getElementById(`err-row-${idPrefix}`);
        const errorMsg = document.getElementById(`err-${idPrefix}`);

        if (!statusBadge) return;

        statusBadge.className = 'status-badge';
        const displayStatus = service.status === 'RUNNING_EXTERNAL' ? 'External' : service.status;
        statusBadge.textContent = displayStatus;

        switch (service.status) {
            case 'RUNNING': statusBadge.classList.add('status-running'); break;
            case 'RUNNING_EXTERNAL': statusBadge.classList.add('status-external'); break;
            case 'STARTING': statusBadge.classList.add('status-starting'); break;
            case 'STOPPED': statusBadge.classList.add('status-stopped'); break;
            case 'ERROR': statusBadge.classList.add('status-error'); break;
        }

        if (portSpan) portSpan.textContent = service.port != null ? service.port : 'N/A';
        if (pidSpan) pidSpan.textContent = service.pid && service.pid > 0 ? service.pid : '-';

        if (service.errorMessage) {
            errorRow.style.display = 'flex';
            errorMsg.textContent = service.errorMessage;
        } else if (errorRow) {
            errorRow.style.display = 'none';
        }

        const card = document.getElementById(`card-${idPrefix}`);
        if (!card) return;

        const startBtn = card.querySelector('[data-action="start"]');
        const stopBtn = card.querySelector('[data-action="stop"]');
        const restartBtn = card.querySelector('[data-action="restart"]');

        if (service.status === 'RUNNING' || service.status === 'RUNNING_EXTERNAL') {
            startBtn.disabled = true;
            stopBtn.disabled = false;
            restartBtn.disabled = false;
            restartBtn.textContent = service.status === 'RUNNING_EXTERNAL' ? 'Take Control' : 'Restart';
        } else if (service.status === 'STARTING') {
            restartBtn.textContent = 'Restart';
            startBtn.disabled = true;
            stopBtn.disabled = true;
            restartBtn.disabled = true;
        } else {
            startBtn.disabled = false;
            stopBtn.disabled = true;
            restartBtn.disabled = true;
            restartBtn.textContent = 'Restart';
        }
    }

    function openHostModal(action) {
        pendingHostAction = action;
        const configs = {
            restart: {
                title: 'Restart Server',
                desc: `This will restart the entire Windows server in ${hostConfig.shutdownDelaySeconds} seconds. You can cancel from the dashboard until the timer expires.`,
                phrase: hostConfig.confirmPhraseRestart,
                btnClass: 'btn btn-warning',
                btnLabel: 'Confirm Restart Server'
            },
            shutdown: {
                title: 'Shutdown Server',
                desc: `This will shut down the entire Windows server in ${hostConfig.shutdownDelaySeconds} seconds. You can cancel from the dashboard until the timer expires.`,
                phrase: hostConfig.confirmPhraseShutdown,
                btnClass: 'btn btn-danger',
                btnLabel: 'Confirm Shutdown Server'
            },
            'rdp-recover': {
                title: 'Recover RDP Session',
                desc: 'Logs off active RDP sessions and restarts TermService. Use when the desktop is stuck on a black "Please wait" screen. Background services are not stopped.',
                phrase: rdpConfig.confirmPhraseRecover,
                btnClass: 'btn btn-warning',
                btnLabel: 'Confirm Recover RDP'
            },
            'rdp-apply': {
                title: 'Apply RDP Mitigations',
                desc: 'Sets fResetBroken=1 so broken RDP sessions reset automatically after TermService crashes. Safe to run repeatedly.',
                phrase: rdpConfig.confirmPhraseApplyMitigations,
                btnClass: 'btn btn-success',
                btnLabel: 'Confirm Apply Fix'
            }
        };

        const cfg = configs[action];
        if (!cfg) return;

        hostModalTitle.textContent = cfg.title;
        hostModalDesc.textContent = cfg.desc;
        hostModalExpected.textContent = cfg.phrase;
        hostModalPhrase.value = '';
        hostModalError.classList.add('hidden');
        hostModalError.textContent = '';
        hostModalConfirm.disabled = true;
        hostModalConfirm.className = cfg.btnClass;
        hostModalConfirm.textContent = cfg.btnLabel;
        hostModal.classList.remove('hidden');
        hostModalPhrase.focus();
    }

    function closeHostModal() {
        pendingHostAction = null;
        hostModal.classList.add('hidden');
        hostModalPhrase.value = '';
        hostModalError.classList.add('hidden');
    }

    function updateHostModalConfirmState() {
        if (!pendingHostAction) return;
        const phraseMap = {
            restart: hostConfig.confirmPhraseRestart,
            shutdown: hostConfig.confirmPhraseShutdown,
            'rdp-recover': rdpConfig.confirmPhraseRecover,
            'rdp-apply': rdpConfig.confirmPhraseApplyMitigations
        };
        const phraseRequired = phraseMap[pendingHostAction];
        const matches = hostModalPhrase.value.trim() === phraseRequired;
        hostModalConfirm.disabled = !matches;
        if (hostModalPhrase.value.length > 0 && !matches) {
            hostModalError.textContent = 'Phrase does not match yet.';
            hostModalError.classList.remove('hidden');
        } else {
            hostModalError.classList.add('hidden');
        }
    }

    async function submitHostModal() {
        if (!pendingHostAction || hostModalConfirm.disabled) return;
        const action = pendingHostAction;
        const phrase = hostModalPhrase.value.trim();
        closeHostModal();

        if (action === 'rdp-recover' || action === 'rdp-apply') {
            const endpoint = action === 'rdp-recover' ? 'recover' : 'apply-mitigations';
            addSystemLog(`RDP ${endpoint}...`);
            try {
                const response = await apiFetch(`/api/infrastructure/rdp/${endpoint}`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ confirmPhrase: phrase })
                });
                if (!response.ok) throw new Error(`HTTP error ${response.status}`);
                const result = await response.json();
                addSystemLog(result.message || `RDP ${endpoint} finished.`, !result.success);
                fetchRdpStatus();
            } catch (error) {
                addSystemLog(`RDP ${endpoint} failed: ${error.message}`, true);
            }
            return;
        }

        addSystemLog(`Scheduling host ${action}...`);
        try {
            const response = await apiFetch(`/api/host/${action}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ confirmPhrase: phrase })
            });
            if (!response.ok) throw new Error(`HTTP error ${response.status}`);
            const result = await response.json();
            addSystemLog(result.message || `Host ${action} scheduled.`, !result.success);
            fetchHostStatus();
        } catch (error) {
            addSystemLog(`Host ${action} failed: ${error.message}`, true);
        }
    }

    async function fetchRdpStatus() {
        try {
            const response = await apiFetch('/api/infrastructure/rdp/status');
            if (!response.ok) throw new Error('RDP status request failed');
            updateRdpCard(await response.json());
        } catch (error) {
            console.error('Error fetching RDP status:', error);
        }
    }

    function updateRdpCard(status) {
        const statusBadge = document.getElementById('status-rdp');
        const termSpan = document.getElementById('rdp-termservice');
        const fresetSpan = document.getElementById('rdp-freset');
        const sessionsSpan = document.getElementById('rdp-sessions');
        const crashesSpan = document.getElementById('rdp-crashes');
        const lastCrashSpan = document.getElementById('rdp-last-crash');
        const errorRow = document.getElementById('err-row-rdp');
        const errorMsg = document.getElementById('err-rdp');

        if (!statusBadge) return;

        if (status.confirmPhraseRecover) rdpConfig.confirmPhraseRecover = status.confirmPhraseRecover;
        if (status.confirmPhraseApplyMitigations) {
            rdpConfig.confirmPhraseApplyMitigations = status.confirmPhraseApplyMitigations;
        }

        statusBadge.className = 'status-badge';
        if (status.enabled === false) {
            statusBadge.classList.add('status-stopped');
            statusBadge.textContent = 'Disabled';
            return;
        }

        const healthy = !!status.healthy;
        if (healthy) {
            statusBadge.classList.add('status-running');
            statusBadge.textContent = 'Healthy';
        } else {
            statusBadge.classList.add('status-error');
            statusBadge.textContent = 'Degraded';
        }

        const ts = status.termService || {};
        if (termSpan) termSpan.textContent = ts.status || '-';
        if (fresetSpan) {
            const v = status.fResetBroken;
            fresetSpan.textContent = v === 1 ? 'Enabled' : (v === 0 ? 'Disabled' : String(v ?? '-'));
            fresetSpan.style.color = v === 1 ? COLOR_OK : COLOR_ERR;
        }

        const sessions = Array.isArray(status.sessions) ? status.sessions : [];
        const activeRdp = sessions.filter(s => (s.sessionName || '').toLowerCase().includes('rdp'));
        if (sessionsSpan) {
            sessionsSpan.textContent = activeRdp.length
                ? activeRdp.map(s => `#${s.id} ${s.state}`).join(', ')
                : (sessions.length ? `${sessions.length} total` : 'none');
        }

        if (crashesSpan) crashesSpan.textContent = status.crashCount24h != null ? status.crashCount24h : '-';

        const lc = status.lastRdpcoretsCrash;
        if (lastCrashSpan) {
            if (lc && lc.timeCreated) {
                const short = lc.timeCreated.replace('T', ' ').slice(0, 19);
                lastCrashSpan.textContent = short;
                lastCrashSpan.title = lc.message || lc.timeCreated;
            } else {
                lastCrashSpan.textContent = 'none recent';
                lastCrashSpan.title = '';
            }
        }

        const warnings = Array.isArray(status.warnings) ? status.warnings : [];
        if (status.error) warnings.push(status.error);
        if (warnings.length) {
            errorRow.style.display = 'flex';
            errorMsg.textContent = warnings.join('; ');
        } else {
            errorRow.style.display = 'none';
        }

        const enabled = status.enabled !== false;
        rdpRecoverBtn.disabled = !enabled;
        rdpMitigateBtn.disabled = !enabled;
    }

    async function fetchNginxStatus() {
        try {
            const response = await apiFetch('/api/infrastructure/nginx/status');
            if (!response.ok) throw new Error('nginx status request failed');
            updateNginxCard(await response.json());
        } catch (error) {
            console.error('Error fetching nginx status:', error);
        }
    }

    function updateNginxCard(status) {
        const statusBadge = document.getElementById('status-nginx');
        const homeSpan = document.getElementById('nginx-home');
        const portSpan = document.getElementById('nginx-port');
        const pidsSpan = document.getElementById('nginx-pids');
        const configSpan = document.getElementById('nginx-config');
        const errorRow = document.getElementById('err-row-nginx');
        const errorMsg = document.getElementById('err-nginx');
        const healthGrid = document.getElementById('nginx-health-grid');

        if (!statusBadge) return;

        statusBadge.className = 'status-badge';
        if (status.running) {
            statusBadge.classList.add('status-running');
            statusBadge.textContent = 'Running';
        } else {
            statusBadge.classList.add('status-stopped');
            statusBadge.textContent = 'Stopped';
        }

        if (homeSpan) homeSpan.textContent = status.home || '-';
        if (portSpan) portSpan.textContent = status.port != null ? status.port : '80';
        if (pidsSpan) {
            const pids = Array.isArray(status.pids) ? status.pids : [];
            pidsSpan.textContent = pids.length ? pids.join(', ') : '-';
        }

        const configOk = status.configTest && status.configTest.success;
        if (configSpan) {
            configSpan.textContent = configOk ? 'OK' : 'Failed';
            configSpan.style.color = configOk ? COLOR_OK : COLOR_ERR;
        }

        if (healthGrid) {
            const checks = Array.isArray(status.healthChecks) ? status.healthChecks : [];
            const upstream = status.upstreamChecks || {};
            const upstreamHtml = Object.entries(upstream).map(([key, up]) => {
                const labels = {
                    frontend4200: 'frontend :4200',
                    stackPilot8091: 'stack-pilot :8091',
                    backend8081: 'backend :8081'
                };
                const label = labels[key] || key;
                return `<span class="health-pill ${up ? 'up' : 'down'}">${label} ${up ? 'up' : 'down'}</span>`;
            }).join('');
            const checksHtml = checks.map(c => {
                const ok = c.success;
                const detail = ok ? (c.statusCode || 'OK') : (c.error || 'fail');
                return `<span class="health-pill ${ok ? 'up' : 'down'}">${c.name}: ${detail}</span>`;
            }).join('');
            healthGrid.innerHTML = checksHtml + upstreamHtml;
        }

        const failedChecks = (status.healthChecks || []).filter(c => !c.success);
        if (!status.running || !configOk || failedChecks.length > 0) {
            const parts = [];
            if (!status.running) parts.push('nginx is not running');
            if (!configOk) parts.push('config test failed');
            if (failedChecks.length) parts.push('health check failures: ' + failedChecks.map(c => c.name).join(', '));
            errorRow.style.display = 'flex';
            errorMsg.textContent = parts.join('; ');
        } else {
            errorRow.style.display = 'none';
        }

        nginxStartBtn.disabled = !!status.running;
        nginxStopBtn.disabled = !status.running;
        nginxReloadBtn.disabled = !status.running;
    }

    async function callNginxAction(action, label) {
        addSystemLog(`Triggered: ${label}...`);
        try {
            const response = await apiFetch(`/api/infrastructure/nginx/${action}`, { method: 'POST' });
            if (!response.ok) throw new Error(`HTTP error ${response.status}`);
            const result = await response.json();
            addSystemLog(result.message || `${label} finished.`, !result.success);
            fetchNginxStatus();
            if (activeTab === 'nginx-error' || activeTab === 'nginx-access') fetchLogs();
        } catch (error) {
            addSystemLog(`nginx ${action} failed: ${error.message}`, true);
        }
    }

    async function fetchHostStatus() {
        try {
            const response = await apiFetch('/api/host/status');
            if (!response.ok) throw new Error('host status request failed');
            const status = await response.json();
            if (status.confirmPhraseRestart) hostConfig.confirmPhraseRestart = status.confirmPhraseRestart;
            if (status.confirmPhraseShutdown) hostConfig.confirmPhraseShutdown = status.confirmPhraseShutdown;
            if (status.shutdownDelaySeconds) hostConfig.shutdownDelaySeconds = status.shutdownDelaySeconds;

            const delayLabel = document.getElementById('host-delay-label');
            if (delayLabel) delayLabel.textContent = hostConfig.shutdownDelaySeconds;

            const banner = document.getElementById('host-pending-banner');
            const text = document.getElementById('host-pending-text');
            const pending = status.pending || {};
            if (pending.active) {
                banner.style.display = 'flex';
                text.textContent = `${pending.action} scheduled - ${pending.remainingSeconds}s remaining`;
                hostRestartBtn.disabled = true;
                hostShutdownBtn.disabled = true;
            } else {
                banner.style.display = 'none';
                hostRestartBtn.disabled = !status.enabled;
                hostShutdownBtn.disabled = !status.enabled;
            }
        } catch (error) {
            console.error('Error fetching host status:', error);
        }
    }

    async function callHostCancel() {
        addSystemLog('Cancelling pending host action...');
        try {
            const response = await apiFetch('/api/host/cancel', { method: 'POST' });
            if (!response.ok) throw new Error(`HTTP error ${response.status}`);
            const result = await response.json();
            addSystemLog(result.message || 'Host action cancelled.', !result.success);
            fetchHostStatus();
        } catch (error) {
            addSystemLog(`Host cancel failed: ${error.message}`, true);
        }
    }

    async function callBulkAction(action, label) {
        addSystemLog(`Triggered: ${label}...`);
        try {
            const response = await apiFetch(`/api/services/bulk/${action}`, { method: 'POST' });
            if (!response.ok) throw new Error(`HTTP error ${response.status}`);
            const result = await response.json();
            if (result.results) {
                Object.entries(result.results).forEach(([name, detail]) => {
                    const ok = detail.success;
                    const msg = detail.errorMessage ? `: ${detail.errorMessage}` : '';
                    addSystemLog(`${name}: ${ok ? 'OK' : 'FAILED'}${msg}`, !ok);
                });
            }
            addSystemLog(result.success ? `${label} completed.` : `${label} finished with errors.`, !result.success);
            fetchStatus();
            fetchLogs();
        } catch (error) {
            addSystemLog(`Bulk action failed: ${error.message}`, true);
        }
    }

    async function callServiceAction(name, action) {
        addSystemLog(`Executing ${action} for service: ${name}...`);
        try {
            const response = await apiFetch(`/api/services/${name}/${action}`, { method: 'POST' });
            if (!response.ok) throw new Error(`HTTP error ${response.status}`);
            const result = await response.json();
            if (result.success) {
                addSystemLog(`Successfully triggered ${action} for ${name}.`);
            } else {
                const detail = result.errorMessage ? `: ${result.errorMessage}` : '';
                addSystemLog(`Failed to execute ${action} for ${name}${detail}`, true);
            }
            fetchStatus();
        } catch (error) {
            addSystemLog(`Network Error during ${action} for ${name}: ${error.message}`, true);
        }
    }

    async function fetchLogs() {
        if (!activeTab) return;

        try {
            let url;
            if (activeTab === 'stackpilot-actions') {
                url = '/api/manager/logs?tail=200';
            } else if (activeTab === 'nginx-error') {
                url = '/api/infrastructure/nginx/logs/error?tail=200';
            } else if (activeTab === 'nginx-access') {
                url = '/api/infrastructure/nginx/logs/access?tail=200';
            } else {
                url = `/api/services/${activeTab}/logs?tail=200`;
            }

            const response = await apiFetch(url);
            if (!response.ok) throw new Error('API logs request failed');
            const logLines = await response.json();
            const logText = logLines.join('\n');
            const currentHash = getSimpleHash(logText);
            if (currentHash === lastLogHash) return;
            lastLogHash = currentHash;

            if (logLines.length === 0) {
                terminalBody.innerHTML = `<div class="log-line system-msg">No logs available for ${escapeHtml(activeTab)}. Service might be stopped.</div>`;
            } else {
                terminalBody.innerHTML = logLines.map(line => {
                    let className = 'log-line';
                    if (line.includes('[ERROR]') || line.toLowerCase().includes('error') || line.toLowerCase().includes('exception')) {
                        className += ' error-log';
                    } else if (line.includes('[WARN]') || line.startsWith('[StackPilot]') || line.includes('[INFO]')) {
                        className += ' system-msg';
                    }
                    return `<div class="${className}">${escapeHtml(line)}</div>`;
                }).join('');
            }

            if (autoScrollCheck.checked) {
                terminalBody.scrollTop = terminalBody.scrollHeight;
            }
        } catch (error) {
            console.error('Error fetching logs:', error);
        }
    }

    function addSystemLog(message, isError = false) {
        const line = document.createElement('div');
        line.className = 'log-line ' + (isError ? 'error-log' : 'system-msg');
        line.textContent = `[MANAGER] ${new Date().toLocaleTimeString()} - ${message}`;
        terminalBody.appendChild(line);
        if (autoScrollCheck.checked) {
            terminalBody.scrollTop = terminalBody.scrollHeight;
        }
    }

    function getSimpleHash(str) {
        let hash = 0;
        for (let i = 0; i < str.length; i++) {
            hash = (hash << 5) - hash + str.charCodeAt(i);
            hash |= 0;
        }
        return hash.toString();
    }
});
