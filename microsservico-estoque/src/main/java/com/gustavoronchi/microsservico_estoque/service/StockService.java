package com.gustavoronchi.microsservico_estoque.service;

import com.gustavoronchi.microsservico_estoque.domain.entities.Product;
import com.gustavoronchi.microsservico_estoque.domain.repository.ProductRepository;
import com.gustavoronchi.microsservico_estoque.dto.StockItemRequestDTO;
import com.gustavoronchi.microsservico_estoque.dto.StockItemResponseDTO;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class StockService {

    private final ProductRepository productRepository;

    public StockService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public StockItemResponseDTO reserve(List<StockItemRequestDTO> itens) {
        for (StockItemRequestDTO item : itens) {
            Product product = productRepository.findById(item.getProductId()).orElse(null);

            if (product == null) {
                return new StockItemResponseDTO(false, "Produto não encontrado: " + item.getProductId());
            }

            int disponivel = product.getQuantityAvailable() - product.getQuantityReserved();
            if (disponivel < item.getQuantity()) {
                return new StockItemResponseDTO(false, "Estoque insuficiente para o produto " + product.getName());
            }
        }

        try {
            for (StockItemRequestDTO item : itens) {
                Product product = productRepository.findById(item.getProductId()).orElseThrow();
                product.setQuantityReserved(product.getQuantityReserved() + item.getQuantity());
                productRepository.save(product);
            }
        } catch (OptimisticLockingFailureException ex) {
            return new StockItemResponseDTO(false, "Conflito de concorrência ao reservar estoque, tente novamente.");
        }

        return new StockItemResponseDTO(true, null);
    }

    @Transactional
    public void release(List<StockItemRequestDTO> itens) {
        for (StockItemRequestDTO item : itens) {
            productRepository.findById(item.getProductId()).ifPresent(product -> {
                int novaQuantidade = Math.max(0, product.getQuantityReserved() - item.getQuantity());
                product.setQuantityReserved(novaQuantidade);
                productRepository.save(product);
            });
        }
    }
}
