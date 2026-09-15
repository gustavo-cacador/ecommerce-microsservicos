package com.gustavoronchi.microsservico_pedido.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

@Component
public class StockClient {

    private final RestClient restClient;

    public StockClient(@Value("${clients.estoque-service.url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public StockReserveResponseDTO reserve(List<StockItemRequestDTO> itens) {
        return restClient.post()
                .uri("/stock/reserve")
                .body(itens)
                .retrieve()
                .body(StockReserveResponseDTO.class);
    }

    public void release(List<StockItemRequestDTO> itens) {
        restClient.post()
                .uri("/stock/release")
                .body(itens)
                .retrieve()
                .toBodilessEntity();
    }

    public void confirm(List<StockItemRequestDTO> itens) {
        restClient.post()
                .uri("/stock/confirm")
                .body(itens)
                .retrieve()
                .toBodilessEntity();
    }

    // implementar depois para detalhar produto no frontend chamando pedido-service
    public ProductResponseDTO searchProduct(UUID productId) {
        return restClient.get()
                .uri("/products/{id}", productId)
                .retrieve()
                .body(ProductResponseDTO.class);
    }
}
