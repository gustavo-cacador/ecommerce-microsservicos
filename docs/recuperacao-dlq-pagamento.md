# Recuperação manual da DLQ do pagamento

Este procedimento recupera um `StockReservedEvent` de `payment.stock.reserved.dlq` pelo painel do RabbitMQ. A cópia original permanece na DLQ até confirmar o resultado do pagamento. O reenvio preserva o payload e o `message_id` e vai somente para `payment.stock.reserved.queue`.

## 1. Preparar um pedido recuperável sem reiniciar durante o teste

Antes de iniciar o pagamento, configure temporariamente seu `application.yml`:

```yaml
payment:
  gateway:
    simulate-timeout: false
    failures-before-response: 3
```

Inicie os três serviços e o RabbitMQ. Crie um pedido novo pelo Postman em `POST http://localhost:8082/orders`, com um produto ativo e estoque suficiente:

```json
{
  "clientId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
  "items": [
    {
      "productId": "11111111-1111-1111-1111-111111111111",
      "quantity": 2
    }
  ]
}
```

Com o preço demonstrativo de R$ 249,90, o total será R$ 499,80. Guarde o `id` retornado.

As três tentativas falham e a mensagem chega à DLQ. O gateway já contabilizou as três falhas desse pedido; a próxima chamada poderá responder normalmente. Mantenha os serviços ligados a partir daqui: os bancos H2 e os contadores da simulação estão em memória.

## 2. Inspecionar e validar a mensagem

Abra `http://localhost:15672`, com `guest` / `guest`, e acesse **Queues and Streams → payment.stock.reserved.dlq → Get messages**.

- Ack Mode: **Nack message requeue true**.
- Encoding: **Auto string / base64**.
- Messages: **1**.

Clique em **Get Message(s)**. Esse modo devolve a mensagem à mesma DLQ; sozinho, ele não tenta processar o pagamento novamente. Copie o JSON completo do payload e seu `eventId`. Confirme que o `message_id` corresponde ao `eventId` e que o `orderId` é o pedido que deseja recuperar.

Se a primeira mensagem for de outro pedido, não a exclua para chegar à nova. Inspecione um lote pequeno mantendo **Nack message requeue true** e selecione o payload pelo `orderId`. Este procedimento não remove seletivamente mensagens por ID.

Confira o pedido em `GET http://localhost:8082/orders/{orderId}`. No H2 do pagamento (`http://localhost:8083/h2-console`, JDBC `jdbc:h2:mem:pagamento`, usuário `sa`, senha vazia), execute:

```sql
SELECT id, order_id, reservation_id, status, amount, currency
FROM payments
WHERE order_id = 'ID_DO_PEDIDO';
```

Espere um pagamento `PENDING`, com a mesma reserva, valor e moeda do evento. Guarde também o ID do pagamento para comparar depois. No H2 do estoque (`http://localhost:8081/h2-console`, JDBC `jdbc:h2:mem:estoque`), execute:

```sql
SELECT id, order_id, status
FROM stock_reservations
WHERE order_id = 'ID_DO_PEDIDO';
```

A reserva deve estar `RESERVED` e seu ID deve corresponder ao `reservationId` do evento. Para este cenário, o pedido deve estar `WAITING_PAYMENT`.

Se houve reinício e algum desses registros desapareceu, não reprocesse a mensagem antiga. Recomece com um pedido novo e preserve a mensagem antiga para análise. JSON inválido ou dados divergentes também precisam ser investigados antes do reenvio. Não invente IDs nem altere valor, moeda ou reserva para forçar uma aprovação.

## 3. Reenviar exclusivamente ao pagamento

Abra **Queues and Streams → payment.stock.reserved.queue → Publish message**.

- Delivery mode: **2 - Persistent**, quando apresentado como seletor.
- Properties: `content_type` = `application/json`, `content_encoding` = `UTF-8` e `message_id` = UUID original do `eventId`.
- Payload encoding: **string**.
- Payload: o JSON integral copiado da DLQ, sem aspas adicionais em volta dele.
- Headers: deixe vazio; os headers `x-death`, `x-first-death-*`, `x-last-death-*` e de correlação pertencem à entrega anterior.

