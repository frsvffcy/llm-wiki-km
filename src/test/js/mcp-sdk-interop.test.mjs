/**
 * Pinned Tier-1 SDK black-box interoperability procedure (#334, Finding D).
 *
 * This is NOT a required CI gate: it needs a live server with an enabled MCP adapter plus a
 * pinned `@modelcontextprotocol/sdk` install, so it self-skips unless MCP_SMOKE_URL is set.
 * Reproducible setup (pinned SDK 1.30.0, the latest legacy-era Tier-1 release):
 *
 *   npm install --prefix /tmp/mcp-sdk-probe @modelcontextprotocol/sdk@1.30.0
 *   MCP_ADAPTER_ENABLED=true MCP_ADAPTER_AUTH_TOKEN=<token> java -jar target/llm-wiki-km-*.jar
 *   MCP_SMOKE_URL=http://127.0.0.1:8765/api/mcp MCP_SMOKE_TOKEN=<token> \
 *     MCP_SDK_PATH=/tmp/mcp-sdk-probe/node_modules \
 *     node --test src/test/js/mcp-sdk-interop.test.mjs
 *
 * The script drives the default (legacy) SDK client end to end: initialize negotiation
 * (offers 2025-11-25, must receive the 2025-06-18 counter-offer), tools/list shape,
 * tools/call, legacy ping, and the unknown-tool typed error. Modern-era surfaces cannot be
 * driven by any released Tier-1 client today (latest published SDK is legacy-only; the
 * auto/discover flow exists only in unreleased main docs), so modern coverage stays with the
 * deterministic MockMvc contract tests; re-run this procedure when a Tier-1 SDK ships a
 * 2026-07-28 client.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const ENDPOINT = process.env.MCP_SMOKE_URL;
const TOKEN = process.env.MCP_SMOKE_TOKEN ?? '';
const SDK_PATH = process.env.MCP_SDK_PATH;

test('pinned SDK legacy interop against the live adapter', { skip: !ENDPOINT }, async () => {
    assert.ok(SDK_PATH, 'MCP_SDK_PATH must point at the pinned SDK install');
    const sdkRequire = createRequire(SDK_PATH + '/placeholder.js');
    const { Client } = sdkRequire('@modelcontextprotocol/sdk/client/index.js');
    const { StreamableHTTPClientTransport } =
        sdkRequire('@modelcontextprotocol/sdk/client/streamableHttp.js');
    const authHeaders = {
        'Content-Type': 'application/json',
        'Accept': 'application/json, text/event-stream',
        'Authorization': `Bearer ${TOKEN}`
    };

    // Raw wire evidence first: the counter-offer must be visible on the wire.
    const rawInit = await fetch(ENDPOINT, {
        method: 'POST',
        headers: authHeaders,
        body: JSON.stringify({ jsonrpc: '2.0', id: 'raw-1', method: 'initialize',
            params: { protocolVersion: '2025-11-25', capabilities: {},
                clientInfo: { name: 'probe', version: '1.0.0' } } })
    });
    const rawInitBody = await rawInit.json();
    assert.equal(rawInit.status, 200);
    assert.equal(rawInitBody.result?.protocolVersion, '2025-06-18');

    const transport = new StreamableHTTPClientTransport(new URL(ENDPOINT), {
        requestInit: { headers: { Authorization: `Bearer ${TOKEN}` } }
    });
    const client = new Client({ name: 'conformance-probe', version: '1.0.0' },
        { capabilities: {} });
    await client.connect(transport);

    const listed = await client.listTools();
    const names = (listed.tools ?? []).map((t) => t.name);
    for (const expected of ['km_status', 'km_search', 'km_retrieval_inspect',
        'km_source_locator', 'km_ask']) {
        assert.ok(names.includes(expected), `missing tool ${expected}`);
    }
    const askTool = (listed.tools ?? []).find((t) => t.name === 'km_ask');
    assert.equal(askTool?.inputSchema?.type, 'object');

    const called = await client.callTool({ name: 'km_status', arguments: {} });
    assert.equal(called.isError, false);

    await client.ping();

    const unknown = await client.callTool({ name: 'km_publish', arguments: {} });
    assert.equal(unknown.isError, true);

    await transport.close();
});
