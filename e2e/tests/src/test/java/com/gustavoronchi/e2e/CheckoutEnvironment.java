package com.gustavoronchi.e2e;

import org.h2.tools.Server;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

final class CheckoutEnvironment implements AutoCloseable {

    private static final Pattern HTTP_PORT = Pattern.compile("Tomcat started on port (\\d+)");
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

    private final String password = UUID.randomUUID().toString();
    private final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management")
            .withAdminUser("checkout-e2e")
            .withAdminPassword(password);
    private final List<Process> processes = new ArrayList<>();
    private final Map<String, Connection> databases = new HashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    private final JsonMapper json = new JsonMapper();

    private Server h2;
    private URI stock;
    private URI orders;

    void start() throws IOException, SQLException {
        Path logs = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "checkout-");
        System.out.println("Logs dos serviços em " + logs);
        rabbit.start();
        h2 = Server.createTcpServer("-tcpPort", "0", "-ifExists").start();

        // Com H2 via TCP, o Spring não executa data.sql pelo modo padrão para bancos embutidos.
        stock = startService("estoque", logs, "--spring.sql.init.mode=always");
        startService("pagamento", logs, "--payment.gateway.simulate-timeout=false",
                "--payment.gateway.failures-before-response=0");
        orders = startService("pedido", logs, "--clients.estoque-service.url=" + stock);
    }

    URI stock() {
        return stock;
    }

    URI orders() {
        return orders;
    }

    JsonNode get(URI uri) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri).GET(), 200);
    }

    JsonNode send(HttpRequest.Builder request, int expectedStatus) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request.timeout(HTTP_TIMEOUT).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("HTTP %s: %s", response.uri(), response.body())
                .isEqualTo(expectedStatus);
        return json.readTree(response.body());
    }

    String queryStatus(String service, String sql, UUID orderId) throws SQLException {
        try (var statement = databases.get(service).prepareStatement(sql)) {
            statement.setObject(1, orderId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString("status") : null;
            }
        }
    }

    private URI startService(String name, Path logs, String... properties) throws IOException, SQLException {
        String artifact = "microsservico-" + name;
        Path jar = Path.of("..", "..", artifact, "target", artifact + "-0.0.1-SNAPSHOT.jar").toAbsolutePath();
        assertThat(jar).as("JAR do serviço %s. Execute e2e/run.ps1.", name).isRegularFile();

        // A conexão local mantém o banco em memória enquanto o serviço acessa o mesmo banco via TCP.
        String database = "mem:" + name + "-" + UUID.randomUUID();
        databases.put(name, DriverManager.getConnection("jdbc:h2:" + database, "sa", password));
        Path log = logs.resolve(name + ".log");
        List<String> command = new ArrayList<>(List.of(
                ProcessHandle.current().info().command().orElseThrow(), "-Dfile.encoding=UTF-8", "-jar", jar.toString(),
                "--server.address=127.0.0.1", "--server.port=0", "--spring.output.ansi.enabled=never",
                "--spring.datasource.url=jdbc:h2:tcp://127.0.0.1:" + h2.getPort() + "/" + database,
                "--spring.datasource.username=sa", "--spring.datasource.password=" + password,
                "--spring.rabbitmq.addresses=" + rabbit.getHost() + ":" + rabbit.getAmqpPort(),
                "--spring.rabbitmq.username=" + rabbit.getAdminUsername(),
                "--spring.rabbitmq.password=" + rabbit.getAdminPassword(),
                "--spring.rabbitmq.virtual-host=/"));
        command.addAll(List.of(properties));
        Process process = new ProcessBuilder(command).directory(logs.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        processes.add(process);

        String startup = await("inicialização de " + name + " (" + log + ")")
                .pollInterval(Duration.ofMillis(250)).atMost(Duration.ofSeconds(90))
                .until(() -> {
                    String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
                    if (!process.isAlive()) {
                        throw new IllegalStateException(name + " encerrou com código " + process.exitValue()
                                + "\n" + output);
                    }
                    return output;
                }, output -> output.contains("Started Microsservico"));
        var port = HTTP_PORT.matcher(startup);
        assertThat(port.find()).as("Porta HTTP de %s em %s", name, log).isTrue();
        return URI.create("http://127.0.0.1:" + port.group(1));
    }

    @Override
    public void close() throws SQLException {
        try {
            processes.reversed().forEach(process -> process.destroyForcibly().onExit().join());
            for (Connection database : databases.values()) {
                database.close();
            }
        } finally {
            if (h2 != null) {
                h2.stop();
            }
            rabbit.stop();
            http.close();
        }
    }
}
