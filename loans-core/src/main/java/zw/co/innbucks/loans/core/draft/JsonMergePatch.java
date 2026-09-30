package zw.co.innbucks.loans.core.draft;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * JSON Merge Patch (RFC 7386): a field in the patch replaces the same field in the target, an object is
 * merged field by field, a {@code null} removes the field, and a field the patch leaves out is kept. So a
 * form can be saved a step at a time, sending only what changed.
 */
final class JsonMergePatch {

    private JsonMergePatch() {
    }

    static JsonNode apply(JsonNode target, JsonNode patch) {
        if (!patch.isObject()) {
            return patch;
        }
        ObjectNode result = target != null && target.isObject()
                ? (ObjectNode) target.deepCopy()
                : JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, JsonNode> field : patch.properties()) {
            if (field.getValue().isNull()) {
                result.remove(field.getKey());
            } else {
                result.set(field.getKey(), apply(result.get(field.getKey()), field.getValue()));
            }
        }
        return result;
    }
}
