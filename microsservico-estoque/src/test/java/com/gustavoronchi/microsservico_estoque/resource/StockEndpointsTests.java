package com.gustavoronchi.microsservico_estoque.resource;

import com.gustavoronchi.microsservico_estoque.dto.ProductPriceDTO;
import com.gustavoronchi.microsservico_estoque.service.CategoryService;
import com.gustavoronchi.microsservico_estoque.service.ProductService;
import com.gustavoronchi.microsservico_estoque.service.StockService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
class StockEndpointsTests {

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private StockService stockService;
    @MockitoBean
    private ProductService productService;
    @MockitoBean
    private CategoryService categoryService;

    @ParameterizedTest
    @ValueSource(strings = {"reserve", "confirm", "release"})
    void manualStockChangesReturnNotFoundWithoutCallingStockService(String action) throws Exception {
        String body = """
                [{"productId":"%s","quantity":2}]
                """.formatted(UUID.randomUUID());

        mvc.perform(post("/stock/{action}", action)
                        .param("orderId", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());

        verifyNoInteractions(stockService, productService, categoryService);
    }

    @Test
    void priceLookupRemainsAvailableWithoutCallingStockService() throws Exception {
        UUID productId = UUID.randomUUID();
        when(productService.findPrices(List.of(productId)))
                .thenReturn(List.of(new ProductPriceDTO(productId, new BigDecimal("10.00"), 5)));

        mvc.perform(post("/products/prices").contentType(MediaType.APPLICATION_JSON)
                        .content("[\"" + productId + "\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(productId.toString()))
                .andExpect(jsonPath("$[0].price").value(10.00))
                .andExpect(jsonPath("$[0].availableStock").value(5));

        verify(productService).findPrices(List.of(productId));
        verifyNoInteractions(stockService, categoryService);
    }
}
