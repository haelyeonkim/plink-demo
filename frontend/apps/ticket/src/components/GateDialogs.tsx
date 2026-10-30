import { useEffect, useRef, useState } from 'react';
import type { GateRow } from '../console/model';

/**
 * Which terminal a tablet is about to become. Several terminals of one booth (or several
 * doors) share a screen, so connecting starts by naming the one meant, rather than by
 * finding its card. A terminal already held by a tablet is shown but not offered: a second
 * tablet would be refused, and the way to move it is to release it first.
 */
export function GateConnectDialog({ title, gates, busy, onPick, onClose }: {
  title: string; gates: GateRow[]; busy: boolean;
  onPick: (gate: GateRow) => void; onClose: () => void;
}) {
  const free = gates.filter(gate => !gate.boundDevice);
  const [picked, setPicked] = useState<string>(free[0]?.gateId ?? '');
  const first = useRef<HTMLInputElement>(null);

  useEffect(() => { first.current?.focus(); }, []);
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape' && !busy) onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [busy, onClose]);

  const chosen = gates.find(gate => gate.gateId === picked) ?? null;
  return (
    <div className="modal-backdrop" role="presentation" onClick={() => { if (!busy) onClose(); }}>
      <div className="modal gate-pick" role="dialog" aria-modal="true" aria-labelledby="gate-pick-title"
        onClick={event => event.stopPropagation()}>
        <h3 id="gate-pick-title">{title}</h3>
        <p className="hint-text">연결할 단말을 고르면 그 단말의 QR과 인증번호가 나와요. 태블릿에서 QR을 열고 번호를 넣으면 그 단말이 돼요.</p>
        <div className="gate-pick-list" role="radiogroup" aria-label="연결할 단말">
          {gates.map((gate, index) => {
            const held = Boolean(gate.boundDevice);
            return (
              <label key={gate.gateId} className={`gate-pick-row${held ? ' is-held' : ''}${picked === gate.gateId ? ' on' : ''}`}>
                <input ref={index === gates.indexOf(free[0]) ? first : undefined} type="radio" name="gate-pick"
                  value={gate.gateId} checked={picked === gate.gateId} disabled={held}
                  onChange={() => setPicked(gate.gateId)} />
                <span className="gate-pick-name"><b>{gate.label || gate.gateId}</b><small>{gate.gateId}</small></span>
                <span className={`pill pill-${held ? 'ok' : 'warn'}`}>{held ? '연결됨' : '연결 안 됨'}</span>
              </label>
            );
          })}
        </div>
        {free.length === 0 && (
          <p className="hint-text">모든 단말이 연결돼 있어요. 태블릿을 바꾸려면 그 단말의 메뉴에서 연결을 해제한 뒤 다시 연결하세요.</p>
        )}
        <div className="modal-actions">
          <button type="button" className="btn-secondary" disabled={busy} onClick={onClose}>닫기</button>
          <button type="button" className="btn-primary" disabled={busy || !chosen || Boolean(chosen.boundDevice)}
            onClick={() => chosen && onPick(chosen)}>{busy ? '준비 중…' : '연결 QR 보기'}</button>
        </div>
      </div>
    </div>
  );
}

/** A terminal's name: asked for when one is added, and changeable later from its card. */
export function GateNameDialog({ title, initial, confirmLabel, busy, onSave, onClose }: {
  title: string; initial: string; confirmLabel: string; busy: boolean;
  onSave: (label: string) => void; onClose: () => void;
}) {
  const [label, setLabel] = useState(initial);
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => { input.current?.select(); }, []);
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape' && !busy) onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [busy, onClose]);

  const name = label.trim();
  return (
    <div className="modal-backdrop" role="presentation" onClick={() => { if (!busy) onClose(); }}>
      <form className="modal gate-name" role="dialog" aria-modal="true" aria-labelledby="gate-name-title"
        onClick={event => event.stopPropagation()}
        onSubmit={event => { event.preventDefault(); if (name) onSave(name); }}>
        <h3 id="gate-name-title">{title}</h3>
        <div className="field">
          <label htmlFor="gate-name-input">이름 <span className="label-note">태블릿과 기록에서 이 이름으로 구분해요</span></label>
          <input id="gate-name-input" ref={input} value={label} maxLength={40} required
            onChange={event => setLabel(event.target.value)} placeholder="예: 계산대 1" />
        </div>
        <div className="modal-actions">
          <button type="button" className="btn-secondary" disabled={busy} onClick={onClose}>취소</button>
          <button type="submit" className="btn-primary" disabled={busy || !name}>{busy ? '저장 중…' : confirmLabel}</button>
        </div>
      </form>
    </div>
  );
}
