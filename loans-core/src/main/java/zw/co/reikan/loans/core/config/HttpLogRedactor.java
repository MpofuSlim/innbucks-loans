package zw.co.reikan.loans.core.config;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns outbound HTTP traffic into text that is safe to write to the application log.
 *
 * <p>Every Ndasenda and InnBucks call rides the shared {@code RestTemplate}, so its
 * traffic carries the integration credentials (the Ndasenda password grant and
 * security code, the InnBucks login and the {@code accessToken} it returns, bearer
 * tokens, {@code X-Api-Key}) and the applicant's KYC (national ID numbers, base64
 * ID/payslip/signature images). The log files are kept for 180 days, so anything
 * printed here outlives the credential rotation that would otherwise contain it.
 *
 * <p>Redaction is by NAME, case- and separator-insensitive ({@code access_token},
 * {@code accessToken} and {@code ACCESS-TOKEN} are one key), plus a value scrub for
 * the shapes that are secret wherever they appear: JWTs, {@code Bearer}/{@code Basic}
 * credentials and long base64 runs. Nothing here throws on malformed input — an
 * unparseable body falls back to the regex scrub rather than to the raw text.
 */
public final class HttpLogRedactor {

    public static final String MASK = "***";

    /** Upper bound on any body written to the log, after redaction. */
    static final int MAX_LOGGED_BODY_CHARS = 4096;

    /** How much of an unparseable body is scanned at all; the rest is never printed. */
    private static final int MAX_SCANNED_TEXT_CHARS = 16 * 1024;

    /** Bigger JSON is not worth parsing just to print its first few kilobytes; it takes the text path. */
    private static final int MAX_PARSED_JSON_CHARS = 1024 * 1024;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Header names (lower-cased) that are credentials outright. */
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key");

    /** A header whose name contains any of these is treated as a credential. */
    private static final List<String> SENSITIVE_HEADER_FRAGMENTS = List.of(
            "token", "secret", "key", "password", "auth");

