// WeSing (全民K歌) PC-side lyric watcher.
//
// 1. Streams `adb logcat` and follows the song lifecycle (verified against 11.0.38.278):
//    every entry into the recording page logs `enterRecordData=: mSongId = X; mSongTitle = Y;`
//    regardless of cache hits, and `SingLoadHelper: dealQrc begin : <path>` names the
//    exact file being loaded.
// 2. Pulls + decrypts the cached QRC (triple modified-DES + zlib, see qrc_crypto.js)
//    into watcher_state/.
// 3. With --bar <barId> joins the wemew cloud room as a second phone-side source and
//    pushes protocol-v1 track/state frames (docs/web-protocol.md) so any display in
//    the room gets word-level lyrics. The on-phone app defers its own WeSing publishing
//    while our heartbeat is fresh.
//
// Usage: node wesing_watcher.js [--bar <门店号>] [adb-serial]
const { spawn, execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const crypto = require('./qrc_crypto');

const args = process.argv.slice(2);
let BAR_ID = process.env.WEMEW_BAR_ID || '';
for (let i = 0; i < args.length; i++) {
    if (args[i] === '--bar' && args[i + 1]) { BAR_ID = args[i + 1]; args.splice(i, 2); i--; }
}
const SERIAL = args[0] || '';
const ROOM_BASE = 'wss://socket.wemew.com/websocket/bluetooth_box/';
const WATCHER_DEVICE_ID = 'wesing-watcher-pc';

const QRC_DEVICE_DIR = '/storage/emulated/0/Android/data/com.tencent.karaoke/files/qrc';
const OUT_DIR = path.join(__dirname, 'watcher_state');
const QRC_DIR = path.join(OUT_DIR, 'qrc');
const CURRENT_JSON = path.join(OUT_DIR, 'current.json');
const EVENTS_LOG = path.join(OUT_DIR, 'events.log');

// ---- verified logcat signatures -------------------------------------------
const RE_ENTER = /enterRecordData=: mSongId = (\S+?); mSongTitle = ([^;]+);/;
const RE_DEAL_QRC = /dealQrc begin : (\S*\/qrc\/(\S+?)_(original|dynamic|pronounce|homophony)\.qrc)/;
const RE_META = /songMid=(\S+?), songName=([^,]+), singerName=([^,]+), singerMid=(\S+?)[,)]/;
const RE_START_RECORD = /startRecord,recordServiceData=.*?recordDelay=(\d+)/;
const RE_BT_PLAY = /try on(Play|Pause|Start): \$?SongInfo\(songName=([^,]+), singerName=([^,]+), album=, duration=(\d+)/;
const RE_SCORE = /RecordScoreModule: \[UI\]songId = (\S+?), totalScore =(\d+)/;

// ---- state -----------------------------------------------------------------
const state = {
    songId: '',
    title: '',
    artist: '',
    qrcPath: '',
    durationMs: -1,
    qrcXml: '',
    phase: 'idle', // idle | entered | loading | ready | recording | paused | scored
    lastBtKind: '',
    events: [],
    /** Wall-clock ms when accompaniment position 0 started (startRecord + countdown). */
    anchorWallMs: 0,
    /** Song position when paused; lets a resume rebase the anchor without a jump. */
    posAtPauseMs: 0,
    /** Set once we pushed a track frame, so reset knows to clear the displays. */
    trackPushed: false,
};

function adb(args, opts) {
    const prefix = SERIAL ? ['-s', SERIAL] : [];
    if (opts && opts.capture) {
        return execFileSync('adb', prefix.concat(args), { maxBuffer: 16 * 1024 * 1024 });
    }
    return spawn('adb', prefix.concat(args), opts);
}

const pad = (n, w) => String(n).padStart(w || 2, '0');
function fmt(ms) {
    const d = new Date(ms);
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} `
        + `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`;
}
function now() { return fmt(Date.now()); }

// logcat -v time stamps lines "MM-DD HH:MM:SS.mmm" without a year; assume the
// current year unless that lands far in the future (Dec→Jan rollover).
function eventWallMs(line) {
    const m = /^(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})\.(\d{3})/.exec(line);
    if (!m) return Date.now();
    const d = new Date();
    d.setMonth(Number(m[1]) - 1, Number(m[2]));
    d.setHours(Number(m[3]), Number(m[4]), Number(m[5]), Number(m[6]));
    if (d.getTime() > Date.now() + 24 * 3600 * 1000) d.setFullYear(d.getFullYear() - 1);
    return d.getTime();
}

function log(line) {
    const text = `[${now()}] ${line}`;
    console.log(text);
    try {
        fs.mkdirSync(OUT_DIR, { recursive: true });
        fs.appendFileSync(EVENTS_LOG, text + '\n');
    } catch (ignored) { /* console still works */ }
}

function remember(kind, detail) {
    state.events.push({ at: now(), kind, detail });
    if (state.events.length > 200) state.events.shift();
}

function persist() {
    try {
        fs.mkdirSync(QRC_DIR, { recursive: true });
        fs.writeFileSync(CURRENT_JSON, JSON.stringify({
            songId: state.songId,
            title: state.title,
            artist: state.artist,
            durationMs: state.durationMs,
            phase: state.phase,
            positionMs: songPositionMs(),
            qrcFile: state.songId ? path.join('qrc', state.songId + '.xml') : '',
            events: state.events,
            updatedAt: now(),
        }, null, 2));
    } catch (error) {
        log('persist failed: ' + error.message);
    }
}

// ---- song position -----------------------------------------------------------
/** Song position implied by the anchor, clamped to [0, duration]. */
function songPositionMs() {
    if (!state.anchorWallMs) return 0;
    let pos = Date.now() - state.anchorWallMs;
    if (state.phase === 'paused') pos = state.posAtPauseMs;
    if (state.durationMs > 0) pos = Math.min(pos, state.durationMs);
    return Math.max(0, pos);
}

function resetSong(reason) {
    if (state.songId) log(`── leave ${state.title || state.songId} (${reason})`);
    if (state.trackPushed) cloudPush(inactiveTrackFrame());
    state.songId = '';
    state.title = '';
    state.artist = '';
    state.qrcPath = '';
    state.durationMs = -1;
    state.qrcXml = '';
    state.phase = 'idle';
    state.lastBtKind = '';
    state.anchorWallMs = 0;
    state.posAtPauseMs = 0;
    state.trackPushed = false;
    persist();
}

// ---- qrc fetch + decrypt ----------------------------------------------------
function fetchQrc(songId, devicePath) {
    const target = path.join(QRC_DIR, songId + '.xml');
    try {
        if (fs.existsSync(target)) {
            state.qrcXml = fs.readFileSync(target, 'utf8');
            return;
        }
        const hex = adb(['exec-out', 'cat', devicePath], { capture: true }).toString('utf8').trim();
        if (hex.length === 0) throw new Error('empty qrc from device');
        const xml = crypto.decryptToXml(hex);
        fs.mkdirSync(QRC_DIR, { recursive: true });
        fs.writeFileSync(target, xml);
        state.qrcXml = xml;
        const meta = crypto.titleArtist(xml);
        if (meta.title && !state.title) state.title = meta.title;
        if (meta.artist && !state.artist) state.artist = meta.artist;
        log(`qrc decrypted ✓ ${songId} [ti:${meta.title}] [ar:${meta.artist}]`);
    } catch (error) {
        log(`qrc fetch failed for ${songId}: ${error.message}`);
    }
}

/** QRC LyricContent → protocol-v1 lines ({"t","d","text","words"}). */
function qrcToLines(xml) {
    const attr = /LyricContent="([\s\S]*?)"/.exec(xml);
    const body = attr ? attr[1] : xml;
    const lines = [];
    for (const raw of body.split(/\r?\n/)) {
        const head = /^\[(\d+),(\d+)\](.*)$/.exec(raw.trim());
        if (!head) continue;
        const words = [];
        let joined = '';
        const wordRe = /([^(]*)\((\d+),(\d+)\)/g;
        let w;
        while ((w = wordRe.exec(head[3]))) {
            if (w[1]) { words.push({ t: Number(w[2]), d: Number(w[3]), text: w[1] }); joined += w[1]; }
        }
        if (!words.length) continue;
        // text must equal the words joined verbatim; the page falls back to line-fill otherwise.
        lines.push({ t: Number(head[1]), d: Number(head[2]), text: joined, words });
    }
    return lines;
}

// ---- cloud room (protocol v1, phone-sender role) ------------------------------
let ws = null;
let wsOpen = false;

function cloudPush(frame) {
    if (!wsOpen || !frame) return;
    try { ws.send(frame); } catch (error) { log('cloud send failed: ' + error.message); }
}

function envelope(type, data) {
    return JSON.stringify({ v: 1, type, ts: Date.now(), data });
}

function onlineFrame() {
    return JSON.stringify({
        type: 'online', sender: 'control_box_phone',
        data: { deviceId: WATCHER_DEVICE_ID, timestamp: Date.now() },
    });
}

function trackFrame() {
    return envelope('track', {
        active: true,
        playing: state.phase === 'recording',
        title: state.title || state.songId,
        artist: state.artist,
        source: 'wesing',
        sourceName: '全民K歌',
        durationMs: state.durationMs > 0 ? state.durationMs : 0,
        positionMs: songPositionMs(),
        speed: 1.0,
        offsetMs: 0,
        lyric: {
            live: false,
            sourceName: 'WeSing QRC 逐字',
            lines: qrcToLines(state.qrcXml),
        },
    });
}

function inactiveTrackFrame() {
    return envelope('track', {
        active: false, playing: false, title: '', artist: '', source: 'wesing',
        sourceName: '全民K歌', durationMs: 0, positionMs: 0, speed: 1.0, offsetMs: 0,
        lyric: null,
    });
}

function stateFrame(playing) {
    return envelope('state', {
        active: true, playing, positionMs: songPositionMs(), speed: 1.0,
        durationMs: state.durationMs > 0 ? state.durationMs : 0, track: true,
    });
}

function pushTrackIfReady() {
    if (!state.songId || !state.qrcXml) return;
    state.trackPushed = true;
    cloudPush(trackFrame());
    if (wsOpen) log(`☁ track pushed 「${state.title}」 lines=${qrcToLines(state.qrcXml).length}`);
}

function startCloud() {
    if (!BAR_ID) return;
    const url = ROOM_BASE + BAR_ID;
    log('cloud room joining: ' + url);
    ws = new WebSocket(url);
    ws.onopen = () => {
        wsOpen = true;
        log('☁ cloud room joined');
        cloudPush(onlineFrame());
        // A song may already be active when the room connects (or we reconnect).
        if (state.songId && state.qrcXml) pushTrackIfReady();
    };
    ws.onmessage = (event) => {
        let frame;
        try { frame = JSON.parse(event.data); } catch (ignored) { return; }
        // A display joining the room expects a snapshot; keep it fed while we own WeSing.
        if (frame && frame.type === 'online' && frame.sender === 'control_box'
                && frame.data && frame.data.deviceId) {
            if (state.songId && state.qrcXml) {
                pushTrackIfReady();
                cloudPush(stateFrame(state.phase === 'recording'));
            }
        }
    };
    ws.onclose = () => {
        if (wsOpen) log('☁ cloud room closed; retrying in 3s');
        wsOpen = false;
        setTimeout(startCloud, 3000);
    };
    ws.onerror = () => { /* onclose follows */ };
    setInterval(() => { if (wsOpen) cloudPush(onlineFrame()); }, 2000);
}

// One cheap state anchor per second while singing; displays interpolate between.
setInterval(() => {
    if (wsOpen && state.trackPushed && state.phase === 'recording') {
        cloudPush(stateFrame(true));
    }
    if (state.songId && state.phase === 'recording') persist();
}, 1000);

// ---- logcat parsing ----------------------------------------------------------
function onLine(line) {
    let m;
    if ((m = RE_ENTER.exec(line))) {
        if (m[1] !== state.songId) {
            if (state.songId) resetSong('song switch');
            state.songId = m[1];
            state.title = m[2].trim();
            state.phase = 'entered';
            log(`♪ enter ${state.songId} 「${state.title}」 (${fmt(eventWallMs(line))})`);
            remember('enter', `${state.songId} ${state.title}`);
            // Cache-hit re-entries never log dealQrc, so try the default path directly.
            fetchQrc(state.songId, QRC_DEVICE_DIR + '/' + state.songId + '_original.qrc');
            if (state.qrcXml) state.phase = 'ready';
            persist();
        }
        return;
    }
    if ((m = RE_DEAL_QRC.exec(line))) {
        const songIdFromFile = m[2];
        if (state.songId && songIdFromFile !== state.songId) {
            resetSong('qrc mismatch ' + songIdFromFile);
            state.songId = songIdFromFile;
        } else if (!state.songId) {
            state.songId = songIdFromFile;
        }
        state.qrcPath = m[1];
        state.phase = 'loading';
        fetchQrc(state.songId, state.qrcPath);
        if (state.phase === 'loading') state.phase = state.qrcXml ? 'ready' : 'loading';
        persist();
        return;
    }
    if ((m = RE_META.exec(line)) && m[1] === state.songId) {
        const artist = m[3].trim();
        if (state.artist !== artist) log(`meta: 「${m[2].trim()}」 ${artist}`);
        state.title = m[2].trim();
        state.artist = artist;
        if (state.durationMs < 0) persist();
        return;
    }
    if ((m = RE_START_RECORD.exec(line))) {
        const delay = Number(m[1]);
        state.anchorWallMs = eventWallMs(line) + delay;
        state.phase = 'recording';
        log(`▶ startRecord (delay ${delay}ms) — clock anchor at ${fmt(state.anchorWallMs)}`);
        remember('startRecord', `delay=${delay}ms at=${fmt(state.anchorWallMs)}`);
        pushTrackIfReady();
        cloudPush(stateFrame(true));
        persist();
        return;
    }
    if ((m = RE_BT_PLAY.exec(line))) {
        const kind = m[1] === 'Pause' ? 'paused' : 'playing';
        if (state.durationMs < 0 && m[4]) state.durationMs = Number(m[4]) * 1000;
        if (kind === 'paused') {
            if (state.phase === 'recording') {
                state.posAtPauseMs = eventWallMs(line) - state.anchorWallMs;
                log(`⏸ pause at ~${Math.round(state.posAtPauseMs)}ms`);
                remember('pause', `${Math.round(state.posAtPauseMs)}ms`);
            }
            state.phase = 'paused';
            cloudPush(stateFrame(false));
            persist();
        } else if (state.phase === 'paused') {
            // Resume: rebase the anchor so the position continues from the pause point.
            state.anchorWallMs = Date.now() - state.posAtPauseMs;
            state.phase = 'recording';
            log(`▶ resume from ~${Math.round(state.posAtPauseMs)}ms`);
            remember('resume', `${Math.round(state.posAtPauseMs)}ms`);
            cloudPush(stateFrame(true));
            persist();
        }
        state.lastBtKind = kind;
        return;
    }
    if ((m = RE_SCORE.exec(line)) && m[1] === state.songId) {
        state.phase = 'scored';
        log(`✔ finished ${state.songId} score=${m[2]}`);
        remember('score', m[2]);
        persist();
        return;
    }
}

// ---- main loop ---------------------------------------------------------------
// Never infer the current song from local/qrc files (mtime says nothing about what is
// playing — cache hits never rewrite). A watcher started mid-song shows nothing until
// the next enterRecordData event; that trade-off is accepted by design.

function startLogcat() {
    log('watcher starting: adb logcat -v time' + (SERIAL ? ' (serial ' + SERIAL + ')' : '')
            + (BAR_ID ? ' (bar ' + BAR_ID + ')' : ' (local-only, pass --bar <门店号> to push)'));
    const child = adb(['logcat', '-v', 'time'], { stdio: ['ignore', 'pipe', 'pipe'] });
    let buffer = '';
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', (chunk) => {
        buffer += chunk;
        let idx;
        while ((idx = buffer.indexOf('\n')) >= 0) {
            const line = buffer.slice(0, idx);
            buffer = buffer.slice(idx + 1);
            if (/RecordingActivity|NewRecordingFragment|SingLoadHelper|RecordLoadingModule|RecordService|RecordScoreModule|BluetoothMusicManager/.test(line)) {
                try { onLine(line); } catch (error) { log('parse error: ' + error.message); }
            }
        }
    });
    child.stderr.on('data', (chunk) => {
        const text = String(chunk).trim();
        if (text) log('adb stderr: ' + text);
    });
    child.on('exit', () => {
        log('adb logcat exited — reconnecting in 2s');
        setTimeout(startLogcat, 2000);
    });
}

fs.mkdirSync(QRC_DIR, { recursive: true });
persist();
startCloud();
startLogcat();
