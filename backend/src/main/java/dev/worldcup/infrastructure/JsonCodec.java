package dev.worldcup.infrastructure;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class JsonCodec {
    private final JsonMapper mapper;
    public JsonCodec(JsonMapper mapper) { this.mapper = mapper; }
    public String write(Object value) { return mapper.writeValueAsString(value); }
    public <T> T read(String json, Class<T> type) { return mapper.readValue(json, type); }
}
