// WeSing/QQ QRC decryption — triple modified-DES + zlib inflate.
// Ported from https://github.com/TLittlePrince/qrcDecrypt (lx-music-api-server pyqdes lineage).
// Keys live verbatim in WeSing's libdesdecrypt.so (.rodata @0x1928+); only the first
// 8 bytes of each 16-byte string feed the key schedule.
const zlib = require('zlib');

function bitNum(a, b, c) {
    const byteIndex = Math.floor(b / 32) * 4 + 3 - Math.floor((b % 32) / 8);
    return (((a[byteIndex] >> (7 - (b % 8))) & 1) << c);
}
function bitNumIntR(a, b, c) { return ((a >>> (31 - b)) & 1) << c; }
function bitNumIntL(a, b, c) { return (((a << b) & 0x80000000) >>> 0) >>> c; }
function sBoxBit(a) { return (a & 0x20) | ((a & 0x1f) >> 1) | ((a & 1) << 4); }

const S1 = [14,4,13,1,2,15,11,8,3,10,6,12,5,9,0,7, 0,15,7,4,14,2,13,1,10,6,12,11,9,5,3,8, 4,1,14,8,13,6,2,11,15,12,9,7,3,10,5,0, 15,12,8,2,4,9,1,7,5,11,3,14,10,0,6,13];
const S2 = [15,1,8,14,6,11,3,4,9,7,2,13,12,0,5,10, 3,13,4,7,15,2,8,15,12,0,1,10,6,9,11,5, 0,14,7,11,10,4,13,1,5,8,12,6,9,3,2,15, 13,8,10,1,3,15,4,2,11,6,7,12,0,5,14,9];
const S3 = [10,0,9,14,6,3,15,5,1,13,12,7,11,4,2,8, 13,7,0,9,3,4,6,10,2,8,5,14,12,11,15,1, 13,6,4,9,8,15,3,0,11,1,2,12,5,10,14,7, 1,10,13,0,6,9,8,7,4,15,14,3,11,5,2,12];
const S4 = [7,13,14,3,0,6,9,10,1,2,8,5,11,12,4,15, 13,8,11,5,6,15,0,3,4,7,2,12,1,10,14,9, 10,6,9,0,12,11,7,13,15,1,3,14,5,2,8,4, 3,15,0,6,10,10,13,8,9,4,5,11,12,7,2,14];
const S5 = [2,12,4,1,7,10,11,6,8,5,3,15,13,0,14,9, 14,11,2,12,4,7,13,1,5,0,15,10,3,9,8,6, 4,2,1,11,10,13,7,8,15,9,12,5,6,3,0,14, 11,8,12,7,1,14,2,13,6,15,0,9,10,4,5,3];
const S6 = [12,1,10,15,9,2,6,8,0,13,3,4,14,7,5,11, 10,15,4,2,7,12,9,5,6,1,13,14,0,11,3,8, 9,14,15,5,2,8,12,3,7,0,4,10,1,13,11,6, 4,3,2,12,9,5,15,10,11,14,1,7,6,0,8,13];
const S7 = [4,11,2,14,15,0,8,13,3,12,9,7,5,10,6,1, 13,0,11,7,4,9,1,10,14,3,5,12,2,15,8,6, 1,4,11,13,12,3,7,14,10,15,6,8,0,5,9,2, 6,11,13,8,1,4,10,7,9,5,0,15,14,2,3,12];
const S8 = [13,2,8,4,6,15,11,1,10,9,3,14,5,0,12,7, 1,15,13,8,10,3,7,4,12,5,6,11,0,14,9,2, 7,11,4,1,9,12,14,2,0,6,10,13,15,3,5,8, 2,1,14,7,4,10,8,13,15,12,9,0,3,5,6,11];

