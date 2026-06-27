document.addEventListener('DOMContentLoaded', () => {
    // State management
    let activeTab = 'python-downloader';
    let lastLogHash = '';
    let pollingInterval = null;
    let services = [];

    // DOM Elements
    const tabButtons = document.querySelectorAll('.tab-btn');
    const terminalBody = document.getElementById('terminal-body');
    const autoScrollCheck = document.getElementById('chk-autoscroll');
    const clearLogsBtn = document.getElementById('btn-clear-logs');
    const startAllBtn = document.getElementById('btn-start-all');
    const restartAllBtn = document.getElementById('btn-restart-all');
    const stopAllBtn = document.getElementById('btn-stop-all');

    // Initial load
    init();

    function init() {
        setupEventListeners();
        fetchStatus();
        fetchLogs();
        
        // Start polling status and active logs every 1.5 seconds
        pollingInterval = setInterval(() => {
            fetchStatus();
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
            const url = activeTab === 'stackpilot-actions'
                ? '/api/manager/logs?tail=200'
                : `/api/services/${activeTab}/logs?tail=200`;
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
