document.addEventListener('DOMContentLoaded', () => {
    let activeTab = null;
    let lastLogHash = '';
    let pollingInterval = null;
    let services = [];
    let renderedServiceKey = '';
    let pendingHostAction = null;
    let hostConfig = {
        confirmPhraseRestart: 'RESTART SERVER',
        confirmPhraseShutdown: 'SHUTDOWN SERVER',
        shutdownDelaySeconds: 60
    };

    const LOG_TAB_LABELS = {
        'python-downloader': 'Downloader',
        'python-order-rsi': 'Order RSI',
        'backend': 'Backend',
        'frontend': 'Frontend',
        'nginx-error': 'NGINX Error',
        'nginx-access': 'NGINX Access',
        'stackpilot-actions': 'Actions'
    };

    const servicesGrid = document.getElementById('services-grid');
    const logsTabs = document.getElementById('logs-tabs');
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

    const hostModal = document.getElementById('host-modal');
    const hostModalTitle = document.getElementById('host-modal-title');
    const hostModalDesc = document.getElementById('host-modal-desc');
    const hostModalPhrase = document.getElementById('host-modal-phrase');
    const hostModalExpected = document.getElementById('host-modal-expected');
    const hostModalError = document.getElementById('host-modal-error');
    const hostModalConfirm = document.getElementById('host-modal-confirm');
    const hostModalCancel = document.getElementById('host-modal-cancel');
    const hostModalBackdrop = document.getElementById('host-modal-backdrop');

    init();

    function init() {
        setupEventListeners();
        fetchStatus();
        fetchNginxStatus();
        fetchHostStatus();
        fetchLogs();

        pollingInterval = setInterval(() => {
            fetchStatus();
            fetchNginxStatus();
            fetchHostStatus();
            fetchLogs();
        }, 1500);
    }

    function setupEventListeners() {
        servicesGrid.addEventListener('click', (e) => {
            const btn = e.target.closest('button[data-service][data-action]');
            if (!btn || btn.disabled) return;
            callServiceAction(btn.dataset.service, btn.dataset.action);
        });

        logsTabs.addEventListener('click', (e) => {
            const btn = e.target.closest('.tab-btn');
            if (!btn) return;
            switchLogTab(btn.getAttribute('data-tab'));
        });

        startAllBtn.addEventListener('click', () => callBulkAction('start-all', 'Starting all services'));
        restartAllBtn.addEventListener('click', () => {
            if (confirm('Kill all external/managed grok-dev processes and relaunch under StackPilot?')) {
                callBulkAction('restart-all', 'Restarting all services (take control)');
            }
        });
        stopAllBtn.addEventListener('click', () => callBulkAction('stop-all', 'Stopping all services'));

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

        hostModalCancel.addEventListener('click', closeHostModal);
        hostModalBackdrop.addEventListener('click', closeHostModal);
        hostModalConfirm.addEventListener('click', submitHostModal);
        hostModalPhrase.addEventListener('input', updateHostModalConfirmState);
        hostModalPhrase.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !hostModalConfirm.disabled) submitHostModal();
            if (e.key === 'Escape') closeHostModal();
        });

        clearLogsBtn.addEventListener('click', () => {
            terminalBody.innerHTML = '<div class="log-line system-msg">Log terminal view cleared.</div>';
            lastLogHash = '';
        });
    }

    function formatDisplayName(name) {
        const labels = {
            'python-downloader': 'Python Downloader',
            'python-order-rsi': 'Python Order RSI',
            'backend': 'Backend Service',
            'frontend': 'Frontend UI'
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

    function buildServiceCardHtml(service) {
        const name = service.name;
        const display = formatDisplayName(name);
        const dir = shortPath(service.workingDir);
        const port = service.port != null ? service.port : 'N/A';
        return `
            <div class="card" id="card-${escapeHtml(name)}" data-service="${escapeHtml(name)}">
                <div class="card-header">
                    <h3>${escapeHtml(display)}</h3>
                    <span class="status-badge status-stopped" id="status-${escapeHtml(name)}">Stopped</span>
                </div>
                <div class="card-body">
                    <div class="info-row">
                        <span class="label">Dir:</span>
                        <span class="value path" title="${escapeHtml(service.workingDir || '')}">${escapeHtml(dir)}</span>
                    </div>
                    <div class="info-row">
                        <span class="label">Cmd:</span>
                        <span class="value path" title="${escapeHtml(service.command || '')}">${escapeHtml((service.command || '-').slice(0, 40))}${(service.command || '').length > 40 ? '...' : ''}</span>
                    </div>
                    <div class="info-row">
                        <span class="label">Port:</span>
                        <span class="value" id="port-${escapeHtml(name)}">${escapeHtml(port)}</span>
                    </div>
                    <div class="info-row">
                        <span class="label">PID:</span>
                        <span class="value pid" id="pid-${escapeHtml(name)}">-</span>
                    </div>
                    <div class="info-row error-row" id="err-row-${escapeHtml(name)}" style="display: none;">
                        <span class="label">Error:</span>
                        <span class="value error-msg" id="err-${escapeHtml(name)}"></span>
                    </div>
                </div>
                <div class="card-actions">
                    <button class="btn btn-card btn-success start-btn" data-service="${escapeHtml(name)}" data-action="start" type="button">Start</button>
                    <button class="btn btn-card btn-warning restart-btn" data-service="${escapeHtml(name)}" data-action="restart" type="button">Restart</button>
                    <button class="btn btn-card btn-danger stop-btn" data-service="${escapeHtml(name)}" data-action="stop" type="button">Stop</button>
                </div>
            </div>`;
    }

    function renderServiceCards(servicesData) {
        const key = servicesData.map(s => s.name).join('|');
        if (key !== renderedServiceKey) {
            renderedServiceKey = key;
            servicesGrid.innerHTML = servicesData.map(buildServiceCardHtml).join('');
            renderLogTabs(servicesData.map(s => s.name));
        }
        servicesData.forEach(updateServiceCard);
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
        if (!tab || tab === activeTab) return;
        activeTab = tab;
        logsTabs.querySelectorAll('.tab-btn').forEach(btn => {
            btn.classList.toggle('active', btn.getAttribute('data-tab') === tab);
        });
        lastLogHash = '';
        terminalBody.innerHTML = `<div class="log-line system-msg">Switched tab to ${tab}. Loading logs...</div>`;
        fetchLogs();
    }

    async function fetchStatus() {
        try {
            const response = await fetch('/api/services');
            if (!response.ok) throw new Error('API request failed');
            const servicesData = await response.json();
            services = servicesData.map(s => s.name);
            renderServiceCards(servicesData);
        } catch (error) {
            console.error('Error fetching service status:', error);
            if (!renderedServiceKey) {
                servicesGrid.innerHTML = '<div class="card card-loading"><p class="error-msg">Failed to load services.</p></div>';
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
        const isRestart = action === 'restart';
        const phraseRequired = isRestart ? hostConfig.confirmPhraseRestart : hostConfig.confirmPhraseShutdown;
        const title = isRestart ? 'Restart Server' : 'Shutdown Server';

        hostModalTitle.textContent = title;
        hostModalDesc.textContent = `This will ${isRestart ? 'restart' : 'shut down'} the entire Windows server in ${hostConfig.shutdownDelaySeconds} seconds. You can cancel from the dashboard until the timer expires.`;
        hostModalExpected.textContent = phraseRequired;
        hostModalPhrase.value = '';
        hostModalError.classList.add('hidden');
        hostModalError.textContent = '';
        hostModalConfirm.disabled = true;
        hostModalConfirm.className = isRestart ? 'btn btn-warning' : 'btn btn-danger';
        hostModalConfirm.textContent = `Confirm ${title}`;
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
        const isRestart = pendingHostAction === 'restart';
        const phraseRequired = isRestart ? hostConfig.confirmPhraseRestart : hostConfig.confirmPhraseShutdown;
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

        addSystemLog(`Scheduling host ${action}...`);
        try {
            const response = await fetch(`/api/host/${action}`, {
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

    async function fetchNginxStatus() {
        try {
            const response = await fetch('/api/infrastructure/nginx/status');
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
            configSpan.style.color = configOk ? '#34d399' : '#f87171';
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
            const response = await fetch(`/api/infrastructure/nginx/${action}`, { method: 'POST' });
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
            const response = await fetch('/api/host/status');
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
            const response = await fetch('/api/host/cancel', { method: 'POST' });
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
            const response = await fetch(`/api/services/bulk/${action}`, { method: 'POST' });
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
            const response = await fetch(`/api/services/${name}/${action}`, { method: 'POST' });
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

            const response = await fetch(url);
            if (!response.ok) throw new Error('API logs request failed');
            const logLines = await response.json();
            const logText = logLines.join('\n');
            const currentHash = getSimpleHash(logText);
            if (currentHash === lastLogHash) return;
            lastLogHash = currentHash;

            if (logLines.length === 0) {
                terminalBody.innerHTML = `<div class="log-line system-msg">No logs available for ${activeTab}. Service might be stopped.</div>`;
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
