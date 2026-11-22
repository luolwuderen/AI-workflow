import { expect, test } from '@playwright/test';
import type { APIRequestContext, Page } from '@playwright/test';

const API = 'http://127.0.0.1:8080/api/v1';

type Graph = {
  version: 1;
  appType: 'WORKFLOW' | 'CHATFLOW';
  nodes: Array<Record<string, unknown>>;
  edges: Array<Record<string, unknown>>;
  viewport: { x: number; y: number; zoom: number };
};

async function createApp(request: APIRequestContext, name: string, appType: 'WORKFLOW' | 'CHATFLOW', graph?: Graph) {
  const createdResponse = await request.post(`${API}/apps`, { data: { name, appType } });
  expect(createdResponse.ok()).toBeTruthy();
  let app = await createdResponse.json();
  if (graph) {
    const savedResponse = await request.put(`${API}/apps/${app.id}/draft`, {
      data: { graph, expectedVersion: app.version },
    });
    expect(savedResponse.ok()).toBeTruthy();
    app = await savedResponse.json();
  }
  return app;
}

async function openApp(page: Page, name: string) {
  await page.goto('/');
  await expect(page.getByTestId('canvas')).toBeVisible();
  const switcher = page.getByLabel('打开应用');
  const appId = await switcher.locator('option', { hasText: name }).getAttribute('value');
  expect(appId).toBeTruthy();
  if (await switcher.inputValue() !== appId) {
    const loaded = page.waitForResponse((response) =>
      response.request().method() === 'GET' && new URL(response.url()).pathname === `/api/v1/apps/${appId}`);
    await switcher.selectOption({ label: name });
    await loaded;
  }
  await expect(switcher).toHaveValue(appId!);
  await expect(page.getByTestId('save-status')).toContainText('草稿已保存');
}

async function execute(page: Page, input?: string) {
  await page.getByTestId('run-button').click();
  if (input !== undefined) await page.locator('#run-text').fill(input);
  await page.getByRole('button', { name: '执行当前草稿' }).click();
  await expect(page.getByTestId('run-result')).toContainText('成功', { timeout: 20_000 });
}

function branchGraph(): Graph {
  return {
    version: 1,
    appType: 'WORKFLOW',
    viewport: { x: 0, y: 0, zoom: 0.85 },
    nodes: [
      { id: 'user-input', type: 'user-input', title: '输入', position: { x: 40, y: 220 }, config: { fields: [{ variable: 'text', label: '文本', type: 'text', required: true, default: 'UPPER' }] } },
      { id: 'condition', type: 'if-else', title: '判断大小写', position: { x: 330, y: 220 }, config: { cases: [{ id: 'case-1', logicalOperator: 'and', conditions: [{ variable: 'user-input.text', operator: 'equals', value: 'UPPER' }] }] } },
      { id: 'python', type: 'code', title: 'Python 大写', position: { x: 650, y: 70 }, config: { language: 'python3', code: 'def main(text):\n    return {"result": text.upper()}', inputs: [{ name: 'text', selector: 'user-input.text' }], outputs: { result: { type: 'string' } } } },
      { id: 'javascript', type: 'code', title: 'JavaScript 小写', position: { x: 650, y: 390 }, config: { language: 'javascript', code: 'function main({ text }) {\n  return { result: text.toLowerCase() };\n}', inputs: [{ name: 'text', selector: 'user-input.text' }], outputs: { result: { type: 'string' } } } },
      { id: 'python-output', type: 'output', title: 'Python 输出', position: { x: 960, y: 70 }, config: { mappings: [{ name: 'pythonResult', selector: 'python.result' }] } },
      { id: 'javascript-output', type: 'output', title: 'JavaScript 输出', position: { x: 960, y: 390 }, config: { mappings: [{ name: 'javascriptResult', selector: 'javascript.result' }] } },
    ],
    edges: [
      { id: 'e1', source: 'user-input', target: 'condition' },
      { id: 'e2', source: 'condition', target: 'python', sourceHandle: 'case-1' },
      { id: 'e3', source: 'condition', target: 'javascript', sourceHandle: 'else' },
      { id: 'e4', source: 'python', target: 'python-output' },
      { id: 'e5', source: 'javascript', target: 'javascript-output' },
    ],
  };
}