function ipPerm(state, inBytes) {
    state[0] = bitNum(inBytes,57,31)|bitNum(inBytes,49,30)|bitNum(inBytes,41,29)|bitNum(inBytes,33,28)|
               bitNum(inBytes,25,27)|bitNum(inBytes,17,26)|bitNum(inBytes,9,25)|bitNum(inBytes,1,24)|
               bitNum(inBytes,59,23)|bitNum(inBytes,51,22)|bitNum(inBytes,43,21)|bitNum(inBytes,35,20)|
               bitNum(inBytes,27,19)|bitNum(inBytes,19,18)|bitNum(inBytes,11,17)|bitNum(inBytes,3,16)|
               bitNum(inBytes,61,15)|bitNum(inBytes,53,14)|bitNum(inBytes,45,13)|bitNum(inBytes,37,12)|
               bitNum(inBytes,29,11)|bitNum(inBytes,21,10)|bitNum(inBytes,13,9)|bitNum(inBytes,5,8)|
               bitNum(inBytes,63,7)|bitNum(inBytes,55,6)|bitNum(inBytes,47,5)|bitNum(inBytes,39,4)|
               bitNum(inBytes,31,3)|bitNum(inBytes,23,2)|bitNum(inBytes,15,1)|bitNum(inBytes,7,0);
    state[1] = bitNum(inBytes,56,31)|bitNum(inBytes,48,30)|bitNum(inBytes,40,29)|bitNum(inBytes,32,28)|
               bitNum(inBytes,24,27)|bitNum(inBytes,16,26)|bitNum(inBytes,8,25)|bitNum(inBytes,0,24)|
               bitNum(inBytes,58,23)|bitNum(inBytes,50,22)|bitNum(inBytes,42,21)|bitNum(inBytes,34,20)|
               bitNum(inBytes,26,19)|bitNum(inBytes,18,18)|bitNum(inBytes,10,17)|bitNum(inBytes,2,16)|
               bitNum(inBytes,60,15)|bitNum(inBytes,52,14)|bitNum(inBytes,44,13)|bitNum(inBytes,36,12)|
               bitNum(inBytes,28,11)|bitNum(inBytes,20,10)|bitNum(inBytes,12,9)|bitNum(inBytes,4,8)|
               bitNum(inBytes,62,7)|bitNum(inBytes,54,6)|bitNum(inBytes,46,5)|bitNum(inBytes,38,4)|
               bitNum(inBytes,30,3)|bitNum(inBytes,22,2)|bitNum(inBytes,14,1)|bitNum(inBytes,6,0);
}

function invIp(state, out) {
    out[3] = bitNumIntR(state[1],7,7)|bitNumIntR(state[0],7,6)|bitNumIntR(state[1],15,5)|bitNumIntR(state[0],15,4)|
             bitNumIntR(state[1],23,3)|bitNumIntR(state[0],23,2)|bitNumIntR(state[1],31,1)|bitNumIntR(state[0],31,0);
    out[2] = bitNumIntR(state[1],6,7)|bitNumIntR(state[0],6,6)|bitNumIntR(state[1],14,5)|bitNumIntR(state[0],14,4)|
             bitNumIntR(state[1],22,3)|bitNumIntR(state[0],22,2)|bitNumIntR(state[1],30,1)|bitNumIntR(state[0],30,0);
    out[1] = bitNumIntR(state[1],5,7)|bitNumIntR(state[0],5,6)|bitNumIntR(state[1],13,5)|bitNumIntR(state[0],13,4)|
             bitNumIntR(state[1],21,3)|bitNumIntR(state[0],21,2)|bitNumIntR(state[1],29,1)|bitNumIntR(state[0],29,0);
    out[0] = bitNumIntR(state[1],4,7)|bitNumIntR(state[0],4,6)|bitNumIntR(state[1],12,5)|bitNumIntR(state[0],12,4)|
             bitNumIntR(state[1],20,3)|bitNumIntR(state[0],20,2)|bitNumIntR(state[1],28,1)|bitNumIntR(state[0],28,0);
    out[7] = bitNumIntR(state[1],3,7)|bitNumIntR(state[0],3,6)|bitNumIntR(state[1],11,5)|bitNumIntR(state[0],11,4)|
             bitNumIntR(state[1],19,3)|bitNumIntR(state[0],19,2)|bitNumIntR(state[1],27,1)|bitNumIntR(state[0],27,0);
    out[6] = bitNumIntR(state[1],2,7)|bitNumIntR(state[0],2,6)|bitNumIntR(state[1],10,5)|bitNumIntR(state[0],10,4)|
             bitNumIntR(state[1],18,3)|bitNumIntR(state[0],18,2)|bitNumIntR(state[1],26,1)|bitNumIntR(state[0],26,0);
    out[5] = bitNumIntR(state[1],1,7)|bitNumIntR(state[0],1,6)|bitNumIntR(state[1],9,5)|bitNumIntR(state[0],9,4)|
             bitNumIntR(state[1],17,3)|bitNumIntR(state[0],17,2)|bitNumIntR(state[1],25,1)|bitNumIntR(state[0],25,0);
    out[4] = bitNumIntR(state[1],0,7)|bitNumIntR(state[0],0,6)|bitNumIntR(state[1],8,5)|bitNumIntR(state[0],8,4)|
             bitNumIntR(state[1],16,3)|bitNumIntR(state[0],16,2)|bitNumIntR(state[1],24,1)|bitNumIntR(state[0],24,0);
}

