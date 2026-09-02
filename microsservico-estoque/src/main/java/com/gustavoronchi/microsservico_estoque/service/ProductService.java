package com.gustavoronchi.microsservico_estoque.service;

import com.gustavoronchi.microsservico_estoque.exception.ProductNotFoundException;
import com.gustavoronchi.microsservico_estoque.domain.entities.Product;
import com.gustavoronchi.microsservico_estoque.domain.repository.ProductRepository;
import com.gustavoronchi.microsservico_estoque.dto.ProductRequestDTO;
import com.gustavoronchi.microsservico_estoque.dto.ProductResponseDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public ProductResponseDTO findById(UUID id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException("Produto com id: " + id + " não encontrado."));
        return new ProductResponseDTO(product);
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

        Product salvo = productRepository.save(product);
        return new ProductResponseDTO(salvo);
    }
}
