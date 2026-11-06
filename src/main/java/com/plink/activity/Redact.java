package com.plink.activity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Takes the secrets out of what is about to be logged.
 *
 * <p>Personal tokens sit in paths (a ticket's, an invite's, a setup link's): a segment
 * that looks like one keeps its first four characters, enough to tell two apart and not
 * enough to use. Request bodies lose every field whose name says it is a secret or a
 * biometric, at any depth.
 */
final class Redact {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_\\-]{20,}");
    private static final Pattern SECRET_KEY = Pattern.compile(
        "(?i).*(password|passcode|secret|token|otp|assertion|credential|attestation|clientdata|"
        + "authenticatordata|signature|userhandle|publickey|image|photo|selfie|face|template|embedding|"
        + "cipher|^code$|pin$).*");
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int BODY_LIMIT = 16_000;

    private Redact() { }

    static String path(String path) {
        if (path == null) return null;
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (TOKEN.matcher(segments[i]).matches()) segments[i] = segments[i].substring(0, 4) + "…";
        }
        return String.join("/", segments);
    }

    static boolean secretKey(String key) {
        return key != null && SECRET_KEY.matcher(key).matches();
    }

    /** The query parameters, secrets replaced. */
    static String query(Map<String, String[]> parameters) {
        if (parameters == null || parameters.isEmpty()) return null;
        StringBuilder out = new StringBuilder();
        parameters.forEach((key, values) -> {
            for (String value : values) {
                if (out.length() > 0) out.append('&');
                out.append(key).append('=').append(secretKey(key) ? "[가림]" : value);
            }
        });
        return ActivityLog.cut(out.toString(), 2000);
    }

    /** A JSON body with the secret fields removed, or a note of what it was. */
    static Object body(byte[] content, String contentType) {
        if (content == null || content.length == 0) return null;
        if (contentType == null || !contentType.toLowerCase().contains("json")) {
            return "(" + (contentType == null ? "본문" : contentType) + " · " + content.length + " bytes)";
        }
        try {
            JsonNode tree = JSON.readTree(content);
            scrub(tree);
            String text = JSON.writeValueAsString(tree);
            if (text.length() > BODY_LIMIT) return text.substring(0, BODY_LIMIT) + "…";
            return tree;
        } catch (RuntimeException unreadable) {
            return "(JSON이 아님 · " + content.length + " bytes)";
        }
    }

    private static void scrub(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.properties().iterator();
            java.util.List<String> secret = new java.util.ArrayList<>();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (secretKey(field.getKey())) secret.add(field.getKey());
                else scrub(field.getValue());
            }
            for (String key : secret) object.put(key, "[가림]");
        } else if (node instanceof ArrayNode array) {
            for (JsonNode item : array) scrub(item);
        }
    }
}