function parallelGraph(): Graph {
  return {
    version: 1,
    appType: 'WORKFLOW',
    viewport: { x: 0, y: 0, zoom: 0.85 },
    nodes: [
      { id: 'user-input', type: 'user-input', title: '输入', position: { x: 40, y: 220 }, config: { fields: [{ variable: 'text', label: '文本', type: 'text', required: true, default: 'E2E' }] } },
      { id: 'left', type: 'template', title: '左分支', position: { x: 340, y: 90 }, config: { template: 'left-{{user-input.text}}', variables: [] } },
      { id: 'right', type: 'template', title: '右分支', position: { x: 340, y: 360 }, config: { template: 'right-{{user-input.text}}', variables: [] } },
      { id: 'join', type: 'output', title: '汇合输出', position: { x: 660, y: 220 }, config: { mappings: [{ name: 'leftResult', selector: 'left.output' }, { name: 'rightResult', selector: 'right.output' }] } },
    ],
    edges: [
      { id: 'p1', source: 'user-input', target: 'left' },
      { id: 'p2', source: 'user-input', target: 'right' },
      { id: 'p3', source: 'left', target: 'join' },
      { id: 'p4', source: 'right', target: 'join' },
    ],
  };
}

test('并行分支全部执行并在汇合处合并结果', async ({ page, request }) => {
  const name = `E2E Parallel ${Date.now()}`;
  await createApp(request, name, 'WORKFLOW', parallelGraph());
  await openApp(page, name);

  await execute(page, 'Flow');
  await expect(page.getByTestId('run-result')).toContainText('left-Flow');
  await expect(page.getByTestId('run-result')).toContainText('right-Flow');
  await expect(page.locator('[data-node-id="left"]')).toHaveClass(/status-succeeded/);
  await expect(page.locator('[data-node-id="right"]')).toHaveClass(/status-succeeded/);
  await expect(page.locator('[data-node-id="join"]')).toHaveClass(/status-succeeded/);
});

function humanInputGraph(): Graph {
  return {
    version: 1,
    appType: 'WORKFLOW',
    viewport: { x: 0, y: 0, zoom: 0.85 },
    nodes: [
      { id: 'user-input', type: 'user-input', title: '输入', position: { x: 40, y: 220 }, config: { fields: [{ variable: 'text', label: '文本', type: 'text', required: true }] } },
      { id: 'human', type: 'human-input', title: '人工审批', position: { x: 340, y: 220 }, config: {
        prompt: '请处理 {{ user-input.text }}',
        fields: [
          { variable: 'note', label: '处理意见', type: 'paragraph', required: true },
          { variable: 'score', label: '评分', type: 'number', required: true, default: 1 },
          { variable: 'approved', label: '是否通过', type: 'boolean', required: true },
        ],
      } },
      { id: 'output', type: 'output', title: '审批结果', position: { x: 660, y: 220 }, config: { mappings: [
        { name: 'note', selector: 'human.note' },
        { name: 'score', selector: 'human.score' },
        { name: 'approved', selector: 'human.approved' },
      ] } },
    ],
    edges: [
      { id: 'h1', source: 'user-input', target: 'human' },
      { id: 'h2', source: 'human', target: 'output' },
    ],
  };
}

