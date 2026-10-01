package com.gustavoronchi.microsservico_estoque.service;

import com.gustavoronchi.microsservico_estoque.exception.DatabaseException;
import com.gustavoronchi.microsservico_estoque.exception.InvalidProductRequestException;
import com.gustavoronchi.microsservico_estoque.exception.ProductNotFoundException;
import com.gustavoronchi.microsservico_estoque.domain.entities.Product;
import com.gustavoronchi.microsservico_estoque.domain.repository.ProductRepository;
import com.gustavoronchi.microsservico_estoque.dto.ProductRequestDTO;
import com.gustavoronchi.microsservico_estoque.dto.ProductResponseDTO;
import com.gustavoronchi.microsservico_estoque.dto.ProductPriceDTO;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
            return new ProductPriceDTO(product.getId(), product.getPrice());
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
        try {
            Product product = productRepository.getReferenceById(id);
            product.setName(dto.getName());
            product.setDescription(dto.getDescription());
            product.setPrice(dto.getPrice());
            product.setImgUrl(dto.getImgUrl());
            product.setQuantityAvailable(dto.getQuantityAvailable());
            product.setCategoryId(dto.getCategoryId());
            Product updatedProduct = productRepository.save(product);
            return new ProductResponseDTO(updatedProduct);
        } catch (EntityNotFoundException e) {
            throw new ProductNotFoundException("Produto com id: " + id + " não encontrado.");
        }
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public void delete(UUID id) {
        if (!productRepository.existsById(id)) {
            throw new ProductNotFoundException("Produto com id: " + id + " não encontrado.");
        } try {
            productRepository.deleteById(id);
        } catch (DataIntegrityViolationException e) {
            throw new DatabaseException("Falha de integridade referencial.");
        }
    }
}
