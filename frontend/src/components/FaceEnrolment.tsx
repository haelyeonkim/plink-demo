import { useEffect, useRef, useState } from 'react';
import {
  enrollFace, fetchFaceStatus, giveFaceConsent, withdrawFace, type FaceStatus,
} from '../ticket/api';
import { captureFrames, openCamera } from '../ticket/camera';

/**
 * Optional face enrolment. Consent is taken on its own screen with the purpose and the
 * retention window stated, refusing it costs the holder nothing, and withdrawal deletes
 * the template rather than flagging it.
 */
export default function FaceEnrolment({ sessionId, token }: { sessionId: string; token: string }) {
  const [status, setStatus] = useState<FaceStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [capturing, setCapturing] = useState(false);
  const video = useRef<HTMLVideoElement>(null);
  const stream = useRef<MediaStream | null>(null);

  useEffect(() => {
    fetchFaceStatus(sessionId, token).then(setStatus).catch(() => undefined);
    return () => { stream.current?.getTracks().forEach(track => track.stop()); };
  }, [sessionId, token]);

  async function guard(action: () => Promise<void>) {
    if (busy) return;
    setBusy(true); setError(''); setNotice('');
    try { await action(); }
    catch (err) { setError(err instanceof Error ? err.message : '요청을 처리하지 못했어요.'); }
    finally { setBusy(false); }
  }

  const agree = () => guard(async () => {
    await giveFaceConsent(sessionId, token);
    setStatus(await fetchFaceStatus(sessionId, token));
    setNotice('동의를 기록했어요. 이제 얼굴을 등록할 수 있어요.');
  });

  const startCamera = () => guard(async () => {
    setCapturing(true);
    if (video.current) stream.current = await openCamera(video.current);
  });

  const enroll = () => guard(async () => {
    if (!video.current) return;
    const frames = await captureFrames(video.current, 3);
    const result = await enrollFace(sessionId, token, frames);
    stream.current?.getTracks().forEach(track => track.stop());
    setCapturing(false);
    setStatus(await fetchFaceStatus(sessionId, token));
    setNotice(`얼굴을 등록했어요. ${new Date(result.purgeAfter).toLocaleDateString('ko-KR')}에 자동 파기됩니다.`);
  });

  const withdraw = () => guard(async () => {
    await withdrawFace(sessionId, token);
    setStatus(await fetchFaceStatus(sessionId, token));
    setNotice('얼굴 정보를 파기했어요. 앞으로는 QR로 입장합니다.');
  });

  if (!status) return null;

  return (
    <div className="ticket-face">
      <h3>얼굴 입장 (선택)</h3>
      {notice && <p className="ticket-notice" role="status">{notice}</p>}
      {error && <p className="ticket-error" role="alert">{error}</p>}

      {status.enrolled ? (
        <>
          <p className="ticket-hint">
            등록 완료. 게이트에서 폰을 꺼내지 않고 얼굴로 지나갈 수 있어요.
          </p>
          <button className="secondary" onClick={withdraw} disabled={busy}>얼굴 정보 삭제</button>
        </>
      ) : !status.consented ? (
        <>
          <p className="ticket-hint">
            얼굴 정보는 민감정보입니다. 등록하지 않아도 <strong>패스키와 QR로 동일하게 입장</strong>할 수 있어요.
          </p>
          <ul className="ticket-consent">
            <li><strong>수집 항목</strong> 얼굴 특징값 (사진 원본은 저장하지 않습니다)</li>
            <li><strong>이용 목적</strong> {status.purposes}</li>
            <li><strong>보유 기간</strong> 회차 종료 후 정해진 기간이 지나면 자동 파기</li>
            <li><strong>거부 권리</strong> 동의하지 않아도 입장에 불이익이 없습니다</li>
          </ul>
          <button className="secondary" onClick={agree} disabled={busy}>
            위 내용에 동의하고 진행
          </button>
        </>
      ) : capturing ? (
        <>
          <video ref={video} muted playsInline className="ticket-camera" />
          <p className="ticket-hint">정면을 보고 밝은 곳에서 촬영해 주세요.</p>
          <button className="primary" onClick={enroll} disabled={busy}>촬영하고 등록</button>
        </>
      ) : (
        <button className="secondary" onClick={startCamera} disabled={busy}>카메라 열기</button>
      )}
    </div>
  );
}
