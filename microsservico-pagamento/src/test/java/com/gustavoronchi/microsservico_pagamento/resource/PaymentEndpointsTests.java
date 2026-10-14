package com.gustavoronchi.microsservico_pagamento.resource;

import com.gustavoronchi.microsservico_pagamento.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
class PaymentEndpointsTests {

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private PaymentService paymentService;

    @Test
    void manualPaymentReturnsNotFoundWithoutCallingPaymentService() throws Exception {
        String body = """
                {"orderId":"%s","amount":499.80}
                """.formatted(UUID.randomUUID());

        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());

        verifyNoInteractions(paymentService);
    }
}
