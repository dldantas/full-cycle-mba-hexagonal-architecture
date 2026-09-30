# MBA Hexagonal Architecture: cancelamento de evento

Fork de [devfullcycle/MBA-hexagonal-architecture](https://github.com/devfullcycle/MBA-hexagonal-architecture), a partir da branch `clean-arch`. Este fork adiciona a feature de **cancelamento de evento**: o comando síncrono, a cascata assíncrona que cancela os ingressos, a consulta de evento por id com presenter e a exposição via REST e GraphQL.

## Como a cascata de cancelamento funciona

O cancelamento acontece em `Event.cancel()`. O método muda o estado do agregado para `CANCELLED` e registra o evento de domínio **`EventCancelled`** (`type = "event.cancelled"`). O `CancelEventUseCase` só persiste o evento e não conhece ingressos. O `EventDatabaseRepository` grava os eventos de domínio do agregado na tabela **`outbox`**, na mesma transação. O **`OutboxRelay`** publica a mensagem no **`QueueGateway`**, e o **`ConsumerQueueGateway`** roteia o tipo `event.cancelled` para o **`CancelEventTicketsUseCase`**. Esse caso de uso busca os ingressos do evento por `TicketRepository.ticketsByEventId` e chama `Ticket.cancel()` em cada um, o que é idempotente. É o mesmo caminho do fluxo `EventTicketReserved → CreateTicketForCustomerUseCase` já existente. Assim, o agregado `Event` nunca toca o agregado `Ticket`.

```
POST /events/{id}/cancel
  └─ CancelEventUseCase ─ event.cancel() ─ registra EventCancelled
       └─ EventDatabaseRepository.update ─ grava na outbox
            └─ OutboxRelay (a cada 2s) ─ QueueGateway.publish
                 └─ ConsumerQueueGateway ("event.cancelled")
                      └─ CancelEventTicketsUseCase ─ ticket.cancel() em cada ingresso
```

## Como subir o projeto

Pré-requisitos: **JDK 17** e Docker. O build fixa o Java 17 por *toolchain* (`buildSrc/src/main/kotlin/java-conventions.gradle.kts`), então compilação, testes e `bootRun` usam o JDK 17 instalado mesmo que o `java` padrão da máquina seja outro. Para isso o wrapper foi atualizado para Gradle 8.10.2 e o JaCoCo para 0.8.12.

```bash
docker compose up -d            # MySQL 8 em localhost:3306
./gradlew :infrastructure:bootRun
```

A aplicação lê `infrastructure/src/main/resources/application.properties`, que aponta para `jdbc:mysql://localhost:3306/events` com usuário e senha `root`. Crie o schema `events` se ele não existir. O GraphiQL fica em `http://localhost:8080/graphiql`.

## Como rodar os testes

```bash
./gradlew test
```

Os testes de `infrastructure` usam o perfil `test`, com H2 em memória no modo MySQL. Não é preciso subir o MySQL para rodá-los.

| Camada | Testes da feature |
|---|---|
| Domínio | `EventTest` (cancel, `EventCancelled` em `allDomainEvents()`, cancelar duas vezes, reservar em evento cancelado), `TicketTest` (cancel idempotente) |
| Caso de uso (in-memory) | `CancelEventUseCaseTest`, `GetEventByIdUseCaseTest`, `CancelEventTicketsUseCaseTest`, `SubscribeCustomerToEventUseCaseTest` |
| Integração (H2) | `CancelEventUseCaseIT`, `TicketDatabaseRepositoryIT` (busca por evento) |
| Fluxo assíncrono ponta a ponta | `EventCancelledFlowIT`: publica o JSON de `EventCancelled` no `ConsumerQueueGateway` e também percorre o fluxo completo (inscrição → outbox → relay → ingressos criados → cancelamento → outbox → relay → ingressos `CANCELLED`) |
| Drivers | `EventControllerTest` (REST), `EventResolverTest` (GraphQL) |

> Observação sobre o H2: no H2, uma coluna `JSON` grava um parâmetro VARCHAR como *string* JSON, ou seja, duplamente codificada. O MySQL não faz isso. Por isso o `OutboxRelay` não conseguia desserializar nenhuma mensagem nos testes. O arquivo `infrastructure/src/test/resources/h2-outbox.sql` altera `outbox.content` para VARCHAR somente no banco em memória dos testes. O script é carregado pelo `application-test.properties`, e o código de produção não muda.

## Contratos

### REST

| Método | Rota | Resposta |
|---|---|---|
| `POST` | `/events/{id}/cancel` | `200 {"id", "status": "CANCELLED"}`, ou `422` com `Event not found` / `Event already cancelled` |
| `GET` | `/events/{id}` | `200 {"id", "name", "date", "totalSpots", "partnerId", "status"}` (presenter `GetEventByIdResponseEntity`) |
| `GET` | `/events/{id}` com `X-Public: true` | `200 {"id", "status"}` (presenter `PublicGetEventByIdResponseEntity`) |
| `GET` | `/events/{id}` inexistente | `404` sem corpo, nos dois presenters |
| `POST` | `/events/{id}/subscribe` em evento cancelado | `422 Event is cancelled` |

### GraphQL

```graphql
mutation { cancelEvent(id: "<id>") { id status } }
query    { eventOfId(id: "<id>") { id name date totalSpots status } }
```

`status` é opcional no `type Event`. Assim, `createEvent` continua válido e devolve `status: null`.
