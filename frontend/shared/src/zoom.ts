/**
 * Keeps the holder's ticket at the size it was laid out for, as an app would, while
 * that screen is open. The viewport tag asks for it; iOS Safari ignores the tag for
 * pinching, so the pinch gesture itself is cancelled there. Returns the way back, so the
 * console and the gate terminal under the same app keep their zoom.
 */
export function holdZoom(): () => void {
  const meta = document.querySelector('meta[name="viewport"]');
  const before = meta?.getAttribute('content') ?? null;
  meta?.setAttribute('content', 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no');
  const stop = (event: Event) => event.preventDefault();
  const events = ['gesturestart', 'gesturechange', 'gestureend'];
  events.forEach(name => document.addEventListener(name, stop, { passive: false }));
  return () => {
    events.forEach(name => document.removeEventListener(name, stop));
    if (meta && before !== null) meta.setAttribute('content', before);
  };
}