test('Human Input 暂停后提交表单并从原节点继续', async ({ page, request }) => {
  const name = `E2E Human Input ${Date.now()}`;
  await createApp(request, name, 'WORKFLOW', humanInputGraph());
  await openApp(page, name);

  await page.getByTestId('run-button').click();
  await page.locator('#run-text').fill('E2E Human');
  await page.getByRole('button', { name: '执行当前草稿' }).click();

  const form = page.getByTestId('human-input-form');
  await expect(form).toBeVisible({ timeout: 20_000 });
  await expect(form).toContainText('请处理 E2E Human');
  await expect(page.getByTestId('run-result')).toContainText('等待人工输入');
  await expect(page.locator('[data-node-id="human"]')).toHaveClass(/status-waiting/);
  await expect(page.getByTestId('run-events')).toContainText('RUN_PAUSED');

  await page.getByTestId('human-input-submit').click();
  await expect(form.locator('.field-error')).toContainText('请输入此项');
  await expect(form).toBeVisible();
  await page.locator('#human-human-note').fill('同意');
  await page.locator('#human-human-score').fill('3');
  await page.locator('#human-human-approved').check();
  await page.getByTestId('human-input-submit').click();

  await expect(page.getByTestId('run-result')).toContainText('成功', { timeout: 20_000 });
  await expect(form).toHaveCount(0);
  await expect(page.locator('.output-result')).toContainText('同意');
  await expect(page.locator('.output-result')).toContainText('"score": 3');
  await expect(page.locator('.output-result')).toContainText('"approved": true');
  await expect(page.locator('[data-node-id="human"]')).toHaveClass(/status-succeeded/);
  await expect(page.getByTestId('run-events')).toContainText('NODE_RESUMED');
  await expect(page.getByTestId('run-events')).toContainText('RUN_RESUMED');
});

test('Workflow 默认图真实运行并保留草稿', async ({ page, request }) => {
  const name = `E2E Workflow ${Date.now()}`;
  await createApp(request, name, 'WORKFLOW');
  await openApp(page, name);
  await expect(page.locator('[data-node-id="user-input"]')).toBeVisible();
  await execute(page, 'Hello E2E');
  await expect(page.getByTestId('run-result')).toContainText('Hello E2E');
  await expect(page.getByTestId('run-events')).toContainText('RUN_SUCCEEDED');
  await page.reload();
  await expect(page.locator('[data-node-id="output"]')).toBeVisible();
});

test('Chatflow Answer 流式事件与结果可见', async ({ page, request }) => {
  const name = `E2E Chatflow ${Date.now()}`;
  await createApp(request, name, 'CHATFLOW');
  await openApp(page, name);
  await execute(page, '流织');
  await expect(page.getByTestId('run-result')).toContainText('你好，流织');
  await expect(page.getByTestId('run-events')).toContainText('ANSWER_DELTA');
});

test('节点目录拖拽、未支持标记与刷新恢复', async ({ page, request }) => {
  const name = `E2E Editor ${Date.now()}`;
  await createApp(request, name, 'WORKFLOW');
  await openApp(page, name);
  await page.getByTestId('node-search').fill('tool');
  const tool = page.locator('[data-node-type="tool"]');
  await expect(tool).toBeVisible();
  await tool.dragTo(page.getByTestId('canvas'), { targetPosition: { x: 650, y: 350 } });
  await expect(page.locator('.workflow-node.unsupported')).toHaveCount(1);
  await expect(page.getByTestId('inspector')).toContainText('不可执行');
  await expect(page.getByTestId('save-status')).toContainText('草稿已保存');
  await page.reload();
  await expect(page.locator('.workflow-node.unsupported')).toHaveCount(1);
  await page.getByTestId('validate-button').click();
  await expect(page.locator('.validation-dock')).toContainText('运行时尚未支持');
});

test('IF 真分支执行 Python，假分支执行 JavaScript', async ({ page, request }) => {
  const name = `E2E Branches ${Date.now()}`;
  await createApp(request, name, 'WORKFLOW', branchGraph());
  await openApp(page, name);

  await execute(page, 'UPPER');
  await expect(page.getByTestId('run-result')).toContainText('pythonResult');
  await expect(page.getByTestId('run-result')).toContainText('UPPER');
  await expect(page.locator('[data-node-id="javascript"]')).toHaveClass(/status-skipped/);

  await execute(page, 'MiXeD');
  await expect(page.getByTestId('run-result')).toContainText('javascriptResult');
  await expect(page.getByTestId('run-result')).toContainText('mixed');
  await expect(page.locator('[data-node-id="python"]')).toHaveClass(/status-skipped/);
});

