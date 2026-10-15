// Read-only diagnostic: joins the wemew room as a display and dumps every frame it
// sees, so we can tell exactly who (app vs watcher) is publishing what.
const barId = process.argv[2];
if (!barId) { console.error('usage: node room_probe.js <barId>'); process.exit(1); }
const url = 'wss://socket.wemew.com/websocket/bluetooth_box/' + barId;
console.log('[probe] joining ' + url);
const ws = new WebSocket(url);
ws.onopen = () => {
    console.log('[probe] joined');
    const beat = () => ws.send(JSON.stringify({
        type: 'online', sender: 'control_box',
        data: { deviceId: 'diag-probe', timestamp: Date.now() },
    }));
    beat();
    setInterval(beat, 2000);
};
ws.onmessage = (event) => {
    const raw = String(event.data);
    let frame;
    try { frame = JSON.parse(raw); } catch (ignored) { console.log('[probe] raw: ' + raw.slice(0, 120)); return; }
    if (frame.type === 'online') return; // heartbeats are noise here
    const who = frame.sender || '?';
    const data = JSON.stringify(frame.data || {});
    console.log(`[probe] ${new Date().toISOString().slice(11, 23)} ${who} ${frame.type} ${data.slice(0, 220)}`);
};
ws.onclose = () => { console.log('[probe] closed; exiting'); process.exit(0); };
ws.onerror = () => { /* onclose follows */ };
