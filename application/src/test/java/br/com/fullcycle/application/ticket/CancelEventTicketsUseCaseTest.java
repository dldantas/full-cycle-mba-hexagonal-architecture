package br.com.fullcycle.application.ticket;

import br.com.fullcycle.application.repository.InMemoryTicketRepository;
import br.com.fullcycle.domain.customer.CustomerId;
import br.com.fullcycle.domain.event.EventId;
import br.com.fullcycle.domain.event.ticket.Ticket;
import br.com.fullcycle.domain.event.ticket.TicketStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CancelEventTicketsUseCaseTest {

    @Test
    @DisplayName("Deve cancelar todos os tickets de um evento")
    public void testCancelEventTickets() {
        // given
        final var anEventId = EventId.unique();
        final var otherEventId = EventId.unique();

        final var ticketRepository = new InMemoryTicketRepository();
        final var ticket1 = ticketRepository.create(Ticket.newTicket(CustomerId.unique(), anEventId));
        final var ticket2 = ticketRepository.create(Ticket.newTicket(CustomerId.unique(), anEventId));
        final var otherTicket = ticketRepository.create(Ticket.newTicket(CustomerId.unique(), otherEventId));

        final var expectedCancelledTickets = 2;

        final var input = new CancelEventTicketsUseCase.Input(anEventId.value());

        // when
        final var useCase = new CancelEventTicketsUseCase(ticketRepository);
        final var output = useCase.execute(input);

        // then
        Assertions.assertEquals(anEventId.value(), output.eventId());
        Assertions.assertEquals(expectedCancelledTickets, output.cancelledTickets());

        Assertions.assertEquals(TicketStatus.CANCELLED, ticketRepository.ticketOfId(ticket1.ticketId()).get().status());
        Assertions.assertEquals(TicketStatus.CANCELLED, ticketRepository.ticketOfId(ticket2.ticketId()).get().status());
        Assertions.assertEquals(TicketStatus.PENDING, ticketRepository.ticketOfId(otherTicket.ticketId()).get().status());
    }

    @Test
    @DisplayName("Reprocessar o cancelamento dos tickets de um evento deve ser idempotente")
    public void testCancelEventTicketsTwice() {
        // given
        final var anEventId = EventId.unique();

        final var ticketRepository = new InMemoryTicketRepository();
        final var ticket1 = ticketRepository.create(Ticket.newTicket(CustomerId.unique(), anEventId));
        final var ticket2 = ticketRepository.create(Ticket.newTicket(CustomerId.unique(), anEventId));

        final var input = new CancelEventTicketsUseCase.Input(anEventId.value());
        final var useCase = new CancelEventTicketsUseCase(ticketRepository);
        useCase.execute(input);

        // when
        final var output = Assertions.assertDoesNotThrow(() -> useCase.execute(input));

        // then
        Assertions.assertEquals(2, output.cancelledTickets());
        Assertions.assertEquals(TicketStatus.CANCELLED, ticketRepository.ticketOfId(ticket1.ticketId()).get().status());
        Assertions.assertEquals(TicketStatus.CANCELLED, ticketRepository.ticketOfId(ticket2.ticketId()).get().status());
    }

    @Test
    @DisplayName("Não deve falhar ao cancelar tickets de um evento sem tickets")
    public void testCancelEventTicketsWithoutTickets() {
        // given
        final var input = new CancelEventTicketsUseCase.Input(EventId.unique().value());

        // when
        final var useCase = new CancelEventTicketsUseCase(new InMemoryTicketRepository());
        final var output = useCase.execute(input);

        // then
        Assertions.assertEquals(0, output.cancelledTickets());
    }
}