Clique em **Publish message** e confira a confirmação de roteamento. Se a interface apresentar `delivery_mode` dentro de Properties, use o valor numérico `2` nesse campo.

A publicação pela página dessa fila usa a exchange padrão, de nome vazio, com routing key `payment.stock.reserved.queue`. Não use a exchange fanout `stock.reserved` para recuperar o pagamento: ela também entrega cópias aos demais assinantes, como o serviço de pedido.

Uma confirmação de publicação não comprova que o pagamento terminou. Mantenha a mensagem original na DLQ e prossiga com as consultas.

## 4. Confirmar a recuperação e a idempotência

Neste teste, o console mostrará uma nova ocorrência de `Processando pagamento` e depois `Pagamento processado`. Consulte no H2 do pagamento:

```sql
SELECT id, order_id, status, amount
FROM payments
WHERE order_id = 'ID_DO_PEDIDO';

SELECT event_id, exchange_name, published_at
FROM outbox_events
WHERE order_id = 'ID_DO_PEDIDO';
```

Para R$ 499,80, espere o mesmo registro de pagamento em `APPROVED`, um evento `payment.approved`, e `published_at` preenchido após o relay publicar. Aguarde também `PAID` no pedido e `CONFIRMED` na reserva. Para valores acima de R$ 500,00, a recuperação termina em `REFUSED`, evento `payment.refused`, pedido `CANCELED` e reserva `RELEASED`.

Opcionalmente, publique uma segunda vez o mesmo payload na fila de pagamento. O ID do pagamento e a quantidade de eventos na outbox devem permanecer iguais. O serviço reconhece o resultado final e não chama novamente o gateway para esse pedido.

Se houver novo timeout, o pagamento permanece `PENDING` e a reserva continua aberta. O reenvio poderá gerar outra cópia na DLQ; não faça um ciclo automático de republicação. Corrija a causa antes de outra tentativa. `simulate-timeout: true` continua falhando permanentemente.

## 5. Remover a cópia já recuperada

Depois de confirmar a conclusão nos três serviços, a cópia original ainda estará na DLQ por escolha deste procedimento.

Para o teste com **uma única mensagem na DLQ**, confira novamente seu `eventId` usando **Nack message requeue true**. Com essa mensagem identificada e sem outra operação de recuperação em andamento, selecione **Automatic ack**, Messages **1**, e clique em **Get Message(s)**. Essa operação retira a próxima mensagem da fila. Confirme que o payload retirado é o mesmo e que a DLQ ficou vazia.

Se houver mensagens de outros pedidos, mantenha as cópias até realizar uma limpeza seletiva com uma ferramenta operacional apropriada. O painel não oferece remoção por `eventId`: **Automatic ack** remove a próxima mensagem, e **Purge** apagaria todas. Não use essas ações para tentar selecionar uma mensagem no meio da fila.

Ao terminar os testes, volte `failures-before-response` para `0` e mantenha `simulate-timeout: false` antes do commit.

## Validação automatizada

`PaymentListenerRabbitTests` usa um broker isolado para verificar a leitura real da DLQ com requeue, preservação do payload e `message_id`, reenvio somente à fila de pagamento, manutenção do mesmo pagamento, ausência de eventos duplicados e retirada da cópia original após o resultado persistido. Um assinante auxiliar verifica que a recuperação não foi publicada novamente na fanout `stock.reserved`.

O teste é habilitado por `RABBITMQ_TEST_PORT` e deve usar um RabbitMQ separado do ambiente manual. Não use a porta do broker com seus pedidos de teste.

Referências: [painel de gerenciamento do RabbitMQ](https://www.rabbitmq.com/docs/3.13/management), [opções de leitura no painel 3.13.7](https://github.com/rabbitmq/rabbitmq-server/blob/v3.13.7/deps/rabbitmq_management/priv/www/js/tmpl/queue.ejs), [requeue com basic.nack](https://www.rabbitmq.com/docs/3.13/nack).
