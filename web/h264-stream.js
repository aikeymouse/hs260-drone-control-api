let ws = null;
let packetCount = 0;
let totalBytes = 0;
let lastBytes = 0;
let startTime = Date.now();
let lastUpdateTime = Date.now();
let decoder = null;
let frameCount = 0;
let spsData = null;
let ppsData = null;
let decoderConfigured = false;
let waitingForKeyframe = false;
let showVideoLogs = false;

const canvas = document.getElementById('canvas');
const ctx = canvas.getContext('2d');

// Draw placeholder
ctx.fillStyle = '#000';
ctx.fillRect(0, 0, canvas.width, canvas.height);
ctx.fillStyle = '#fff';
ctx.font = '24px Arial';
ctx.textAlign = 'center';
ctx.fillText('Waiting for stream...', canvas.width / 2, canvas.height / 2);

// Initialize decoder
function initDecoder() {
    if (!('VideoDecoder' in window)) {
        log('❌ WebCodecs not supported - will only show packets', 'warn');
        return;
    }
    
    log('✅ WebCodecs API available - using native H.264 decoder', 'info');
    
    decoder = new VideoDecoder({
        output: (frame) => {
            try {
                // Draw decoded frame to canvas
                ctx.drawImage(frame, 0, 0, canvas.width, canvas.height);
                frameCount++;
                document.getElementById('framesDecoded').textContent = frameCount;
                
                if (frameCount === 1) {
                    log('🎬 First frame decoded and rendered!', 'info');
                    log(`   Frame size: ${frame.displayWidth}x${frame.displayHeight}`, 'info');
                }
                if (frameCount % 30 === 0) {
                    log(`✅ Decoded ${frameCount} frames`, 'info');
                }
            } catch (e) {
                log('❌ Frame output error: ' + e.message, 'error');
            } finally {
                frame.close();
            }
        },
        error: (e) => {
            log('❌ Decoder error: ' + e.message, 'error');
            log('   Error name: ' + e.name, 'error');
            log('   Decoder state: ' + decoder.state, 'error');
            console.error('Decoder error details:', e);
            
            // Don't recreate immediately to avoid infinite loop
            if (decoder.state === 'closed') {
                log('⚠️ Decoder closed due to error', 'warn');
                decoderConfigured = false;
                decoder = null;
                // Will recreate on next valid SPS/PPS
            }
        }
    });
    
    log('🎥 H.264 decoder created (waiting for SPS/PPS)', 'info');
}

initDecoder();

function toggleVideoLogs() {
    showVideoLogs = document.getElementById('videoLogsToggle').checked;
    
    // Re-filter existing log entries
    const logEl = document.getElementById('log');
    const entries = logEl.querySelectorAll('.log-entry');
    entries.forEach(entry => {
        const isVideoLog = entry.hasAttribute('data-video-log');
        if (isVideoLog) {
            entry.style.display = showVideoLogs ? 'block' : 'none';
        }
    });
}

function log(message, type = 'info') {
    const logEl = document.getElementById('log');
    const entry = document.createElement('div');
    entry.className = 'log-entry';
    
    // Mark video-related logs
    const isVideoLog = message.includes('frame') || 
                       message.includes('Frame') || 
                       message.includes('Decoded') || 
                       message.includes('SPS') || 
                       message.includes('PPS') || 
                       message.includes('NAL') ||
                       message.includes('codec') ||
                       message.includes('Decoder') ||
                       message.includes('decoder');
    
    if (isVideoLog) {
        entry.setAttribute('data-video-log', 'true');
        if (!showVideoLogs) {
            entry.style.display = 'none';
        }
    }
    
    const time = new Date().toLocaleTimeString();
    entry.innerHTML = `<span class="log-time">[${time}]</span> <span class="log-${type}">${message}</span>`;
    
    logEl.appendChild(entry);
    logEl.scrollTop = logEl.scrollHeight;
    
    // Keep only last 50 entries
    while (logEl.children.length > 50) {
        logEl.removeChild(logEl.firstChild);
    }
}

function updateStatus(status, indicatorClass) {
    document.getElementById('status').textContent = status;
    const indicator = document.getElementById('indicator');
    indicator.className = 'status-indicator ' + indicatorClass;
}

