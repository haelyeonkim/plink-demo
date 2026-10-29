/**
 * Makes a phone photo small enough to send over a venue's network: at most 1600px on its
 * long edge, as a JPEG. The browser applies the camera's rotation while drawing, so the
 * picture stands the right way up. The server draws it again either way.
 */
export async function shrinkPhoto(file: File, maxEdge = 1600): Promise<Blob> {
  if (!/^image\/(jpeg|png|webp|heic|heif)$/i.test(file.type) && file.type !== '') {
    throw new Error('사진 파일을 골라 주세요.');
  }
  try {
    const bitmap = await createImageBitmap(file);
    const scale = Math.min(1, maxEdge / Math.max(bitmap.width, bitmap.height));
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(bitmap.width * scale));
    canvas.height = Math.max(1, Math.round(bitmap.height * scale));
    const context = canvas.getContext('2d');
    if (!context) return file;
    context.fillStyle = '#fff';
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    bitmap.close();
    const blob = await new Promise<Blob | null>(resolve => canvas.toBlob(resolve, 'image/jpeg', 0.85));
    return blob ?? file;
  } catch {
    // A format this browser cannot draw goes as it is; the server decides.
    return file;
  }
}
