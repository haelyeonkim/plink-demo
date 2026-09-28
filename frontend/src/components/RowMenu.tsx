import { useEffect, useLayoutEffect, useRef, useState } from 'react';

export interface MenuItem {
  label: string;
  onSelect: () => void;
  danger?: boolean;
  disabled?: boolean;
}

/**
 * The actions of a row that are not its main one, behind a "⋯".
 *
 * <p>Six buttons to a row made every row in 발급 현황 look like a toolbar and, on a
 * phone, squeezed the row's own text to a column one character wide. The one or two
 * things done most stay on the row; the rest are here, with anything destructive set
 * apart at the bottom.
 *
 * <p>The list is placed against the viewport rather than inside the row, because the
 * rows live in a scrolling table that would otherwise cut it off.
 */
export default function RowMenu({ label, items }: { label: string; items: Array<MenuItem | 'separator'> }) {
  const [open, setOpen] = useState(false);
  const [place, setPlace] = useState<{ top: number; right: number; up: boolean } | null>(null);
  const button = useRef<HTMLButtonElement>(null);
  const list = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    if (!open || !button.current) return;
    const rect = button.current.getBoundingClientRect();
    const height = list.current?.offsetHeight ?? 0;
    // Open upward when the row is near the bottom of the screen.
    const up = rect.bottom + height + 12 > window.innerHeight && rect.top > height + 12;
    setPlace({ top: up ? rect.top - height - 6 : rect.bottom + 6, right: window.innerWidth - rect.right, up });
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const close = (event: Event) => {
      if (event.target instanceof Node && (list.current?.contains(event.target) || button.current?.contains(event.target))) return;
      setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { setOpen(false); button.current?.focus(); }
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault();
        const choices = [...(list.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])];
        const at = choices.indexOf(document.activeElement as HTMLButtonElement);
        const next = event.key === 'ArrowDown' ? (at + 1) % choices.length : (at - 1 + choices.length) % choices.length;
        choices[next]?.focus();
      }
    };
    const shut = () => setOpen(false);
    document.addEventListener('pointerdown', close);
    document.addEventListener('keydown', onKey);
    window.addEventListener('resize', shut);
    window.addEventListener('scroll', shut, true);
    list.current?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus();
    return () => {
      document.removeEventListener('pointerdown', close);
      document.removeEventListener('keydown', onKey);
      window.removeEventListener('resize', shut);
      window.removeEventListener('scroll', shut, true);
    };
  }, [open]);

  return (
    <>
      <button ref={button} type="button" className="btn-tiny row-menu-button" aria-haspopup="menu"
        aria-expanded={open} aria-label={label} title={label} onClick={() => setOpen(value => !value)}>
        ⋯
      </button>
      {open && (
        <div ref={list} className="row-menu" role="menu"
          style={place ? { top: place.top, right: place.right } : { visibility: 'hidden' }}>
          {items.map((item, index) => item === 'separator'
            ? <hr key={`sep-${index}`} />
            : (
              <button key={item.label} type="button" role="menuitem" disabled={item.disabled}
                className={item.danger ? 'row-menu-danger' : undefined}
                onClick={() => { setOpen(false); item.onSelect(); }}>
                {item.label}
              </button>
            ))}
        </div>
      )}
    </>
  );
}
