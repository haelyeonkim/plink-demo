import { useEffect, useRef, type RefObject } from 'react';

/**
 * The keyboard half of a menu that a button opens: focus goes into the menu when it
 * opens, the arrow keys walk its entries, Escape closes it, and closing hands focus back
 * to the button - so somebody without a mouse is never left on an entry that has gone.
 *
 * <p>Focus is only handed back when it was in the menu (or went nowhere, because the
 * entry it was on was taken away); a click somewhere else on the page keeps its focus.
 */
export function useMenuFocus(open: boolean, toggle: RefObject<HTMLElement | null>, menu: RefObject<HTMLElement | null>,
  close: () => void) {
  const closeRef = useRef(close);
  closeRef.current = close;

  useEffect(() => {
    if (!open) return;
    const node = menu.current;
    const entries = () => [...(node?.querySelectorAll<HTMLElement>('a[href], button:not(:disabled)') ?? [])];
    // The entry for the screen in view, when the menu has one, is where a reader is.
    (node?.querySelector<HTMLElement>('[aria-current="page"]') ?? entries()[0])?.focus();

    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { closeRef.current(); return; }
      if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
      const list = entries();
      if (list.length === 0) return;
      event.preventDefault();
      const at = list.indexOf(document.activeElement as HTMLElement);
      const down = event.key === 'ArrowDown';
      const next = at < 0 ? (down ? 0 : list.length - 1)
        : down ? (at + 1) % list.length : (at - 1 + list.length) % list.length;
      list[next].focus();
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      const focused = document.activeElement;
      if (!focused || focused === document.body || node?.contains(focused)) {
        toggle.current?.focus({ preventScroll: true });
      }
    };
  }, [open, toggle, menu]);
}
