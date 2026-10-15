package dev.erezalkalay.jobhunter.jobdata;

import dev.erezalkalay.jobhunter.common.exception.InvalidRequestException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/**
 * (De)serialization of the job data file with its own strict mapper,
 * independent of the API's mapper: the file is untrusted on import, so
 * unknown properties, scalar coercion ("85" for a number, 1.5 for an integer),
 * trailing content and default typing are all refused. Output is indented
 * and timestamps are ISO-8601 strings.
 */
@Component
class JobDataJson {

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            // Text fields only accept JSON strings (not true, 123 or 1.5).
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail))
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    byte[] write(JobDataV1 data) {
        return mapper.writeValueAsBytes(data);
    }

    /** Reads at most {@code maxBytes}; anything larger, malformed or mistyped is a client error. */
    JobDataV1 read(InputStream body, long maxBytes) {
        byte[] bytes = readBounded(body, maxBytes);
        if (bytes.length == 0) {
            throw new InvalidRequestException("The import file is empty");
        }
        try {
            JobDataV1 data = mapper.readValue(bytes, JobDataV1.class);
            if (data == null) {
                throw new InvalidRequestException("The import file does not contain a job data export");
            }
            return data;
        } catch (JacksonException e) {
            // Only the location and Jackson's short reason — never the input text itself.
            String path = e.getPath().stream()
                    .map(ref -> ref.getPropertyName() != null ? ref.getPropertyName() : "[" + ref.getIndex() + "]")
                    .collect(Collectors.joining(".")).replace(".[", "[");
            throw new InvalidRequestException(path.isEmpty()
                    ? "The import file is not valid JSON for a job data export"
                    : "The import file has an invalid or unexpected value at '" + path + "'");
        }
    }

    private static byte[] readBounded(InputStream body, long maxBytes) {
        try {
            byte[] bytes = body.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
            if (bytes.length > maxBytes) {
                throw new ImportTooLargeException(maxBytes);
            }
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the import file", e);
        }
    }
}
