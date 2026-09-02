package com.gustavoronchi.microsservico_estoque.resource;

import com.gustavoronchi.microsservico_estoque.dto.StockItemRequestDTO;
import com.gustavoronchi.microsservico_estoque.dto.StockItemResponseDTO;
import com.gustavoronchi.microsservico_estoque.service.StockService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("stock")
public class StockResource {

    private final StockService stockService;

    public StockResource(StockService stockService) {
        this.stockService = stockService;
    }

    @PostMapping("reserve")
    public ResponseEntity<StockItemResponseDTO> reserve(@RequestBody List<StockItemRequestDTO> itens) {
        return ResponseEntity.ok(stockService.reserve(itens));
    }

    @PostMapping("release")
    public ResponseEntity<Void> release(@RequestBody List<StockItemRequestDTO> itens) {
        stockService.release(itens);
        return ResponseEntity.noContent().build();
    }
}
