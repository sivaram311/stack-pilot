document.addEventListener('DOMContentLoaded', () => {
    // State management
    let activeTab = 'python-downloader';
    let lastLogHash = '';
    let pollingInterval = null;
    let services = [];
    let hostConfig = { confirmPhraseRestart: 'RESTART SERVER', confirmPhraseShutdown: 'SHUTDOWN SERVER', shutdownDelaySeconds: 60 };

    // DOM Elements
    const tabButtons = document.querySelectorAll('.tab-btn');
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

    // Initial load
    init();

    function init() {
        setupEventListeners();
        fetchStatus();
        fetchNginxStatus();
        fetchHostStatus();
        fetchLogs();
        
        // Start polling status and active logs every 1.5 seconds
        pollingInterval = setInterval(() => {
            fetchStatus();
            fetchNginxStatus();
            fetchHostStatus();
            fetchLogs();
        }, 1500);
    }

    function setupEventListeners() {
        // Tab switching
        tabButtons.forEach(btn => {
            btn.addEventListener('click', (e) => {
                tabButtons.forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                activeTab = btn.getAttribute('data-tab');
                
                // Clear state so it updates immediately on next poll
                lastLogHash = '';
                terminalBody.innerHTML = `<div class="log-line system-msg">Switched tab to ${activeTab}. Loading logs...</div>`;
                fetchLogs();
            });
        });

        // Single service actions (Start, Stop, Restart)
        document.querySelectorAll('.start-btn').forEach(btn => {
            btn.addEventListener('click', () => callServiceAction(btn.dataset.service, 'start'));
        });
        document.querySelectorAll('.stop-btn').forEach(btn => {
            btn.addEventListener('click', () => callServiceAction(btn.dataset.service, 'stop'));
        });
        document.querySelectorAll('.restart-btn').forEach(btn => {
            btn.addEventListener('click', () => callServiceAction(btn.dataset.service, 'restart'));
        });

        // Bulk actions
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

        hostRestartBtn.addEventListener('click', () => callHostAction('restart'));
        hostShutdownBtn.addEventListener('click', () => callHostAction('shutdown'));
        hostCancelBtn.addEventListener('click', () => callHostCancel());

        // Utility actions
        clearLogsBtn.addEventListener('click', () => {
            terminalBody.innerHTML = '<div class="log-line system-msg">Log terminal view cleared.</div>';
            lastLogHash = '';
        });
    }

    // Fetch states of all services and update cards
    async function fetchStatus() {
        try {
            const response = await fetch('/api/services');
            if (!response.ok) throw new Error('API request failed');
            
            const servicesData = await response.json();
            services = servicesData.map(service => service.name);
            servicesData.forEach(service => {
                updateServiceCard(service);
            });
        } catch (error) {
            console.error('Error fetching service status:', error);
        }
    }

    // Update single service card elements
    function updateServiceCard(service) {
        const idPrefix = service.name;
        const statusBadge = document.getElementById(`status-${idPrefix}`);
        const portSpan = document.getElementById(`port-${idPrefix}`);
        const pidSpan = document.getElementById(`pid-${idPrefix}`);
        const errorRow = document.getElementById(`err-row-${idPrefix}`);
        const errorMsg = document.getElementById(`err-${idPrefix}`);
        
        if (!statusBadge) return;

        // Clean classes and set badge text
        statusBadge.className = 'status-badge';
        const displayStatus = service.status === 'RUNNING_EXTERNAL' ? 'External' : service.status;
        statusBadge.textContent = displayStatus;
        
        // Status specific styles
        switch(service.status) {
            case 'RUNNING':
                statusBadge.classList.add('status-running');
                break;
            case 'RUNNING_EXTERNAL':
                statusBadge.classList.add('status-external');
                break;
            case 'STARTING':
                statusBadge.classList.add('status-starting');
                break;
            case 'STOPPED':
                statusBadge.classList.add('status-stopped');
                break;
            case 'ERROR':
                statusBadge.classList.add('status-error');
                break;
        }

        // Update port
        if (portSpan) {
            portSpan.textContent = service.port != null ? service.port : 'N/A';
        }

        // Update PID
        pidSpan.textContent = service.pid && service.pid > 0 ? service.pid : '-';

        // Update Error / info message
        if (service.errorMessage) {
            errorRow.style.display = 'flex';
            errorMsg.textContent = service.errorMessage;
        } else {
            errorRow.style.display = 'none';
        }

        // Toggle button states based on status
        const card = document.getElementById(`card-${idPrefix}`);
        if (card) {
            const startBtn = card.querySelector('.start-btn');
            const stopBtn = card.querySelector('.stop-btn');
            const restartBtn = card.querySelector('.restart-btn');

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
            } else { // STOPPED or ERROR
                startBtn.disabled = false;
                stopBtn.disabled = true;
                restartBtn.disabled = true;
                restartBtn.textContent = 'Restart';
            }
        }
    }

    async function fetchNginxStatus() {
        try {
            const response = await fetch('/api/infrastructure/nginx/status');
            if (!response.ok) throw new Error('nginx status request failed');
            const status = await response.json();
            updateNginxCard(status);
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
            if (activeTab === 'nginx-error' || activeTab === 'nginx-access') {
                fetchLogs();
            }
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
                text.textContent = `${pending.action} scheduled — ${pending.remainingSeconds}s remaining`;
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

    async function callHostAction(action) {
        const isRestart = action === 'restart';
        const phraseRequired = isRestart ? hostConfig.confirmPhraseRestart : hostConfig.confirmPhraseShutdown;
        const warning = isRestart
            ? `This will RESTART the entire Windows server in ${hostConfig.shutdownDelaySeconds} seconds.\n\nType the confirmation phrase exactly:`
            : `This will SHUTDOWN the entire Windows server in ${hostConfig.shutdownDelaySeconds} seconds.\n\nType the confirmation phrase exactly:`;

        const typed = prompt(warning, '');
        if (typed === null) return;
        if (typed.trim() !== phraseRequired) {
            addSystemLog(`Host ${action} cancelled: confirmation phrase did not match.`, true);
            return;
        }

        if (!confirm(`Final confirmation: schedule server ${action} in ${hostConfig.shutdownDelaySeconds} seconds?`)) {
            return;
        }

        addSystemLog(`Scheduling host ${action}...`);
        try {
            const response = await fetch(`/api/host/${action}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ confirmPhrase: typed.trim() })
            });
            if (!response.ok) throw new Error(`HTTP error ${response.status}`);
            const result = await response.json();
            addSystemLog(result.message || `Host ${action} scheduled.`, !result.success);
            fetchHostStatus();
        } catch (error) {
            addSystemLog(`Host ${action} failed: ${error.message}`, true);
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

    // Call service endpoints (start, stop, restart)
    async function callServiceAction(name, action) {
        addSystemLog(`Executing ${action} for service: ${name}...`);
        try {
            const response = await fetch(`/api/services/${name}/${action}`, {
                method: 'POST'
            });
            if (!response.ok) throw new Error(`HTTP error ${response.status}`);
            
            const result = await response.json();
            if (result.success) {
                addSystemLog(`Successfully triggered ${action} for ${name}.`);
                fetchStatus();
            } else {
                const detail = result.errorMessage ? `: ${result.errorMessage}` : '';
                addSystemLog(`Failed to execute ${action} for ${name}${detail}`, true);
                fetchStatus();
            }
        } catch (error) {
            console.error(`Error calling ${action} for service ${name}:`, error);
            addSystemLog(`Network Error during ${action} for ${name}: ${error.message}`, true);
        }
    }

    // Fetch and display logs for the active tab
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
            
            // Optimization: Generate hash of log text to check for changes
            const logText = logLines.join('\n');
            const currentHash = getSimpleHash(logText);
            if (currentHash === lastLogHash) {
                return; // Nothing changed, skip render
            }
            lastLogHash = currentHash;

            // Render logs
            if (logLines.length === 0) {
                terminalBody.innerHTML = `<div class="log-line system-msg">No logs available for ${activeTab}. Service might be stopped.</div>`;
            } else {
                terminalBody.innerHTML = logLines.map(line => {
                    let className = 'log-line';
                    if (line.includes('[ERROR]') || line.toLowerCase().includes('error') || line.toLowerCase().includes('exception')) {
                        className += ' error-log';
                    } else if (line.includes('[WARN]')) {
                        className += ' system-msg';
                    } else if (line.startsWith('[StackPilot]') || line.includes('[INFO]')) {
                        className += ' system-msg';
                    }
                    // Clean line HTML escaping
                    const escaped = line
                        .replace(/&/g, "&amp;")
                        .replace(/</g, "&lt;")
                        .replace(/>/g, "&gt;");
                    return `<div class="${className}">${escaped}</div>`;
                }).join('');
            }

            // Scroll if checked
            if (autoScrollCheck.checked) {
                terminalBody.scrollTop = terminalBody.scrollHeight;
            }
        } catch (error) {
            console.error('Error fetching logs:', error);
        }
    }

    // Utility: add message to terminal directly
    function addSystemLog(message, isError = false) {
        const line = document.createElement('div');
        line.className = 'log-line ' + (isError ? 'error-log' : 'system-msg');
        line.textContent = `[MANAGER] ${new Date().toLocaleTimeString()} - ${message}`;
        terminalBody.appendChild(line);
        if (autoScrollCheck.checked) {
            terminalBody.scrollTop = terminalBody.scrollHeight;
        }
    }

    // Helper: simple fast string hash
    function getSimpleHash(str) {
        let hash = 0;
        for (let i = 0; i < str.length; i++) {
            hash = (hash << 5) - hash + str.charCodeAt(i);
            hash |= 0; // Convert to 32bit integer
        }
        return hash.toString();
    }
});
