/* Telemetry Collection JavaScript Functions */

// Telemetry state
let telemetryCollecting = false;
let telemetryStats = { packets_stored: 0, collecting: false };

// Toggle telemetry panel visibility
function toggleTelemetryPanel() {
    const panel = document.getElementById('telemetry-panel');
    panel.classList.toggle('open');
}

// Update telemetry stats periodically
setInterval(async () => {
    if (telemetryCollecting) {
        try {
            const response = await fetch('http://localhost:9000/api/telemetry/stats');
            telemetryStats = await response.json();
            updateTelemetryUI();
        } catch (error) {
            // Silently fail
        }
    }
}, 1000);

function updateTelemetryUI() {
    const btn = document.getElementById('telemetry-toggle');
    const stats = document.getElementById('telemetry-stats');
    
    if (telemetryStats.collecting) {
        btn.textContent = '⏹️ Stop Recording';
        btn.style.background = 'linear-gradient(135deg, #f00 0%, #c00 100%)';
        
        let statsText = `📊 Recording: ${telemetryStats.packets_stored} packets`;
        if (telemetryStats.duration_seconds) {
            statsText += ` (${telemetryStats.duration_seconds.toFixed(1)}s)`;
        }
        stats.textContent = statsText;
        stats.style.display = 'block';
    } else {
        btn.textContent = '⏺️ Start Recording';
        btn.style.background = 'linear-gradient(135deg, #0f0 0%, #0c0 100%)';
        
        if (telemetryStats.packets_stored > 0) {
            stats.textContent = `📊 ${telemetryStats.packets_stored} packets collected`;
            stats.style.display = 'block';
        } else {
            stats.style.display = 'none';
        }
    }
}

async function toggleTelemetryRecording() {
    try {
        if (telemetryCollecting) {
            // Stop recording
            const response = await fetch('http://localhost:9000/api/telemetry/stop', {
                method: 'POST'
            });
            telemetryCollecting = false;
            log('⏹️ Telemetry recording stopped', 'info');
        } else {
            // Start recording
            const response = await fetch('http://localhost:9000/api/telemetry/start', {
                method: 'POST'
            });
            telemetryCollecting = true;
            log('⏺️ Telemetry recording started', 'info');
        }
        
        // Update stats immediately
        const statsResponse = await fetch('http://localhost:9000/api/telemetry/stats');
        telemetryStats = await statsResponse.json();
        updateTelemetryUI();
        
    } catch (error) {
        log('❌ Error toggling telemetry: ' + error.message, 'error');
    }
}

async function downloadTelemetry(format) {
    try {
        log(`📥 Downloading telemetry as ${format.toUpperCase()}...`, 'info');
        
        const url = `http://localhost:9000/api/telemetry/download/${format}`;
        const response = await fetch(url);
        
        if (!response.ok) {
            throw new Error('Download failed');
        }
        
        const blob = await response.blob();
        const downloadUrl = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.style.display = 'none';
        a.href = downloadUrl;
        
        const timestamp = new Date().toISOString().replace(/:/g, '-').split('.')[0];
        const extensions = { json: 'json', csv: 'csv', raw: 'bin' };
        a.download = `telemetry_${timestamp}.${extensions[format]}`;
        
        document.body.appendChild(a);
        a.click();
        window.URL.revokeObjectURL(downloadUrl);
        document.body.removeChild(a);
        
        log(`✅ Downloaded ${blob.size} bytes as ${format.toUpperCase()}`, 'info');
        
    } catch (error) {
        log('❌ Error downloading telemetry: ' + error.message, 'error');
    }
}

async function clearTelemetry() {
    if (!confirm('Clear all collected telemetry data?')) {
        return;
    }
    
    try {
        await fetch('http://localhost:9000/api/telemetry/clear', {
            method: 'POST'
        });
        
        telemetryStats = { packets_stored: 0, collecting: false };
        updateTelemetryUI();
        log('🗑️ Telemetry data cleared', 'info');
        
    } catch (error) {
        log('❌ Error clearing telemetry: ' + error.message, 'error');
    }
}
