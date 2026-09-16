import { useEffect, useRef, useState } from 'react';

/**
 * Confirmation for something that cannot be undone.
 *
 * It states what will actually be destroyed rather than asking a generic "are you
 * sure", and when {@code requireText} is given the operator has to type that name. The
 * typing is not ceremony: it is what stops a click landing on the wrong row.
 */
export default function ConfirmDialog({
  open, title, message, details, requireText, confirmLabel = '삭제', busy = false, onConfirm, onCancel,
}: {
  open: boolean;
  title: string;
  message: string;
  details?: Array<[string, string | number]>;
  requireText?: string;
  confirmLabel?: string;
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const [typed, setTyped] = useState('');
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!open) { setTyped(''); return; }
    input.current?.focus();
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onCancel(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onCancel]);

  if (!open) return null;
  const ready = !requireText || typed.trim() === requireText;

  return (
    <div className="modal-backdrop" role="presentation" onClick={onCancel}>
      <div className="modal" role="alertdialog" aria-modal="true" aria-labelledby="confirm-title"
        onClick={event => event.stopPropagation()}>
        <h3 id="confirm-title">{title}</h3>
        <p className="modal-message">{message}</p>

        {details && details.length > 0 && (
          <dl className="modal-details">
            {details.map(([label, value]) => (
              <div key={label}><dt>{label}</dt><dd>{value}</dd></div>
            ))}
          </dl>
        )}

        {requireText && (
          <div className="field">
            <label htmlFor="confirm-text">확인을 위해 <b>{requireText}</b> 를 입력해 주세요</label>
            <input id="confirm-text" ref={input} value={typed}
              onChange={event => setTyped(event.target.value)} autoComplete="off" />
          </div>
        )}

        <div className="modal-actions">
          <button className="btn-secondary" onClick={onCancel} disabled={busy}>취소</button>
          <button className="btn-danger" onClick={onConfirm} disabled={!ready || busy}>
            {busy ? '처리 중…' : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
