import 'package:flutter/material.dart';

/// Тени Epharm. Источник: `_reference/design-tokens.md` §4.
abstract final class AppShadows {
  /// Список-роу, белые карточки. Тень достаточно выраженная, чтобы карточки
  /// чётко отделялись от paperCanvas (`#F4F6FA`) и не сливались с фоном.
  static const List<BoxShadow> card = [
    BoxShadow(
      color: Color(0x140F1424), // rgba(15,20,36,0.08) — близкая «контактная» тень
      offset: Offset(0, 1),
      blurRadius: 3,
    ),
    BoxShadow(
      color: Color(0x1F0F1424), // rgba(15,20,36,0.12) — мягкое нижнее свечение
      offset: Offset(0, 6),
      blurRadius: 18,
    ),
  ];

  /// Bottom sheets, модалки.
  static const List<BoxShadow> elevated = [
    BoxShadow(
      color: Color(0x0F0F1424),
      offset: Offset(0, 4),
      blurRadius: 8,
    ),
    BoxShadow(
      color: Color(0x1A0F1424), // rgba(15,20,36,0.10)
      offset: Offset(0, 12),
      blurRadius: 32,
    ),
  ];

  /// Primary CTA, active filter chip, tier pills — зелёное свечение.
  /// Усилено относительно изначального 0.35 → 0.55 для большего «pop» и
  /// контраста в основном flow приложения (CTA-кнопки, активные chip'ы).
  static const List<BoxShadow> fab = [
    BoxShadow(
      color: Color(0x8C16C97A), // rgba(22,201,122,0.55) — раньше было 0.35
      offset: Offset(0, 10),
      blurRadius: 24,
    ),
  ];

  /// Верхняя кромка bottom-nav.
  static const List<BoxShadow> navTop = [
    BoxShadow(
      color: Color(0x0D0F1424), // rgba(15,20,36,0.05)
      offset: Offset(0, -2),
      blurRadius: 12,
    ),
  ];
}
