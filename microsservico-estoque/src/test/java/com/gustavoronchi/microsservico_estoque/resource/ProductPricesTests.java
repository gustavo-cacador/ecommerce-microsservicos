package com.gustavoronchi.microsservico_estoque.resource;

import com.gustavoronchi.microsservico_estoque.domain.entities.Product;
import com.gustavoronchi.microsservico_estoque.domain.repository.ProductRepository;
import com.gustavoronchi.microsservico_estoque.domain.repository.StockReservationRepository;
import com.gustavoronchi.microsservico_estoque.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DataJpaTest(showSql = false, properties = "spring.sql.init.mode=never")
@Import(ProductService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductPricesTests {

    @Autowired
    private ProductService service;
    @Autowired
    private ProductRepository products;
    @Autowired
    private StockReservationRepository reservations;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProductResource(service)).build();
    }

    @Test
    void returnsUniquePricesInRequestedOrderWithoutReservingStock() throws Exception {
        Product first = product("10.00", 5, 2, true);
        Product second = product("21.50", 0, 0, true);
        long reservationCount = reservations.count();
        String body = "[\"" + second.getId() + "\",\"" + first.getId() + "\",\"" + second.getId() + "\"]";

        mvc.perform(post("/products/prices").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].productId").value(second.getId().toString()))
                .andExpect(jsonPath("$[0].price").value(21.50))
                .andExpect(jsonPath("$[0].availableStock").value(0))
                .andExpect(jsonPath("$[1].productId").value(first.getId().toString()))
                .andExpect(jsonPath("$[1].price").value(10.00))
                .andExpect(jsonPath("$[1].availableStock").value(3));

        Product persisted = products.findById(first.getId()).orElseThrow();
        assertThat(persisted.getQuantityAvailable()).isEqualTo(5);
        assertThat(persisted.getQuantityReserved()).isEqualTo(2);
        assertThat(reservations.count()).isEqualTo(reservationCount);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[null]", "null", "[\"invalid-uuid\"]", "{}"})
    void rejectsInvalidInput(String body) throws Exception {
        mvc.perform(post("/products/prices").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsWholeBatchWhenAnyProductDoesNotExist() throws Exception {
        Product product = product("10.00", 5, 0, true);
        String body = "[\"" + product.getId() + "\",\"" + UUID.randomUUID() + "\"]";

        mvc.perform(post("/products/prices").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsInactiveProduct() throws Exception {
        Product product = product("10.00", 5, 0, false);

        mvc.perform(post("/products/prices").contentType(MediaType.APPLICATION_JSON)
                        .content("[\"" + product.getId() + "\"]"))
                .andExpect(status().isNotFound());
    }

    private Product product(String price, int available, int reserved, boolean active) {
        return products.saveAndFlush(new Product(null, "Produto de teste", null, new BigDecimal(price),
                null, available, reserved, UUID.randomUUID(), active));
    }
}
