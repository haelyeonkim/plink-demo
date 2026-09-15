package com.plink.service;

import java.util.Locale;

public final class RecipientContact {
    private RecipientContact() {}
    public static String type(String value) {
        if (value == null) return null;
        String contact = value.trim();
        if (contact.length() <= 254 && contact.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) return "email";
        if (contact.matches("[0-9]{11}|[0-9]{3}-[0-9]{4}-[0-9]{4}")) return "phone";
        return null;
    }
    public static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replace("-", "");
    }
    public static boolean matches(String expected, String supplied) {
        String kind = type(expected);
        if (kind == null || !kind.equals(type(supplied))) return false;
        return kind.equals("email") ? expected.trim().equalsIgnoreCase(supplied.trim())
            : normalize(expected).equals(normalize(supplied));
    }
}
