package com.gustavoronchi.microsservico_estoque.resource;

import com.gustavoronchi.microsservico_estoque.dto.CategoryRequestDTO;
import com.gustavoronchi.microsservico_estoque.dto.CategoryResponseDTO;
import com.gustavoronchi.microsservico_estoque.dto.ProductRequestDTO;
import com.gustavoronchi.microsservico_estoque.dto.ProductResponseDTO;
import com.gustavoronchi.microsservico_estoque.service.CategoryService;
import com.gustavoronchi.microsservico_estoque.service.ProductService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.MethodArgumentNotValidException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ProductResource.class, CategoryResource.class})
class RequestValidationTests {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JsonMapper jsonMapper;
    @MockitoBean
    private ProductService productService;
    @MockitoBean
    private CategoryService categoryService;

    @ParameterizedTest
    @MethodSource("invalidProductFields")
    void rejectsInvalidProductFieldsOnCreateAndUpdate(String field, Object value) throws Exception {
        Map<String, Object> request = validProduct();
        request.put(field, value);

        assertInvalidProduct(request);
    }

    static Stream<Arguments> invalidProductFields() {
        return Stream.of(
                Arguments.of("name", null),
                Arguments.of("name", ""),
                Arguments.of("name", " \t\n"),
                Arguments.of("price", null),
                Arguments.of("price", new BigDecimal("-0.01")),
                Arguments.of("quantityAvailable", null),
                Arguments.of("quantityAvailable", -1),
                Arguments.of("categoryId", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "price", "quantityAvailable", "categoryId"})
    void rejectsMissingProductFieldsOnCreateAndUpdate(String field) throws Exception {
        Map<String, Object> request = validProduct();
        request.remove(field);

        assertInvalidProduct(request);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptsZeroPriceAndQuantityWithOptionalFieldsAbsentOrNull(boolean includeNulls) throws Exception {
        Map<String, Object> request = validProduct();
        if (includeNulls) {
            request.put("description", null);
            request.put("imgUrl", null);
        }
        UUID productId = UUID.randomUUID();
        ProductResponseDTO response = new ProductResponseDTO(productId, "Produto", null, BigDecimal.ZERO,
                null, 0, 0, (UUID) request.get("categoryId"), true);
        when(productService.create(any(ProductRequestDTO.class))).thenReturn(response);
        when(productService.update(any(UUID.class), any(ProductRequestDTO.class))).thenReturn(response);
        String body = jsonMapper.writeValueAsString(request);

        mvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/products/" + productId));
        mvc.perform(put("/products/{id}", productId).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":null}", "{\"name\":\"\"}", "{\"name\":\" \\t\\n\"}"})
    void rejectsMissingNullOrBlankCategoryName(String body) throws Exception {
        mvc.perform(post("/categories").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResolvedException())
                        .isInstanceOf(MethodArgumentNotValidException.class));

        verifyNoInteractions(productService, categoryService);
    }

    @Test
    void acceptsCategoryWithNonBlankName() throws Exception {
        UUID categoryId = UUID.randomUUID();
        when(categoryService.create(any(CategoryRequestDTO.class)))
                .thenReturn(new CategoryResponseDTO(categoryId, "Categoria"));

        mvc.perform(post("/categories").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Categoria\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/categories/" + categoryId));
    }

    private Map<String, Object> validProduct() {
        return new HashMap<>(Map.of("name", "Produto", "price", BigDecimal.ZERO,
                "quantityAvailable", 0, "categoryId", UUID.randomUUID()));
    }

    private void assertInvalidProduct(Map<String, Object> request) throws Exception {
        String body = jsonMapper.writeValueAsString(request);

        mvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResolvedException())
                        .isInstanceOf(MethodArgumentNotValidException.class));
        mvc.perform(put("/products/{id}", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResolvedException())
                        .isInstanceOf(MethodArgumentNotValidException.class));

        verifyNoInteractions(productService, categoryService);
    }
}
