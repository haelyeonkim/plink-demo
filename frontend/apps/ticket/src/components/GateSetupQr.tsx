import { useEffect, useState } from 'react';
import QRCode from 'qrcode';

export interface SetupTarget { name: string; url: string; code: string | null; expiresAt: string | null }

/**
 * A gate's setup link as a code the tablet's camera can open, so nobody types or pastes
 * a long address on the device. The verification number stays on screen beside it,
 * because opening the link is only half of connecting.
 */
export default function GateSetupQr({ target, onClose }: { target: SetupTarget; onClose: () => void }) {
  const [image, setImage] = useState('');
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    let current = true;
    QRCode.toDataURL(target.url, { width: 560, margin: 1, errorCorrectionLevel: 'M' })
      .then(data => { if (current) setImage(data); })
      .catch(() => { if (current) setImage(''); });
    return () => { current = false; };
  }, [target.url]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  async function copy() {
    try { await navigator.clipboard?.writeText(target.url); setCopied(true); }
    catch { setCopied(false); }
  }

  return (
    <div className="modal-backdrop" role="presentation" onClick={onClose}>
      <div className="modal gate-qr" role="dialog" aria-modal="true" aria-labelledby="gate-qr-title"
        onClick={event => event.stopPropagation()}>
        <h3 id="gate-qr-title">{target.name} 연결</h3>
        <p className="hint-text">태블릿 카메라로 QR을 비추면 설정 화면이 열려요. 열린 화면에 아래 인증번호를 입력하세요.</p>
        <div className="gate-qr-code">
          {image
            ? <img src={image} alt={`${target.name} 설정 링크 QR 코드`} width="280" height="280" />
            : <span className="console-none">QR을 만들고 있어요.</span>}
        </div>
        {target.code && (
          <p className="gate-qr-pin">인증번호 <b>{target.code}</b></p>
        )}
        {target.expiresAt && (
          <p className="hint-text">설정 링크는 {new Date(target.expiresAt).toLocaleString('ko-KR', {
            month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })}까지 쓸 수 있어요.</p>
        )}
        <div className="modal-actions">
          <button type="button" className="btn-secondary" onClick={() => void copy()}>
            {copied ? '복사했어요' : '링크 복사'}</button>
          <button type="button" className="btn-primary" onClick={onClose}>닫기</button>
        </div>
      </div>
    </div>
  );
}
