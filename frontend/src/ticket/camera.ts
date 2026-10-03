/**
 * The most a single frame may weigh once decoded. The server refuses anything over
 * 400 KB; this leaves room so a frame is never refused for its size.
 */
const MAX_FRAME_BYTES = 360_000;
/** Long side of a frame sent for face matching. A face at a gate stays well above the
 *  service's 112px minimum at this size, and a 1080p camera no longer sends 1080p. */
const MAX_SIDE = 1280;

function decodedBytes(dataUrl: string): number {
  const payload = dataUrl.slice(dataUrl.indexOf(',') + 1);
  return Math.floor((payload.length * 3) / 4);
}

/**
 * Grabs still frames from a live video element as JPEG data, without ever storing them.
 *
 * <p>A gate's camera runs at 1080p for the QR reader, and a frame that size with a face
 * in it came out over the server's limit - every one was refused, and the gate took the
 * refusals for an outage and switched face matching off. Frames are now scaled to a
 * 1280px long side and re-encoded at lower quality until they fit.
 */
export async function captureFrames(video: HTMLVideoElement, count = 3, gapMs = 220): Promise<string[]> {
  const canvas = document.createElement('canvas');
  const context = canvas.getContext('2d');
  if (!context) throw new Error('카메라 화면을 읽을 수 없어요.');
  const sourceWidth = video.videoWidth || 640;
  const sourceHeight = video.videoHeight || 480;
  const frames: string[] = [];
  for (let i = 0; i < count; i++) {
    let scale = Math.min(1, MAX_SIDE / Math.max(sourceWidth, sourceHeight));
    let quality = 0.82;
    let frame = '';
    // Each pass gives up a little quality, then a little size: detection needs the face's
    // shape far more than its texture.
    for (let attempt = 0; attempt < 8; attempt++) {
      canvas.width = Math.round(sourceWidth * scale);
      canvas.height = Math.round(sourceHeight * scale);
      context.drawImage(video, 0, 0, canvas.width, canvas.height);
      frame = canvas.toDataURL('image/jpeg', quality);
      if (decodedBytes(frame) <= MAX_FRAME_BYTES) break;
      if (quality > 0.55) quality -= 0.1;
      else scale *= 0.85;
    }
    frames.push(frame);
    if (i < count - 1) await new Promise(resolve => setTimeout(resolve, gapMs));
  }
  return frames;
}

export async function openCamera(video: HTMLVideoElement): Promise<MediaStream> {
  const stream = await navigator.mediaDevices.getUserMedia({
    video: { facingMode: 'user', width: { ideal: 1280 }, height: { ideal: 720 } },
    audio: false,
  });
  video.srcObject = stream;
  await video.play();
  return stream;
}
