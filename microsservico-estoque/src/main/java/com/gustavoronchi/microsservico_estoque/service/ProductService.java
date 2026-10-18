package com.gustavoronchi.microsservico_estoque.service;

import com.gustavoronchi.microsservico_estoque.exception.InvalidProductRequestException;
import com.gustavoronchi.microsservico_estoque.exception.ProductNotFoundException;
import com.gustavoronchi.microsservico_estoque.exception.StockInconsistencyException;
import com.gustavoronchi.microsservico_estoque.domain.entities.Product;
import com.gustavoronchi.microsservico_estoque.domain.repository.ProductRepository;
import com.gustavoronchi.microsservico_estoque.dto.ProductRequestDTO;
import com.gustavoronchi.microsservico_estoque.dto.ProductResponseDTO;
import com.gustavoronchi.microsservico_estoque.dto.ProductPriceDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public ProductResponseDTO findById(UUID id) {
        Product product = productRepository
                .findById(id)
                .orElseThrow(() -> new ProductNotFoundException("Produto com id: " + id + " não encontrado."));
        if (!product.getActive()) {
            throw new ProductNotFoundException("Produto com id: " + id + " não encontrado.");
        }
        return new ProductResponseDTO(product);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponseDTO> findAll(UUID categoryId, Pageable pageable) {
        return productRepository
                .search(categoryId, pageable)
                .map(ProductResponseDTO::new);
    }

    @Transactional(readOnly = true)
    public List<ProductPriceDTO> findPrices(List<UUID> productIds) {
        if (productIds == null || productIds.isEmpty() || productIds.stream().anyMatch(Objects::isNull)) {
            throw new InvalidProductRequestException("Informe ao menos um produto e não envie IDs nulos.");
        }

        List<UUID> uniqueIds = productIds.stream().distinct().toList();
        Map<UUID, Product> products = productRepository.findAllById(uniqueIds).stream()
                .collect(Collectors.toMap(Product::getId, product -> product));

        return uniqueIds.stream().map(id -> {
            Product product = products.get(id);
            if (product == null || !Boolean.TRUE.equals(product.getActive())) {
                throw new ProductNotFoundException("Produto com id: " + id + " não encontrado.");
            }
            int availableStock = Math.max(0, product.getQuantityAvailable() - product.getQuantityReserved());
            return new ProductPriceDTO(product.getId(), product.getPrice(), availableStock);
        }).toList();
    }

    @Transactional
    public ProductResponseDTO create(ProductRequestDTO dto) {
        Product product = new Product();
        product.setName(dto.getName());
        product.setDescription(dto.getDescription());
        product.setPrice(dto.getPrice());
        product.setImgUrl(dto.getImgUrl());
        product.setQuantityAvailable(dto.getQuantityAvailable());
        product.setQuantityReserved(0);
        product.setCategoryId(dto.getCategoryId());
        product.setActive(true);

        Product createProduct = productRepository.save(product);
        return new ProductResponseDTO(createProduct);
    }

    @Transactional
    public ProductResponseDTO update(UUID id, ProductRequestDTO dto) {
        if (dto == null || dto.getQuantityAvailable() == null || dto.getQuantityAvailable() < 0) {
            throw new InvalidProductRequestException("Informe uma quantidade de estoque maior ou igual a zero.");
        }
        Product product = productRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ProductNotFoundException("Produto com id: " + id + " não encontrado."));
        if (dto.getQuantityAvailable() < product.getQuantityReserved()) {
            throw new StockInconsistencyException("Quantidade de estoque não pode ser menor que a quantidade reservada: "
                    + product.getQuantityReserved());
        }
        product.setName(dto.getName());
        product.setDescription(dto.getDescription());
        product.setPrice(dto.getPrice());
        product.setImgUrl(dto.getImgUrl());
        product.setQuantityAvailable(dto.getQuantityAvailable());
        product.setCategoryId(dto.getCategoryId());
        Product updatedProduct = productRepository.save(product);
        return new ProductResponseDTO(updatedProduct);
    }

    @Transactional
    public void delete(UUID id) {
        Product product = productRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ProductNotFoundException("Produto com id: " + id + " não encontrado."));
        if (product.getQuantityReserved() > 0) {
            throw new StockInconsistencyException("Não é possível desativar um produto com reservas pendentes de pedidos.");
        }
        product.setActive(false);
        productRepository.save(product);
    }
}
