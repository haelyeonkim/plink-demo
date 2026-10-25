package com.plink.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Decimal money; strings at the JSON boundary avoid JavaScript precision loss. */
public final class ArtworkPrice {
    private ArtworkPrice() {}
    public static final Set<String> KEYS = Set.of("priceMode", "priceAmount", "priceCurrency",
        "priceNote", "priceKrwRate", "priceRateDate");
    public static final Set<String> CURRENCIES = Set.of("USD", "EUR", "KRW", "GBP", "JPY", "CHF",
        "CNY", "HKD", "SGD", "AUD", "CAD");
    private static final Pattern LEGACY = Pattern.compile(
        "(?i)^(USD|US\\$|\\$|EUR|€|KRW|₩|GBP|£|JPY|CHF|CNY|HKD|SGD|AUD|CAD)?\\s*"
        + "([0-9]+(?:\\.[0-9]{1,2})?|[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?)"
        + "\\s*(USD|EUR|KRW|GBP|JPY|CHF|CNY|HKD|SGD|AUD|CAD|원)?$");

    public record Value(String mode, BigDecimal amount, String currency, String note,
                        BigDecimal krwRate, LocalDate rateDate) {
        public Map<String, String> fields() {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("priceMode", mode); result.put("priceAmount", decimal(amount));
            result.put("priceCurrency", currency); result.put("priceNote", note == null ? "" : note);
            result.put("priceKrwRate", decimal(krwRate));
            result.put("priceRateDate", rateDate == null ? "" : rateDate.toString());
            return result;
        }
        public String display() {
            return switch (mode) {
                case "amount" -> currency + " " + decimal(amount);
                case "inquiry" -> note == null || note.isBlank() ? "가격 문의" : note;
                default -> "";
            };
        }
    }
    public static String decimal(BigDecimal value) { return value == null ? "" : value.stripTrailingZeros().toPlainString(); }

    /** The existing unlabelled/dollar prices were confirmed to be US dollars. */
    public static Value legacy(String original) {
        String text = original == null ? "" : original.trim();
        if (text.isEmpty()) return new Value("hidden", null, "USD", "", null, null);
        var match = LEGACY.matcher(text);
        if (match.matches()) {
            String prefix = currency(match.group(1));
            String suffix = currency(match.group(3));
            if (prefix == null || suffix == null || prefix.equals(suffix)) {
                try {
                    BigDecimal amount = number(match.group(2).replace(",", ""), 2, 16, false);
                    return new Value("amount", amount, prefix != null ? prefix : suffix != null ? suffix : "USD", "", null, null);
                } catch (ResponseStatusException tooLarge) { /* Preserve rather than guess or truncate. */ }
            }
        }
        return new Value("inquiry", null, "USD", text, null, null);
    }
    private static String currency(String token) {
        if (token == null) return null;
        return switch (token.toUpperCase(Locale.ROOT)) {
            case "$", "US$" -> "USD"; case "€" -> "EUR"; case "₩", "원" -> "KRW"; case "£" -> "GBP";
            default -> token.toUpperCase(Locale.ROOT);
        };
    }
    public static Value read(Map<?, ?> fields) {
        boolean structured = KEYS.stream().anyMatch(fields::containsKey);
        if (!structured) return legacy(text(fields, "price"));
        String mode = text(fields, "priceMode");
        String currency = text(fields, "priceCurrency");
        if (currency.isEmpty()) currency = "USD";
        if (!Set.of("amount", "inquiry", "hidden").contains(mode) || !CURRENCIES.contains(currency)) throw invalid();
        String note = text(fields, "priceNote");
        if (note.length() > 300) throw invalid();
        if (!mode.equals("amount")) return new Value(mode, null, currency, mode.equals("inquiry") ? note : "", null, null);
        BigDecimal amount = number(text(fields, "priceAmount"), 2, 16, false);
        String rate = text(fields, "priceKrwRate");
        String date = text(fields, "priceRateDate");
        if (currency.equals("KRW")) return new Value(mode, amount, currency, "", null, null);
        if (rate.isEmpty() != date.isEmpty()) throw invalid();
        try {
            return new Value(mode, amount, currency, "", rate.isEmpty() ? null : number(rate, 6, 12, true),
                date.isEmpty() ? null : LocalDate.parse(date));
        } catch (java.time.DateTimeException invalidDate) { throw invalid(); }
    }
    private static String text(Map<?, ?> fields, String key) {
        Object value = fields.get(key);
        if (value == null) return "";
        if (!(value instanceof String) && !(value instanceof Number)) throw invalid();
        return value.toString().trim();
    }
    private static BigDecimal number(String text, int scale, int integers, boolean positive) {
        if (!text.matches("[0-9]{1," + integers + "}(?:\\.[0-9]{1," + scale + "})?")) throw invalid();
        BigDecimal number = new BigDecimal(text).setScale(scale, RoundingMode.UNNECESSARY);
        if (positive && number.signum() <= 0) throw invalid();
        return number;
    }
    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "가격·통화와 환율·기준일을 확인해 주세요.");
    }
    /** Copy the document so immutable importer/test maps are also supported. */
    public static Object normalizeBody(Object value) { return normalizeBody(value, true); }
    public static Object normalizeBody(Object value, boolean updateDisplay) {
        if (!(value instanceof Map<?, ?> document) || !(document.get("artworks") instanceof List<?> rows)) return value;
        Map<Object, Object> copy = new LinkedHashMap<>(document);
        copy.put("artworks", rows.stream().map(row -> {
            if (!(row instanceof Map<?, ?> fields)) return row;
            Map<Object, Object> result = new LinkedHashMap<>(fields);
            Value price = read(fields);
            result.putAll(price.fields());
            // Keep imported legacy wording; new structured edits get a compatible display value.
            if (updateDisplay && KEYS.stream().anyMatch(fields::containsKey)) result.put("price", price.display());
            return result;
        }).toList());
        return copy;
    }
}
