import 'package:flutter/material.dart';

/// Тени Epharm. Источник: `_reference/design-tokens.md` §4.
abstract final class AppShadows {
  /// Список-роу, белые карточки. Тень МЯГКАЯ — карточки спокойно лежат на
  /// paperCanvas (`#FAF7F2`) без жёсткой тёмной кромки (приятнее глазу).
  /// Раньше свечение было 0.12 — давало резкую границу под блоками.
  static const List<BoxShadow> card = [
    BoxShadow(
      color: Color(0x0D071B26),
      offset: Offset(0, 1),
      blurRadius: 3,
    ),
    BoxShadow(
      color: Color(0x12071B26),
      offset: Offset(0, 4),
      blurRadius: 16,
    ),
  ];

  /// Bottom sheets, модалки.
  static const List<BoxShadow> elevated = [
    BoxShadow(
      color: Color(0x0F071B26),
      offset: Offset(0, 4),
      blurRadius: 8,
    ),
    BoxShadow(
      color: Color(0x1A071B26),
      offset: Offset(0, 12),
      blurRadius: 32,
    ),
  ];

  /// Primary CTA, active filter chip, tier pills — мягкое коралловое свечение.
  /// Бренд = Claude coral: glow приглушён (rgba(217,119,87,0.28)) — не неон,
  /// а спокойная брендовая подсветка под CTA-кнопками и активными chip'ами.
  static const List<BoxShadow> fab = [
    BoxShadow(
      color: Color(0x330F766E),
      offset: Offset(0, 10),
      blurRadius: 24,
    ),
  ];

  /// Верхняя кромка bottom-nav.
  static const List<BoxShadow> navTop = [
    BoxShadow(
      color: Color(0x0D071B26),
      offset: Offset(0, -2),
      blurRadius: 12,
    ),
  ];
}