    /** Normalised field names (lower-case, letters and digits only) that are sensitive outright. */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "pass", "pwd", "passcode", "pin", "pincode", "pinblock", "pinnumber", "newpin", "oldpin",
            "currentpin", "userpin", "otp", "cvv", "cvc", "authorization", "cookie", "setcookie");

    /** A normalised field name containing any of these is sensitive. */
    private static final List<String> SENSITIVE_KEY_FRAGMENTS = List.of(
            // credentials: password, secret, token, accessToken, access_token, refresh_token,
            // id_token, securityToken, security_code, apiKey, api_key, client_secret, ...
            "password", "passwd", "passphrase", "secret", "token", "apikey", "securitycode",
            "privatekey", "credential",
            // personal data: idNumber, nationalId, nationalIdNumber, national_id,
            // nextOfKinIdNumber, and the base64 ID / payslip / signature documents
            "idnumber", "nationalid", "passport", "picture", "image", "photo", "base64",
            "signature", "payslip", "document", "attachment");

    private static final Pattern JWT = Pattern.compile(
            "eyJ[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]*");

    private static final Pattern AUTH_SCHEME_CREDENTIAL = Pattern.compile(
            "(?i)\\b(bearer|basic)(\\s+)[A-Za-z0-9._~+/=-]+");

    /** A base64 run long enough to be a document rather than an identifier. */
    private static final Pattern LONG_BASE64 = Pattern.compile("[A-Za-z0-9+/]{200,}={0,2}");

    /**
     * {@code key <sep> value} in any of the shapes an unparseable body uses: JSON
     * ({@code "password":"x"}), form or query ({@code password=x}) and prose
     * ({@code password: x}). The closing quote is optional so a value cut off by a
     * truncated body is still masked.
     */
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?<quote>[\"']?)(?<key>[A-Za-z_][A-Za-z0-9_.\\-]{0,99})\\k<quote>(?<sep>\\s*[:=]\\s*)"
                    + "(?<value>\"(?:[^\"\\\\]|\\\\.)*\"?|'(?:[^'\\\\]|\\\\.)*'?|[^\\s,;&}\\]\"'<>]+)");

    /** {@code <password>x</password>}, for the odd XML or HTML error page. */
    private static final Pattern XML_ELEMENT = Pattern.compile(
            "<(?<tag>[A-Za-z_][A-Za-z0-9_.:\\-]{0,99})(?<attrs>\\s[^<>]{0,500})?>(?<value>[^<]*)</\\k<tag>\\s*>");

    private HttpLogRedactor() {
    }

    /** Whether a header value must never be logged. */
    public static boolean isSensitiveHeader(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (SENSITIVE_HEADERS.contains(lower)) {
            return true;
        }
        for (String fragment : SENSITIVE_HEADER_FRAGMENTS) {
            if (lower.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the value of a body field / query parameter with this name must never be logged. */
    public static boolean isSensitiveKey(String name) {
        if (name == null) {
            return false;
        }
        String normalised = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (normalised.isEmpty()) {
            return false;
        }
        if (SENSITIVE_KEYS.contains(normalised)) {
            return true;
        }
        for (String fragment : SENSITIVE_KEY_FRAGMENTS) {
            if (normalised.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** {@code [Name:"value", Authorization:"***"]}, in the shape {@link HttpHeaders#toString()} uses. */
    public static String redactHeaders(HttpHeaders headers) {
        if (headers == null) {
            return "[]";
        }
        StringBuilder out = new StringBuilder("[");
        for (Map.Entry<String, List<String>> header : headers.headerSet()) {
            if (out.length() > 1) {
                out.append(", ");
            }
            String name = header.getKey();
            out.append(name).append(':');
            if (isSensitiveHeader(name)) {
                out.append('"').append(MASK).append('"');
                continue;
            }
            List<String> values = header.getValue();
            if (values == null || values.isEmpty()) {
                out.append("\"\"");
                continue;
            }
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append('"').append(scrubValue(values.get(i))).append('"');
            }
        }
        return out.append(']').toString();
    }

    /**
     * The URI without user-info or fragment, with the value of every sensitive query
     * parameter masked and any JWT in the path or query scrubbed. Safe at INFO.
     */
    public static String redactUri(URI uri) {
        if (uri == null) {
            return "null";
        }
        if (uri.isOpaque()) {
            return uri.getScheme() + ":" + MASK;
        }
        StringBuilder out = new StringBuilder();
        if (uri.getScheme() != null) {
            out.append(uri.getScheme()).append(':');
        }
        if (uri.getHost() != null) {
            out.append("//").append(uri.getHost());
            if (uri.getPort() >= 0) {
                out.append(':').append(uri.getPort());
            }
        } else if (uri.getRawAuthority() != null) {
            // An authority java.net.URI could not split into host and port: keep what follows
            // any user-info, never the user-info itself.
            String authority = uri.getRawAuthority();
            out.append("//").append(authority.substring(authority.lastIndexOf('@') + 1));
        }
        if (uri.getRawPath() != null) {
            out.append(scrubValue(uri.getRawPath()));
        }
        if (uri.getRawQuery() != null) {
            out.append('?').append(scrubValue(redactPairs(uri.getRawQuery())));
        }
        return out.toString();
    }

    /**
     * A body, redacted for its content type and cut to {@link #MAX_LOGGED_BODY_CHARS}.
     * Only ever meant for DEBUG: personal data that is not on the sensitive list
     * (names, phone numbers, amounts) is still present.
     */
    public static String redactBody(byte[] body, MediaType contentType) {
        if (body == null || body.length == 0) {
            return "";
        }
        try {
            if (contentType != null && !isTextual(contentType)) {
                return "<" + body.length + " bytes of " + contentType + " omitted>";
            }
            String text = new String(body, charsetOf(contentType));
            if (contentType != null && MediaType.APPLICATION_FORM_URLENCODED.includes(contentType)) {
                return truncate(scrubValue(redactPairs(firstChars(text, MAX_SCANNED_TEXT_CHARS))));
            }
            String trimmed = text.strip();
            if ((trimmed.startsWith("{") || trimmed.startsWith("[")) && trimmed.length() <= MAX_PARSED_JSON_CHARS) {
                String json = redactJson(trimmed);
                if (json != null) {
                    return truncate(json);
                }
            }
            return truncate(redactText(text));
        } catch (RuntimeException ex) {
            return "<" + body.length + " bytes not logged: " + ex.getClass().getSimpleName() + ">";
        }
    }

    /** The regex scrub for text that could not be parsed. Masks, never removes, so the shape stays readable. */
    public static String redactText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String scanned = firstChars(text, MAX_SCANNED_TEXT_CHARS);
        String redacted = redactXmlElements(redactKeyValues(scrubValue(scanned)));
        if (scanned.length() < text.length()) {
            redacted += "...[" + (text.length() - scanned.length()) + " more chars not scanned]";
        }
        return redacted;
    }

    /**
     * Masks the value after every sensitive key. A non-sensitive key's value is not
     * skipped over but searched in turn, so {@code error: password=x} and
     * {@code "message":"token=x"} still lose {@code x}.
     */
    private static String redactKeyValues(String text) {
        Matcher matcher = KEY_VALUE.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int copied = 0;
        int from = 0;
        while (from < text.length() && matcher.find(from)) {
            if (!isSensitiveKey(matcher.group("key"))) {
                from = matcher.end("sep");
                continue;
            }
            int valueStart = matcher.start("value");
            char first = text.charAt(valueStart);
            int valueEnd = (first == '{' || first == '[') ? endOfComposite(text, valueStart) : matcher.end("value");
            String masked = first == '"' ? "\"" + MASK + "\"" : first == '\'' ? "'" + MASK + "'" : MASK;
            out.append(text, copied, valueStart).append(masked);
            copied = valueEnd;
            from = valueEnd;
        }
        out.append(text, copied, text.length());
        return out.toString();
    }

    /**
     * The index just past the object or array opening at {@code start}, or the end of the
     * text when it was cut off first — everything under a sensitive key goes.
     */
    private static int endOfComposite(String text, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if ((c == '}' || c == ']') && --depth == 0) {
                return i + 1;
            }
        }
        return text.length();
    }

    private static String redactXmlElements(String text) {
        Matcher matcher = XML_ELEMENT.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement = matcher.group();
            if (isSensitiveKey(localName(matcher.group("tag")))) {
                String attrs = matcher.group("attrs") == null ? "" : matcher.group("attrs");
                replacement = "<" + matcher.group("tag") + attrs + ">" + MASK + "</" + matcher.group("tag") + ">";
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String localName(String tag) {
        return tag.substring(tag.lastIndexOf(':') + 1);
    }

    /** JWTs, {@code Bearer}/{@code Basic} credentials and long base64 runs, wherever they appear. */
    static String scrubValue(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String scrubbed = JWT.matcher(value).replaceAll(MASK);
        scrubbed = AUTH_SCHEME_CREDENTIAL.matcher(scrubbed).replaceAll("$1$2" + Matcher.quoteReplacement(MASK));
        return LONG_BASE64.matcher(scrubbed).replaceAll(MASK);
    }

    /** {@code null} when the text is not JSON after all. */
    private static String redactJson(String text) {
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (RuntimeException notJson) {
            return null;
        }
        if (root == null || root.isMissingNode()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        render(root, out);
        return out.toString();
    }

    private static void render(JsonNode node, StringBuilder out) {
        if (node.isObject()) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(quote(property.getKey())).append(':');
                JsonNode value = property.getValue();
                if (isSensitiveKey(property.getKey()) && !value.isNull()) {
                    out.append(quote(MASK));
                } else {
                    render(value, out);
                }
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            boolean first = true;
            for (JsonNode element : node.values()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                render(element, out);
            }
            out.append(']');
        } else if (node.isString()) {
            // A free-text value can itself carry "token=..." (an echoed URL, an error message).
            out.append(quote(redactKeyValues(scrubValue(node.stringValue()))));
        } else {
            out.append(node);
        }
    }

    private static String quote(String value) {
        return JSON.writeValueAsString(value);
    }

    /** {@code a=1&password=***}: the raw pairs kept verbatim unless the (decoded) key is sensitive. */
    private static String redactPairs(String raw) {
        StringBuilder out = new StringBuilder();
        String[] pairs = raw.split("&", -1);
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i];
            if (i > 0) {
                out.append('&');
            }
            int eq = pair.indexOf('=');
            String rawKey = eq < 0 ? pair : pair.substring(0, eq);
            if (eq >= 0 && isSensitiveKey(decode(rawKey))) {
                out.append(rawKey).append('=').append(MASK);
            } else {
                out.append(pair);
            }
        }
        return out.toString();
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return value;
        }
    }

    private static boolean isTextual(MediaType contentType) {
        if ("text".equalsIgnoreCase(contentType.getType())) {
            return true;
        }
        String subtype = contentType.getSubtype().toLowerCase(Locale.ROOT);
        return subtype.equals("json") || subtype.endsWith("+json")
                || subtype.equals("xml") || subtype.endsWith("+xml")
                || subtype.equals("x-www-form-urlencoded")
                || subtype.equals("javascript");
    }

    private static Charset charsetOf(MediaType contentType) {
        try {
            if (contentType != null && contentType.getCharset() != null) {
                return contentType.getCharset();
            }
        } catch (RuntimeException unsupported) {
            // fall through to UTF-8
        }
        return StandardCharsets.UTF_8;
    }

    private static String firstChars(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String truncate(String text) {
        if (text.length() <= MAX_LOGGED_BODY_CHARS) {
            return text;
        }
        return text.substring(0, MAX_LOGGED_BODY_CHARS)
                + "...[truncated " + (text.length() - MAX_LOGGED_BODY_CHARS) + " chars]";
    }
}
