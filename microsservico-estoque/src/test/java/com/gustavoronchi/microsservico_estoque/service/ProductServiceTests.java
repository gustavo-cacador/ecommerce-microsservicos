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
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
