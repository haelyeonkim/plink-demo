import { useState } from 'react';
import ConfirmDialog from './ConfirmDialog';

/**
 * Adds and removes one catalogue entry at a time.
 *
 * There are no defaults: an event starts with no seats and no tiers, and what the
 * organiser adds here is exactly what the issue screen offers.
 *
 * <p>An entry a ticket already carries can be removed too, with a confirmation: the
 * ticket keeps the seat it was issued with, and the list stops offering it for the next
 * one. Pasting several at once still works - commas and newlines split.
 */
export default function CatalogEditor({ label, placeholder, items, inUse, busy, onChange }: {
  label: string;
  placeholder: string;
  items: string[];
  inUse: Set<string>;
  busy: boolean;
  onChange: (next: string[]) => void;
}) {
  const [draft, setDraft] = useState('');
  const [pending, setPending] = useState<string | null>(null);

  function remove(entry: string) {
    onChange(items.filter(value => value !== entry));
  }

  function add() {
    const additions = draft.split(/[\n,]/).map(value => value.trim()).filter(Boolean);
    if (additions.length === 0) return;
    const next = [...items];
    for (const entry of additions) if (!next.includes(entry)) next.push(entry);
    setDraft('');
    onChange(next);
  }

  return (
    <div className="catalog">
      <div className="catalog-head">
        <h3>{label}</h3>
        <span className="hint-text">{items.length}개</span>
      </div>

      <div className="catalog-add">
        <input value={draft} placeholder={placeholder} disabled={busy}
          onChange={event => setDraft(event.target.value)}
          onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); add(); } }} />
        <button type="button" className="btn-secondary" onClick={add} disabled={busy || !draft.trim()}>
          추가
        </button>
      </div>

      {items.length === 0 ? (
        <p className="hint-text">아직 없습니다. 추가하면 발급 화면에서 고를 수 있어요.</p>
      ) : (
        <ul className="catalog-list">
          {items.map(entry => (
            <li key={entry}>
              <span>{entry}</span>
              {inUse.has(entry) && <em title="이미 발급된 입장권이 쓰고 있어요">발급됨</em>}
              <button type="button" aria-label={`${entry} 삭제`} disabled={busy}
                onClick={() => (inUse.has(entry) ? setPending(entry) : remove(entry))}>×</button>
            </li>
          ))}
        </ul>
      )}
      <p className="hint-text">쉼표나 줄바꿈으로 여러 개를 한 번에 붙여넣을 수 있어요.</p>

      <ConfirmDialog
        open={pending != null}
        title={`${label} '${pending}' 을(를) 목록에서 지울까요?`}
        message={'이미 발급된 입장권은 이 값을 그대로 유지합니다. 목록에서만 사라져 앞으로 발급할 때 '
          + '고를 수 없게 돼요.'}
        confirmLabel="목록에서 삭제"
        busy={busy}
        onConfirm={() => { if (pending) remove(pending); setPending(null); }}
        onCancel={() => setPending(null)}
      />
    </div>
  );
}
