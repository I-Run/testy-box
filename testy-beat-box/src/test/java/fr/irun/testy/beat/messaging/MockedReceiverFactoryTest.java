package fr.irun.testy.beat.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Delivery;
import fr.irun.testy.beat.extensions.WithRabbitMock;
import fr.irun.testy.beat.messaging.receivers.MockedReceiver;
import fr.irun.testy.beat.messaging.receivers.MockedReceiverFactory;
import fr.irun.testy.beat.messaging.receivers.MockedResponse;
import fr.irun.testy.beat.utils.samples.TestModel;
import fr.irun.testy.core.extensions.ChainedExtension;
import fr.irun.testy.core.extensions.WithObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.RabbitFlux;
import reactor.rabbitmq.RpcClient;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;


class MockedReceiverFactoryTest {

    private static final String QUEUE = "test-queue";
    private static final String EXCHANGE = "test-exchange";
    private static final Duration BLOCK_TIMEOUT = Duration.ofMillis(500);

    private static final WithObjectMapper wObjectMapper = WithObjectMapper.builder()
            .addModule(new ParameterNamesModule())
            .build();
    private static final WithRabbitMock wRabbitMock = WithRabbitMock.builder()
            .withObjectMapper(wObjectMapper)
            .declareQueueAndExchange(QUEUE, EXCHANGE)
            .build();

    @RegisterExtension
    static final ChainedExtension chain = ChainedExtension.outer(wObjectMapper)
            .append(wRabbitMock)
            .register();

    private static Sender sender;

    @BeforeAll
    static void beforeAll(SenderOptions senderOptions) {
        sender = RabbitFlux.createSender(senderOptions);
    }

    @AfterAll
    static void afterAll() {
        sender.close();
    }

    private RpcClient rpcClient;
    private ObjectMapper objectMapper;

    private MockedReceiverFactory tested;

    @BeforeEach
    void setUp(Channel channel, ObjectMapper objectMapper) {
        this.rpcClient = new RpcClient(Mono.just(channel), EXCHANGE, "", () -> UUID.randomUUID().toString());
        this.objectMapper = objectMapper;

        tested = new MockedReceiverFactory(channel);
    }

    @Test
    void should_store_messages_sent_to_queue() {
        final int nbRequests = 5;

        final String[] messages = IntStream.range(0, nbRequests)
                .mapToObj(i -> "test-message-" + i)
                .toArray(String[]::new);

        final MockedReceiver receiver = tested.consume(nbRequests).on(QUEUE).start();

        Stream.of(messages)
                .map(s -> new OutboundMessage(EXCHANGE, "", s.getBytes(StandardCharsets.UTF_8)))
                .forEach(m -> sender.send(Mono.just(m)).block());

        final List<String> actualReceived = receiver.getReceivedMessages()
                .map(d -> new String(d.getBody(), StandardCharsets.UTF_8))
                .collectList()
                .block(BLOCK_TIMEOUT);
        assertThat(actualReceived).containsExactly(messages);
    }

    @Test
    void should_respond_to_sent_message() throws IOException {
        final String request = TestModel.OBIWAN.login;
        final TestModel response = TestModel.OBIWAN;
        final String responseHeaderKey = "status";
        final int responseHeaderValue = 200;

        final MockedReceiver receiver = tested.consumeOne().on(QUEUE)
                .thenRespond(MockedResponse.builder()
                        .body(objectMapper.writeValueAsBytes(response))
                        .header(responseHeaderKey, responseHeaderValue)
                        .build())
                .start();

        final Delivery actualResponse = rpcClient.rpc(Mono.just(new RpcClient.RpcRequest(objectMapper.writeValueAsBytes(request))))
                .block(BLOCK_TIMEOUT);
        assertThat(actualResponse).isNotNull();
        assertThat(actualResponse.getBody()).isNotEmpty();
        assertThat(objectMapper.readValue(actualResponse.getBody(), TestModel.class)).isEqualTo(response);
        assertThat(actualResponse.getProperties()).isNotNull();
        final Map<String, Object> actualHeaders = actualResponse.getProperties().getHeaders();
        assertThat(actualHeaders).isNotNull();
        assertThat(actualHeaders.get(responseHeaderKey)).isEqualTo(responseHeaderValue);

        final Delivery actualRequest = receiver.getReceivedMessages()
                .single()
                .block(BLOCK_TIMEOUT);
        assertThat(actualRequest).isNotNull();
        assertThat(objectMapper.readValue(actualRequest.getBody(), String.class)).isEqualTo(request);
    }

}