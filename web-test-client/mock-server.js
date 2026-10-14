// Mock of the wemew web sync protocol used to debug the page against scripted transitions.
// Serves the real assets/web/index.html plus WS at /ws; replays: track A -> states -> track B.
'use strict';
var http = require('http');
var fs = require('fs');
var crypto = require('crypto');

var PAGE = fs.readFileSync('D:/SynologyDrive/Lyrics-Companion/web-test-client/index.html')
    .toString('utf8')
    .replace('<script>', '<script>\n' +
        'window.__rafCount = 0;\n' +
        'var __origRaf = window.requestAnimationFrame.bind(window);\n' +
        'window.requestAnimationFrame = function (cb) {\n' +
        '    return __origRaf(function (ts) {\n' +
        '        window.__rafCount++;\n' +
        '        document.documentElement.setAttribute("data-raf", String(window.__rafCount));\n' +
        '        try { cb(ts); } catch (e) {\n' +
        '            document.documentElement.setAttribute("data-raf-error", String(e && e.message));\n' +
        '        }\n' +
        '    });\n' +
        '};\n' +
        'window.onerror = function (msg) { document.documentElement.setAttribute("data-error", String(msg)); };\n');

function trackFrame(title, artist, source, lines, positionMs) {
    return JSON.stringify({
        v: 1, type: 'track', ts: Date.now(),
        data: {
            active: true, playing: true, title: title, artist: artist,
            source: source, sourceName: source, durationMs: 300000,
            positionMs: positionMs, speed: 1, offsetMs: 0,
            lyric: { live: false, sourceName: source + '歌词', lines: lines },
            hasArt: false, artVersion: 0
        }
    });
}

function stateFrame(positionMs) {
    return JSON.stringify({ v: 1, type: 'state', ts: Date.now(),
        data: { active: true, playing: true, positionMs: positionMs, speed: 1,
                durationMs: 300000, track: true } });
}

function words(text, startMs, perMs) {
    return text.split('').map(function (ch, i) {
        return { t: startMs + i * perMs, d: perMs, text: ch };
    });
}

var trackA = trackFrame('歌A', '歌手A', 'qqmusic', [
    { t: 0, d: 2000, text: '序曲A', words: words('序曲A', 0, 500) },
    { t: 4000, d: 4000, text: '这是第一首歌的当前行', words: words('这是第一首歌的当前行', 4000, 250) },
    { t: 10000, d: 4000, text: '第一首歌的下一行', words: words('第一首歌的下一行', 10000, 300) }
], 4100);

var trackB = trackFrame('歌B', '歌手B', 'netease', [
    { t: 0, d: 1000, text: '作词 : 歌手B', words: [{ t: 0, d: 1000, text: '作词 : 歌手B' }] },
    { t: 20000, d: 4200, text: '第二首歌正在唱的这一句歌词', words: words('第二首歌正在唱的这一句歌词', 20000, 300) },
    { t: 26000, d: 3000, text: '第二首歌的尾句', words: words('第二首歌的尾句', 26000, 300) }
], 20400);

var server = http.createServer(function (request, response) {
    var url = request.url.split('?')[0];
    if (url === '/ws') {
        // Handshake only happens for upgrade requests; handled below.
        response.writeHead(400); response.end('upgrade required');
        return;
    }
    if (url === '/' || url === '/index.html') {
        response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        response.end(PAGE);
        return;
    }
    if (url === '/api/state') {
        response.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8',
                'Access-Control-Allow-Origin': '*' });
        response.end('{"v":1,"track":null,"state":{"active":false,"playing":false,"positionMs":0,"speed":1,"durationMs":-1,"track":false}}');
        return;
    }
    response.writeHead(404); response.end('not found');
});

server.on('upgrade', function (request, socket) {
    var key = request.headers['sec-websocket-key'];
    var accept = crypto.createHash('sha1').update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
    socket.write('HTTP/1.1 101 Switching Protocols\r\n' +
        'Upgrade: websocket\r\nConnection: Upgrade\r\n' +
        'Sec-WebSocket-Accept: ' + accept + '\r\n\r\n');
    var opened = false;
    var buffer = Buffer.alloc(0);
    socket.on('data', function (chunk) {
        buffer = Buffer.concat([buffer, chunk]);
        // Parse complete text frames (client->server is masked).
        while (buffer.length >= 2) {
            var length = buffer[1] & 0x7F;
            var offset = 2;
            if (length === 126) { length = buffer.readUInt16BE(2); offset = 4; }
            if (buffer.length < offset + length + 4) break;
            var masked = buffer.slice(offset, offset + length);
            var mask = buffer.slice(offset + length, offset + length + 4);
            var text = '';
            for (var i = 0; i < masked.length; i++) text += String.fromCharCode(masked[i] ^ mask[i % 4]);
            buffer = buffer.slice(offset + length + 4);
            if (text.indexOf('"ping"') >= 0) {
                var t0 = JSON.parse(text).ts;
                sendFrame(JSON.stringify({ v: 1, type: 'pong', ts: Date.now(), data: { t0: t0 } }));
            }
        }
        if (!opened) {
            opened = true;
            sendFrame(JSON.stringify({ v: 1, type: 'welcome', ts: Date.now(),
                data: { protocol: 1, server: { name: 'mock', version: '0' },
                        capabilities: ['track', 'state', 'line', 'cover', 'ping'] } }));
            sendFrame(trackA);
            sendFrame(stateFrame(4100));
        }
    });
    function sendFrame(text) {
        var payload = Buffer.from(text, 'utf8');
        var header;
        if (payload.length < 126) {
            header = Buffer.from([0x81, payload.length]);
        } else {
            header = Buffer.alloc(4);
            header[0] = 0x81; header[1] = 126; header.writeUInt16BE(payload.length, 2);
        }
        try { socket.write(Buffer.concat([header, payload])); } catch (error) { }
    }
    // Advance A for 6s, then switch to track B (the player-switch moment).
    var position = 4100;
    var ticker = setInterval(function () {
        position += 1000;
        sendFrame(stateFrame(position));
    }, 1000);
    setTimeout(function () {
        clearInterval(ticker);
        sendFrame(trackB);
        position = 20400;
        sendFrame(stateFrame(20400));
        ticker = setInterval(function () {
            position += 1000;
            sendFrame(stateFrame(position));
        }, 1000);
        console.log('SWITCHED_TO_B');
    }, 800);
    socket.on('close', function () { clearInterval(ticker); });
    socket.on('error', function () { clearInterval(ticker); });
});

server.listen(18904, function () { console.log('MOCK_READY'); });
setTimeout(function () { process.exit(0); }, 40000);