function connect() {
    log('Connecting to ws://localhost:9000/stream...', 'info');
    updateStatus('Connecting...', 'status-connecting');
    
    try {
        ws = new WebSocket('ws://localhost:9000/stream');
        ws.binaryType = 'arraybuffer';
        
        ws.onopen = () => {
            log('✅ WebSocket connected!', 'info');
            updateStatus('Connected', 'status-connected');
            startTime = Date.now();
            packetCount = 0;
            totalBytes = 0;
            frameCount = 0;
            
            // Show waiting message on canvas using same style as initial placeholder
            ctx.fillStyle = '#000';
            ctx.fillRect(0, 0, canvas.width, canvas.height);
            ctx.fillStyle = '#fff';
            ctx.font = '24px Arial';
            ctx.textAlign = 'center';
            ctx.fillText('Waiting for stream...', canvas.width / 2, canvas.height / 2);
        };
        
        ws.onmessage = (event) => {
            const data = new Uint8Array(event.data);
            packetCount++;
            totalBytes += data.length;
            
            // Clear placeholder on first packet
            if (packetCount === 1) {
                ctx.fillStyle = '#000';
                ctx.fillRect(0, 0, canvas.width, canvas.height);
                ctx.fillStyle = '#0f0';
                ctx.font = '20px monospace';
                ctx.fillText('Processing stream...', 20, canvas.height / 2);
            }
            
            // Log first few packets with detailed analysis
            if (packetCount <= 10) {
                log(`📦 Received packet #${packetCount}: ${data.length} bytes`, 'info');
                // Show hex dump of first 40 bytes
                const hex = Array.from(data.slice(0, 40))
                    .map(b => b.toString(16).padStart(2, '0'))
                    .join(' ');
                log(`   Hex: ${hex}`, 'info');
                
                // Check for Annex B start codes
                let foundStartCodes = 0;
                for (let i = 0; i < Math.min(100, data.length - 4); i++) {
                    if (data[i] === 0 && data[i+1] === 0 && data[i+2] === 0 && data[i+3] === 1) {
                        const nalType = data[i+4] & 0x1F;
                        log(`   Start code at offset ${i}, NAL type: ${nalType}`, 'info');
                        foundStartCodes++;
                    }
                }
                if (foundStartCodes === 0) {
                    log(`   No Annex B start codes found in first 100 bytes`, 'warn');
                }
            }
            
            // These packets don't have Annex B start codes (00 00 00 01)
            // They appear to be raw NAL units, possibly with length prefix
            // Try to parse and add Annex B format
            
            let nalUnits = [];
            let offset = 0;
            
            // Try parsing as length-prefixed NAL units (4-byte big-endian length)
            while (offset + 4 < data.length) {
                const length = (data[offset] << 24) | (data[offset+1] << 16) | 
                              (data[offset+2] << 8) | data[offset+3];
                
                if (length > 0 && length <= 100000 && offset + 4 + length <= data.length) {
                    const nalUnit = data.slice(offset + 4, offset + 4 + length);
                    nalUnits.push(nalUnit);
                    offset += 4 + length;
                    
                    if (packetCount <= 3) {
                        const nalType = nalUnit[0] & 0x1F;
                        log(`   Parsed NAL: offset=${offset-4-length}, length=${length}, type=${nalType}`, 'info');
                    }
                } else {
                    // Invalid length, might not be length-prefixed
                    break;
                }
            }
            
            // If parsing failed or no NAL units found, treat entire packet as one NAL
            if (nalUnits.length === 0) {
                if (packetCount <= 3) {
                    log(`   Not length-prefixed, using entire packet as NAL`, 'info');
                }
                nalUnits.push(data);
            }
            
            // Process each NAL unit
            for (const nalUnit of nalUnits) {
                const nalType = nalUnit[0] & 0x1F;
                const nalNames = {
                    1: 'P-frame',
                    5: 'IDR (I-frame)',
                    6: 'SEI',
                    7: 'SPS',
                    8: 'PPS',
                    9: 'AUD'
                };
                
                if (packetCount <= 10 || nalType === 7 || nalType === 8 || nalType === 5) {
                    log(`📺 NAL Type ${nalType}: ${nalNames[nalType] || 'Unknown'} (${nalUnit.length} bytes)`, 'info');
                }
                
                // Extract SPS
                if (nalType === 7) {
                    spsData = nalUnit;
                    log('📋 SPS extracted (' + spsData.length + ' bytes)', 'info');
                }
                
                // Extract PPS
                if (nalType === 8) {
                    ppsData = nalUnit;
                    log('📋 PPS extracted (' + ppsData.length + ' bytes)', 'info');
                }
                
                // Configure decoder once we have both SPS and PPS
                if (spsData && ppsData && !decoderConfigured) {
                    // Recreate decoder if it was closed due to error
                    if (!decoder || decoder.state === 'closed') {
                        log('🔄 Recreating decoder...', 'info');
                        initDecoder();
                    }
                    
                    if (decoder) {
                        try {
                            // Create avcC-style description box
                            const description = createAVCDecoderConfig(spsData, ppsData);
                            
                            log(`🔧 Configuring decoder with:`, 'info');
                            log(`   SPS: ${spsData.length} bytes`, 'info');
                            log(`   PPS: ${ppsData.length} bytes`, 'info');
                            log(`   Profile: ${spsData[1].toString(16)}, Level: ${spsData[3].toString(16)}`, 'info');
                            
                            decoder.configure({
                                codec: 'avc1.42E01E',
                                codedWidth: 1280,
                                codedHeight: 720,
                                description: description,
                                hardwareAcceleration: 'prefer-hardware'
                            });
                            
                            decoderConfigured = true;
                            waitingForKeyframe = true;
                            log('✅ Decoder configured with SPS/PPS! Waiting for I-frame...', 'info');
                        } catch (e) {
                            log('❌ Failed to configure decoder: ' + e.message, 'error');
                            log('   Stack: ' + e.stack, 'error');
                            console.error('Configure error:', e);
                        }
                    }
                }
                
                // Decode frames (not SPS/PPS/SEI) - must wait for keyframe after configure
                if (decoder && decoder.state === 'configured' && decoderConfigured && nalType !== 7 && nalType !== 8 && nalType !== 6 && nalType !== 9) {
                    // Skip frames until we get a keyframe (I-frame)
                    if (waitingForKeyframe && nalType !== 5) {
                        if (packetCount % 30 === 0) {
                            log(`⏳ Waiting for I-frame (got NAL type ${nalType})`, 'warn');
                        }
                        continue; // Skip non-keyframes
                    }
                    
                    if (waitingForKeyframe && nalType === 5) {
                        waitingForKeyframe = false;
                        log('🎬 Got I-frame! Starting decode...', 'info');
                    }
                    
                    try {
                        // Convert to length-prefixed format (avcC mode with description box)
                        const nalData = toLengthPrefixed(nalUnit);
                        
                        // Feed length-prefixed NAL unit to decoder
                        const chunk = new EncodedVideoChunk({
                            type: (nalType === 5) ? 'key' : 'delta',
                            timestamp: packetCount * 33333,
                            data: nalData
                        });
                        
                        if (nalType === 5 && frameCount < 5) {
                            log(`🎬 Submitting I-frame for decode (${nalUnit.length} bytes)`, 'info');
                            log(`   Chunk type: ${chunk.type}, timestamp: ${chunk.timestamp}`, 'info');
                            log(`   Decoder state before: ${decoder.state}`, 'info');
                        }
                        
                        decoder.decode(chunk);
                        
                        if (nalType === 5 && frameCount < 5) {
                            log(`   Decoder state after: ${decoder.state}, queue: ${decoder.decodeQueueSize}`, 'info');
                        }
                        
                        if (decoder && decoder.decodeQueueSize > 20) {
                            log(`⚠️ Decode queue backing up: ${decoder.decodeQueueSize}`, 'warn');
                        }
                    } catch (e) {
                        log('❌ Decode error: ' + e.message, 'error');
                        log('   NAL type: ' + nalType + ', length: ' + nalUnit.length, 'error');
                        console.error(e);
                    }
                }
            }
            
            // Update stats
            document.getElementById('packets').textContent = packetCount.toLocaleString();
        };
        
        ws.onerror = (error) => {
            log('❌ WebSocket error: ' + error, 'error');
            updateStatus('Error', 'status-error');
        };
        
        ws.onclose = () => {
            log('⚠️ WebSocket closed. Reconnecting in 3s...', 'warn');
            updateStatus('Disconnected', 'status-error');
            setTimeout(connect, 3000);
        };
        
    } catch (e) {
        log('❌ Connection failed: ' + e.message, 'error');
        updateStatus('Error', 'status-error');
        setTimeout(connect, 3000);
    }
}

