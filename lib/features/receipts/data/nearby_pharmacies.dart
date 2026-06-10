import 'package:flutter/material.dart';

/// Аптека «поблизости» — для AddressSheet (pharmacy picker).
/// Источник дизайна: `_reference/recipe/review.jsx` → `NEARBY_PHARMACIES`.
///
/// При USE_API=true список приходит из нашего реестра аптек
/// (`GET /api/mobile/pharmacies`) — реальные адреса. В офлайн-режиме (mock) —
/// фиксированный список аптек Алматы ниже. Геолокации/дистанции в реестре нет:
/// для API-аптек в слот [distance] кладём район (или город), а не «120 м».
class NearbyPharmacy {
  const NearbyPharmacy({
    required this.id,
    required this.chain,
    required this.name,
    required this.addr,
    required this.city,
    required this.distance,
    required this.color,
  });

  /// Маппинг из MobilePharmacyDto бэкенда (id,name,chain,chainColor,city,district,addr).
  factory NearbyPharmacy.fromApi(Map<String, dynamic> json) {
    final district = (json['district'] as String?)?.trim() ?? '';
    final city = (json['city'] as String?) ?? '';
    return NearbyPharmacy(
      id: (json['id'] as String?) ?? '',
      chain: (json['chain'] as String?) ?? '',
      name: (json['name'] as String?) ?? '',
      addr: (json['addr'] as String?) ?? '',
      city: city,
      // GPS-дистанции нет → показываем район, иначе город.
      distance: district.isNotEmpty ? district : city,
      color: _parseHexColor(json['chainColor'] as String?),
    );
  }

  final String id;
  final String chain;
  final String name;
  final String addr;
  final String city;
  final String distance;
  final Color color;
}

/// «#16C97A» → Color. Пустое/битое значение → фирменный зелёный.
Color _parseHexColor(String? hex) {
  const fallback = Color(0xFF16C97A);
  if (hex == null) return fallback;
  final cleaned = hex.replaceAll('#', '').trim();
  if (cleaned.length != 6) return fallback;
  final value = int.tryParse('FF$cleaned', radix: 16);
  return value == null ? fallback : Color(value);
}

/// Mock-данные для AddressSheet. Цвета — для leading-tile chain-маркера в
/// списке аптек (буква первого символа `chain` на цветной плитке).
const nearbyPharmacies = <NearbyPharmacy>[
  NearbyPharmacy(
    id: 'eu_112',
    chain: 'Europharma',
    name: 'Europharma №112',
    addr: 'пр. Достык, 132',
    city: 'Алматы',
    distance: '120 м',
    color: Color(0xFF16C97A),
  ),
  NearbyPharmacy(
    id: 'sadyhan_3',
    chain: 'Садыхан',
    name: 'Садыхан №3',
    addr: 'ул. Толе би, 286',
    city: 'Алматы',
    distance: '340 м',
    color: Color(0xFF0F8F55),
  ),
  NearbyPharmacy(
    id: 'biosfera',
    chain: 'Биосфера',
    name: 'Биосфера',
    addr: 'ул. Жандосова, 51',
    city: 'Алматы',
    distance: '480 м',
    color: Color(0xFF3DCDA2),
  ),
  NearbyPharmacy(
    id: 'zerde_18',
    chain: 'Зерде',
    name: 'Зерде №18',
    addr: 'мкр. Алмагуль, 9',
    city: 'Алматы',
    distance: '620 м',
    color: Color(0xFF16C97A),
  ),
  NearbyPharmacy(
    id: 'farmis_4',
    chain: 'Фармис',
    name: 'Фармис №4',
    addr: 'пр. Абая, 159',
    city: 'Алматы',
    distance: '780 м',
    color: Color(0xFF21D17A),
  ),
  NearbyPharmacy(
    id: 'krug_15',
    chain: 'Круглосуточная',
    name: 'Круглосуточная №15',
    addr: 'ул. Сейфуллина, 458',
    city: 'Алматы',
    distance: '1.1 км',
    color: Color(0xFF0F8F55),
  ),
  NearbyPharmacy(
    id: 'avic',
    chain: 'Avicenna',
    name: 'Avicenna',
    addr: 'ул. Назарбаева, 232',
    city: 'Алматы',
    distance: '1.4 км',
    color: Color(0xFF3DCDA2),
  ),
  NearbyPharmacy(
    id: 'a36_22',
    chain: 'Аптека 36',
    name: 'Аптека 36, №22',
    addr: 'ул. Розыбакиева, 247',
    city: 'Алматы',
    distance: '2.0 км',
    color: Color(0xFF16C97A),
  ),
];
