/*
 * Copyright 2026 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
'use strict';

// Only built-in Node modules are used. The disposable profile and pinned server
// certificate are exclusive to this test; no installed profile or trust store is changed.
const { spawn } = require('node:child_process');
const { readFile } = require('node:fs/promises');
const http = require('node:http');
const path = require('node:path');
const { once } = require('node:events');

const [chromePath, transportUrl, certificateHash, profile] = process.argv.slice(2);
let chrome;
let socket;
let nextId = 0;
const pending = new Map();
const events = new Map();
const sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
const page = http.createServer((request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html' });
    response.end('<!doctype html><title>WebTransport interoperability smoke test</title>');
});

function command(method, params = {}, sessionId) {
    const id = ++nextId;
    return new Promise((resolve, reject) => {
        pending.set(id, { resolve, reject });
        socket.send(JSON.stringify({ id, method, params, sessionId }));
    });
}

async function cleanup() {
    if (socket) {
        // Browser.close closes the browser process and its children gracefully.
        if (socket.readyState === WebSocket.OPEN) {
            socket.send(JSON.stringify({ id: ++nextId, method: 'Browser.close' }));
            socket.close();
        }
    }
    if (chrome && chrome.exitCode === null && chrome.signalCode === null) {
        await Promise.race([once(chrome, 'exit'), sleep(1000)]);
        if (chrome.exitCode === null && chrome.signalCode === null) {
            chrome.kill('SIGTERM');
            await Promise.race([once(chrome, 'exit'), sleep(1000)]);
        }
        if (chrome.exitCode === null && chrome.signalCode === null) {
            chrome.kill('SIGKILL');
            await Promise.race([once(chrome, 'exit'), sleep(1000)]);
        }
    }
    page.closeAllConnections();
    page.close();
}

process.once('SIGTERM', () => cleanup().finally(() => process.exit(1)));
const deadline = setTimeout(() => {
    console.error('Browser smoke test exceeded 35 seconds');
    cleanup().finally(() => process.exit(1));
}, 35000);

async function runInBrowser(url, certificateHash) {
    if (!isSecureContext || typeof WebTransport !== 'function') {
        throw new Error('WebTransport is unavailable in the localhost secure context');
    }
    const transport = new WebTransport(url, {
        serverCertificateHashes: [{
            algorithm: 'sha-256',
            value: Uint8Array.from(atob(certificateHash), character => character.charCodeAt(0))
        }]
    });
    // Attach immediately so handshake failures do not become unhandled rejections.
    const closed = transport.closed;
    closed.catch(() => {});
    await transport.ready;
    const encoder = new TextEncoder();
    const decoder = new TextDecoder();
    const stream = await transport.createBidirectionalStream();
    const writer = stream.writable.getWriter();
    await writer.write(encoder.encode('chrome-stream'));
    await writer.close();
    const reader = stream.readable.getReader();
    let echoed = '';
    for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        echoed += decoder.decode(value, { stream: true });
    }
    if (echoed !== 'chrome-stream') throw new Error('Incorrect stream echo: ' + echoed);

    const datagramWriter = transport.datagrams.writable.getWriter();
    const datagramReader = transport.datagrams.readable.getReader();
    // Datagrams are unreliable; repeat while waiting for a successful round trip.
    const retry = setInterval(() => {
        datagramWriter.write(encoder.encode('chrome-datagram')).catch(() => {});
    }, 200);
    try {
        await datagramWriter.write(encoder.encode('chrome-datagram'));
        const { value, done } = await datagramReader.read();
        if (done || decoder.decode(value) !== 'chrome-datagram') {
            throw new Error('Incorrect datagram echo');
        }
    } finally {
        clearInterval(retry);
        datagramWriter.releaseLock();
        datagramReader.releaseLock();
    }
    transport.close({ closeCode: 73, reason: 'chrome close' });
    const result = await closed;
    if (result.closeCode !== 73 || result.reason !== 'chrome close') {
        throw new Error('Incorrect browser close result: ' + JSON.stringify(result));
    }
    return { stream: echoed, closeCode: result.closeCode, reason: result.reason };
}

async function main() {
    if (!chromePath || !transportUrl || !certificateHash || !profile || typeof WebSocket !== 'function') {
        throw new Error('Expected Chrome path, WebTransport URL, certificate hash and disposable profile; '
            + 'Node.js 22 or newer is required');
    }
    page.listen(0, '127.0.0.1');
    await once(page, 'listening');
    const pageUrl = `http://127.0.0.1:${page.address().port}/`;
    chrome = spawn(chromePath, [
        '--headless=new', '--no-first-run', '--no-default-browser-check',
        '--disable-background-networking', '--disable-component-update', '--disable-sync',
        '--remote-debugging-address=127.0.0.1', '--remote-debugging-port=0', '--enable-quic',
        `--log-net-log=${path.join(profile, 'netlog.json')}`,
        `--user-data-dir=${profile}`, 'about:blank'
    ], { stdio: ['ignore', 'ignore', 'pipe'] });
    chrome.stderr.pipe(process.stderr);
    let launchError;
    chrome.once('error', error => { launchError = error; });
    let activePort;
    for (let attempt = 0; attempt < 100; attempt++) {
        if (launchError) throw launchError;
        if (chrome.exitCode !== null) throw new Error('Chrome exited before DevTools became ready');
        try {
            activePort = (await readFile(path.join(profile, 'DevToolsActivePort'), 'utf8')).trim().split('\n');
            if (activePort.length >= 2) break;
        } catch (error) {
            if (error.code !== 'ENOENT') throw error;
        }
        await sleep(100);
    }
    if (!activePort || activePort.length < 2) throw new Error('Chrome DevTools startup timed out');
    socket = new WebSocket(`ws://127.0.0.1:${activePort[0]}${activePort[1]}`);
    socket.addEventListener('message', event => {
        const message = JSON.parse(event.data);
        if (message.id && pending.has(message.id)) {
            const callback = pending.get(message.id);
            pending.delete(message.id);
            if (message.error) callback.reject(new Error(JSON.stringify(message.error)));
            else callback.resolve(message.result);
        } else if (message.method) {
            const key = `${message.sessionId}:${message.method}`;
            if (events.has(key)) {
                events.get(key)(message.params);
                events.delete(key);
            }
        }
    });
    await new Promise((resolve, reject) => {
        socket.addEventListener('open', resolve, { once: true });
        socket.addEventListener('error', reject, { once: true });
    });
    const version = await command('Browser.getVersion');
    console.log('Testing ' + version.product);
    const { targetId } = await command('Target.createTarget', { url: 'about:blank' });
    const { sessionId } = await command('Target.attachToTarget', { targetId, flatten: true });
    await command('Page.enable', {}, sessionId);
    const loaded = new Promise(resolve => events.set(`${sessionId}:Page.loadEventFired`, resolve));
    await command('Page.navigate', { url: pageUrl }, sessionId);
    await loaded;
    const response = await command('Runtime.evaluate', {
        expression: `(${runInBrowser.toString()})(${JSON.stringify(transportUrl)},${JSON.stringify(certificateHash)})`,
        awaitPromise: true, returnByValue: true
    }, sessionId);
    if (response.exceptionDetails) throw new Error(JSON.stringify(response.exceptionDetails));
    console.log('WEBTRANSPORT_CHROME_OK ' + JSON.stringify(response.result.value));
}

main().catch(error => {
    console.error(error);
    process.exitCode = 1;
}).finally(async () => {
    await cleanup();
    if (process.exitCode) {
        try {
            const log = JSON.parse(await readFile(path.join(profile, 'netlog.json'), 'utf8'));
            const names = Object.fromEntries(Object.entries(log.constants.logEventTypes)
                .map(([name, value]) => [value, name]));
            for (const event of log.events) {
                const name = names[event.type];
                if (/CERT|WEB_TRANSPORT|QUIC_SESSION_CLOSED/.test(name)) {
                    console.error(name + ' ' + JSON.stringify(event.params));
                }
            }
        } catch (error) {
            console.error('Could not read Chrome network diagnostics: ' + error.message);
        }
    }
    clearTimeout(deadline);
});
