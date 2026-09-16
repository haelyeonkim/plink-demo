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
export default function FaceEnrolment({ sessionId, token, inside, onChange }: {
  sessionId: string; token: string;
  /** Inside the venue: enrolling now would be after the fact, so the door is closed. */
  inside?: boolean;
  onChange?: () => Promise<void> | void;
}) {
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

  // The <video> only exists once capturing is true, so the camera cannot be opened in
  // the same tick as the click: video.current is still null there, which is why the
  // preview stayed black and no frame was ever captured.
  useEffect(() => {
    if (!capturing || !video.current) return;
    let cancelled = false;
    openCamera(video.current)
      .then(opened => {
        if (cancelled) { opened.getTracks().forEach(track => track.stop()); return; }
        stream.current = opened;
      })
      .catch(() => {
        if (cancelled) return;
        setCapturing(false);
        setError('카메라를 열 수 없어요. 브라우저의 카메라 권한을 허용하고 다시 시도해 주세요.');
      });
    return () => { cancelled = true; };
  }, [capturing]);

  function stopCamera() {
    stream.current?.getTracks().forEach(track => track.stop());
    stream.current = null;
    setCapturing(false);
  }

  const enroll = () => guard(async () => {
    if (!video.current) return;
    if (video.current.videoWidth === 0) {
      throw new Error('카메라 화면이 아직 준비되지 않았어요. 잠시 후 다시 눌러 주세요.');
    }
    const frames = await captureFrames(video.current, 3);
    const result = await enrollFace(sessionId, token, frames);
    stopCamera();
    setStatus(await fetchFaceStatus(sessionId, token));
    await onChange?.();
    setNotice(`얼굴을 등록했어요. ${new Date(result.purgeAfter).toLocaleDateString('ko-KR')}에 자동 파기됩니다.`);
  });

  const withdraw = () => guard(async () => {
    await withdrawFace(sessionId, token);
    setStatus(await fetchFaceStatus(sessionId, token));
    await onChange?.();
    setNotice('얼굴 정보를 파기했어요. 앞으로는 QR로 입장합니다.');
  });

  if (!status) return null;

  return (
    <>
      <h3>얼굴 입장 (선택)</h3>
      {notice && <p className="notice-text" role="status">{notice}</p>}
      {error && <p className="error-text" role="alert">{error}</p>}

      {status.enrolled ? (
        <>
          <p className="hint-text">등록 완료. 게이트에서 폰을 꺼내지 않고 얼굴로 지나갈 수 있어요.</p>
          <button className="btn-secondary" onClick={withdraw} disabled={busy}>얼굴 정보 삭제</button>
        </>
      ) : !status.consented && !status.qrUsed && !(inside ?? status.inside) ? (
        <>
          <p className="hint-text">
            얼굴 정보는 민감정보입니다. 등록하지 않아도 <b>패스키와 QR로 동일하게 입장</b>할 수 있어요.
          </p>
          <ul className="consent-list">
            <li><b>수집 항목</b> 얼굴 특징값 (사진 원본은 저장하지 않습니다)</li>
            <li><b>이용 목적</b> {status.purposes}</li>
            <li><b>보유 기간</b> 행사 종료 후 정해진 기간이 지나면 자동 파기</li>
            <li><b>거부 권리</b> 동의하지 않아도 입장에 불이익이 없습니다</li>
          </ul>
          <button className="btn-secondary" onClick={agree} disabled={busy}>위 내용에 동의하고 진행</button>
        </>
      ) : capturing ? (
        <>
          <video ref={video} muted playsInline autoPlay className="camera-preview" />
          <p className="hint-text">정면을 보고 밝은 곳에서 촬영해 주세요.</p>
          <div className="button-row">
            <button className="btn-primary" onClick={enroll} disabled={busy}>촬영하고 등록</button>
            <button className="btn-secondary" onClick={stopCamera} disabled={busy}>취소</button>
          </div>
        </>
      ) : status.qrUsed ? (
        <p className="hint-text">
          이미 QR로 사용한 입장권이에요. 얼굴 등록은 QR을 쓰기 전에만 할 수 있어, 이 입장권은 앞으로도
          QR로 입장합니다.
        </p>
      ) : (inside ?? status.inside) ? (
        <p className="hint-text">
          지금은 장내에 있어 얼굴을 등록할 수 없어요. 퇴장한 뒤에 등록하면 다음 입장부터 사용할 수 있어요.
        </p>
      ) : (
        <button className="btn-secondary" onClick={() => setCapturing(true)} disabled={busy}>
          카메라 열기
        </button>
      )}
    </>
  );
}
