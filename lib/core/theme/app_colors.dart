import 'package:flutter/material.dart';

/// Цветовые токены Epharm.
/// Источник: `_reference/design-tokens.md` §1.
///
/// Использовать только через эти константы — не хардкодить хексы в виджетах.
abstract final class AppColors {
  // Brand — Bright Orange (Claude). Ярко-оранжевый + белый, БЕЗ коричневого:
  // тёмный конец рампы остаётся насыщенно-оранжевым (D86C3A), не уходит в бурый.
  // Имена brandGreen* сохранены для совместимости с виджетами; значения — оранж.
  static const Color brandGreen700 = Color(0xFFD86C3A); // deep, vivid orange
  static const Color brandGreen600 = Color(0xFFE07E52); // PRIMARY ярко-оранжевый
  static const Color brandGreen500 = Color(0xFFE78A5C);
  static const Color brandGreen400 = Color(0xFFEE9A6F);
  static const Color brandGreen100 = Color(0xFFF8E7DD);

  // Brand — Orange accent (моно-бренд, акцент чуть глубже primary).
  static const Color brandBlue700 = Color(0xFFC95E33);
  static const Color brandBlue600 = Color(0xFFD86C3A);
  static const Color brandBlue500 = Color(0xFFE07E52);
  static const Color brandBlue400 = Color(0xFFEE9A6F);
  static const Color brandBlue300 = Color(0xFFF0AE8C);
  static const Color brandBlue200 = Color(0xFFF0C8B4);
  static const Color brandBlue100 = Color(0xFFF8E7DD);

  // Neutrals — ink (тёплый)
  static const Color ink900 = Color(0xFF221C16);
  static const Color ink700 = Color(0xFF423B32);
  static const Color ink500 = Color(0xFF6F665B);
  static const Color ink400 = Color(0xFF9D9388);
  static const Color ink300 = Color(0xFFD4CCC0);

  // Surfaces (кремовый)
  static const Color paperCanvas = Color(0xFFFAF7F2);
  static const Color paperCard = Color(0xFFFFFFFF);
  static const Color paperInput = Color(0xFFF3EEE7);

  // Text on brand fills
  static const Color textOnBrand = Color(0xFFFFFFFF);

  // Borders / overlays (тёплый ink-тон)
  static const Color borderHairline = Color(0x0F221C16); // rgba(34,28,22,0.06)
  static const Color overlayScrim = Color(0x73221C16); // rgba(34,28,22,0.45)

  // Status / accents
  static const Color accentTrophy = Color(0xFFE78A5C);
  static const Color accentWarning = Color(0xFFE8902E);
  static const Color accentCoinDeep = Color(0xFFD86C3A);
}