function iterationGraph(): Graph {
  return {
    version: 1,
    appType: 'WORKFLOW',
    viewport: { x: 0, y: 0, zoom: 0.85 },
    nodes: [
      { id: 'user-input', type: 'user-input', title: '输入', position: { x: 40, y: 220 }, config: { fields: [{ variable: 'items', label: '列表', type: 'json', required: true, default: [1, 2, 3] }] } },
      { id: 'iteration', type: 'iteration', title: '逐项迭代', position: { x: 340, y: 220 }, config: { inputSelector: 'user-input.items', outputSelector: 'body.output', parallel: true, maxParallelism: 3 } },
      { id: 'body', type: 'template', title: '迭代体', position: { x: 370, y: 260 }, config: { template: 'v-{{item}}', variables: [] }, parentId: 'iteration' },
      { id: 'output', type: 'output', title: '输出', position: { x: 700, y: 220 }, config: { mappings: [{ name: 'list', selector: 'iteration.output' }] } },
    ],
    edges: [
      { id: 'i1', source: 'user-input', target: 'iteration' },
      { id: 'i2', source: 'iteration', target: 'output' },
    ],
  };
}

function loopGraph(): Graph {
  return {
    version: 1,
    appType: 'WORKFLOW',
    viewport: { x: 0, y: 0, zoom: 0.85 },
    nodes: [
      { id: 'user-input', type: 'user-input', title: '输入', position: { x: 40, y: 220 }, config: { fields: [{ variable: 'text', label: '文本', type: 'text', required: true, default: 'go' }] } },
      { id: 'loop', type: 'loop', title: '循环', position: { x: 340, y: 220 }, config: { maxIterations: 3, outputSelector: 'loop-body.output' } },
      { id: 'loop-body', type: 'template', title: '循环体', position: { x: 370, y: 260 }, config: { template: 'tick-{{index}}', variables: [] }, parentId: 'loop' },
      { id: 'loop-exit', type: 'exit-loop', title: '退出条件', position: { x: 540, y: 260 }, config: { conditions: [{ variable: 'loop-body.output', operator: 'equals', value: 'tick-1' }], logicalOperator: 'and' }, parentId: 'loop' },
      { id: 'output', type: 'output', title: '输出', position: { x: 760, y: 220 }, config: { mappings: [{ name: 'loopResult', selector: 'loop.output' }, { name: 'iterations', selector: 'loop.iterations' }] } },
    ],
    edges: [
      { id: 'l1', source: 'user-input', target: 'loop' },
      { id: 'l2', source: 'loop', target: 'output' },
      { id: 'l3', source: 'loop-body', target: 'loop-exit' },
    ],
  };
}

test('Iteration 逐项执行并收集每项输出', async ({ page, request }) => {
  const name = `E2E Iteration ${Date.now()}`;
  await createApp(request, name, 'WORKFLOW', iterationGraph());
  await openApp(page, name);

  await expect(page.locator('[data-node-id="iteration"]')).toContainText('1 个子节点');
  await page.locator('[data-node-id="body"]').click();
  await expect(page.locator('#node-scope')).toHaveValue('iteration');
  await expect(page.locator('[data-node-id="body"]')).toContainText('作用域：逐项迭代');

  await execute(page);
  await expect(page.getByTestId('run-result')).toContainText('v-1');
  await expect(page.getByTestId('run-result')).toContainText('v-3');
  await expect(page.locator('[data-node-id="iteration"]')).toHaveClass(/status-succeeded/);
  await expect(page.locator('[data-node-id="body"]')).toHaveClass(/status-succeeded/);
});

