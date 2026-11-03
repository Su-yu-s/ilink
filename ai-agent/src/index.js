#!/usr/bin/env node
/**
 * 入口：
 *   node src/index.js --self-test        验证工具链（不调模型）
 *   node src/index.js "问题"              单轮 CLI 问答
 */
import { createAgent, describeEvent } from './agent.js';
import { config } from './config.js';
import { kgPingTool } from './tools/kg.js';
import { officeTools } from './tools/office.js';

async function selfTest() {
  const results = [];
  const ping = await kgPingTool().execute('self-1', {});
  results.push(['kg_ping', ping.content[0].text]);

  const [generate, list] = officeTools();
  for (const [format, spec] of [
    ['docx', { format: 'docx', title: '自检文档', blocks: [{ type: 'heading', text: '第一章' }, { type: 'paragraph', text: '内容' }] }],
    ['xlsx', { format: 'xlsx', title: '自检表格', sheets: [{ name: '任务', columns: [{ key: 'a', label: '任务' }], rows: [{ a: '联调' }] }] }],
    ['pptx', { format: 'pptx', title: '自检演示', slides: [{ title: '第一页', bullets: ['要点一', '要点二'] }] }],
    ['pdf', { format: 'pdf', title: '自检 PDF', paragraphs: ['这是自检段落。'] }]
  ]) {
    const result = await generate.execute(`self-${format}`, spec);
    results.push([`office_generate:${format}`, result.content[0].text]);
  }
  const listing = await list.execute('self-list', {});
  results.push(['office_list_workspace', listing.content[0].text]);

  for (const [name, output] of results) console.log(`[ok] ${name}: ${output}`);
  console.log(`工作区：${config.workspace}`);
}

async function ask(question) {
  if (!process.env[config.model.apiKeyEnv]) {
    console.error(`缺少环境变量 ${config.model.apiKeyEnv}，无法调用模型。`);
    process.exitCode = 1;
    return;
  }
  const context = {
    teamId: Number(process.env.ILINK_AGENT_TEAM_ID || 0),
    userId: Number(process.env.ILINK_AGENT_USER_ID || 0),
    runId: Number(process.env.ILINK_AGENT_RUN_ID || 0) || undefined
  };
  if (!context.teamId || !context.userId) {
    console.error('缺少 ILINK_AGENT_TEAM_ID / ILINK_AGENT_USER_ID，无法建立团队作用域。');
    process.exitCode = 1;
    return;
  }
  const agent = createAgent({ context });
  agent.subscribe((event) => {
    const summary = describeEvent(event);
    if (summary) console.log(JSON.stringify(summary));
  });
  await agent.prompt(question);
  await agent.waitForIdle();
  const last = [...agent.state.messages].reverse().find((message) => message.role === 'assistant');
  if (last) {
    const text = Array.isArray(last.content)
      ? last.content.filter((part) => part.type === 'text').map((part) => part.text).join('\n')
      : String(last.content ?? '');
    console.log(text);
  }
}

const args = process.argv.slice(2);
if (args.includes('--self-test')) {
  await selfTest();
} else if (args.length) {
  await ask(args.join(' '));
} else {
  console.log('用法：node src/index.js --self-test | node src/index.js "你的问题"');
}
