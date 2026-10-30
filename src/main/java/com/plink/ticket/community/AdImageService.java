package com.plink.ticket.community;

import com.plink.service.ContentImageStorage;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageWriteParam;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Photos on the event's board. Each is decoded and drawn again as a JPEG no wider than a
 * phone needs, so what is stored is only pixels: no camera metadata, no location, nothing
 * riding along inside the file. They live in the same private store as content images and
 * are only ever served through a check of who is asking.
 */
@Service
public class AdImageService {
    private static final int MAX_UPLOAD = 15 * 1024 * 1024;
    private static final int MAX_EDGE = 1600;
    private static final long MAX_PIXELS = 40_000_000L;
    /**
     * Photos being drawn at once. Even read at a reduced size one takes tens of megabytes,
     * and a booth rush uploading together must not take the heap with it.
     */
    private final Semaphore drawing = new Semaphore(2);

    private final CommunityRepository repo;
    private final ContentImageStorage storage;

    public AdImageService(CommunityRepository repo, ContentImageStorage storage) {
        this.repo = repo;
        this.storage = storage;
    }

    public record Download(byte[] bytes, String type) {}

    /** Stores a photo for the console ({@code boothId} null) or for one booth's terminal. */
    public String upload(long sessionId, Long boothId, MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_UPLOAD) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사진은 15MB 이하의 JPEG 또는 PNG로 올려 주세요.");
        }
        byte[] bytes;
        try {
            if (!drawing.tryAcquire(15, TimeUnit.SECONDS)) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "사진을 올리는 사람이 많아요. 잠시 뒤에 다시 올려 주세요.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "사진을 올리지 못했어요. 다시 올려 주세요.");
        }
        try { bytes = redraw(file.getBytes()); }
        catch (IOException e) { throw unreadable(); }
        finally { drawing.release(); }
        String id = UUID.randomUUID().toString();
        String key = id + ".jpg";
        storage.put(key, bytes, "image/jpeg");
        try {
            repo.insertImage(id, sessionId, boothId, key, storage.backend(), "image/jpeg", bytes.length);
        } catch (RuntimeException failure) {
            storage.delete(storage.backend(), key);
            throw failure;
        }
        return id;
    }

    private byte[] redraw(byte[] original) throws IOException {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(original))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw unreadable();
            var reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!List.of("jpeg", "jpg", "png").contains(format)) throw unreadable();
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사진이 너무 커요. 4,000만 화소 이하로 올려 주세요.");
                }
                // Read every n-th pixel when the photo is at least twice what is kept: a 40MP
                // picture decoded whole is 160MB of pixels to throw nearly all of away. The
                // step leaves the long edge at or above MAX_EDGE, so the final scale still
                // draws down rather than up.
                ImageReadParam read = reader.getDefaultReadParam();
                int step = Math.max(1, Math.max(width, height) / MAX_EDGE);
                if (step > 1) read.setSourceSubsampling(step, step, 0, 0);
                BufferedImage source = reader.read(0, read);
                double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(width, height));
                int w = Math.max(1, (int) Math.round(width * scale)), h = Math.max(1, (int) Math.round(height * scale));
                BufferedImage target = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = target.createGraphics();
                try {
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    // A transparent PNG lands on white rather than black.
                    g.setColor(Color.WHITE);
                    g.fillRect(0, 0, w, h);
                    g.drawImage(source, 0, 0, w, h, null);
                } finally { g.dispose(); }
                var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
                var output = new ByteArrayOutputStream();
                try (var stream = ImageIO.createImageOutputStream(output)) {
                    writer.setOutput(stream);
                    ImageWriteParam param = writer.getDefaultWriteParam();
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(0.85f);
                    writer.write(null, new IIOImage(target, null, null), param);
                } finally { writer.dispose(); }
                return output.toByteArray();
            } finally { reader.dispose(); }
        } catch (IllegalArgumentException e) { throw unreadable(); }
    }

    /**
     * A photo this side of the event uploaded and no other notice carries, ready to go on
     * one of its notices; anything else is refused. {@code current} is the photo the notice
     * already has, which it may keep. Without the last two checks a booth could put up a
     * picture lifted from another booth's notice, whose id it has seen on the board.
     */
    public String attachable(long sessionId, Long boothId, String imageId, String current) {
        if (imageId == null || imageId.isBlank()) return null;
        if (imageId.equals(current)) return current;
        return repo.findImage(imageId)
            .filter(image -> image.sessionId() == sessionId && java.util.Objects.equals(image.boothId(), boothId))
            .filter(image -> !repo.imageInUse(image.id()))
            .map(CommunityRepository.Image::id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "사진을 다시 올려 주세요."));
    }

    public Download read(String imageId) {
        CommunityRepository.Image image = repo.findImage(imageId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사진을 찾을 수 없어요."));
        return new Download(storage.get(image.backend(), image.key()), image.type());
    }

    /** Removes a photo once no notice carries it: replaced, taken off, or its notice deleted. */
    public void release(String imageId) {
        if (imageId == null || repo.imageInUse(imageId)) return;
        repo.findImage(imageId).ifPresent(this::remove);
    }

    private void remove(CommunityRepository.Image image) {
        try { storage.delete(image.backend(), image.key()); }
        catch (RuntimeException unavailable) { return; } // The next sweep tries again.
        repo.deleteImage(image.id());
    }

    /** Every photo the event holds, read before the event goes: its rows go with it, the files would not. */
    public List<CommunityRepository.Image> ofSession(long sessionId) {
        return repo.imagesOf(sessionId);
    }

    /**
     * Removes the files of photos whose rows are already gone. Best effort: the event is
     * deleted either way, and a store that is down leaves a file nothing points at.
     */
    public void removeFiles(List<CommunityRepository.Image> images) {
        for (CommunityRepository.Image image : images) {
            try { storage.delete(image.backend(), image.key()); }
            catch (RuntimeException unavailable) {
                org.slf4j.LoggerFactory.getLogger(AdImageService.class)
                    .warn("Could not remove board photo {} of a deleted event", image.id());
            }
        }
    }

    /** Photos uploaded but never attached - a form left half done - go after a day. */
    @Scheduled(initialDelay = 3_600_000, fixedDelay = 3_600_000)
    public void cleanup() {
        for (CommunityRepository.Image image : repo.looseImages(Timestamp.from(Instant.now().minus(Duration.ofDays(1))))) {
            remove(image);
        }
    }

    private ResponseStatusException unreadable() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "사진을 읽을 수 없어요. JPEG 또는 PNG로 올려 주세요.");
    }
}
