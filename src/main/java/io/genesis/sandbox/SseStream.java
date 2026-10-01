package io.genesis.sandbox;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Incremental SSE decoder. Use with try-with-resources. */
public final class SseStream implements AutoCloseable {
    private final BufferedReader reader;

    SseStream(InputStream input) {
        this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    }

    public Optional<SseEvent> next() throws IOException {
        String id = "";
        String event = "message";
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (data.length() == 0) continue;
                return Optional.of(new SseEvent(id, event, data.toString()));
            }
            if (line.startsWith("id:")) {
                id = line.substring(3).trim();
            } else if (line.startsWith("event:")) {
                event = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                String value = line.substring(5);
                data.append(value.startsWith(" ") ? value.substring(1) : value);
            }
        }
        if (data.length() > 0) {
            return Optional.of(new SseEvent(id, event, data.toString()));
        }
        return Optional.empty();
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
