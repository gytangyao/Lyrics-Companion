"use strict";

const {spawn} = require("child_process");
const net = require("net");
const path = require("path");

const PROXY_HOST = process.env.SYNC_PROXY_HOST || "127.0.0.1";
const PROXY_PORT = Number(process.env.SYNC_PROXY_PORT || 7897);
const SCRIPT = path.join(__dirname, "sync-release.js");

function log(message) { console.log(`[sync-with-fallback] ${message}`); }

function supportsEnvProxy() {
  const parts = process.versions.node.split(".").map(Number);
  return parts[0] > 22 || (parts[0] === 22 && parts[1] >= 21);
}

function runNode(extraArgs, extraEnv) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, extraArgs, {
      stdio: "inherit",
      env: {...process.env, ...extraEnv},
    });
    child.on("close", (code) => resolve(code === 0 ? 0 : (code || 1)));
    child.on("error", (error) => {
      console.error(`[sync-with-fallback] failed to start node: ${error.message}`);
      resolve(2);
    });
  });
}

function proxyAvailable(timeoutMs = 2500) {
  return new Promise((resolve) => {
    const socket = net.connect({host: PROXY_HOST, port: PROXY_PORT});
    const timer = setTimeout(() => { socket.destroy(); resolve(false); }, timeoutMs);
    socket.once("connect", () => { clearTimeout(timer); socket.destroy(); resolve(true); });
    socket.once("error", () => { clearTimeout(timer); socket.destroy(); resolve(false); });
  });
}

(async () => {
  const rawArgs = process.argv.slice(2);
  const proxyOnly = rawArgs.includes("--proxy-only");
  const args = rawArgs.filter((arg) => arg !== "--proxy-only");
  let directCode = 1;
  if (!proxyOnly) {
    directCode = await runNode([SCRIPT, ...args], {});
    if (directCode === 0) {
      log("direct sync succeeded");
      process.exit(0);
    }
    log(`direct sync failed with code ${directCode}, trying proxy fallback`);
  } else {
    log("using requested proxy-only sync");
  }
  if (!(await proxyAvailable())) {
    console.error(`[sync-with-fallback] proxy ${PROXY_HOST}:${PROXY_PORT} unavailable, giving up`);
    process.exit(directCode);
  }
  if (!supportsEnvProxy()) {
    console.error("[sync-with-fallback] proxy mode requires Node.js 22.21+ or 24.5+");
    process.exit(2);
  }
  const proxyUrl = `http://${PROXY_HOST}:${PROXY_PORT}`;
  log(`using proxy ${proxyUrl}`);
  const proxyCode = await runNode(["--use-env-proxy", SCRIPT, ...args], {
    HTTPS_PROXY: proxyUrl,
    HTTP_PROXY: proxyUrl,
    ALL_PROXY: proxyUrl,
  });
  process.exit(proxyCode);
})();
