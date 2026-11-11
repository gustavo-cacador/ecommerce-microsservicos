package com.gustavoronchi.microsservico_pedido.resource;

import com.gustavoronchi.microsservico_pedido.dto.OrderItemRequestDTO;
import com.gustavoronchi.microsservico_pedido.dto.OrderRequestDTO;
import com.gustavoronchi.microsservico_pedido.dto.OrderResponseDTO;
import com.gustavoronchi.microsservico_pedido.service.OrderService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderResource.class)
class OrderEndpointsTests {

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private OrderService orderService;

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"items\":[{\"productId\":\"%2$s\",\"quantity\":1}]}",
            "{\"clientId\":null,\"items\":[{\"productId\":\"%2$s\",\"quantity\":1}]}",
            "{\"clientId\":\"%1$s\"}",
            "{\"clientId\":\"%1$s\",\"items\":null}",
            "{\"clientId\":\"%1$s\",\"items\":[]}",
            "{\"clientId\":\"%1$s\",\"items\":[{\"productId\":\"%2$s\",\"quantity\":1},null]}",
            "{\"clientId\":\"%1$s\",\"items\":[{\"quantity\":1}]}",
            "{\"clientId\":\"%1$s\",\"items\":[{\"productId\":null,\"quantity\":1}]}",
            "{\"clientId\":\"%1$s\",\"items\":[{\"productId\":\"%2$s\"}]}",
            "{\"clientId\":\"%1$s\",\"items\":[{\"productId\":\"%2$s\",\"quantity\":null}]}",
            "{\"clientId\":\"%1$s\",\"items\":[{\"productId\":\"%2$s\",\"quantity\":0}]}",
            "{\"clientId\":\"%1$s\",\"items\":[{\"productId\":\"%2$s\",\"quantity\":-1}]}",
            "{\"clientId\":\"%1$s\",\"items\":[{\"productId\":\"%2$s\",\"quantity\":1},{\"productId\":\"%2$s\",\"quantity\":0}]}"
    })
    void invalidRequestReturns400WithoutCallingOrderService(String body) throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResolvedException())
                        .isInstanceOf(MethodArgumentNotValidException.class));

        verifyNoInteractions(orderService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "diff08-order")
    void validRequestCallsOrderServiceWithOptionalIdempotencyKey(String idempotencyKey) throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        OrderResponseDTO response = new OrderResponseDTO();
        response.setOrderId(orderId);
        when(orderService.createOrder(any(OrderRequestDTO.class), eq(idempotencyKey))).thenReturn(response);
        String body = """
                {"clientId":"%s","items":[{"productId":"%s","quantity":1},{"productId":"%s","quantity":2}]}
                """.formatted(clientId, productId, productId);
        var request = post("/orders").contentType(MediaType.APPLICATION_JSON).content(body);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }

        mvc.perform(request)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/orders/" + orderId))
                .andExpect(jsonPath("$.orderId").value(orderId.toString()));

        ArgumentCaptor<OrderRequestDTO> captor = ArgumentCaptor.forClass(OrderRequestDTO.class);
        verify(orderService).createOrder(captor.capture(), eq(idempotencyKey));
        OrderRequestDTO received = captor.getValue();
        assertThat(received.getClientId()).isEqualTo(clientId);
        assertThat(received.getItems()).extracting(OrderItemRequestDTO::getProductId)
                .containsExactly(productId, productId);
        assertThat(received.getItems()).extracting(OrderItemRequestDTO::getQuantity).containsExactly(1, 2);
        verifyNoMoreInteractions(orderService);
    }
}
