import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pharmacy/features/receipts/data/mock_pharmacy_repository.dart';
import 'package:pharmacy/features/receipts/data/nearby_pharmacies.dart';

void main() {
  const green = Color(0xFF16C97A);

  group('NearbyPharmacy.fromApi', () {
    test('маппит поля и парсит цвет сети', () {
      final p = NearbyPharmacy.fromApi(const {
        'id': 'ph_1',
        'name': 'Аптека Алматы Достык',
        'chain': 'Europharma',
        'chainColor': '#16C97A',
        'city': 'Алматы',
        'district': 'Медеуский',
        'addr': 'пр. Достык, 132',
      });
      expect(p.id, 'ph_1');
      expect(p.chain, 'Europharma');
      expect(p.addr, 'пр. Достык, 132');
      expect(p.city, 'Алматы');
      expect(p.distance, 'Медеуский'); // GPS нет → район в слоте дистанции
      expect(p.color, green);
    });

    test('без района distance = город; битый цвет → зелёный fallback', () {
      final p = NearbyPharmacy.fromApi(const {
        'id': 'ph_2',
        'name': 'A',
        'chain': 'C',
        'chainColor': 'нет',
        'city': 'Астана',
        'district': '',
        'addr': 'ул. 1',
      });
      expect(p.distance, 'Астана');
      expect(p.color, green);
    });

    test('null chainColor и отсутствующие поля не роняют маппинг', () {
      final p = NearbyPharmacy.fromApi(const {'id': 'x'});
      expect(p.id, 'x');
      expect(p.name, '');
      expect(p.color, green);
    });
  });

  test('MockPharmacyRepository отдаёт фиксированный список', () async {
    final list = await MockPharmacyRepository().list();
    expect(list, isNotEmpty);
    expect(list, same(nearbyPharmacies));
  });
}
