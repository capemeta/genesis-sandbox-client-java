package io.genesis.sandbox;

/** One Server-Sent Event from a Job or Session exec log stream. */
public final class SseEvent {
    private final String id;
    private final String event;
    private final String data;

    SseEvent(String id, String event, String data) {
        this.id = id == null ? "" : id;
        this.event = event == null || event.isEmpty() ? "message" : event;
        this.data = data == null ? "" : data;
    }

    public String id() { return id; }
    public String event() { return event; }
    public String data() { return data; }
}
