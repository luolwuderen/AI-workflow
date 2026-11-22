// 轻量 OpenAI 兼容 mock，用于 E2E 验证 Agent 工具调用、分类器与 embedding。
// 启动：node tests/e2e/mock-openai.mjs  （默认监听 127.0.0.1:8081，可用 PORT 覆盖）
// 行为：分类器提示词返回固定类别 id；带 tool 消息的请求返回终答；无 tool 消息按请求声明的工具返回 tool_call；
//      /v1/embeddings 按文本是否包含“苹果”返回确定性向量。
import http from 'node:http';

const PORT = Number(process.env.PORT ?? 8081);
const HOST = '127.0.0.1';

function jsonResponse(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}

function toolCallFor(body) {
  const names = new Set(
    (Array.isArray(body.tools) ? body.tools : [])
      .map((tool) => tool?.function?.name)
      .filter(Boolean),
  );
  if (names.has('code')) {
    return {
      id: 'call_e2e_code',
      type: 'function',
      function: {
        name: 'code',
        arguments: JSON.stringify({
          language: 'python3',
          code: 'def main(**inputs):\n    return {"result": 6 * 7}',
        }),
      },
    };
  }
  return {
    id: 'call_e2e_1',
    type: 'function',
    function: { name: 'calculator', arguments: '{"expression":"6*7"}' },
  };
}

// 维度与平台默认 spring.ai.openai.embedding.dimensions=1536 保持一致。
function embeddingFor(text) {
  const vector = new Array(1536).fill(0);
  vector[String(text).includes('苹果') ? 0 : 1] = 1;
  return vector;
}

function handleEmbeddings(res, body) {
  const input = body?.input;
  const texts = Array.isArray(input) ? input : input === undefined ? [] : [input];
  jsonResponse(res, 200, {
    object: 'list',
    data: texts.map((text, index) => ({
      object: 'embedding',
      index,
      embedding: embeddingFor(text),
    })),
    model: body?.model ?? 'text-embedding-3-small',
    usage: { prompt_tokens: texts.length, total_tokens: texts.length },
  });
}

const server = http.createServer((req, res) => {
  if (req.method !== 'POST' || !req.url?.startsWith('/v1/')) {
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
    if (req.url.startsWith('/v1/embeddings')) {
      handleEmbeddings(res, body);
      return;
    }
    if (!req.url.startsWith('/v1/chat/completions')) {
      res.writeHead(404);
      res.end();
      return;
    }
    const messages = Array.isArray(body.messages) ? body.messages : [];
    const lastUser = [...messages].reverse().find((m) => m && m.role === 'user');
    const isClassifier = String(lastUser?.content ?? '').includes('只输出类别 id');
    if (isClassifier) {
      jsonResponse(res, 200, {
        id: 'chatcmpl-classifier',
        object: 'chat.completion',
        created: Math.floor(Date.now() / 1000),
        model: body.model ?? 'default',
        choices: [
          {
            index: 0,
            finish_reason: 'stop',
            message: { role: 'assistant', content: '1' },
          },
        ],
      });
      return;
    }
    const hasToolMessage = messages.some((m) => m && m.role === 'tool');
    if (hasToolMessage) {
      const toolMessage = [...messages].reverse().find((m) => m && m.role === 'tool');
      let answer = 'The result is 42';
      try {
        const parsed = JSON.parse(String(toolMessage?.content ?? ''));
        if (parsed && parsed.result !== undefined) answer = `The result is ${parsed.result}`;
      } catch {
        // 非 JSON 工具结果时保留默认终答
      }
      jsonResponse(res, 200, {
        id: 'chatcmpl-final',
        object: 'chat.completion',
        created: Math.floor(Date.now() / 1000),
        model: body.model ?? 'default',
        choices: [
          {
            index: 0,
            finish_reason: 'stop',
            message: { role: 'assistant', content: answer },
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
            tool_calls: [toolCallFor(body)],
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