test('Loop 在 Exit Loop 条件命中时中断', async ({ page, request }) => {
  const name = `E2E Loop ${Date.now()}`;
  await createApp(request, name, 'WORKFLOW', loopGraph());
  await openApp(page, name);

  await execute(page);
  await expect(page.getByTestId('run-result')).toContainText('tick-1');
  await expect(page.getByTestId('run-result')).toContainText('iterations');
  await expect(page.locator('[data-node-id="loop"]')).toHaveClass(/status-succeeded/);
  await expect(page.locator('[data-node-id="loop-exit"]')).toHaveClass(/status-succeeded/);
});

function agentGraph(): Graph {
  return {
    version: 1,
    appType: 'WORKFLOW',
    viewport: { x: 0, y: 0, zoom: 0.85 },
    nodes: [
      { id: 'user-input', type: 'user-input', title: '输入', position: { x: 40, y: 220 }, config: { fields: [{ variable: 'text', label: '文本', type: 'text', required: true, default: '6*7' }] } },
      { id: 'agent', type: 'agent', title: '智能体', position: { x: 340, y: 220 }, config: {
        model: 'default',
        instruction: 'Compute {{user-input.text}}',
        strategy: 'function_call',
        tools: ['calculator'],
        maxIterations: 5,
      } },
      { id: 'output', type: 'output', title: '输出', position: { x: 660, y: 220 }, config: { mappings: [{ name: 'result', selector: 'agent.text' }] } },
    ],
    edges: [
      { id: 'a1', source: 'user-input', target: 'agent' },
      { id: 'a2', source: 'agent', target: 'output' },
    ],
  };
}

test('Agent 调用 calculator 工具并返回终答，事件含 AGENT_TOOL_CALL', async ({ page, request }) => {
  const name = `E2E Agent ${Date.now()}`;
  await createApp(request, name, 'WORKFLOW', agentGraph());
  await openApp(page, name);

  await execute(page, '6*7');
  await expect(page.getByTestId('run-result')).toContainText('42');
  await expect(page.locator('[data-node-id="agent"]')).toHaveClass(/status-succeeded/);
  await expect(page.getByTestId('run-events')).toContainText('AGENT_TOOL_CALL');
});

test('Agent 调用 code 工具在沙箱执行并返回终答', async ({ page, request }) => {
  const name = `E2E Agent ${Date.now()}`;
  const graph = agentGraph();
  const agent = graph.nodes.find((node) => node.id === 'agent');
  if (agent) agent.config = { ...agent.config, tools: ['code'] };
  await createApp(request, name, 'WORKFLOW', graph);
  await openApp(page, name);

  await execute(page, '6*7');
  await expect(page.getByTestId('run-result')).toContainText('42');
  await expect(page.locator('[data-node-id="agent"]')).toHaveClass(/status-succeeded/);
  await expect(page.getByTestId('run-events')).toContainText('AGENT_TOOL_CALL');
});

function classifierGraph(): Graph {
  return {
    version: 1,
    appType: 'WORKFLOW',
    viewport: { x: 0, y: 0, zoom: 0.85 },
    nodes: [
      { id: 'user-input', type: 'user-input', title: '输入', position: { x: 40, y: 220 }, config: { fields: [{ variable: 'text', label: '文本', type: 'text', required: true, default: '苹果' }] } },
      { id: 'classifier', type: 'question-classifier', title: '问题分类', position: { x: 340, y: 220 }, config: {
        model: 'default',
        query: '{{user-input.text}}',
        classes: [{ id: '1', name: '水果' }, { id: '2', name: '其他' }],
        instruction: '',
        temperature: 0.2,
        topP: 1,
        maxTokens: 1024,
      } },
      { id: 'fruit-output', type: 'output', title: '水果输出', position: { x: 660, y: 90 }, config: { mappings: [{ name: 'fruit', selector: 'classifier.class_name' }] } },
      { id: 'other-output', type: 'output', title: '其他输出', position: { x: 660, y: 360 }, config: { mappings: [{ name: 'other', selector: 'classifier.class_name' }] } },
    ],
    edges: [
      { id: 'c1', source: 'user-input', target: 'classifier' },
      { id: 'c2', source: 'classifier', target: 'fruit-output', sourceHandle: '1' },
      { id: 'c3', source: 'classifier', target: 'other-output', sourceHandle: '2' },
    ],
  };
}

