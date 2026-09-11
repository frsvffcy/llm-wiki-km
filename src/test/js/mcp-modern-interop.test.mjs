/**
 * Pinned Tier-1 SDK v2 modern-wire black-box interoperability procedure (#340).
 *
 * This is NOT a required CI gate: it needs a live server with an enabled MCP adapter plus a
 * pinned `@modelcontextprotocol/client` install, so it self-skips unless MCP_SMOKE_URL is set.
 * Reproducible setup (pinned exact versions, never `latest`):
 *
 *   npm install --prefix /tmp/mcp-v2-probe @modelcontextprotocol/client@2.0.0
 *   MCP_ADAPTER_ENABLED=true MCP_ADAPTER_AUTH_TOKEN=<token> java -jar target/llm-wiki-km-*.jar
 *   MCP_SMOKE_URL=http://127.0.0.1:8765/api/mcp MCP_SMOKE_TOKEN=<token> \
 *     MCP_V2_SDK_PATH=/tmp/mcp-v2-probe/node_modules \
 *     node --test src/test/js/mcp-modern-interop.test.mjs
 *
 * The script drives the official v2 client end to end on the 2026-07-28 era: `auto`
 * negotiation must select `modern` (era asserted via `getProtocolEra()`, never inferred
 * from success alone), pinned connect, tools/list shape, tools/call success paths
 * (a workspace is created via REST under os.tmpdir so the with-arguments calls can
 * succeed — the previously active workspace is restored afterwards; scratch servers
 * only), modern error behavior (unknown tool and invalid arguments surface typed
 * `-32602`, verified both through the client and at raw HTTP level), `ping` is not
 * a legal modern method, stateless repeatability, loud pin-version mismatch, and the
 * legacy-fallback path against a bounded legacy-only stub fixture. Re-run this procedure
 * when Tier-1 ships a newer v2 line or when the modern wire surface changes.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import fs from 'node:fs/promises';

const SMOKE_URL = process.env.MCP_SMOKE_URL;
const SMOKE_TOKEN = process.env.MCP_SMOKE_TOKEN;
const SDK_PATH = process.env.MCP_V2_SDK_PATH;

test('modern Tier-1 v2 interop', { skip: !SMOKE_URL || !SMOKE_TOKEN || !SDK_PATH ? 'needs MCP_SMOKE_URL/MCP_SMOKE_TOKEN/MCP_V2_SDK_PATH' : false }, async (t) => {
    assert.match(SMOKE_URL, /\/api\/mcp\/?$/, 'MCP_SMOKE_URL must be the MCP endpoint (.../api/mcp)');
    const require = createRequire(import.meta.url);
    const sdk = require(`${SDK_PATH}/@modelcontextprotocol/client/dist/index.mjs`);
    const sdkPackage = require(`${SDK_PATH}/@modelcontextprotocol/client/package.json`);
    assert.equal(sdkPackage.version, '2.0.0');

    const { Client, StreamableHTTPClientTransport, UnsupportedProtocolVersionError } = sdk;
    assert.equal(typeof UnsupportedProtocolVersionError, 'function');
    const base = SMOKE_URL.replace(/\/api\/mcp\/?$/, '');
    const modernMeta = {
        'io.modelcontextprotocol/protocolVersion': '2026-07-28',
        'io.modelcontextprotocol/clientInfo': { name: 'llm-wiki-km-modern-probe', version: '0.0.1' },
        'io.modelcontextprotocol/clientCapabilities': {},
    };
    const modernHeaders = (method, name) => {
        const headers = {
            authorization: `Bearer ${SMOKE_TOKEN}`,
            'content-type': 'application/json',
            accept: 'application/json, text/event-stream',
            'mcp-protocol-version': '2026-07-28',
            'mcp-method': method,
        };
        if (name) headers['mcp-name'] = name;
        return headers;
    };
    const postToolCall = (id, name, args) => fetch(SMOKE_URL, {
        method: 'POST',
        headers: modernHeaders('tools/call', name),
        body: JSON.stringify({ jsonrpc: '2.0', id, method: 'tools/call', params: { name, arguments: args, _meta: modernMeta } }),
    });
    const connect = (mode, url = SMOKE_URL) => {
        const client = new Client({ name: 'llm-wiki-km-modern-probe', version: '0.0.1' }, { versionNegotiation: { mode } });
        const transport = new StreamableHTTPClientTransport(new URL(url), {
            authProvider: { token: async () => SMOKE_TOKEN },
        });
        return { client, transport };
    };

    await t.test('auto negotiation selects modern, tools/list/call succeed', async () => {
        const { client, transport } = connect('auto');
        try {
            await client.connect(transport);
            assert.equal(client.getProtocolEra(), 'modern');

            const listed = await client.listTools();
            const names = (listed.tools ?? []).map((tool) => tool.name).sort();
            assert.deepEqual(names, ['km_ask', 'km_retrieval_inspect', 'km_search', 'km_source_locator', 'km_status']);
            const askTool = (listed.tools ?? []).find((tool) => tool.name === 'km_ask');
            assert.equal(askTool?.inputSchema?.type, 'object');
            assert.deepEqual(askTool?.inputSchema?.required, ['question']);

            const status = await client.callTool({ name: 'km_status', arguments: {} });
            assert.equal(status.isError, false);
            assert.equal(status.content?.[0]?.type, 'text');

            // Scratch-server workspace so workspace-scoped tools can succeed end to end.
            // The previously active workspace (if any) is restored afterwards: this probe
            // must not permanently hijack the server's global active workspace.
            const before = await (await fetch(`${base}/api/v1/workspaces/current`)).json();
            const previousId = before?.data?.workspace?.id ?? null;
            const rootPath = await fs.mkdtemp(path.join(os.tmpdir(), 'mcp-modern-probe-'));
            try {
                const created = await fetch(`${base}/api/v1/workspaces`, {
                    method: 'POST',
                    headers: { 'content-type': 'application/json' },
                    body: JSON.stringify({ name: `modern-probe-${Date.now()}`, rootPath }),
                });
                assert.equal(created.status, 201);

                const search = await client.callTool({ name: 'km_search', arguments: { query: 'probe' } });
                assert.equal(search.isError, false);
                assert.ok(search.structuredContent && typeof search.structuredContent === 'object');

                const inspect = await client.callTool({ name: 'km_retrieval_inspect', arguments: { question: 'probe?' } });
                assert.equal(inspect.isError, false);
            } finally {
                if (previousId !== null) {
                    await fetch(`${base}/api/v1/workspaces/current`, {
                        method: 'PUT',
                        headers: { 'content-type': 'application/json' },
                        body: JSON.stringify({ workspaceId: previousId }),
                    });
                }
            }

            const again = await client.listTools();
            assert.deepEqual((again.tools ?? []).map((tool) => tool.name).sort(), names);
        } finally {
            await client.close();
        }
    });

    await t.test('pinned connect succeeds; pinned mismatch fails loudly without fallback', async () => {
        const { client, transport } = connect({ pin: '2026-07-28' });
        try {
            await client.connect(transport);
            assert.equal(client.getProtocolEra(), 'modern');
        } finally {
            await client.close();
        }

        const bad = connect({ pin: '2026-09-99' });
        let thrown = null;
        try {
            await bad.client.connect(bad.transport);
        } catch (error) {
            thrown = error;
        }
        assert.ok(thrown, 'pinned mismatch must reject, never silently fall back');
        assert.ok(thrown instanceof UnsupportedProtocolVersionError,
            `expected UnsupportedProtocolVersionError, got ${thrown?.constructor?.name}: ${thrown?.message}`);
    });

    await t.test('modern error plane is typed: unknown tool and invalid args are -32602', async () => {
        const { client, transport } = connect({ pin: '2026-07-28' });
        try {
            await client.connect(transport);
            assert.equal(client.getProtocolEra(), 'modern');

            let unknown = null;
            try {
                await client.callTool({ name: 'km_publish', arguments: {} });
            } catch (error) {
                unknown = error;
            }
            assert.ok(unknown, 'unknown tool must reject');
            assert.equal(unknown.code, -32602);

            let invalid = null;
            try {
                await client.callTool({ name: 'km_search', arguments: { query: 'q', size: '200' } });
            } catch (error) {
                invalid = error;
            }
            assert.ok(invalid, 'invalid arguments must reject, not return a result');
            assert.equal(invalid.code, -32602);
            assert.match(invalid.message, /must be an integer/);
            assert.doesNotMatch(invalid.message, /Bearer|apiKey|RID:|\/Users\//);

            let pinged = null;
            try {
                await client.ping();
            } catch (error) {
                pinged = error;
            }
            assert.ok(pinged, 'ping is not a legal modern method and must not resolve');
            assert.equal(pinged.code, 'METHOD_NOT_SUPPORTED_BY_PROTOCOL_VERSION');
        } finally {
            await client.close();
        }

        // Raw-wire proof of the exact HTTP mapping the decision rests on: modern
        // unknown-tool and invalid-args are 400 + JSON-RPC -32602 envelopes (a server
        // regression back to 200 envelopes or other statuses would still surface as
        // -32602 through the client and hide here), with no minted session id.
        const unknownRaw = await postToolCall('raw-unknown', 'km_publish', {});
        assert.equal(unknownRaw.status, 400);
        assert.equal(unknownRaw.headers.get('mcp-session-id'), null);
        const unknownBody = await unknownRaw.json();
        assert.equal(unknownBody.error?.code, -32602);

        const invalidRaw = await postToolCall('raw-invalid', 'km_search', { query: 'q', size: '200' });
        assert.equal(invalidRaw.status, 400);
        const invalidBody = await invalidRaw.json();
        assert.equal(invalidBody.error?.code, -32602);
        assert.match(invalidBody.error?.message ?? '', /must be an integer/);

        // Server-side ping rejection stays spec-mandated 404 + -32601.
        const pingRaw = await fetch(SMOKE_URL, {
            method: 'POST',
            headers: modernHeaders('ping'),
            body: JSON.stringify({ jsonrpc: '2.0', id: 'raw-ping', method: 'ping', params: { _meta: modernMeta } }),
        });
        assert.equal(pingRaw.status, 404);
        assert.equal((await pingRaw.json()).error?.code, -32601);
    });

    await t.test('auto falls back to legacy only against a legacy-only endpoint', async () => {
        // Client-capability check (not our server: our dual-era endpoint always offers
        // modern, so fallback can never trigger there): the same v2 client must reach
        // the legacy era when discover is unrecognized, proving era reporting is honest
        // and modern regressions cannot hide behind silent fallback.
        const stub = http.createServer((req, res) => {
            let body = '';
            req.on('data', (chunk) => (body += chunk));
            req.on('end', () => {
                const message = JSON.parse(body || '{}');
                if (message.method === 'server/discover') {
                    res.writeHead(404, { 'content-type': 'application/json' }).end('{}');
                } else if (message.method === 'initialize') {
                    res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({
                        jsonrpc: '2.0',
                        id: message.id,
                        result: {
                            protocolVersion: '2025-06-18',
                            capabilities: { tools: {} },
                            serverInfo: { name: 'legacy-stub', version: '0' },
                        },
                    }));
                } else if (message.method === 'notifications/initialized') {
                    res.writeHead(202).end();
                } else if (message.method === 'tools/list') {
                    res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({
                        jsonrpc: '2.0',
                        id: message.id,
                        result: { tools: [{ name: 'stub_tool', inputSchema: { type: 'object' } }] },
                    }));
                } else {
                    res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({
                        jsonrpc: '2.0', id: message.id, error: { code: -32601, message: 'nope' },
                    }));
                }
            });
        });
        await new Promise((resolve) => stub.listen(0, '127.0.0.1', resolve));
        const address = stub.address();
        try {
            const { client, transport } = connect('auto', `http://127.0.0.1:${address.port}/`);
            await client.connect(transport);
            assert.equal(client.getProtocolEra(), 'legacy');
            const listed = await client.listTools();
            assert.deepEqual((listed.tools ?? []).map((tool) => tool.name), ['stub_tool']);
            await client.close();
        } finally {
            stub.close();
        }
    });
});
