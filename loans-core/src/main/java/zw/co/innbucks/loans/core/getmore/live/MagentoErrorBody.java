package zw.co.innbucks.loans.core.getmore.live;

import tools.jackson.databind.JsonNode;
import zw.co.innbucks.loans.core.getmore.GetMoreCodes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Magento's error envelope, {@code {message, parameters, errors[], code?, trace?}}: {@code message} is the RAW
 * template ({@code "Error occurred when generating coupons: %1"}), {@code parameters} an object for named
 * placeholders or an array for positional ones. {@code trace} (developer mode only) is never kept, so it can never
 * be logged.
 */
public record MagentoErrorBody(String message, JsonNode parameters, List<MagentoErrorBody> errors, Integer code) {

    /** What a rendered message is cut to, so a log line or a stored error stays bounded. */
    static final int MAX_RENDERED = 500;

    private static final Pattern POSITIONAL = Pattern.compile("%(\\d+)");
    private static final Pattern NAMED = Pattern.compile("%([A-Za-z_][A-Za-z0-9_]*)");

    public MagentoErrorBody {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /** The envelope, when {@code body} is a JSON object with a textual {@code message}; empty for anything else. */
    static Optional<MagentoErrorBody> parse(byte[] body) {
        if (body == null || body.length == 0) {
            return Optional.empty();
        }
        try {
            JsonNode root = MagentoJson.tree(body);
            return root != null && root.isObject() && root.path("message").isString()
                    ? Optional.of(from(root, 0)) : Optional.empty();
        } catch (RuntimeException notJson) {
            return Optional.empty();
        }
    }

    private static MagentoErrorBody from(JsonNode node, int depth) {
        List<MagentoErrorBody> nested = new ArrayList<>();
        JsonNode errors = node.path("errors");
        if (depth == 0 && errors.isArray()) {
            for (JsonNode error : errors) {
                if (error.isObject() && error.path("message").isString()) {
                    nested.add(from(error, depth + 1));
                }
            }
        }
        JsonNode code = node.path("code");
        return new MagentoErrorBody(node.path("message").asString(), node.get("parameters"), nested,
                code.isIntegralNumber() ? code.asInt() : null);
    }

    /** Whether Magento masked a database or unexpected error ({@code Internal Error... Report ID}). */
    boolean internalError() {
        return message != null && message.startsWith("Internal Error");
    }

    /**
     * The message with its parameters substituted, then any nested errors, every code masked
     * ({@link GetMoreCodes#maskAll}) and the whole cut to {@value #MAX_RENDERED} characters.
     */
    public String render(Collection<String> knownCodes) {
        StringBuilder out = new StringBuilder(substitute(message, parameters));
        if (!errors.isEmpty()) {
            StringJoiner nested = new StringJoiner("; ", " (", ")");
            errors.forEach(e -> nested.add(substitute(e.message(), e.parameters())));
            out.append(nested);
        }
        String masked = GetMoreCodes.maskAll(out.toString(), knownCodes);
        return masked.length() > MAX_RENDERED ? masked.substring(0, MAX_RENDERED) + "…" : masked;
    }

    static String substitute(String template, JsonNode parameters) {
        if (template == null) {
            return "";
        }
        if (parameters == null || parameters.isNull() || parameters.isMissingNode()) {
            return template;
        }
        if (parameters.isArray()) {
            Matcher m = POSITIONAL.matcher(template);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                int index = Integer.parseInt(m.group(1)) - 1;
                JsonNode value = index >= 0 && index < parameters.size() ? parameters.get(index) : null;
                m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : text(value)));
            }
            m.appendTail(out);
            return out.toString();
        }
        if (parameters.isObject()) {
            Matcher m = NAMED.matcher(template);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                JsonNode value = parameters.get(m.group(1));
                m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : text(value)));
            }
            m.appendTail(out);
            return out.toString();
        }
        return template;
    }

    private static String text(JsonNode value) {
        return value.isValueNode() ? value.asString() : value.toString();
    }

    /** Never the message or parameters: they can echo a code. */
    @Override
    public String toString() {
        return "MagentoErrorBody[errors=" + errors.size() + ", code=" + code + "]";
    }
}
