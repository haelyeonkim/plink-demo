import { columnIndexes, parseTable } from './ticket/csv';

export function validateRecipients(input: string) {
  const table = parseTable(input, true);
  const mapped = columnIndexes(table.headers, { email: ['email', '이메일', '메일'] }).email;
  const emailColumn = table.headers.length ? mapped : 0;
  const errors: string[] = [];
  const seen = new Map<string, number>();
  const rows = table.rows.map((cells, index) => {
    const line = table.lineNumbers[index];
    const email = (emailColumn === undefined ? '' : cells[emailColumn] ?? '').trim();
    if (email.length > 255 || !/^[^\s@,;<>]+@[^\s@,;<>]+\.[^\s@,;<>]+$/.test(email)) {
      errors.push(`${line}번째 줄: 이메일 형식을 확인해 주세요.`);
    } else if (seen.has(email.toLowerCase())) {
      errors.push(`${line}번째 줄: ${seen.get(email.toLowerCase())}번째 줄과 이메일이 중복됩니다.`);
    } else {
      seen.set(email.toLowerCase(), line);
    }
    if (!table.headers.length && cells.slice(1).some(cell => cell.includes('@'))) {
      errors.push(`${line}번째 줄: 이메일은 한 줄에 한 명씩 입력해 주세요.`);
    }
    return { email, line };
  });
  if (table.headers.length && emailColumn === undefined) errors.unshift('표에 이메일 열이 필요합니다.');
  if (rows.length > 500) errors.push('수신자는 한 번에 500명까지 입력할 수 있습니다.');
  return { rows, errors };
}
