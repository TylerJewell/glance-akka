package io.akka.glance.util;

import java.util.Map;

/**
 * The HTML entities a feed's title or description carries.
 *
 * <p>The named set is the one Go's {@code html.UnescapeString} covers most often in practice
 * — a feed writes an ampersand, a quote or a dash, not the long tail — and the numeric forms
 * are handled in full, which is where the rest of them arrive.
 */
final class Entities {

  private static final Map<String, String> NAMED =
      Map.ofEntries(
          Map.entry("amp", "&"),
          Map.entry("lt", "<"),
          Map.entry("gt", ">"),
          Map.entry("quot", "\""),
          Map.entry("apos", "'"),
          Map.entry("nbsp", "\u00A0"),
          Map.entry("hellip", "…"),
          Map.entry("mdash", "—"),
          Map.entry("ndash", "–"),
          Map.entry("lsquo", "‘"),
          Map.entry("rsquo", "’"),
          Map.entry("ldquo", "“"),
          Map.entry("rdquo", "”"),
          Map.entry("copy", "©"),
          Map.entry("reg", "®"),
          Map.entry("trade", "™"),
          Map.entry("deg", "°"),
          Map.entry("middot", "·"),
          Map.entry("bull", "•"),
          Map.entry("laquo", "«"),
          Map.entry("raquo", "»"),
          Map.entry("euro", "€"),
          Map.entry("pound", "£"),
          Map.entry("yen", "¥"),
          Map.entry("cent", "¢"),
          Map.entry("sect", "§"),
          Map.entry("para", "¶"),
          Map.entry("dagger", "†"),
          Map.entry("permil", "‰"),
          Map.entry("prime", "′"),
          Map.entry("times", "×"),
          Map.entry("divide", "÷"),
          Map.entry("frac12", "½"),
          Map.entry("frac14", "¼"),
          Map.entry("frac34", "¾"));

  private Entities() {}

  /** The text an entity stands for, or nothing when it is not one. */
  static String lookup(String entity) {
    if (entity.startsWith("#")) {
      try {
        int code =
            entity.length() > 1 && (entity.charAt(1) == 'x' || entity.charAt(1) == 'X')
                ? Integer.parseInt(entity.substring(2), 16)
                : Integer.parseInt(entity.substring(1));
        if (code <= 0 || code > Character.MAX_CODE_POINT) {
          return null;
        }
        return new String(Character.toChars(code));
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return NAMED.get(entity);
  }
}
