/** Grabs still frames from a live video element as JPEG data, without ever storing them. */
export async function captureFrames(video: HTMLVideoElement, count = 3, gapMs = 220): Promise<string[]> {
  const canvas = document.createElement('canvas');
  const context = canvas.getContext('2d');
  if (!context) throw new Error('카메라 화면을 읽을 수 없어요.');
  const frames: string[] = [];
  for (let i = 0; i < count; i++) {
    canvas.width = video.videoWidth || 640;
    canvas.height = video.videoHeight || 480;
    context.drawImage(video, 0, 0, canvas.width, canvas.height);
    frames.push(canvas.toDataURL('image/jpeg', 0.82));
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
