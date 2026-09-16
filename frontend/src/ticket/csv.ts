/**
 * Reading a spreadsheet's idea of a table.
 *
 * <p>Excel's "save as CSV" and its clipboard disagree - one writes commas, the other
 * tabs - and a Korean Excel writes UTF-8 with a BOM. Both arrive here, so the delimiter
 * is detected rather than assumed and quoted fields keep their commas and newlines.
 */
export interface ParsedTable {
  headers: string[];
  rows: string[][];
}

function detectDelimiter(text: string): string {
  const firstLine = text.split(/\r?\n/, 1)[0] ?? '';
  const counts: Array<[string, number]> = [
    ['\t', (firstLine.match(/\t/g) || []).length],
    [',', (firstLine.match(/,/g) || []).length],
    [';', (firstLine.match(/;/g) || []).length],
  ];
  counts.sort((a, b) => b[1] - a[1]);
  return counts[0][1] > 0 ? counts[0][0] : ',';
}

export function parseTable(input: string): ParsedTable {
  const text = input.replace(/^﻿/, '').trim();
  if (!text) return { headers: [], rows: [] };
  const delimiter = detectDelimiter(text);

  const rows: string[][] = [];
  let field = '';
  let row: string[] = [];
  let quoted = false;

  for (let i = 0; i < text.length; i++) {
    const char = text[i];
    if (quoted) {
      if (char === '"') {
        if (text[i + 1] === '"') { field += '"'; i++; } else { quoted = false; }
      } else {
        field += char;
      }
      continue;
    }
    if (char === '"') { quoted = true; continue; }
    if (char === delimiter) { row.push(field); field = ''; continue; }
    if (char === '\r') continue;
    if (char === '\n') { row.push(field); rows.push(row); field = ''; row = []; continue; }
    field += char;
  }
  row.push(field);
  rows.push(row);

  const cleaned = rows
    .map(cells => cells.map(cell => cell.trim()))
    .filter(cells => cells.some(cell => cell.length > 0));
  if (cleaned.length === 0) return { headers: [], rows: [] };

  // A header row is one whose cells name columns rather than hold an address.
  const first = cleaned[0].map(cell => cell.toLowerCase());
  const looksLikeHeader = first.some(cell => HEADER_WORDS.some(word => cell.includes(word)))
    && !first.some(cell => cell.includes('@'));
  return looksLikeHeader
    ? { headers: cleaned[0], rows: cleaned.slice(1) }
    : { headers: [], rows: cleaned };
}

const HEADER_WORDS = ['email', '이메일', '메일', 'seat', '좌석', 'tier', '등급', 'phone', '휴대폰',
  '전화', 'label', '메모', '이름', 'name'];

/** Maps a header cell to one of our fields, so column order does not have to match. */
export function columnIndexes(headers: string[], fields: Record<string, string[]>): Record<string, number> {
  const found: Record<string, number> = {};
  headers.forEach((header, index) => {
    const cell = header.toLowerCase().replace(/\s/g, '');
    for (const [field, words] of Object.entries(fields)) {
      if (found[field] === undefined && words.some(word => cell.includes(word))) found[field] = index;
    }
  });
  return found;
}
