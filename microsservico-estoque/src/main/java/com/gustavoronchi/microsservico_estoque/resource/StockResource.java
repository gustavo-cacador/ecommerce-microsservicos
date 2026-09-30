package com.gustavoronchi.microsservico_estoque.resource;

import com.gustavoronchi.microsservico_estoque.dto.StockItemRequestDTO;
import com.gustavoronchi.microsservico_estoque.dto.StockItemResponseDTO;
import com.gustavoronchi.microsservico_estoque.service.StockService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("stock")
public class StockResource {

    private final StockService stockService;

    public StockResource(StockService stockService) {
        this.stockService = stockService;
    }

    @PostMapping("reserve")
    public ResponseEntity<StockItemResponseDTO> reserve(@RequestParam UUID orderId,
                                                      @RequestBody List<StockItemRequestDTO> items) {
        return ResponseEntity.ok(stockService.reserve(orderId, items));
    }

    @PostMapping("release")
    public ResponseEntity<Void> release(@RequestParam UUID orderId) {
        stockService.release(orderId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("confirm")
    public ResponseEntity<Void> confirm(@RequestParam UUID orderId) {
        stockService.confirm(orderId);
        return ResponseEntity.noContent().build();
    }
}
