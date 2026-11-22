// 轻量 OpenAI 兼容 mock，用于 E2E 验证 Agent 工具调用往返。
// 启动：node tests/e2e/mock-openai.mjs  （默认监听 127.0.0.1:8081，可用 PORT 覆盖）
// 行为：首次请求（无 tool 消息）返回 calculator 工具调用；携带 tool 消息的后续请求返回终答。
import http from 'node:http';

const PORT = Number(process.env.PORT ?? 8081);
const HOST = '127.0.0.1';

function jsonResponse(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}

const server = http.createServer((req, res) => {
  if (req.method !== 'POST' || !req.url?.startsWith('/v1/chat/completions')) {
    res.writeHead(404);
    res.end();
    return;
  }
  const chunks = [];
  req.on('data', (chunk) => chunks.push(chunk));
  req.on('end', () => {
    let body = {};
    try {
      body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    } catch {
      body = {};
    }
    const messages = Array.isArray(body.messages) ? body.messages : [];
    const hasToolMessage = messages.some((m) => m && m.role === 'tool');
    if (hasToolMessage) {
      jsonResponse(res, 200, {
        id: 'chatcmpl-final',
        object: 'chat.completion',
        created: Math.floor(Date.now() / 1000),
        model: body.model ?? 'default',
        choices: [
          {
            index: 0,
            finish_reason: 'stop',
            message: { role: 'assistant', content: 'The result is 42' },
          },
        ],
      });
      return;
    }
    jsonResponse(res, 200, {
      id: 'chatcmpl-tool',
      object: 'chat.completion',
      created: Math.floor(Date.now() / 1000),
      model: body.model ?? 'default',
      choices: [
        {
          index: 0,
          finish_reason: 'tool_calls',
          message: {
            role: 'assistant',
            content: null,
            tool_calls: [
              {
                id: 'call_e2e_1',
                type: 'function',
                function: { name: 'calculator', arguments: '{"expression":"6*7"}' },
              },
            ],
          },
        },
      ],
    });
  });
});

server.listen(PORT, HOST, () => {
  console.log(`[mock-openai] listening on http://${HOST}:${PORT}/v1`);
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    server.close(() => process.exit(0));
  });
}
