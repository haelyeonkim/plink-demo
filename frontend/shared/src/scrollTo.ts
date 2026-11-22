/**
 * Brings part of this page into view, in place of a #fragment link: addresses on this site
 * carry no fragments. Smooth, unless the reader asked their device for less motion.
 */
export function scrollToElement(element: Element | null | undefined, block: ScrollLogicalPosition = 'start') {
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  element?.scrollIntoView({ block, behavior: reduce ? 'instant' : 'smooth' });
}