test('问题分类器按模型返回的类别 id 选择分支', async ({ page, request }) => {
  const name = `E2E Classifier ${Date.now()}`;
  await createApp(request, name, 'WORKFLOW', classifierGraph());
  await openApp(page, name);

  await execute(page, '苹果');
  await expect(page.getByTestId('run-result')).toContainText('水果');
  await expect(page.locator('[data-node-id="classifier"]')).toHaveClass(/status-succeeded/);
  await expect(page.locator('[data-node-id="fruit-output"]')).toHaveClass(/status-succeeded/);
  await expect(page.locator('[data-node-id="other-output"]')).toHaveClass(/status-skipped/);
});

test('知识检索命中文档并把上下文传给下游', async ({ page, request }) => {
  const knowledgeName = `E2E Knowledge ${Date.now()}`;
  const createdBase = await request.post(`${API}/knowledge-bases`, { data: { name: knowledgeName, description: 'e2e' } });
  expect(createdBase.ok()).toBeTruthy();
  const knowledgeBaseId = (await createdBase.json()).id;
  const createdDocument = await request.post(`${API}/knowledge-bases/${knowledgeBaseId}/documents`, {
    data: { name: '水果简介', content: '苹果是一种常见水果。' },
  });
  expect(createdDocument.ok()).toBeTruthy();

  const graph: Graph = {
    version: 1,
    appType: 'WORKFLOW',
    viewport: { x: 0, y: 0, zoom: 0.85 },
    nodes: [
      { id: 'user-input', type: 'user-input', title: '输入', position: { x: 40, y: 220 }, config: { fields: [{ variable: 'text', label: '文本', type: 'text', required: true, default: '苹果' }] } },
      { id: 'retrieval', type: 'knowledge-retrieval', title: '知识检索', position: { x: 340, y: 220 }, config: {
        datasetIds: [knowledgeBaseId],
        query: '{{user-input.text}}',
        topK: 2,
        scoreThreshold: 0.5,
      } },
      { id: 'output', type: 'output', title: '输出', position: { x: 660, y: 220 }, config: { mappings: [
        { name: 'context', selector: 'retrieval.context' },
        { name: 'count', selector: 'retrieval.count' },
      ] } },
    ],
    edges: [
      { id: 'k1', source: 'user-input', target: 'retrieval' },
      { id: 'k2', source: 'retrieval', target: 'output' },
    ],
  };

  const name = `E2E Retrieval ${Date.now()}`;
  await createApp(request, name, 'WORKFLOW', graph);
  await openApp(page, name);

  // 知识库面板可直接查看文档正文
  await page.getByTestId('open-knowledge').click();
  await expect(page.getByTestId('knowledge-panel')).toBeVisible();
  await page.getByTestId('knowledge-panel').getByText(knowledgeName, { exact: true }).click();
  await page.getByTestId('document-toggle').first().click();
  await expect(page.getByTestId('document-detail')).toContainText('苹果是一种常见水果');
  await page.getByTestId('close-knowledge').click();

  // 节点内可视化选择知识库，并可即时测试检索
  await page.locator('[data-node-id="retrieval"]').click();
  const knowledgeSelect = page.getByTestId('knowledge-select');
  await expect(knowledgeSelect).toContainText(knowledgeName);
  await expect(knowledgeSelect).toContainText('已选 1 个知识库');
  const firstBase = knowledgeSelect.locator('input[type="checkbox"]').first();
  await firstBase.uncheck();
  await expect(knowledgeSelect).toContainText('已选 0 个知识库');
  await firstBase.check();
  await expect(knowledgeSelect).toContainText('已选 1 个知识库');
  await page.getByTestId('retrieval-test-query').fill('苹果');
  await page.getByTestId('retrieval-test').getByRole('button', { name: '测试' }).click();
  await expect(page.getByTestId('retrieval-test')).toContainText('水果简介');

  await execute(page, '苹果');
  await expect(page.getByTestId('run-result')).toContainText('苹果是一种常见水果');
  await expect(page.locator('[data-node-id="retrieval"]')).toHaveClass(/status-succeeded/);
});

