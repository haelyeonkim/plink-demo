package com.plink.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ArtworkPriceTest {
    @Test void legacyDollarAndBareAmountsAreUsdWithoutLosingCents() {
        for (String text : List.of("$5,000.25", "US$ 5,000.25", "USD 5000.25", "5,000.25 USD", "5000.25")) {
            var value = ArtworkPrice.legacy(text);
            assertThat(value.amount()).isEqualByComparingTo("5000.25");
            assertThat(value.currency()).isEqualTo("USD");
            assertThat(value.mode()).isEqualTo("amount");
        }
        assertThat(ArtworkPrice.legacy("€ 3,000").currency()).isEqualTo("EUR");
        assertThat(ArtworkPrice.legacy("1,000,000 KRW").currency()).isEqualTo("KRW");
        assertThat(ArtworkPrice.legacy("0").amount()).isEqualByComparingTo("0");
    }
    @Test void preservesUnknownWordingAndDoesNotGuessMalformedNumbers() {
        for (String text : List.of("가격 문의", "$5,000 + VAT", "$1,23", "1.234,56 EUR", "$500 EUR",
                "99999999999999999", "JPY ¥500")) {
            assertThat(ArtworkPrice.legacy(text).amount()).isNull();
            assertThat(ArtworkPrice.legacy(text).note()).isEqualTo(text);
        }
        assertThat(ArtworkPrice.legacy("").mode()).isEqualTo("hidden");
    }
    @Test void validatesExactAmountsCurrenciesAndPairedRates() {
        Map<String, Object> valid = new HashMap<>(Map.of("priceMode", "amount", "priceCurrency", "EUR",
            "priceAmount", "9999999999999999.99", "priceKrwRate", "1450.123456", "priceRateDate", "2026-10-11"));
        assertThat(ArtworkPrice.read(valid).amount()).isEqualByComparingTo("9999999999999999.99");
        assertThat(ArtworkPrice.read(valid).krwRate()).isEqualByComparingTo("1450.123456");
        for (var invalid : List.of(Map.entry("priceAmount", "-1"), Map.entry("priceAmount", "NaN"),
                Map.entry("priceAmount", "1.001"), Map.entry("priceAmount", "1e5"),
                Map.entry("priceAmount", "10000000000000000"), Map.entry("priceCurrency", "XYZ"),
                Map.entry("priceMode", "invalid"), Map.entry("priceRateDate", "2026-02-30"),
                Map.entry("priceRateDate", ""), Map.entry("priceKrwRate", ""), Map.entry("priceKrwRate", "0"))) {
            var changed = new HashMap<>(valid); changed.put(invalid.getKey(), invalid.getValue());
            assertThatThrownBy(() -> ArtworkPrice.read(changed)).isInstanceOf(ResponseStatusException.class);
        }
        valid.put("priceMode", "hidden");
        assertThat(ArtworkPrice.read(valid).amount()).isNull();
        assertThat(ArtworkPrice.read(valid).krwRate()).isNull();
    }
}
