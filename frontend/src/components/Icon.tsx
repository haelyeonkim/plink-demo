import type { CSSProperties } from 'react';

type IconName = 'arrow-right' | 'arrow-left' | 'external' | 'close' | 'check' | 'lock' | 'clock' | 'shield' | 'more' | 'edit' | 'document' | 'refresh';

export default function Icon({ name }: { name: IconName }) {
  return <span className="ui-icon" aria-hidden="true" style={{ '--icon-url': `url("/icons/${name}.svg")` } as CSSProperties} />;
}
