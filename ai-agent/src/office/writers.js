/**
 * 办公文件生成器：只写结构化内容，不接收二进制。
 * 生成结果落在沙箱 workspace 内，由调用方决定是否交给 Java 入库。
 */
import { mkdir, writeFile, stat, readFile } from 'node:fs/promises';
import { join, basename } from 'node:path';
import { Document, Packer, Paragraph, HeadingLevel, Table, TableRow, TableCell, WidthType } from 'docx';
import ExcelJS from 'exceljs';
import PptxGenJS from 'pptxgenjs';
import fontkit from '@pdf-lib/fontkit';
import { PDFDocument } from 'pdf-lib';
import { config } from '../config.js';

const SAFE_NAME = /[^\p{L}\p{N}._-]+/gu;

export function safeFileName(name, extension) {
  const cleaned = basename(String(name || 'document')).replace(SAFE_NAME, '_').slice(0, 120);
  return cleaned.toLowerCase().endsWith(`.${extension}`) ? cleaned : `${cleaned}.${extension}`;
}

async function workspacePath(fileName) {
  await mkdir(config.workspace, { recursive: true });
  return join(config.workspace, fileName);
}

/** blocks: [{ type: 'heading'|'paragraph'|'bullet', text } | { type: 'table', headers, rows }] */
export async function writeDocx({ title, blocks = [] }) {
  const children = [new Paragraph({ text: title || '文档', heading: HeadingLevel.TITLE })];
  for (const block of blocks) {
    if (block.type === 'heading') {
      children.push(new Paragraph({ text: block.text, heading: HeadingLevel.HEADING_1 }));
    } else if (block.type === 'bullet') {
      children.push(new Paragraph({ text: block.text, bullet: { level: 0 } }));
    } else if (block.type === 'table' && Array.isArray(block.headers)) {
      const rows = [block.headers, ...(block.rows || [])].map((cells) => new TableRow({
        children: cells.map((value) => new TableCell({ children: [new Paragraph(String(value ?? ''))] }))
      }));
      children.push(new Table({ rows, width: { size: 100, type: WidthType.PERCENTAGE } }));
      // 表格后补一个空段落，避免连续表格黏连
      children.push(new Paragraph(''));
    } else {
      children.push(new Paragraph({ text: block.text ?? '' }));
    }
  }
  const buffer = await Packer.toBuffer(new Document({ sections: [{ children }] }));
  return persist(safeFileName(title || 'document', 'docx'), buffer);
}

/** sheets: [{ name, columns: [{ key, label }], rows: [{ [key]: value }] }] */
export async function writeXlsx({ title, sheets = [] }) {
  const workbook = new ExcelJS.Workbook();
  for (const sheet of sheets.length ? sheets : [{ name: 'Sheet1', columns: [], rows: [] }]) {
    const worksheet = workbook.addWorksheet(String(sheet.name || 'Sheet1').slice(0, 31));
    const columns = sheet.columns || [];
    if (columns.length) {
      worksheet.columns = columns.map((column) => ({
        header: column.label || column.key,
        key: column.key,
        width: Math.max(10, Math.min(60, Number(column.width) || 18))
      }));
      worksheet.getRow(1).font = { bold: true };
      for (const row of sheet.rows || []) worksheet.addRow(row);
    }
  }
  const buffer = Buffer.from(await workbook.xlsx.writeBuffer());
  return persist(safeFileName(title || 'workbook', 'xlsx'), buffer);
}

/** slides: [{ title, bullets: string[] }] */
export async function writePptx({ title, slides = [] }) {
  const deck = new PptxGenJS();
  deck.layout = 'LAYOUT_WIDE';
  const cover = deck.addSlide();
  cover.addText(title || '演示文稿', { x: 0.6, y: 2.2, w: 12, h: 1.2, fontSize: 32, bold: true });
  for (const slide of slides) {
    const page = deck.addSlide();
    page.addText(slide.title || '', { x: 0.6, y: 0.5, w: 12, h: 0.8, fontSize: 24, bold: true });
    page.addText((slide.bullets || []).map((text) => ({ text, options: { bullet: true, breakLine: true } })),
      { x: 0.9, y: 1.5, w: 11.5, h: 5.2, fontSize: 16 });
  }
  const buffer = Buffer.from(await deck.write({ outputType: 'nodebuffer' }));
  return persist(safeFileName(title || 'deck', 'pptx'), buffer);
}

/**
 * PDF 必须嵌入中文字体，标准字体无法编码 CJK。
 * 优先用 ILINK_PDF_FONT，其次常见系统字体；找不到就明确报错而不是输出乱码。
 */
const PDF_FONT_CANDIDATES = [
  process.env.ILINK_PDF_FONT,
  'C:/Windows/Fonts/simhei.ttf',
  '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttf'
].filter(Boolean);

async function embedCjkFont(pdf) {
  pdf.registerFontkit(fontkit);
  for (const path of PDF_FONT_CANDIDATES) {
    try {
      return await pdf.embedFont(await readFile(path), { subset: true });
    } catch {
      // 继续尝试下一个候选字体
    }
  }
  throw new Error('PDF 需要中文字体：请设置 ILINK_PDF_FONT 指向可用的 .ttf 文件');
}

/** paragraphs: string[] */
export async function writePdf({ title, paragraphs = [] }) {
  const pdf = await PDFDocument.create();
  const font = await embedCjkFont(pdf);
  const page = pdf.addPage([595, 842]);
  let y = 800;
  page.drawText(title || 'Document', { x: 50, y, size: 20, font });
  y -= 36;
  for (const paragraph of paragraphs) {
    for (const line of wrap(String(paragraph), 80)) {
      if (y < 60) break;
      page.drawText(line, { x: 50, y, size: 11, font });
      y -= 18;
    }
    y -= 8;
  }
  const buffer = Buffer.from(await pdf.save());
  return persist(safeFileName(title || 'document', 'pdf'), buffer);
}

function wrap(text, width) {
  const lines = [];
  for (let i = 0; i < text.length; i += width) lines.push(text.slice(i, i + width));
  return lines.length ? lines : [''];
}

export async function persistBuffer(fileName, buffer) {
  return persist(fileName, buffer);
}

async function persist(fileName, buffer) {
  if (buffer.length > config.maxFileBytes) {
    throw new Error(`生成文件超过上限 ${config.maxFileBytes} 字节`);
  }
  const path = await workspacePath(fileName);
  await writeFile(path, buffer);
  const info = await stat(path);
  return { path, fileName, bytes: info.size };
}
