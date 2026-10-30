import { useEffect, useRef, useState, type RefObject } from 'react';
import jsQR from 'jsqr';

/** The same code read again within this long is the same person still holding it up. */
const REPEAT_MS = 3000;

/**
 * Reads QR codes from the camera into {@code onCode}, the way the gate terminal does: a
 * video element shows the stream, a canvas takes each frame, and jsQR decodes it.
 *
 * <p>The camera is on only while {@code active} is true and the component is mounted.
 * Switching it off - a tab change, leaving the page - stops every track, including a
 * stream that arrives after the switch because the permission prompt was still open.
 */
export function useQrReader({ active, facing, video, canvas, onCode }: {
  active: boolean;
  facing: 'user' | 'environment';
  video: RefObject<HTMLVideoElement | null>;
  canvas: RefObject<HTMLCanvasElement | null>;
  onCode: (code: string) => void;
}): { error: string; running: boolean } {
  const [error, setError] = useState('');
  const [running, setRunning] = useState(false);
  // The camera must not restart because the caller re-rendered: it reads through a ref.
  const handler = useRef(onCode);
  handler.current = onCode;
  const last = useRef({ code: '', at: 0 });

  useEffect(() => {
    if (!active) return;
    let stream: MediaStream | null = null;
    let raf = 0;
    let stopped = false;
    setError('');

    async function start() {
      try {
        if (!navigator.mediaDevices?.getUserMedia) throw new Error('no camera');
        const opened = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: facing, width: { ideal: 1280 }, height: { ideal: 720 } },
          audio: false,
        });
        // Let go of while the prompt was open: nobody else holds this stream, so it is
        // closed here or the camera light stays on.
        if (stopped || !video.current) { opened.getTracks().forEach(track => track.stop()); return; }
        stream = opened;
        video.current.srcObject = stream;
        await video.current.play();
        if (stopped) return;
        setRunning(true);
        loop();
      } catch {
        if (stopped) return;
        setError('카메라를 열 수 없어요. 카메라 권한을 허용하고 HTTPS 주소로 열었는지 확인해 주세요.');
      }
    }

    function loop() {
      if (stopped) return;
      const source = video.current;
      const target = canvas.current;
      if (source && target && source.videoWidth > 0) {
        const context = target.getContext('2d', { willReadFrequently: true });
        if (context) {
          target.width = source.videoWidth;
          target.height = source.videoHeight;
          context.drawImage(source, 0, 0, target.width, target.height);
          const pixels = context.getImageData(0, 0, target.width, target.height);
          const found = jsQR(pixels.data, pixels.width, pixels.height, { inversionAttempts: 'dontInvert' });
          if (found?.data) {
            const now = Date.now();
            const repeat = found.data === last.current.code && now - last.current.at < REPEAT_MS;
            // Seen again: the window moves on, so a code held up for ten seconds is one read.
            last.current = { code: found.data, at: now };
            if (!repeat) handler.current(found.data);
          }
        }
      }
      raf = window.requestAnimationFrame(loop);
    }

    void start();
    return () => {
      stopped = true;
      window.cancelAnimationFrame(raf);
      stream?.getTracks().forEach(track => track.stop());
      if (video.current) video.current.srcObject = null;
      setRunning(false);
    };
  }, [active, facing, video, canvas]);

  return { error, running };
}
