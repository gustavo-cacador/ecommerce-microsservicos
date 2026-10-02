package com.gustavoronchi.microsservico_pagamento;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"spring.rabbitmq.listener.simple.auto-startup=false", "spring.rabbitmq.dynamic=false"})
class MicrosservicoPagamentoApplicationTests {

	@Test
	void contextLoads() {
	}

}
