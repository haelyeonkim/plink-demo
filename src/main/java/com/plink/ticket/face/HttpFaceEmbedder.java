package com.plink.ticket.face;

import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The production path: extraction and liveness run in the separate face service, which
 * keeps the model, the vector store and their keys outside this application. A breach
 * of the ticket service therefore reaches no biometric data.
 */
public class HttpFaceEmbedder implements FaceEmbedder {
    private final RestClient client;
    private String algoVersion = "unknown";

    public HttpFaceEmbedder(String baseUrl, String token) {
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        if (!token.isEmpty()) builder.defaultHeader("Authorization", "Bearer " + token);
        this.client = builder.build();
    }

    @Override
    public FaceVector embed(List<byte[]> frames) {
        Map<String, Object> response = post("/embed", frames, null);
        List<?> raw = (List<?>) response.get("vector");
        if (raw == null || raw.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                String.valueOf(response.getOrDefault("reason", "얼굴을 인식하지 못했어요.")));
        }
        float[] values = new float[raw.size()];
        for (int i = 0; i < raw.size(); i++) values[i] = ((Number) raw.get(i)).floatValue();
        algoVersion = String.valueOf(response.getOrDefault("algoVersion", algoVersion));
        return new FaceVector(values, algoVersion, ((Number) response.getOrDefault("quality", 0.0)).doubleValue());
    }

    @Override
    public double liveness(List<byte[]> frames, String challenge) {
        Map<String, Object> response = post("/liveness", frames, challenge);
        return ((Number) response.getOrDefault("score", 0.0)).doubleValue();
    }

    @Override
    public String algoVersion() { return algoVersion; }

    private Map<String, Object> post(String path, List<byte[]> frames, String challenge) {
        Base64.Encoder encoder = Base64.getEncoder();
        List<String> encoded = frames.stream().map(encoder::encodeToString).toList();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = client.post().uri(path)
                .body(challenge == null ? Map.of("frames", encoded)
                    : Map.of("frames", encoded, "challenge", challenge))
                .retrieve().body(Map.class);
            return body == null ? Map.of() : body;
        } catch (Exception unreachable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "얼굴 인식 서비스를 사용할 수 없어요. 안내 데스크에서 도움을 받아 주세요.");
        }
    }
}
