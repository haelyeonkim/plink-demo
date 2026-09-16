import { useState } from 'react';

/**
 * Adds and removes one catalogue entry at a time.
 *
 * There are no defaults: an event starts with no seats and no tiers, and what the
 * organiser adds here is exactly what the issue screen offers.
 *
 * <p>Entries already carried by a ticket cannot be removed: the ticket keeps the seat
 * either way, and a list that disagrees with what was issued is worse than a list with
 * one stale row. Pasting several at once still works - commas and newlines split.
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
              {inUse.has(entry) ? (
                <em title="이미 발급된 항목이라 지울 수 없어요">발급됨</em>
              ) : (
                <button type="button" aria-label={`${entry} 삭제`} disabled={busy}
                  onClick={() => onChange(items.filter(value => value !== entry))}>×</button>
              )}
            </li>
          ))}
        </ul>
      )}
      <p className="hint-text">쉼표나 줄바꿈으로 여러 개를 한 번에 붙여넣을 수 있어요.</p>
    </div>
  );
}
