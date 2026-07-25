package com.urlshortener;

/**
 * Encodes non-negative numbers into short Base62 strings (0-9, a-z, A-Z)
 * and decodes them back. Used to turn an auto-increment ID into a
 * compact short code like "aZ3".
 */
public final class Base62 {

    private static final String ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int BASE = ALPHABET.length();

    private Base62() {
    }

    public static String encode(long value) {
        if (value == 0) {
            return String.valueOf(ALPHABET.charAt(0));
        }
        StringBuilder sb = new StringBuilder();
        long n = value;
        while (n > 0) {
            int remainder = (int) (n % BASE);
            sb.append(ALPHABET.charAt(remainder));
            n /= BASE;
        }
        return sb.reverse().toString();
    }

    public static long decode(String code) {
        long result = 0;
        for (int i = 0; i < code.length(); i++) {
            result = result * BASE + ALPHABET.indexOf(code.charAt(i));
        }
        return result;
    }
}