function feistelF(state, key) {
    const ls = new Array(6).fill(0);
    const t1 = (bitNumIntL(state,31,0) | ((state & 0xf0000000) >>> 1) | bitNumIntL(state,4,5) |
               bitNumIntL(state,3,6) | ((state & 0x0f000000) >>> 3) | bitNumIntL(state,8,11) |
               bitNumIntL(state,7,12) | ((state & 0x00f00000) >>> 5) | bitNumIntL(state,12,17) |
               bitNumIntL(state,11,18) | ((state & 0x000f0000) >>> 7) | bitNumIntL(state,16,23)) >>> 0;
    const t2 = (bitNumIntL(state,15,0) | (((state & 0x0000f000) << 15) >>> 0) | bitNumIntL(state,20,5) |
               bitNumIntL(state,19,6) | (((state & 0x00000f00) << 13) >>> 0) | bitNumIntL(state,24,11) |
               bitNumIntL(state,23,12) | (((state & 0x000000f0) << 11) >>> 0) | bitNumIntL(state,28,17) |
               bitNumIntL(state,27,18) | (((state & 0x0000000f) << 9) >>> 0) | bitNumIntL(state,0,23)) >>> 0;
    ls[0] = (t1 >>> 24) & 0xff; ls[1] = (t1 >>> 16) & 0xff; ls[2] = (t1 >>> 8) & 0xff;
    ls[3] = (t2 >>> 24) & 0xff; ls[4] = (t2 >>> 16) & 0xff; ls[5] = (t2 >>> 8) & 0xff;
    for (let i = 0; i < 6; i++) ls[i] ^= key[i];
    let s = ((S1[sBoxBit(ls[0] >> 2)] << 28) | (S2[sBoxBit(((ls[0] & 0x03) << 4) | (ls[1] >> 4))] << 24) |
            (S3[sBoxBit(((ls[1] & 0x0f) << 2) | (ls[2] >> 6))] << 20) | (S4[sBoxBit(ls[2] & 0x3f)] << 16) |
            (S5[sBoxBit(ls[3] >> 2)] << 12) | (S6[sBoxBit(((ls[3] & 0x03) << 4) | (ls[4] >> 4))] << 8) |
            (S7[sBoxBit(((ls[4] & 0x0f) << 2) | (ls[5] >> 6))] << 4) | S8[sBoxBit(ls[5] & 0x3f)]) | 0;
    s = (bitNumIntL(s,15,0)|bitNumIntL(s,6,1)|bitNumIntL(s,19,2)|bitNumIntL(s,20,3)|bitNumIntL(s,28,4)|
         bitNumIntL(s,11,5)|bitNumIntL(s,27,6)|bitNumIntL(s,16,7)|bitNumIntL(s,0,8)|bitNumIntL(s,14,9)|
         bitNumIntL(s,22,10)|bitNumIntL(s,25,11)|bitNumIntL(s,4,12)|bitNumIntL(s,17,13)|bitNumIntL(s,30,14)|
         bitNumIntL(s,9,15)|bitNumIntL(s,1,16)|bitNumIntL(s,7,17)|bitNumIntL(s,23,18)|bitNumIntL(s,13,19)|
         bitNumIntL(s,31,20)|bitNumIntL(s,26,21)|bitNumIntL(s,2,22)|bitNumIntL(s,8,23)|bitNumIntL(s,18,24)|
         bitNumIntL(s,12,25)|bitNumIntL(s,29,26)|bitNumIntL(s,5,27)|bitNumIntL(s,21,28)|bitNumIntL(s,10,29)|
         bitNumIntL(s,3,30)|bitNumIntL(s,24,31)) | 0;
    return s;
}

