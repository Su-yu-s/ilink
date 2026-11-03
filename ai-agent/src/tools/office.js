/**
 * 办公文件工具：写入发生在沙箱 workspace，读取由 Java 侧负责。
 * 工具只接受结构化内容（标题/段落/表格/幻灯片），不接受二进制或 base64。
 */
import { readdir, stat, readFile } from 'node:fs/promises';
import { join, resolve, relative, isAbsolute } from 'node:path';
import { config } from '../config.js';
import { asToolResult } from './kg.js';
import { writeDocx, writeXlsx, writePptx, writePdf, persistBuffer, safeFileName } from '../office/writers.js';
import { fillOfficeTemplate } from '../office/template.js';

function resolveInWorkspace(input) {
  const path = isAbsolute(input) ? resolve(input) : resolve(config.workspace, input);
  const rel = relative(config.workspace, path);
  if (rel.startsWith('..') || isAbsolute(rel)) {
    throw new Error('只能读取沙箱 workspace 内的模板');
  }
  return path;
}

const GENERATE_SCHEMA = {
  type: 'object',
  properties: {
    format: { type: 'string', enum: ['docx', 'xlsx', 'pptx', 'pdf'] },
    title: { type: 'string' },
    blocks: {
      type: 'array',
      description: 'docx：段落块。type 为 heading/paragraph/bullet 时用 text；为 table 时用 headers 与 rows',
      items: {
        type: 'object',
        properties: {
          type: { type: 'string', enum: ['heading', 'paragraph', 'bullet', 'table'] },
          text: { type: 'string' },
          headers: { type: 'array', items: { type: 'string' } },
          rows: { type: 'array', items: { type: 'array', items: { type: 'string' } } }
        },
        required: ['type'],
        additionalProperties: false
      }
    },
    sheets: {
      type: 'array',
      description: 'xlsx：工作表，列定义与行数据',
      items: {
        type: 'object',
        properties: {
          name: { type: 'string' },
          columns: {
            type: 'array',
            items: {
              type: 'object',
              properties: { key: { type: 'string' }, label: { type: 'string' }, width: { type: 'integer' } },
              required: ['key', 'label'],
              additionalProperties: false
            }
          },
          rows: { type: 'array', items: { type: 'object' } }
        },
        required: ['name'],
        additionalProperties: false
      }
    },
    slides: {
      type: 'array',
      description: 'pptx：幻灯片，标题与要点',
      items: {
        type: 'object',
        properties: {
          title: { type: 'string' },
          bullets: { type: 'array', items: { type: 'string' } }
        },
        required: ['title'],
        additionalProperties: false
      }
    },
    paragraphs: { type: 'array', description: 'pdf：段落文本', items: { type: 'string' } },
    templatePath: {
      type: 'string',
      description: 'workspace 内的 docx/pptx 模板路径。提供后按占位符填充，忽略 blocks/slides'
    },
    replacements: {
      type: 'object',
      description: '模板占位符值，键不带 ${}，例如 {"projectName":"iLink"}',
      additionalProperties: true
    }
  },
  required: ['format', 'title'],
  additionalProperties: false
};

const WRITERS = {
  docx: writeDocx,
  xlsx: writeXlsx,
  pptx: writePptx,
  pdf: writePdf
};

export function officeTools() {
  return [
    {
      name: 'office_generate',
      label: 'office_generate',
      description: '在沙箱内生成 docx/xlsx/pptx/pdf 文件。只接受结构化内容，返回文件路径、大小与文件名。',
      parameters: GENERATE_SCHEMA,
      execute: async (_toolCallId, params) => {
        if (params.templatePath) {
          if (params.format !== 'docx' && params.format !== 'pptx') {
            throw new Error('模板填充只支持 docx 与 pptx');
          }
          const bytes = await readFile(resolveInWorkspace(String(params.templatePath)));
          const filled = await fillOfficeTemplate(bytes, params.replacements || {});
          const fileName = safeFileName(params.title || 'document', params.format);
          return asToolResult(await persistBuffer(fileName, filled));
        }
        const writer = WRITERS[params.format];
        if (!writer) throw new Error(`不支持的文件格式：${params.format}`);
        return asToolResult(await writer(params));
      }
    },
    {
      name: 'office_list_workspace',
      label: 'office_list_workspace',
      description: '列出沙箱工作区内已生成的文件。',
      parameters: { type: 'object', properties: {}, additionalProperties: false },
      execute: async () => {
        const rows = [];
        for (const name of await readdir(config.workspace).catch(() => [])) {
          const info = await stat(join(config.workspace, name));
          if (info.isFile()) rows.push({ name, bytes: info.size });
        }
        return asToolResult(rows);
      }
    }
  ];
}
