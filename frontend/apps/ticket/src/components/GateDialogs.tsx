import { useEffect, useRef, useState } from 'react';
import type { GateRow } from '../console/model';

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