function isKeyFrame(data) {
    // Check if this is an IDR frame (NAL type 5) or has SPS/PPS (NAL type 7/8)
    if (data.length > 4 && data[0] === 0 && data[1] === 0 && data[2] === 0 && data[3] === 1) {
        const nalType = data[4] & 0x1F;
        return nalType === 5 || nalType === 7 || nalType === 8;
    }
    // Also check first byte directly
    const nalType = data[0] & 0x1F;
    return nalType === 5 || nalType === 7 || nalType === 8;
}

function createAVCDecoderConfig(sps, pps) {
    // Create avcC box format for WebCodecs
    // Reference: ISO/IEC 14496-15 Section 5.2.4.1
    const config = new Uint8Array(11 + sps.length + pps.length);
    let offset = 0;
    
    config[offset++] = 1; // configurationVersion
    config[offset++] = sps[1]; // AVCProfileIndication
    config[offset++] = sps[2]; // profile_compatibility
    config[offset++] = sps[3]; // AVCLevelIndication
    config[offset++] = 0xFF; // lengthSizeMinusOne (4 bytes)
    config[offset++] = 0xE1; // numOfSequenceParameterSets (1)
    
    // SPS length
    config[offset++] = (sps.length >> 8) & 0xFF;
    config[offset++] = sps.length & 0xFF;
    config.set(sps, offset);
    offset += sps.length;
    
    config[offset++] = 1; // numOfPictureParameterSets
    
    // PPS length
    config[offset++] = (pps.length >> 8) & 0xFF;
    config[offset++] = pps.length & 0xFF;
    config.set(pps, offset);
    
    return config;
}

