import { useState, type ReactNode } from 'react';

/**
 * The list a console opens on: every event, or every link, as rows you can click.
 *
 * <p>A select box hid what the operator came to see - how many there are, which one ran
 * yesterday, which is still empty - behind a click. The list says all of it at once, and
 * only grows a "더보기" when it is long enough that showing everything would bury the
 * rest of the page.
 */
export interface PickerItem {
  id: number;
  title: string;
  meta?: ReactNode;
  badge?: ReactNode;
}

export default function EntityPicker({ items, onOpen, empty, page = 8 }: {
  items: PickerItem[];
  onOpen: (id: number) => void;
  empty: ReactNode;
  page?: number;
}) {
  const [shown, setShown] = useState(page);

  if (items.length === 0) return <div className="tab-panel"><p className="hint-text">{empty}</p></div>;

  const visible = items.slice(0, shown);
  return (
    <div className="tab-panel">
      <ul className="entity-list">
        {visible.map(item => (
          <li key={item.id} className="openable" tabIndex={0} role="button"
            onClick={() => onOpen(item.id)}
            onKeyDown={event => {
              if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); onOpen(item.id); }
            }}>
            <div className="entity-main">
              <span className="entity-title">{item.title}</span>
              {item.meta && <span className="entity-meta">{item.meta}</span>}
            </div>
            {item.badge}
            <span className="entity-chevron" aria-hidden="true">›</span>
          </li>
        ))}
      </ul>
      {shown < items.length && (
        <div className="list-more">
          <button className="btn-secondary" onClick={() => setShown(count => count + page)}>
            더보기 ({items.length - shown})
          </button>
        </div>
      )}
    </div>
  );
}
