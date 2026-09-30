package br.com.fullcycle.application.usecases;

import br.com.fullcycle.IntegrationTest;
import br.com.fullcycle.application.event.CancelEventUseCase;
import br.com.fullcycle.domain.event.Event;
import br.com.fullcycle.domain.event.EventId;
import br.com.fullcycle.domain.event.EventRepository;
import br.com.fullcycle.domain.event.EventStatus;
import br.com.fullcycle.domain.exceptions.ValidationException;
import br.com.fullcycle.domain.partner.Partner;
import br.com.fullcycle.domain.partner.PartnerRepository;
import br.com.fullcycle.infrastructure.jpa.repositories.OutboxJpaRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.stream.StreamSupport;

class CancelEventUseCaseIT extends IntegrationTest {

    @Autowired
    private CancelEventUseCase useCase;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private OutboxJpaRepository outboxJpaRepository;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll();
        partnerRepository.deleteAll();
    }

    @Test
    @DisplayName("Deve cancelar um evento persistindo o estado e gravando EventCancelled na outbox")
    public void testCancel() throws Exception {
        // given
        final var anEvent = createEvent();
        final var expectedId = anEvent.eventId().value();
        final var expectedStatus = "CANCELLED";

        // when
        final var output = useCase.execute(new CancelEventUseCase.Input(expectedId));

        // then
        Assertions.assertEquals(expectedId, output.id());
        Assertions.assertEquals(expectedStatus, output.status());

        final var actualEvent = eventRepository.eventOfId(anEvent.eventId()).get();
        Assertions.assertEquals(EventStatus.CANCELLED, actualEvent.status());

        final var hasOutboxMessage = StreamSupport.stream(outboxJpaRepository.findAll().spliterator(), false)
                .anyMatch(it -> it.content().contains("event.cancelled") && it.content().contains(expectedId));
        Assertions.assertTrue(hasOutboxMessage);
    }

    @Test
    @DisplayName("Não deve cancelar um evento que não existe")
    public void testCancelWithoutEvent() throws Exception {
        // given
        final var expectedError = "Event not found";
        final var input = new CancelEventUseCase.Input(EventId.unique().value());

        // when
        final var actualException = Assertions.assertThrows(ValidationException.class, () -> useCase.execute(input));

        // then
        Assertions.assertEquals(expectedError, actualException.getMessage());
    }

    @Test
    @DisplayName("Não deve cancelar um evento já cancelado")
    public void testCancelTwice() throws Exception {
        // given
        final var expectedError = "Event already cancelled";
        final var anEvent = createEvent();
        final var input = new CancelEventUseCase.Input(anEvent.eventId().value());

        useCase.execute(input);

        // when
        final var actualException = Assertions.assertThrows(ValidationException.class, () -> useCase.execute(input));

        // then
        Assertions.assertEquals(expectedError, actualException.getMessage());
    }

    private Event createEvent() {
        final var aPartner = partnerRepository.create(Partner.newPartner("John Doe", "41.536.538/0001-00", "john.doe@gmail.com"));
        return eventRepository.create(Event.newEvent("Disney on Ice", "2021-01-01", 10, aPartner));
    }
}