test('桌面与窄屏布局稳定，撤销重做快捷键可用', async ({ page, request }) => {
  const name = `E2E Layout ${Date.now()}`;
  const longTitle = '用户输入节点 · 这是一个用于验证长文本省略与布局稳定性的超长节点名称 · User Input';
  const modifier = process.platform === 'darwin' ? 'Meta' : 'Control';
  await createApp(request, name, 'WORKFLOW');
  await page.setViewportSize({ width: 1440, height: 960 });
  await openApp(page, name);

  const inspector = page.getByTestId('inspector');
  await expect(inspector).toHaveClass(/no-selection/);
  await expect(inspector).toContainText('选择一个节点');
  await page.locator('[data-node-id="user-input"]').click();
  await expect(inspector).toHaveClass(/has-selection/);

  const titleInput = page.locator('#node-title');
  const nodeTitle = page.locator('[data-node-id="user-input"] .node-content strong');
  const originalTitle = await titleInput.inputValue();
  await titleInput.fill(longTitle);
  await expect(nodeTitle).toHaveText(longTitle);
  await expect(nodeTitle).toHaveCSS('text-overflow', 'ellipsis');
  expect(await nodeTitle.evaluate((element) => element.scrollWidth > element.clientWidth)).toBeTruthy();
  expect((await page.locator('[data-node-id="user-input"]').boundingBox())?.width).toBe(220);

  await titleInput.evaluate((element: HTMLInputElement) => element.blur());
  await page.keyboard.press(`${modifier}+z`);
  await expect(titleInput).toHaveValue(originalTitle);
  await expect(nodeTitle).toHaveText(originalTitle);
  await page.keyboard.press(`Shift+${modifier}+z`);
  await expect(titleInput).toHaveValue(longTitle);
  await expect(nodeTitle).toHaveText(longTitle);
  await expect(page.getByTestId('save-status')).toContainText('草稿已保存');

  for (const viewport of [{ width: 1440, height: 960 }, { width: 800, height: 860 }]) {
    await page.setViewportSize(viewport);
    const dimensions = await page.evaluate(() => ({
      viewport: window.innerWidth,
      document: document.documentElement.scrollWidth,
      body: document.body.scrollWidth,
    }));
    expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
    expect(dimensions.body).toBeLessThanOrEqual(dimensions.viewport);

    for (const selector of ['.app-header', '.workbench', '[data-testid="canvas"]', '[data-testid="inspector"]', '[data-testid="save-status"]', '[data-testid="new-app-button"]']) {
      const box = await page.locator(selector).boundingBox();
      expect(box, selector).not.toBeNull();
      expect(box!.x, selector).toBeGreaterThanOrEqual(0);
      expect(box!.y, selector).toBeGreaterThanOrEqual(0);
      expect(box!.x + box!.width, selector).toBeLessThanOrEqual(viewport.width + 1);
      expect(box!.y + box!.height, selector).toBeLessThanOrEqual(viewport.height + 1);
    }
  }

  await expect(inspector).toHaveClass(/has-selection/);
  await page.getByRole('button', { name: '取消选择' }).click();
  await expect(inspector).toHaveClass(/no-selection/);
  await expect(inspector).toBeHidden();
});
