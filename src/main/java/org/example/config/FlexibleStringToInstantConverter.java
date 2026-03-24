package org.example.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;

/** MongoDB may store instants as strings with offset {@code +0000} (no colon); Spring's default {@code String}→{@link Instant} converter rejects that. */
@ReadingConverter
public class FlexibleStringToInstantConverter implements Converter<String, Instant> {

    private static final DateTimeFormatter OFFSET_WITHOUT_COLON = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .appendPattern("XX")
            .toFormatter();

    @Override
    public Instant convert(@Nullable String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(source);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(source, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        return OffsetDateTime.parse(source, OFFSET_WITHOUT_COLON).toInstant();
    }
}
