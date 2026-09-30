package br.com.fullcycle.infrastructure.gateways;

import br.com.fullcycle.IntegrationTest;
import br.com.fullcycle.application.event.CancelEventUseCase;
import br.com.fullcycle.application.event.SubscribeCustomerToEventUseCase;
import br.com.fullcycle.domain.customer.Customer;
import br.com.fullcycle.domain.customer.CustomerId;
import br.com.fullcycle.domain.customer.CustomerRepository;
import br.com.fullcycle.domain.event.Event;
import br.com.fullcycle.domain.event.EventCancelled;
import br.com.fullcycle.domain.event.EventId;
import br.com.fullcycle.domain.event.EventRepository;
import br.com.fullcycle.domain.event.ticket.Ticket;
import br.com.fullcycle.domain.event.ticket.TicketRepository;
import br.com.fullcycle.domain.event.ticket.TicketStatus;
import br.com.fullcycle.domain.partner.Partner;
import br.com.fullcycle.domain.partner.PartnerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.function.BooleanSupplier;

// Prova a cascata assíncrona: EventCancelled -> fila (ConsumerQueueGateway) -> CancelEventTicketsUseCase
class EventCancelledFlowIT extends IntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private ConsumerQueueGateway consumerQueueGateway;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private CancelEventUseCase cancelEventUseCase;

    @Autowired
    private SubscribeCustomerToEventUseCase subscribeCustomerToEventUseCase;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @BeforeEach
    void setUp() {
        ticketRepository.deleteAll();
        eventRepository.deleteAll();
        partnerRepository.deleteAll();
        customerRepository.deleteAll();
    }

    @Test
    @DisplayName("Ao publicar EventCancelled na fila, todos os tickets do evento devem terminar CANCELLED (idempotente)")
    public void testPublishEventCancelledCancelsTickets() throws Exception {
        // given
        final var anEventId = EventId.unique();
        final var otherEventId = EventId.unique();

        ticketRepository.create(Ticket.newTicket(CustomerId.unique(), anEventId));
        ticketRepository.create(Ticket.newTicket(CustomerId.unique(), anEventId));
        final var otherTicket = ticketRepository.create(Ticket.newTicket(CustomerId.unique(), otherEventId));

        final var content = mapper.writeValueAsString(new EventCancelled(anEventId));

        // when
        consumerQueueGateway.publish(content);

        // then
        waitUntil(() -> allTicketsCancelled(anEventId));
        Assertions.assertEquals(2, ticketRepository.ticketsByEventId(anEventId).size());
        Assertions.assertEquals(TicketStatus.PENDING, ticketRepository.ticketOfId(otherTicket.ticketId()).get().status());

        // when: reprocessar a mesma mensagem
        Assertions.assertDoesNotThrow(() -> consumerQueueGateway.publish(content));

        // then
        waitUntil(() -> allTicketsCancelled(anEventId));
        Assertions.assertEquals(TicketStatus.PENDING, ticketRepository.ticketOfId(otherTicket.ticketId()).get().status());
    }

    @Test
    @DisplayName("Fluxo completo: cancelar evento grava EventCancelled na outbox e o relay cancela os tickets via fila")
    public void testCancelEventThroughOutboxAndRelay() throws Exception {
        // given: evento com tickets criados pelo fluxo assíncrono já existente (EventTicketReserved)
        final var aPartner = partnerRepository.create(Partner.newPartner("Disney", "41.536.538/0001-00", "disney@gmail.com"));
        final var anEvent = eventRepository.create(Event.newEvent("Disney on Ice", "2021-01-01", 10, aPartner));
        final var john = customerRepository.create(Customer.newCustomer("John Doe", "123.456.789-01", "john@gmail.com"));
        final var mary = customerRepository.create(Customer.newCustomer("Mary Doe", "123.456.789-02", "mary@gmail.com"));

        final var eventId = anEvent.eventId();

        subscribeCustomerToEventUseCase.execute(new SubscribeCustomerToEventUseCase.Input(john.customerId().value(), eventId.value()));
        subscribeCustomerToEventUseCase.execute(new SubscribeCustomerToEventUseCase.Input(mary.customerId().value(), eventId.value()));

        waitUntil(() -> ticketRepository.ticketsByEventId(eventId).size() == 2);
        Assertions.assertTrue(ticketRepository.ticketsByEventId(eventId).stream().noneMatch(Ticket::isCancelled));

        // when: o comando só cancela o evento; os tickets não são tocados de forma síncrona
        cancelEventUseCase.execute(new CancelEventUseCase.Input(eventId.value()));

        // then: o relay publica EventCancelled da outbox na fila e a cascata cancela os tickets
        waitUntil(() -> allTicketsCancelled(eventId));

        final var actualTickets = ticketRepository.ticketsByEventId(eventId);
        Assertions.assertEquals(2, actualTickets.size());
        actualTickets.forEach(it -> Assertions.assertEquals(TicketStatus.CANCELLED, it.status()));
    }

    private boolean allTicketsCancelled(final EventId anEventId) {
        final var tickets = ticketRepository.ticketsByEventId(anEventId);
        return !tickets.isEmpty() && tickets.stream().allMatch(Ticket::isCancelled);
    }

    private static void waitUntil(final BooleanSupplier condition) throws InterruptedException {
        final var deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        Assertions.fail("Condition not met within " + TIMEOUT);
    }
}
