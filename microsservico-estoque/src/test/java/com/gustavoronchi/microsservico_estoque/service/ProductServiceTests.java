package com.gustavoronchi.microsservico_estoque.service;

import com.gustavoronchi.microsservico_estoque.domain.entities.Product;
import com.gustavoronchi.microsservico_estoque.domain.repository.ProductRepository;
import com.gustavoronchi.microsservico_estoque.domain.repository.StockReservationRepository;
import com.gustavoronchi.microsservico_estoque.dto.ProductRequestDTO;
import com.gustavoronchi.microsservico_estoque.dto.StockItemRequestDTO;
import com.gustavoronchi.microsservico_estoque.enums.ReservationStatus;
import com.gustavoronchi.microsservico_estoque.exception.StockInconsistencyException;
import com.gustavoronchi.microsservico_estoque.resource.ProductResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DataJpaTest(showSql = false, properties = "spring.sql.init.mode=never")
@Import({ProductService.class, StockService.class, ProductServiceTests.JsonConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductServiceTests {

    @Autowired
    private ProductService service;
    @Autowired
    private StockService stockService;
    @Autowired
    private ProductRepository products;
    @Autowired
    private StockReservationRepository reservations;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JsonMapper jsonMapper;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProductResource(service)).build();
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void updatePreservesReservationWhenQuantityCoversReservedItems(int quantity) throws Exception {
        Product product = product();
        UUID orderId = reserve(product);

        mvc.perform(put("/products/{id}", product.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request(product, quantity))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Produto atualizado"))
                .andExpect(jsonPath("$.quantityAvailable").value(quantity))
                .andExpect(jsonPath("$.quantityReserved").value(2));

        assertStock(product, quantity, 2);
        assertThat(reservations.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void quantityBelowReservedReturnsConflictWithoutChangingAnyProductField() throws Exception {
        Product product = product();
        UUID orderId = reserve(product);
        Product before = products.findById(product.getId()).orElseThrow();

        mvc.perform(put("/products/{id}", product.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request(product, 1))))
                .andExpect(status().isConflict());

        assertThat(products.findById(product.getId()).orElseThrow()).usingRecursiveComparison().isEqualTo(before);
        assertThat(reservations.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1})
    void invalidQuantityReturnsBadRequestWithoutChangingProduct(Integer quantity) throws Exception {
        Product product = product();
        Product before = products.findById(product.getId()).orElseThrow();

        mvc.perform(put("/products/{id}", product.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request(product, quantity))))
                .andExpect(status().isBadRequest());

        assertThat(products.findById(product.getId()).orElseThrow()).usingRecursiveComparison().isEqualTo(before);
    }

    @Test
    void zeroQuantityIsAllowedWhenThereAreNoReservations() throws Exception {
        Product product = product();

        mvc.perform(put("/products/{id}", product.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request(product, 0))))
                .andExpect(status().isOk());

        assertStock(product, 0, 0);
    }

    @Test
    void unknownProductReturnsNotFound() throws Exception {
        mvc.perform(put("/products/{id}", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request(product(), 1))))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateWaitsForConcurrentReservationAndValidatesCommittedQuantity() throws Exception {
        Product product = product();
        UUID firstOrder = reserve(product);
        UUID secondOrder = UUID.randomUUID();
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch commitReservation = new CountDownLatch(1);
        CountDownLatch updateStarted = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var reservation = executor.submit(() -> transactionTemplate.executeWithoutResult(transaction -> {
                    assertThat(stockService.reserve(secondOrder,
                            List.of(new StockItemRequestDTO(product.getId(), 2))).isSuccess()).isTrue();
                    reserved.countDown();
                    try {
                        assertThat(commitReservation.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                }));
                assertThat(reserved.await(5, TimeUnit.SECONDS)).isTrue();
                var update = executor.submit(() -> {
                    updateStarted.countDown();
                    return service.update(product.getId(), request(product, 3));
                });
                assertThat(updateStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> update.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

                commitReservation.countDown();
                reservation.get(5, TimeUnit.SECONDS);
                assertThatThrownBy(() -> update.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(StockInconsistencyException.class);
            } finally {
                commitReservation.countDown();
                executor.shutdownNow();
            }
        }

        assertStock(product, 5, 4);
        assertThat(reservations.findByOrderId(firstOrder).orElseThrow().getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(reservations.findByOrderId(secondOrder).orElseThrow().getStatus()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void deleteHidesProductAndRejectsNewReservationsWithoutRemovingItsData() throws Exception {
        Product product = product();
        Product before = products.findById(product.getId()).orElseThrow();

        mvc.perform(delete("/products/{id}", product.getId())).andExpect(status().isNoContent());

        Product persisted = products.findById(product.getId()).orElseThrow();
        assertThat(persisted.getActive()).isFalse();
        assertThat(persisted).usingRecursiveComparison().ignoringFields("active").isEqualTo(before);
        mvc.perform(get("/products/{id}", product.getId())).andExpect(status().isNotFound());
        assertThat(service.findAll(product.getCategoryId(), PageRequest.of(0, 10)).getContent()).isEmpty();
        mvc.perform(post("/products/prices").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(List.of(product.getId()))))
                .andExpect(status().isNotFound());

        UUID orderId = UUID.randomUUID();
        assertThat(stockService.reserve(orderId, List.of(new StockItemRequestDTO(product.getId(), 2)))
                .isSuccess()).isFalse();
        assertThat(reservations.findByOrderId(orderId)).isEmpty();
        assertStock(product, 5, 0);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"CONFIRMED", "RELEASED"})
    void deleteIsBlockedUntilReservationIsConfirmedOrReleased(ReservationStatus targetStatus) throws Exception {
        Product product = product();
        UUID orderId = reserve(product);
        UUID reservationId = reservations.findByOrderId(orderId).orElseThrow().getId();
        Product before = products.findById(product.getId()).orElseThrow();

        mvc.perform(delete("/products/{id}", product.getId())).andExpect(status().isConflict());
        assertThat(products.findById(product.getId()).orElseThrow()).usingRecursiveComparison().isEqualTo(before);
        mvc.perform(get("/products/{id}", product.getId())).andExpect(status().isOk());
        assertThat(reservations.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(ReservationStatus.RESERVED);

        for (int attempt = 0; attempt < 2; attempt++) {
            if (targetStatus == ReservationStatus.CONFIRMED) {
                stockService.confirm(orderId, reservationId);
            } else {
                stockService.release(orderId, reservationId);
            }
        }

        assertStock(product, targetStatus == ReservationStatus.CONFIRMED ? 3 : 5, 0);
        mvc.perform(delete("/products/{id}", product.getId())).andExpect(status().isNoContent());
        mvc.perform(delete("/products/{id}", product.getId())).andExpect(status().isNoContent());
        assertThat(products.findById(product.getId()).orElseThrow().getActive()).isFalse();
        assertThat(reservations.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(targetStatus);
    }

    @Test
    void deleteUnknownProductReturnsNotFound() throws Exception {
        mvc.perform(delete("/products/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void deleteRemainsBlockedWhileAnotherOrderStillHasReservedUnits() throws Exception {
        Product product = product();
        UUID firstOrder = reserve(product);
        UUID secondOrder = reserve(product);
        stockService.confirm(firstOrder, reservations.findByOrderId(firstOrder).orElseThrow().getId());

        mvc.perform(delete("/products/{id}", product.getId())).andExpect(status().isConflict());
        assertStock(product, 3, 2);
        assertThat(products.findById(product.getId()).orElseThrow().getActive()).isTrue();

        stockService.release(secondOrder, reservations.findByOrderId(secondOrder).orElseThrow().getId());
        mvc.perform(delete("/products/{id}", product.getId())).andExpect(status().isNoContent());
        assertStock(product, 3, 0);
    }

    @Test
    void deleteWaitsForConcurrentReservationAndRejectsCommittedReservedUnits() throws Exception {
        Product product = product();
        UUID orderId = UUID.randomUUID();
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch commitReservation = new CountDownLatch(1);
        CountDownLatch deleteStarted = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var reservation = executor.submit(() -> transactionTemplate.executeWithoutResult(transaction -> {
                    assertThat(stockService.reserve(orderId,
                            List.of(new StockItemRequestDTO(product.getId(), 2))).isSuccess()).isTrue();
                    reserved.countDown();
                    try {
                        assertThat(commitReservation.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                }));
                assertThat(reserved.await(5, TimeUnit.SECONDS)).isTrue();
                var deletion = executor.submit(() -> {
                    deleteStarted.countDown();
                    service.delete(product.getId());
                });
                assertThat(deleteStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> deletion.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

                commitReservation.countDown();
                reservation.get(5, TimeUnit.SECONDS);
                assertThatThrownBy(() -> deletion.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(StockInconsistencyException.class);
            } finally {
                commitReservation.countDown();
                executor.shutdownNow();
            }
        }

        assertThat(products.findById(product.getId()).orElseThrow().getActive()).isTrue();
        assertStock(product, 5, 2);
    }

    @Test
    void reservationWaitsForDeactivationAndRejectsInactiveProduct() throws Exception {
        Product product = product();
        UUID orderId = UUID.randomUUID();
        CountDownLatch deactivated = new CountDownLatch(1);
        CountDownLatch commitDeactivation = new CountDownLatch(1);
        CountDownLatch reservationStarted = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var deletion = executor.submit(() -> transactionTemplate.executeWithoutResult(transaction -> {
                    service.delete(product.getId());
                    deactivated.countDown();
                    try {
                        assertThat(commitDeactivation.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                }));
                assertThat(deactivated.await(5, TimeUnit.SECONDS)).isTrue();
                var reservation = executor.submit(() -> {
                    reservationStarted.countDown();
                    return stockService.reserve(orderId, List.of(new StockItemRequestDTO(product.getId(), 2)));
                });
                assertThat(reservationStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> reservation.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

                commitDeactivation.countDown();
                deletion.get(5, TimeUnit.SECONDS);
                assertThat(reservation.get(5, TimeUnit.SECONDS).isSuccess()).isFalse();
            } finally {
                commitDeactivation.countDown();
                executor.shutdownNow();
            }
        }

        assertThat(products.findById(product.getId()).orElseThrow().getActive()).isFalse();
        assertThat(reservations.findByOrderId(orderId)).isEmpty();
        assertStock(product, 5, 0);
    }

    private Product product() {
        return products.saveAndFlush(new Product(null, "Produto de teste", null,
                BigDecimal.TEN, null, 5, 0, UUID.randomUUID(), true));
    }

    private UUID reserve(Product product) {
        UUID orderId = UUID.randomUUID();
        assertThat(stockService.reserve(orderId, List.of(new StockItemRequestDTO(product.getId(), 2))).isSuccess()).isTrue();
        return orderId;
    }

    private ProductRequestDTO request(Product product, Integer quantity) {
        return new ProductRequestDTO("Produto atualizado", "Descrição atualizada", new BigDecimal("12.50"),
                "imagem-atualizada", quantity, product.getCategoryId());
    }

    private void assertStock(Product product, int available, int reserved) {
        Product persisted = products.findById(product.getId()).orElseThrow();
        assertThat(persisted.getQuantityAvailable()).isEqualTo(available);
        assertThat(persisted.getQuantityReserved()).isEqualTo(reserved);
    }

    @TestConfiguration
    static class JsonConfig {
        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }
}
