package zw.co.innbucks.loans.core.draft;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** JSON Merge Patch, checked against the examples of RFC 7386, Appendix A. */
class JsonMergePatchTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest(name = "{0} + {1} = {2}")
    @DisplayName("each RFC 7386 example")
    @CsvSource(delimiter = '|', value = {
            "{\"a\":\"b\"}                 | {\"a\":\"c\"}                 | {\"a\":\"c\"}",
            "{\"a\":\"b\"}                 | {\"b\":\"c\"}                 | {\"a\":\"b\",\"b\":\"c\"}",
            "{\"a\":\"b\"}                 | {\"a\":null}                  | {}",
            "{\"a\":\"b\",\"b\":\"c\"}     | {\"a\":null}                  | {\"b\":\"c\"}",
            "{\"a\":[\"b\"]}               | {\"a\":\"c\"}                 | {\"a\":\"c\"}",
            "{\"a\":\"c\"}                 | {\"a\":[\"b\"]}               | {\"a\":[\"b\"]}",
            "{\"a\":{\"b\":\"c\"}}         | {\"a\":{\"b\":\"d\",\"c\":null}} | {\"a\":{\"b\":\"d\"}}",
            "{\"a\":[{\"b\":\"c\"}]}       | {\"a\":[1]}                   | {\"a\":[1]}",
            "[\"a\",\"b\"]                 | [\"c\",\"d\"]                 | [\"c\",\"d\"]",
            "{\"a\":\"b\"}                 | [\"c\"]                       | [\"c\"]",
            "{\"a\":\"foo\"}               | null                          | null",
            "{\"a\":\"foo\"}               | \"bar\"                       | \"bar\"",
            "{\"e\":null}                  | {\"a\":1}                     | {\"e\":null,\"a\":1}",
            "[1,2]                         | {\"a\":\"b\",\"c\":null}      | {\"a\":\"b\"}",
            "{}                            | {\"a\":{\"bb\":{\"ccc\":null}}} | {\"a\":{\"bb\":{}}}"})
    void rfcExamples(String target, String patch, String expected) {
        assertThat(JsonMergePatch.apply(json(target), json(patch))).isEqualTo(json(expected));
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("the target is never changed: the result is a copy")
    @CsvSource(delimiter = '|', value = {"{\"a\":{\"b\":\"c\"}} | {\"a\":{\"b\":\"d\"}}"})
    void targetIsUnchanged(String target, String patch) {
        JsonNode original = json(target);

        JsonMergePatch.apply(original, json(patch));

        assertThat(original).isEqualTo(json(target));
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text.strip());
    }
}