function toAnnexB(nalUnit) {
    // Convert raw NAL unit to Annex B format (add 00 00 00 01 start code)
    const startCode = new Uint8Array([0, 0, 0, 1]);
    const result = new Uint8Array(startCode.length + nalUnit.length);
    result.set(startCode, 0);
    result.set(nalUnit, startCode.length);
    return result;
}

function toLengthPrefixed(nalUnit) {
    // Convert raw NAL unit to length-prefixed format (4-byte big-endian length + NAL)
    const result = new Uint8Array(4 + nalUnit.length);
    result[0] = (nalUnit.length >> 24) & 0xFF;
    result[1] = (nalUnit.length >> 16) & 0xFF;
    result[2] = (nalUnit.length >> 8) & 0xFF;
    result[3] = nalUnit.length & 0xFF;
    result.set(nalUnit, 4);
    return result;
}

function reconnect() {
    if (ws) {
        ws.close();
    }
    if (decoder) {
        decoder.reset();
    }
    packetCount = 0;
    totalBytes = 0;
    frameCount = 0;
    spsData = null;
    ppsData = null;
    decoderConfigured = false;
    connect();
}

function clearLog() {
    document.getElementById('log').innerHTML = '';
}

async function sendCommand(command) {
    try {
        const takeoffLandBtn = document.getElementById('takeoff-land-btn');
        
        // Special handling for takeoff: calibrate first
        if (command === 'takeoff') {
            log('📤 Takeoff sequence: Step 1 - Calibrating gyro...', 'info');
            
            // Send calibration command and wait for it to complete
            const calibrateResponse = await fetch('http://localhost:9000/api/calibrate', {
                method: 'POST'
            });
            const calibrateResult = await calibrateResponse.json();
            log(`✅ Calibration complete: ${calibrateResult.message}`, 'info');
            
            // Small delay before takeoff
            await new Promise(resolve => setTimeout(resolve, 500));
            
            log('📤 Takeoff sequence: Step 2 - Taking off...', 'info');
        }
        
        const response = await fetch(`http://localhost:9000/api/${command}`, {
            method: 'POST'
        });
        const result = await response.json();
        log(`📤 Command: ${command} - ${result.status || 'success'}`, result.status === 'success' ? 'info' : 'error');
        
        // Toggle button after successful takeoff
        if (command === 'takeoff' && takeoffLandBtn) {
            takeoffLandBtn.textContent = '🛬';
            takeoffLandBtn.setAttribute('data-tooltip', 'Land');
            takeoffLandBtn.setAttribute('onclick', "sendCommand('land')");
        } else if (command === 'land' && takeoffLandBtn) {
            takeoffLandBtn.textContent = '🛫';
            takeoffLandBtn.setAttribute('data-tooltip', 'Takeoff');
            takeoffLandBtn.setAttribute('onclick', "sendCommand('takeoff')");
        }
        
        // Update drone status immediately after command
        setTimeout(updateDroneStatus, 500);
    } catch (e) {
        log(`❌ Command failed: ${command} - ${e.message}`, 'error');
    }
}

