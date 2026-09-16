package com.gustavoronchi.microsservico_pedido.client;

import com.gustavoronchi.microsservico_pedido.dto.PaymentRequestDTO;
import com.gustavoronchi.microsservico_pedido.dto.PaymentResponseDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PaymentClient {

    private final RestClient restClient;

    public PaymentClient(@Value("${clients.pagamento-service.url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public PaymentResponseDTO process(PaymentRequestDTO dto) {
        return restClient.post()
                .uri("/payments")
                .body(dto)
                .retrieve()
                .body(PaymentResponseDTO.class);
    }
}
