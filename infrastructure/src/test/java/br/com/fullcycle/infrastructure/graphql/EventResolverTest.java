package br.com.fullcycle.infrastructure.graphql;

import br.com.fullcycle.domain.event.EventId;
import br.com.fullcycle.domain.event.EventRepository;
import br.com.fullcycle.domain.partner.Partner;
import br.com.fullcycle.domain.partner.PartnerRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@AutoConfigureHttpGraphQlTester
@SpringBootTest
class EventResolverTest {

    @Autowired
    private HttpGraphQlTester graphQlTester;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private PartnerRepository partnerRepository;

    private Partner disney;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll();
        partnerRepository.deleteAll();

        disney = partnerRepository.create(Partner.newPartner("Disney", "45.123.123/0001-12", "disney@gmail.com"));
    }

    @Test
    @DisplayName("Deve cancelar um evento e consultar seu estado via GraphQL")
    public void testCancelAndQueryEvent() {
        final var eventId = createEvent();

        graphQlTester.document("""
                        query($id: ID!) { eventOfId(id: $id) { id name date totalSpots status } }
                        """)
                .variable("id", eventId)
                .execute()
                .path("eventOfId.id").entity(String.class).isEqualTo(eventId)
                .path("eventOfId.name").entity(String.class).isEqualTo("Disney on Ice")
                .path("eventOfId.status").entity(String.class).isEqualTo("ACTIVE");

        graphQlTester.document("""
                        mutation($id: ID!) { cancelEvent(id: $id) { id status } }
                        """)
                .variable("id", eventId)
                .execute()
                .path("cancelEvent.id").entity(String.class).isEqualTo(eventId)
                .path("cancelEvent.status").entity(String.class).isEqualTo("CANCELLED");

        graphQlTester.document("""
                        query($id: ID!) { eventOfId(id: $id) { id status } }
                        """)
                .variable("id", eventId)
                .execute()
                .path("eventOfId.status").entity(String.class).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("Deve retornar null ao consultar um evento inexistente via GraphQL")
    public void testQueryEventNotFound() {
        graphQlTester.document("""
                        query($id: ID!) { eventOfId(id: $id) { id status } }
                        """)
                .variable("id", EventId.unique().value())
                .execute()
                .path("eventOfId").valueIsNull();
    }

    @Test
    @DisplayName("Não deve cancelar duas vezes o mesmo evento via GraphQL")
    public void testCancelTwice() {
        final var eventId = createEvent();
        final var cancel = """
                mutation($id: ID!) { cancelEvent(id: $id) { id status } }
                """;

        graphQlTester.document(cancel).variable("id", eventId).execute()
                .path("cancelEvent.status").entity(String.class).isEqualTo("CANCELLED");

        graphQlTester.document(cancel).variable("id", eventId).execute()
                .errors()
                .satisfy(errors -> Assertions.assertFalse(errors.isEmpty()));
    }

    private String createEvent() {
        return graphQlTester.document("""
                        mutation($partnerId: ID) {
                            createEvent(input: { name: "Disney on Ice", date: "2021-01-01", totalSpots: 100, partnerId: $partnerId }) {
                                id name date totalSpots status
                            }
                        }
                        """)
                .variable("partnerId", disney.partnerId().value())
                .execute()
                .path("createEvent.name").entity(String.class).isEqualTo("Disney on Ice")
                .path("createEvent.id").entity(String.class).get();
    }
}
