package br.com.fullcycle.domain.event;

import br.com.fullcycle.domain.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record EventCancelled(
        String domainEventId,
        String type,
        String eventId,
        Instant occurredOn
) implements DomainEvent {

    public static final String TYPE = "event.cancelled";

    public EventCancelled(EventId eventId) {
        this(UUID.randomUUID().toString(), TYPE, eventId.value(), Instant.now());
    }
}
