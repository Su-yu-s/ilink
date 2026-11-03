#!/usr/bin/env node
/**
 * pi Agent 的 HTTP/SSE 入口，供 Java 的 TeamAgentRunExecutor 调用。
 *
 * POST /v1/agent/run
 *   body: { teamId, userId, runId, question }
 *   header: x-ilink-internal-token
 *   response: text/event-stream
 *
 * 事件：run.started / tool.started / progress / answer / done / error
 */
import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { createAgent, describeEvent } from './agent.js';
import { config } from './config.js';

const MAX_BODY_BYTES = 64 * 1024;
const DEFAULT_TIMEOUT_MS = 120_000;

function readBody(request) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    request.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(new Error('BODY_TOO_LARGE'));
        request.destroy();
        return;
      }
      chunks.push(chunk);
    });
    request.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    request.on('error', reject);
  });
}

function sendEvent(response, event, data) {
  response.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
}

function tokenMatches(provided) {
  const expected = config.internalToken;
  if (!expected || !provided || provided.length !== expected.length) return false;
  let mismatch = 0;
  for (let i = 0; i < expected.length; i += 1) {
    mismatch |= provided.charCodeAt(i) ^ expected.charCodeAt(i);
  }
  return mismatch === 0;
}

function assistantText(message) {
  if (!message || message.role !== 'assistant') return '';
  if (typeof message.content === 'string') return message.content;
  if (!Array.isArray(message.content)) return '';
  return message.content
    .filter((part) => part?.type === 'text')
    .map((part) => part.text)
    .join('\n');
}

async function handleRun(request, response) {
  if (!tokenMatches(request.headers['x-ilink-internal-token'])) {
    response.writeHead(401, { 'content-type': 'application/json' });
    response.end(JSON.stringify({ error: 'INTERNAL_TOKEN_INVALID' }));
    return;
  }
  let payload;
  try {
    payload = JSON.parse(await readBody(request));
  } catch {
    response.writeHead(400, { 'content-type': 'application/json' });
    response.end(JSON.stringify({ error: 'INVALID_JSON' }));
    return;
  }
  const teamId = Number(payload.teamId);
  const userId = Number(payload.userId);
  const runId = Number(payload.runId) || undefined;
  const question = String(payload.question || '').trim();
  if (!teamId || !userId || !question) {
    response.writeHead(400, { 'content-type': 'application/json' });
    response.end(JSON.stringify({ error: 'MISSING_FIELDS' }));
    return;
  }

  response.writeHead(200, {
    'content-type': 'text/event-stream; charset=utf-8',
    'cache-control': 'no-store',
    connection: 'keep-alive',
    'x-accel-buffering': 'no'
  });
  sendEvent(response, 'run.started', { runId: runId ?? null });

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), DEFAULT_TIMEOUT_MS);
  let answer = '';
  let rounds = 0;
  let toolCalls = 0;
  let usage = { input: 0, output: 0, total: 0 };

  const agent = createAgent({
    context: { teamId, userId, runId },
    sessionId: runId ? `run-${runId}` : randomUUID()
  });
  agent.subscribe((event) => {
    if (event.type === 'tool_execution_start') {
      toolCalls += 1;
      sendEvent(response, 'tool.started', {
        tool: event.toolName,
        callId: event.toolCallId,
        displayName: '知识检索'
      });
      return;
    }
    if (event.type === 'tool_execution_end' && event.isError) {
      sendEvent(response, 'tool.failed', { tool: event.toolName, callId: event.toolCallId });
      return;
    }
    if (event.type === 'turn_start') {
      rounds = (event.turnIndex ?? rounds) + 1;
      return;
    }
    if (event.type === 'message_end') {
      const text = assistantText(event.message);
      if (text) answer = text;
      const messageUsage = event.message?.usage;
      if (messageUsage) {
        usage = {
          input: usage.input + (messageUsage.input ?? 0),
          output: usage.output + (messageUsage.output ?? 0),
          total: usage.total + (messageUsage.total ?? 0)
        };
      }
      return;
    }
    const summary = describeEvent(event);
    if (summary?.type === 'run.finished') {
      sendEvent(response, 'progress', { stage: 'DONE', safeText: '本轮检索结束' });
    }
  });

  const abort = () => agent.abort();
  controller.signal.addEventListener('abort', abort, { once: true });

  try {
    await agent.prompt(question);
    await agent.waitForIdle();
    sendEvent(response, 'answer', { answer, rounds, toolCalls, usage });
    sendEvent(response, 'done', { ok: true });
  } catch (failed) {
    sendEvent(response, 'error', {
      code: controller.signal.aborted ? 'AGENT_TIMEOUT' : 'AGENT_FAILED',
      message: failed instanceof Error ? failed.message.slice(0, 300) : 'Agent 执行失败'
    });
  } finally {
    clearTimeout(timer);
    controller.signal.removeEventListener('abort', abort);
    response.end();
  }
}

export function startServer(port = Number(process.env.ILINK_AGENT_PORT || 8787)) {
  const server = createServer(async (request, response) => {
    if (request.method === 'GET' && request.url === '/health') {
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end(JSON.stringify({ ok: true, javaBaseUrl: config.javaBaseUrl }));
      return;
    }
    if (request.method === 'POST' && request.url === '/v1/agent/run') {
      await handleRun(request, response);
      return;
    }
    response.writeHead(404, { 'content-type': 'application/json' });
    response.end(JSON.stringify({ error: 'NOT_FOUND' }));
  });
  server.listen(port, '127.0.0.1', () => {
    console.log(`pi agent listening on http://127.0.0.1:${port}`);
  });
  return server;
}

const entry = process.argv[1];
if (entry && import.meta.url === pathToFileURL(entry).href) {
  startServer();
}
