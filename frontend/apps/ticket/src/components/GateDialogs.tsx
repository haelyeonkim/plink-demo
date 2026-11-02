import { useEffect, useRef, useState } from 'react';
import InfoTip from '@shared/components/InfoTip';
import type { GateRow } from '../console/model';

/** Somebody to invite to a terminal as it is added. */
export interface FirstManager { email: string; notify: boolean }

/**
 * A terminal's name: asked for when one is added, and changeable later from its card.
 * A booth's terminal being added can also be given the person who will run it.
 */
export function GateNameDialog({ title, initial, confirmLabel, busy, askManager = false, onSave, onClose }: {
  title: string; initial: string; confirmLabel: string; busy: boolean;
  /** Offers an address to invite as the new terminal's manager; never when renaming. */
  askManager?: boolean;
  onSave: (label: string, manager?: FirstManager) => void; onClose: () => void;
}) {
  const [label, setLabel] = useState(initial);
  const [email, setEmail] = useState('');
  const [notify, setNotify] = useState(true);
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
        onSubmit={event => {
          event.preventDefault();
          if (name) onSave(name, askManager && email.trim() ? { email: email.trim(), notify } : undefined);
        }}>
        <h3 id="gate-name-title">{title}</h3>
        <div className="field">
          <label htmlFor="gate-name-input">이름
            <InfoTip label="이름">태블릿과 기록에서 이 이름으로 구분해요.</InfoTip></label>
          <input id="gate-name-input" ref={input} value={label} maxLength={40} required
            onChange={event => setLabel(event.target.value)} placeholder="예: 계산대 1" />
        </div>
        {askManager && (<>
          <div className="field">
            <label htmlFor="gate-name-manager">담당자 이메일 (선택)
              <InfoTip label="담당자 이메일">적으면 이 단말의 관리자로 초대해요. 자기 휴대폰으로 쿠폰을 주고 사용 처리할 수 있어요.</InfoTip></label>
            <input id="gate-name-manager" type="email" value={email} onChange={event => setEmail(event.target.value)}
              placeholder="staff@example.com" />
          </div>
          <label className="tm-notify">
            <input type="checkbox" checked={notify} disabled={!email.trim()}
              onChange={event => setNotify(event.target.checked)} /> 메일로 보내기
          </label>
        </>)}
        <div className="modal-actions">
          <button type="button" className="btn-secondary" disabled={busy} onClick={onClose}>취소</button>
          <button type="submit" className="btn-primary" disabled={busy || !name}>{busy ? '저장 중…' : confirmLabel}</button>
        </div>
      </form>
    </div>
  );
}
