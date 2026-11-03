package dev.erezalkalay.jobhunter.application.dto;

import java.time.Instant;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

/**
 * Reads an {@code Optional<Instant>} body property so that "omitted" and "null"
 * differ (Jackson would otherwise turn both into an empty Optional): omitted
 * stays Java {@code null}, JSON {@code null} becomes {@code Optional.empty()},
 * a value becomes {@code Optional.of(value)}. The value itself is read by the
 * mapper's normal Instant handling, so parsing rules are unchanged.
 */
public class OmittableInstantDeserializer extends StdDeserializer<Optional<Instant>> {

    public OmittableInstantDeserializer() {
        super(Optional.class);
    }

    @Override
    public Optional<Instant> deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
        return Optional.of(context.readValue(parser, Instant.class));
    }

    @Override
    public Object getNullValue(DeserializationContext context) {
        return Optional.empty();
    }

    @Override
    public Object getAbsentValue(DeserializationContext context) {
        return null;
    }
}