async function updateDroneStatus() {
    try {
        const response = await fetch('http://localhost:9000/api/status');
        const status = await response.json();
        
        // Battery
        document.getElementById('overlay-battery').textContent = status.battery ? status.battery + '%' : '--';
        
        // Altitude
        document.getElementById('overlay-altitude').textContent = status.altitude ? status.altitude + 'm' : '--';
        
        // Motors
        document.getElementById('overlay-motors').textContent = status.motorRunning ? 'ON' : 'OFF';
        
        // Flight mode
        let modes = [];
        if (status.altitudeHold) modes.push('Alt Hold');
        if (status.headless) modes.push('Headless');
        if (status.followMode) modes.push('Follow');
        if (status.takeOff) modes.push('Takeoff');
        if (status.landed) modes.push('Landed');
        const modeText = modes.length > 0 ? modes.join(', ') : 'Normal';
        document.getElementById('overlay-mode').textContent = modeText;
        
        // Warnings
        let warnings = [];
        if (status.galeWarning) warnings.push('⚠️ Wind');
        if (status.currentOver) warnings.push('⚠️ Current');
        if (status.calibrate) warnings.push('🔧 Calibrating');
        document.getElementById('overlay-warnings').textContent = warnings.length > 0 ? warnings.join(' ') : 'OK';
        
    } catch (e) {
        // Silently fail - likely not connected yet
    }
}

// Update stats every second
setInterval(() => {
    const now = Date.now();
    const elapsed = (now - lastUpdateTime) / 1000;
    
    // Data rate
    const bytesPerSec = (totalBytes - lastBytes) / elapsed;
    document.getElementById('dataRate').textContent = (bytesPerSec / 1024).toFixed(1) + ' KB/s';
    lastBytes = totalBytes;
    lastUpdateTime = now;
    
    // Uptime
    const uptime = Math.floor((now - startTime) / 1000);
    document.getElementById('uptime').textContent = uptime + 's';
}, 1000);

// Update drone status every 2 seconds
setInterval(updateDroneStatus, 2000);

// Manual control functions
let controlInterval = null;

function holdControl(direction) {
    // Send immediately
    fetch(`http://localhost:9000/api/${direction}`, {method: 'POST'})
        .then(() => log(`🎮 Control: ${direction}`, 'info'))
        .catch(e => console.error(e));
    
    // Keep sending while held (every 100ms for responsiveness)
    if (controlInterval) clearInterval(controlInterval);
    controlInterval = setInterval(() => {
        fetch(`http://localhost:9000/api/${direction}`, {method: 'POST'})
            .catch(e => console.error(e));
    }, 100);
}

function stopControl() {
    if (controlInterval) {
        clearInterval(controlInterval);
        controlInterval = null;
    }
    fetch('http://localhost:9000/api/stop', {method: 'POST'})
        .then(() => log('⏹️ Control stopped', 'info'))
        .catch(e => console.error(e));
}

// Keyboard controls
document.addEventListener('keydown', (e) => {
    if (e.repeat) return; // Ignore key repeat
    switch(e.key) {
        case 'ArrowUp': 
            e.preventDefault();
            holdControl('move/up'); 
            break;
        case 'ArrowDown': 
            e.preventDefault();
            holdControl('move/down'); 
            break;
        case 'ArrowLeft': 
            e.preventDefault();
            holdControl('yaw/left'); 
            break;
        case 'ArrowRight': 
            e.preventDefault();
            holdControl('yaw/right'); 
            break;
    }
});

document.addEventListener('keyup', (e) => {
    switch(e.key) {
        case 'ArrowUp':
        case 'ArrowDown':
        case 'ArrowLeft':
        case 'ArrowRight':
            e.preventDefault();
            stopControl();
            break;
    }
});

// Start connection
connect();
updateDroneStatus();
