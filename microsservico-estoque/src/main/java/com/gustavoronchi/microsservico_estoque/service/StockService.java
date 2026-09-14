package com.gustavoronchi.microsservico_estoque.service;

import com.gustavoronchi.microsservico_estoque.domain.entities.Product;
import com.gustavoronchi.microsservico_estoque.domain.repository.ProductRepository;
import com.gustavoronchi.microsservico_estoque.dto.ReservedItemDTO;
import com.gustavoronchi.microsservico_estoque.dto.StockItemRequestDTO;
import com.gustavoronchi.microsservico_estoque.dto.StockItemResponseDTO;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class StockService {

    private final ProductRepository productRepository;

    public StockService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public StockItemResponseDTO reserve(List<StockItemRequestDTO> itens) {
        List<Product> blockedProducts = new ArrayList<>();

        for (StockItemRequestDTO item : itens) {
            Product product = productRepository.findByIdForUpdate(item.getProductId()).orElse(null);

            if (product == null || !product.getActive()) {
                return new StockItemResponseDTO(false, "Produto não encontrado: " + item.getProductId());
            }

            int disponivel = product.getQuantityAvailable() - product.getQuantityReserved();
            if (disponivel < item.getQuantity()) {
                return new StockItemResponseDTO(false, "Estoque insuficiente para o produto " + product.getName());
            }

            blockedProducts.add(product);
        }

        List<ReservedItemDTO> reservedItems = new ArrayList<>();

        for (int i = 0; i < itens.size(); i++) {
            StockItemRequestDTO itemRequest = itens.get(i);
            Product product = blockedProducts.get(i);

            product.setQuantityReserved(product.getQuantityReserved() + itemRequest.getQuantity());
            productRepository.save(product);

            reservedItems.add(new ReservedItemDTO(product.getId(), product.getPrice()));
        }

        return new StockItemResponseDTO(true, null, reservedItems);
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
