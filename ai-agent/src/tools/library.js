/**
 * 资料库写入工具：文件先上传到 Java 的 library 暂存目录，
 * 同时生成 CREATE_LIBRARY_FILE / UPDATE_LIBRARY_FILE 动作草案；
 * 用户确认后才登记资料库。
 *
 * 这里不接受任意本地路径，只允许读取沙箱 workspace 内的文件。
 */
import { readFile, stat } from 'node:fs/promises';
import { resolve, relative, isAbsolute, basename } from 'node:path';
import { config } from '../config.js';
import { asToolResult } from './kg.js';

const MAX_BYTES = 20 * 1024 * 1024;
const ALLOWED_EXTENSIONS = ['.docx', '.xlsx', '.pptx', '.pdf', '.md', '.txt', '.csv'];

function resolveInWorkspace(input) {
  const path = isAbsolute(input) ? resolve(input) : resolve(config.workspace, input);
  const rel = relative(config.workspace, path);
  if (rel.startsWith('..') || isAbsolute(rel)) {
    throw new Error('只能上传沙箱 workspace 内的文件');
  }
  return path;
}

async function upload(context, params, mode) {
  const path = resolveInWorkspace(String(params.path || ''));
  const info = await stat(path).catch(() => null);
  if (!info || !info.isFile()) throw new Error(`文件不存在：${params.path}`);
  if (info.size > MAX_BYTES) throw new Error('文件超过 20 MiB 上限');
  const name = String(params.fileName || basename(path));
  const extension = name.slice(name.lastIndexOf('.')).toLowerCase();
  if (!ALLOWED_EXTENSIONS.includes(extension)) {
    throw new Error(`不支持的文件类型：${extension}`);
  }

  const form = new FormData();
  form.append('file', new Blob([await readFile(path)]), name);
  form.append('teamId', String(context.teamId));
  form.append('userId', String(context.userId));
  form.append('mode', mode);
  if (context.runId) form.append('runId', String(context.runId));
  if (params.folderId) form.append('folderId', String(params.folderId));
  if (params.purpose) form.append('purpose', String(params.purpose));
  if (params.reason) form.append('reason', String(params.reason));
  if (mode === 'update') {
    if (!params.fileId) throw new Error('更新文件必须提供 fileId');
    form.append('targetFileId', String(params.fileId));
  }

  const response = await fetch(`${config.javaBaseUrl}/internal/agent/files`, {
    method: 'POST',
    signal: params.signal,
    headers: { 'x-ilink-internal-token': config.internalToken },
    body: form
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`文件提案失败 ${response.status}: ${text.slice(0, 300)}`);
  }
  return asToolResult(text);
}

/**
 * @param {{teamId:number, userId:number, runId?:number}} context
 */
export function libraryTools(context) {
  if (!context?.teamId || !context?.userId) {
    throw new Error('libraryTools 需要 teamId 与 userId');
  }
  return [
    {
      name: 'library_propose_upload',
      label: 'library_propose_upload',
      description: '把 workspace 内生成的文件提案加入团队资料库。文件不会立即入库，'
        + '需要用户在“待确认动作”里确认；返回 fileName、fileSize 与 pendingConfirmation。',
      parameters: {
        type: 'object',
        properties: {
          path: { type: 'string', description: 'workspace 内的文件路径或文件名' },
          fileName: { type: 'string', description: '资料库中显示的文件名，省略则沿用原文件名' },
          folderId: { type: 'integer', description: '目标文件夹 ID，省略表示根目录' },
          purpose: { type: 'string', description: '文件用途，例如 周报 / 答辩材料' },
          reason: { type: 'string', description: '向用户说明为什么要新增这个文件' }
        },
        required: ['path'],
        additionalProperties: false
      },
      execute: async (_toolCallId, params, signal) =>
        upload(context, { ...params, signal }, 'create')
    },
    {
      name: 'library_propose_update',
      label: 'library_propose_update',
      description: '用 workspace 内的新文件替换资料库中已有的同名文件。旧文件进回收站，'
        + '需要用户确认后才生效。适合“把周报更新到最新版本”这类场景。',
      parameters: {
        type: 'object',
        properties: {
          fileId: { type: 'integer', description: '要更新的资料库文件 ID' },
          path: { type: 'string', description: 'workspace 内的新文件路径或文件名' },
          purpose: { type: 'string', description: '文件用途' },
          reason: { type: 'string', description: '向用户说明为什么要更新这个文件' }
        },
        required: ['fileId', 'path'],
        additionalProperties: false
      },
      execute: async (_toolCallId, params, signal) =>
        upload(context, { ...params, signal }, 'update')
    }
  ];
}
