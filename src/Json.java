/**
 * Minimal JSON string-building helpers. The server only ever emits data it
 * controls itself (fixed node ids/names, numbers), so a hand-rolled writer
 * avoids pulling in a JSON library for a handful of response shapes.
 */
public final class Json {

  private Json() {}

  public static String string(String s) {
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append('"').toString();
  }

  /** Like {@link #string}, but emits a JSON null for a null Java string. */
  public static String stringOrNull(String s) {
    return s == null ? "null" : string(s);
  }

  public static String number(double d) {
    if (d == Math.rint(d) && !Double.isInfinite(d)) {
      return Long.toString((long) d);
    }
    return Double.toString(d);
  }

  public static String error(String message) {
    return "{\"error\":" + string(message) + "}";
  }
}
