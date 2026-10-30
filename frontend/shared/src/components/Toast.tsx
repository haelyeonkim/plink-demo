import { useEffect } from 'react';

/**
 * What a console action came to, at the bottom of the screen where the eye can find it
 * wherever on a long page the button was. Done-messages leave by themselves; a problem
 * stays until it is dismissed, so it is not missed.
 */
export default function Toast({ notice, error, onNotice, onError }: {
  notice: string; error: string; onNotice: (text: string) => void; onError: (text: string) => void;
}) {
  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => onNotice(''), 4500);
    return () => window.clearTimeout(timer);
  }, [notice, onNotice]);

  if (!notice && !error) return null;
  return (
    <div className="toast-stack">
      {error && (
        <p className="toast toast-error" role="alert">
          <span>{error}</span>
          <button type="button" aria-label="닫기" onClick={() => onError('')}>✕</button>
        </p>
      )}
      {notice && !error && <p className="toast" role="status"><span>{notice}</span></p>}
    </div>
  );
}
