-- No H2 uma coluna JSON armazena um parametro VARCHAR como string JSON (duplamente codificada),
-- diferente do MySQL. Para o OutboxRelay ler o mesmo JSON que o MySQL devolveria, a coluna
-- vira VARCHAR apenas no banco em memoria dos testes (executado apos o DDL do Hibernate).
ALTER TABLE outbox ALTER COLUMN content VARCHAR(4000);
