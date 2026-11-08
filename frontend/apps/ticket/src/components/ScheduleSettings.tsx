import { useEffect, useState } from 'react';
import { mutate } from '@shared/auth';
import InfoTip from '@shared/components/InfoTip';
import { dayShort, hourLabel, read, type PassRow, type ScheduleDay, type ScheduleView } from '../console/model';
import { passDays } from './IssuePanel';

const WEEK = ['일', '월', '화', '수', '목', '금', '토'];

function iso(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

/** Every date from one to the other, keeping what was set for the dates that stay. */
function between(start: string, end: string, kept: ScheduleDay[]): ScheduleDay[] {
  const out: ScheduleDay[] = [];
  const from = new Date(`${start}T00:00:00`);
  const to = new Date(`${end}T00:00:00`);
  for (const d = new Date(from); d <= to && out.length < 31; d.setDate(d.getDate() + 1)) {
    const date = iso(d);
    out.push(kept.find(k => k.date === date)
      ?? { date, label: `DAY ${out.length + 1}`, weekday: WEEK[d.getDay()], opensAt: null, closesAt: null, closed: false });
  }
  return out;
}

/**
 * An event over several days: its days, with hours and rest days, and the kinds of
 * ticket by the days they admit. Saved on its own, not by the settings bar: a pass
 * names days, so the days have to exist first.
 */
export default function ScheduleSettings({ sessionId, startsAt, schedule, onChanged, onNotice, onError }: {
  sessionId: number; startsAt: string; schedule: ScheduleView | null;
  onChanged: () => Promise<void> | void; onNotice: (text: string) => void; onError: (text: string) => void;
}) {
  const base = `/api/admin/sessions/${sessionId}/schedule`;
  const saved = schedule?.days ?? [];
  const [days, setDays] = useState<ScheduleDay[]>(saved);
  const [busy, setBusy] = useState(false);
  useEffect(() => { setDays(schedule?.days ?? []); }, [schedule]);
  const dirty = JSON.stringify(days) !== JSON.stringify(saved);
  const first = days[0]?.date ?? startsAt.slice(0, 10);
  const last = days[days.length - 1]?.date ?? first;

  const send = async (url: string, method: string, body?: unknown, done?: string) => {
    setBusy(true);
    try {
      await read(await mutate(url, { method, headers: { 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body) }));
      if (done) onNotice(done);
      await onChanged();
      return true;
    } catch (err) { onError(err instanceof Error ? err.message : '저장하지 못했어요.'); return false; }
    finally { setBusy(false); }
  };
  const setDay = (date: string, patch: Partial<ScheduleDay>) =>
    setDays(current => current.map(d => (d.date === date ? { ...d, ...patch } : d)));
  const openCount = days.filter(d => !d.closed).length;

  return (
    <div className="schedule-settings">
      {days.length === 0 ? (
        <div className="schedule-empty">
          <p>하루짜리 이벤트예요. 여러 날 진행하면 일정을 만들어 날짜별 입장 시간과 입장권 종류를 정할 수 있어요.</p>
          <button className="btn-secondary" type="button" onClick={() => setDays(between(first, first, []))}>일정 만들기</button>
        </div>
      ) : (<>
        <div className="schedule-range">
          <label>시작일<input type="date" value={first} max={last}
            onChange={e => { if (e.target.value) setDays(between(e.target.value, last < e.target.value ? e.target.value : last, days)); }} /></label>
          <span aria-hidden="true">–</span>
          <label>종료일<input type="date" value={last} min={first}
            onChange={e => { if (e.target.value) setDays(between(first, e.target.value, days)); }} /></label>
          <span className="schedule-span">{days.length}일 · 운영 {openCount}일</span>
          <span className="schedule-turn">하루가 바뀌는 시각 {hourLabel(schedule?.dayCloseHour ?? 0)}
            <InfoTip label="하루가 바뀌는 시각">재입장 · 퇴장 섹션의 날짜 기준 시각이에요. 자정을 넘겨 진행하면 새벽으로 늦추세요.
              그 시각 전까지는 전날로 쳐요.</InfoTip></span>
        </div>
        <div className="console-table-box">
          <table className="console-table schedule-table">
            <thead><tr><th>날짜</th><th>이름</th><th>입장 시작</th><th>입장 마감</th><th>운영</th></tr></thead>
            <tbody>
              {days.map(day => (
                <tr key={day.date} className={day.closed ? 'is-off' : ''}>
                  <td><b>{dayShort(day)}</b></td>
                  <td><input aria-label={`${dayShort(day)} 이름`} maxLength={20} value={day.label}
                    onChange={e => setDay(day.date, { label: e.target.value })} /></td>
                  <td><input aria-label={`${dayShort(day)} 입장 시작`} type="time" value={day.opensAt ?? ''} disabled={day.closed}
                    onChange={e => setDay(day.date, { opensAt: e.target.value || null })} /></td>
                  <td><input aria-label={`${dayShort(day)} 입장 마감`} type="time" value={day.closesAt ?? ''} disabled={day.closed}
                    onChange={e => setDay(day.date, { closesAt: e.target.value || null })} /></td>
                  <td><label className="console-switch">
                    <input type="checkbox" checked={!day.closed} onChange={() => setDay(day.date, { closed: !day.closed })} />
                    {day.closed ? '휴관' : '운영'}</label></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="muted settings-note">시간을 비워 두면 그날 하루 종일 입장할 수 있어요. 마지막 운영일의 입장 마감이 지나면 이벤트가 끝나고,
          입장권은 '종료됨'이 돼요.</p>
        <div className="button-row">
          <button className="btn-primary" type="button" disabled={busy || !dirty}
            onClick={() => void send(`${base}/days`, 'PUT', { days }, '일정을 저장했어요. 입장권과 게이트에 바로 반영돼요.')}>일정 저장</button>
          {dirty && <button className="btn-secondary" type="button" onClick={() => setDays(saved)}>되돌리기</button>}
          {saved.length > 0 && (
            <button className="btn-tiny btn-tiny-danger" type="button" disabled={busy} onClick={() => {
              if (window.confirm('일정을 없애면 하루짜리 이벤트처럼 모든 입장권이 날짜와 상관없이 입장해요. 없앨까요?')) {
                void send(`${base}/days`, 'PUT', { days: [] }, '일정을 없앴어요.');
              }
            }}>일정 없애기</button>
          )}
        </div>
      </>)}

      {saved.length > 0 && (
        <section className="schedule-passes" aria-labelledby="passes-title">
          <h4 id="passes-title">입장권 종류
            <InfoTip label="입장권 종류">들어올 수 있는 날짜 묶음이에요. 발급할 때 하나를 고르고, 여러 장 발급 CSV에는
              '입장권 종류' 열에 이름을 적어요. 만들지 않거나 고르지 않은 입장권은 모든 날 입장해요.</InfoTip></h4>
          {(schedule?.passes ?? []).map(pass => (
            <PassEditor key={pass.id} pass={pass} days={saved} busy={busy}
              onSave={(name, chosen) => send(`${base}/passes/${pass.id}`, 'PUT', { name, days: chosen }, `'${name}'을(를) 저장했어요.`)}
              onDelete={() => void send(`${base}/passes/${pass.id}`, 'DELETE', undefined, `'${pass.name}'을(를) 지웠어요.`)} />
          ))}
          <button className="btn-secondary" type="button" disabled={busy}
            onClick={() => void send(`${base}/passes`, 'POST', { name: `종류 ${(schedule?.passes.length ?? 0) + 1}`, days: [] })}>종류 추가</button>
        </section>
      )}
    </div>
  );
}

function PassEditor({ pass, days, busy, onSave, onDelete }: {
  pass: PassRow; days: ScheduleDay[]; busy: boolean;
  onSave: (name: string, days: string[] | null) => Promise<boolean>; onDelete: () => void;
}) {
  const [name, setName] = useState(pass.name);
  const [chosen, setChosen] = useState<string[] | null>(pass.days);
  useEffect(() => { setName(pass.name); setChosen(pass.days); }, [pass]);
  const dirty = name !== pass.name || JSON.stringify(chosen) !== JSON.stringify(pass.days);
  const open = days.filter(d => !d.closed);
  const toggle = (date: string) => {
    const current = chosen ?? open.map(d => d.date);
    setChosen(current.includes(date) ? current.filter(d => d !== date) : [...current, date].sort());
  };
  return (
    <div className="pass-row">
      <input aria-label="입장권 종류 이름" maxLength={30} value={name} onChange={e => setName(e.target.value)} />
      <div className="chip-row" role="group" aria-label={`${pass.name} 입장 가능한 날`}>
        <button type="button" className={chosen === null ? 'on' : ''} aria-pressed={chosen === null}
          onClick={() => setChosen(chosen === null ? [] : null)}>전 일정</button>
        {days.map(day => (
          <button key={day.date} type="button" disabled={day.closed}
            className={chosen !== null && chosen.includes(day.date) ? 'on' : ''}
            aria-pressed={chosen !== null && chosen.includes(day.date)} onClick={() => toggle(day.date)}>
            {day.label} {dayShort(day)}</button>
        ))}
      </div>
      <span className="muted pass-issued">{pass.issued ? `${pass.issued.toLocaleString()}장 발급` : '발급 없음'}</span>
      <span className="row-actions">
        <button className="btn-tiny btn-tiny-primary" type="button" disabled={busy || !dirty}
          onClick={() => void onSave(name, chosen)}>저장</button>
        <button className="btn-tiny btn-tiny-danger" type="button" disabled={busy || pass.issued > 0}
          title={pass.issued > 0 ? '발급된 입장권이 있는 종류는 지울 수 없어요.' : undefined}
          onClick={onDelete}>삭제</button>
      </span>
      <small className="pass-summary">입장 가능: {passDays({ ...pass, days: chosen }, days)}</small>
    </div>
  );
}
