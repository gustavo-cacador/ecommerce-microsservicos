INSERT INTO categories (
    id,
    name
) VALUES
      (
          'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
          'Periféricos'
      ),
      (
          'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
          'Áudio'
      ),
      (
          'cccccccc-cccc-cccc-cccc-cccccccccccc',
          'Monitores'
      ),
      (
          'dddddddd-dddd-dddd-dddd-dddddddddddd',
          'Câmeras e Vídeo'
      );

INSERT INTO products (
    id,
    name,
    description,
    price,
    img_url,
    quantity_available,
    quantity_reserved,
    category_id,
    active
) VALUES
      (
          '11111111-1111-1111-1111-111111111111',
          'Teclado Mecânico',
          'Teclado mecânico com switches red e iluminação RGB',
          249.90,
          'https://example.com/images/teclado-mecanico.jpg',
          50,
          0,
          'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
          true
      ),
      (
          '22222222-2222-2222-2222-222222222222',
          'Mouse Gamer',
          'Mouse gamer com sensor óptico de alta precisão',
          149.90,
          'https://example.com/images/mouse-gamer.jpg',
          100,
          0,
          'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
          true
      ),
      (
          '33333333-3333-3333-3333-333333333333',
          'Headset Gamer',
          'Headset gamer com microfone e áudio surround',
          199.90,
          'https://example.com/images/headset-gamer.jpg',
          30,
          0,
          'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
          true
      ),
      (
          '44444444-4444-4444-4444-444444444444',
          'Monitor 24"',
          'Monitor Full HD de 24 polegadas com 144Hz',
          899.90,
          'https://example.com/images/monitor-24.jpg',
          20,
          0,
          'cccccccc-cccc-cccc-cccc-cccccccccccc',
          true
      ),
      (
          '55555555-5555-5555-5555-555555555555',
          'Webcam Full HD',
          'Webcam Full HD 1080p para chamadas e streaming',
          299.90,
          'https://example.com/images/webcam.jpg',
          15,
          0,
          'dddddddd-dddd-dddd-dddd-dddddddddddd',
          true
      );