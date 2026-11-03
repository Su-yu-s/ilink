/**
 * OOXML 模板填充：docx/pptx 本质是 zip + XML，直接替换 XML 里的占位符。
 * 不重新排版，保留模板原有的样式、页眉页脚和母版。
 *
 * 限制：占位符必须作为连续文本存在于同一个 XML 文本节点里。
 * Word 有时会把 `${name}` 拆进多个 run，这种情况下替换不会命中；
 * 模板制作时请在占位符两侧避免切换格式。
 */
import JSZip from 'jszip';

const PLACEHOLDER = /\$\{([A-Za-z0-9_.-]{1,64})\}/g;

function escapeXml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

/**
 * @param {Buffer} templateBytes 模板文件字节
 * @param {Record<string, unknown>} replacements 占位符值，键不带 ${}
 * @returns {Promise<Buffer>}
 */
export async function fillOfficeTemplate(templateBytes, replacements = {}) {
  const zip = await JSZip.loadAsync(templateBytes);
  const entries = Object.keys(zip.files).filter((name) =>
    /^(word\/document|word\/header\d*|word\/footer\d*|ppt\/slides\/slide\d+|ppt\/notesSlides\/notesSlide\d+)\.xml$/
      .test(name));
  if (!entries.length) {
    throw new Error('模板不是可识别的 docx/pptx 结构');
  }
  let replaced = 0;
  for (const name of entries) {
    let xml = await zip.file(name).async('string');
    if (!xml.includes('${')) continue;
    xml = xml.replace(PLACEHOLDER, (match, key) => {
      if (!(key in replacements)) return match;
      replaced += 1;
      return escapeXml(replacements[key]);
    });
    zip.file(name, xml);
  }
  if (replaced === 0) {
    throw new Error('模板里没有匹配到任何 ${key} 占位符');
  }
  return zip.generateAsync({ type: 'nodebuffer', compression: 'DEFLATE' });
}

/** 列出模板中出现的占位符键，便于先校验再填充。 */
export async function listTemplatePlaceholders(templateBytes) {
  const zip = await JSZip.loadAsync(templateBytes);
  const names = Object.keys(zip.files).filter((name) => name.endsWith('.xml'));
  const keys = new Set();
  for (const name of names) {
    const xml = await zip.file(name).async('string');
    for (const match of xml.matchAll(PLACEHOLDER)) keys.add(match[1]);
  }
  return [...keys].sort();
}
