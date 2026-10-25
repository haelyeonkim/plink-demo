package db.migration;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import tools.jackson.databind.ObjectMapper;

/** Preserves original price strings and all delivery snapshots. */
public class V36__backfill_artwork_prices extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (var select = connection.prepareStatement("SELECT id, price_text FROM artwork");
             var rows = select.executeQuery();
             var update = connection.prepareStatement("UPDATE artwork SET price_mode=?, price_amount=?, price_currency=?, price_note=? WHERE id=?")) {
            while (rows.next()) {
                var price = legacy(rows.getString("price_text"));
                update.setString(1, price.mode()); update.setBigDecimal(2, price.amount());
                update.setString(3, price.currency()); update.setString(4, price.note());
                update.setLong(5, rows.getLong("id")); update.executeUpdate();
            }
        }
        var mapper = new ObjectMapper();
        try (var select = connection.prepareStatement("SELECT id, body FROM link_content WHERE kind <> 'SELECTION'");
             var rows = select.executeQuery();
             var update = connection.prepareStatement("UPDATE link_content SET body=? WHERE id=?")) {
            while (rows.next()) {
                Object body;
                try { body = mapper.readValue(rows.getString("body"), Object.class); }
                catch (RuntimeException malformedLegacy) { continue; }
                update.setString(1, mapper.writeValueAsString(normalizeBody(body)));
                update.setLong(2, rows.getLong("id")); update.executeUpdate();
            }
        }
    }
    // Frozen with this migration: future input/parser changes must not alter historical backfills.
    private record Price(String mode, BigDecimal amount, String currency, String note) {}
    private static final Pattern PRICE = Pattern.compile(
        "(?i)^(USD|US\\$|\\$|EUR|€|KRW|₩|GBP|£|JPY|CHF|CNY|HKD|SGD|AUD|CAD)?\\s*"
        + "([0-9]+(?:\\.[0-9]{1,2})?|[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?)"
        + "\\s*(USD|EUR|KRW|GBP|JPY|CHF|CNY|HKD|SGD|AUD|CAD|원)?$");
    private static Price legacy(String original) {
        String text = original == null ? "" : original.trim();
        if (text.isEmpty()) return new Price("hidden", null, "USD", "");
        var match = PRICE.matcher(text);
        if (match.matches()) {
            String prefix = currency(match.group(1)), suffix = currency(match.group(3));
            String number = match.group(2).replace(",", "");
            if ((prefix == null || suffix == null || prefix.equals(suffix))
                    && number.matches("[0-9]{1,16}(?:\\.[0-9]{1,2})?"))
                return new Price("amount", new BigDecimal(number),
                    prefix != null ? prefix : suffix != null ? suffix : "USD", "");
        }
        return new Price("inquiry", null, "USD", text);
    }
    private static String currency(String token) {
        if (token == null) return null;
        return switch (token.toUpperCase(Locale.ROOT)) {
            case "$", "US$" -> "USD"; case "€" -> "EUR"; case "₩", "원" -> "KRW"; case "£" -> "GBP";
            default -> token.toUpperCase(Locale.ROOT);
        };
    }
    @SuppressWarnings("unchecked")
    private static Object normalizeBody(Object body) {
        if (body instanceof Map<?, ?> map && map.get("artworks") instanceof List<?> rows) {
            for (Object entry : rows) {
                if (!(entry instanceof Map<?, ?>)) continue;
                var row = (Map<String, Object>) entry;
                var price = legacy(Objects.toString(row.get("price"), ""));
                row.put("priceMode", price.mode());
                row.put("priceAmount", price.amount() == null ? "" : price.amount().stripTrailingZeros().toPlainString());
                row.put("priceCurrency", price.currency()); row.put("priceNote", price.note());
                row.put("priceKrwRate", ""); row.put("priceRateDate", "");
            }
        }
        return body;
    }
}
