package com.gustavoronchi.microsservico_estoque.service;

import com.gustavoronchi.microsservico_estoque.domain.entities.Product;
import com.gustavoronchi.microsservico_estoque.domain.repository.ProductRepository;
import com.gustavoronchi.microsservico_estoque.dto.ReservedItemDTO;
import com.gustavoronchi.microsservico_estoque.dto.StockItemRequestDTO;
import com.gustavoronchi.microsservico_estoque.dto.StockItemResponseDTO;
import com.gustavoronchi.microsservico_estoque.exception.ProductNotFoundException;
import com.gustavoronchi.microsservico_estoque.exception.StockInconsistencyException;
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
    public void release(List<StockItemRequestDTO> items) {
        for (StockItemRequestDTO item : items) {
            Product product = productRepository.findById(item.getProductId())
                    .orElseThrow(() -> new ProductNotFoundException("Produto com id: " + item.getProductId() + " não encontrado."));

            if (product.getQuantityReserved() < item.getQuantity()) {
                throw new StockInconsistencyException("Quantidade reservada insuficiente para liberar o produto "
                                + product.getId()
                                + ". Reservado: "
                                + product.getQuantityReserved()
                                + ", solicitado: "
                                + item.getQuantity());
            }
            product.setQuantityReserved(
                    product.getQuantityReserved() - item.getQuantity()
            );
        }
    }

    @Transactional
    public void confirm(List<StockItemRequestDTO> items) {
        for (StockItemRequestDTO item : items) {
            Product product = productRepository.findById(item.getProductId())
                    .orElseThrow(() -> new ProductNotFoundException(
                            "Produto com id: " + item.getProductId() + " não encontrado."));
            if (product.getQuantityReserved() < item.getQuantity()) {
                throw new StockInconsistencyException(
                        "Quantidade reservada insuficiente para confirmar o produto "
                                + product.getId()
                                + ". Reservado: "
                                + product.getQuantityReserved()
                                + ", solicitado: "
                                + item.getQuantity()
                );
            }

            if (product.getQuantityAvailable() < item.getQuantity()) {
                throw new StockInconsistencyException(
                        "Quantidade disponível insuficiente para confirmar o produto "
                                + product.getId()
                                + ". Disponível: "
                                + product.getQuantityAvailable()
                                + ", solicitado: "
                                + item.getQuantity());
            }

            product.setQuantityAvailable(
                    product.getQuantityAvailable() - item.getQuantity()
            );

            product.setQuantityReserved(
                    product.getQuantityReserved() - item.getQuantity()
            );
        }
    }
}
