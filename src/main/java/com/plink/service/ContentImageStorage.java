package com.plink.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Private storage: browsers only receive images through the authorised application route. */
@Service
public class ContentImageStorage {
    private final Path root;
    private final String backend, url, key, bucket;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ContentImageStorage(@Value("${plink.images.backend:local}") String backend,
            @Value("${plink.images.directory:./data/content-images}") String directory,
            @Value("${plink.images.supabase-url:}") String url,
            @Value("${plink.images.supabase-key:}") String key,
            @Value("${plink.images.bucket:content-images}") String bucket) {
        this.backend = backend;
        this.root = Path.of(directory).toAbsolutePath().normalize();
        this.url = url.replaceAll("/+$", "");
        this.key = key;
        this.bucket = bucket;
        if (!backend.equals("local") && !backend.equals("supabase")) throw new IllegalArgumentException("Unknown image backend");
        if (backend.equals("supabase") && (!url.startsWith("https://") || key.isBlank()
                || !bucket.matches("[a-zA-Z0-9_-]+"))) {
            throw new IllegalArgumentException("Configure private Supabase image storage URL, key and bucket");
        }
    }

    public String backend() { return backend; }
    private Path path(String name) {
        if (!name.matches("[a-f0-9-]{36}\\.(jpg|png)")) throw new IllegalArgumentException("Invalid image key");
        return root.resolve(name);
    }
    public void put(String name, byte[] bytes, String type) {
        try {
            if (backend.equals("local")) {
                Files.createDirectories(root);
                Files.write(path(name), bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
            } else request("POST", "/object/", name, bytes, type);
        } catch (IOException e) { throw unavailable(); }
    }
    public byte[] get(String storedBackend, String name) {
        try {
            if (storedBackend.equals("local")) return Files.readAllBytes(path(name));
            return request("GET", "/object/authenticated/", name, null, null);
        } catch (IOException e) { throw unavailable(); }
    }
    public void delete(String storedBackend, String name) {
        try {
            if (storedBackend.equals("local")) Files.deleteIfExists(path(name));
            else request("DELETE", "/object/", name, null, null);
        } catch (IOException e) { throw unavailable(); }
    }
    private byte[] request(String method, String route, String name, byte[] bytes, String type) {
        path(name); // Only application-generated keys are accepted by either backend.
        if (url.isBlank() || key.isBlank()) throw unavailable();
        try {
            var builder = HttpRequest.newBuilder(URI.create(url + "/storage/v1" + route + bucket + "/" + name))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + key).header("apikey", key);
            if (type != null) builder.header("Content-Type", type);
            var response = client.send(builder.method(method, bytes == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofByteArray());
            if (method.equals("DELETE") && response.statusCode() == 404) return new byte[0];
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw unavailable();
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (IOException e) { throw unavailable(); }
    }
    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "이미지 저장소에 연결하지 못했어요. 다시 시도해 주세요.");
    }
}