const KEY_RND_SHIFT = [1,1,2,2,2,2,2,2,1,2,2,2,2,2,2,1];
const KEY_PERM_C = [56,48,40,32,24,16,8,0,57,49,41,33,25,17,9,1,58,50,42,34,26,18,10,2,59,51,43,35];
const KEY_PERM_D = [62,54,46,38,30,22,14,6,61,53,45,37,29,21,13,5,60,52,44,36,28,20,12,4,27,19,11,3];
const KEY_COMPRESSION = [13,16,10,23,0,4,2,27,14,5,20,9,22,18,11,3,25,7,15,6,26,19,12,1,
                         40,51,30,36,46,54,29,39,50,44,32,47,43,48,38,55,33,52,45,41,49,35,28,31];

function keySetup(key, schedule, decrypt) {
    let c = 0, d = 0;
    for (let i = 0; i < 28; i++) {
        c |= bitNum(key, KEY_PERM_C[i], 31 - i);
        d |= bitNum(key, KEY_PERM_D[i], 31 - i);
    }
    for (let i = 0; i < 16; i++) {
        const sh = KEY_RND_SHIFT[i];
        c = (((c << sh) | (c >>> (28 - sh))) & 0xfffffff0) | 0;
        d = (((d << sh) | (d >>> (28 - sh))) & 0xfffffff0) | 0;
        const toGen = decrypt ? 15 - i : i;
        schedule[toGen] = new Array(6).fill(0);
        for (let j = 0; j < 24; j++)
            schedule[toGen][j >> 3] |= bitNumIntR(c, KEY_COMPRESSION[j], 7 - (j % 8));
        for (let j = 24; j < 48; j++)
            schedule[toGen][j >> 3] |= bitNumIntR(d, KEY_COMPRESSION[j] - 27, 7 - (j % 8));
    }
}

function desCrypt(block, schedule) {
    const state = [0, 0];
    ipPerm(state, block);
    for (let idx = 0; idx < 15; idx++) {
        const t = state[1];
        state[1] = (feistelF(state[1], schedule[idx]) ^ state[0]) | 0;
        state[0] = t;
    }
    state[0] = (feistelF(state[1], schedule[15]) ^ state[0]) | 0;
    invIp(state, block);
    return block;
}

function desPass(buf, keyBytes, decrypt) {
    const schedule = new Array(16);
    keySetup(keyBytes, schedule, decrypt);
    for (let i = 0; i + 8 <= buf.length; i += 8) desCrypt(buf.subarray(i, i + 8), schedule);
    return buf;
}

// First 8 bytes of each 16-byte constant from libdesdecrypt.so.
const K1 = Buffer.from('!@#)(NHL');
const K2 = Buffer.from('123ZXC!@');
const K3 = Buffer.from('!@#)(*$%');

/** Decrypts a WeSing-cached QRC hex string to the inflated UTF-8 XML text. */
function decryptToXml(hexText) {
    const hex = String(hexText).trim();
    if (hex.length === 0 || (hex.length & 1) !== 0) throw new Error('odd qrc hex length');
    const buf = Buffer.from(hex, 'hex');
    desPass(buf, K1, true);   // Ddes KEY1
    desPass(buf, K2, false);  // Des  KEY2
    desPass(buf, K3, true);   // Ddes KEY3
    return zlib.inflateSync(buf).toString('utf8');
}

/** Extracts [ti:]/[ar:] metadata from decrypted QRC XML. */
function titleArtist(xml) {
    const ti = /\[ti:([^\]]*)\]/.exec(xml);
    const ar = /\[ar:([^\]]*)\]/.exec(xml);
    return { title: ti ? ti[1].trim() : '', artist: ar ? ar[1].trim() : '' };
}

module.exports = { decryptToXml, titleArtist };
