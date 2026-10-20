import 'package:flutter/material.dart';

/// Градиенты Epharm. Источник: `_reference/design-tokens.md` §8.
abstract final class AppGradients {
  /// Welcome screen — коралловый, почти плоский (узкая дельта стопов).
  static const LinearGradient welcome = LinearGradient(
    begin: Alignment.topCenter,
    end: Alignment.bottomCenter,
    colors: [
      Color(0xFF0A4F4A),
      Color(0xFF073D3A),
    ],
    stops: [0.0, 1.0],
  );

  /// Header authed/unauthed Home (`grad-coralHead`).
  /// Коралловый, почти плоский: узкая дельта стопов — мягкий спуск к низу,
  /// чтобы стык шапки со светлым канвасом не резал глаз.
  static const LinearGradient header = LinearGradient(
    begin: Alignment.topCenter,
    end: Alignment.bottomCenter,
    colors: [
      Color(0xFF073D3A),
      Color(0xFF073D3A),
    ],
    stops: [0.0, 1.0],
  );

  /// Плейсхолдер баннера (пока нет реальных): мягкая кремовая подложка, чей НИЗ
  /// сведён к цвету канваса (#FAF7F2) — нижний край растворяется в фоне, без
  /// шва/«ступеньки». Верх чуть тонирован, чтобы блок мягко читался. Тени у
  /// баннера нет (она и давала жёсткую границу) — он спокойно лежит на канвасе.
  static const LinearGradient bannerPlaceholder = LinearGradient(
    begin: Alignment.topCenter,
    end: Alignment.bottomCenter,
    colors: [
      Color(0xFFE7F4F1),
      Color(0xFFF7FAF9),
    ],
  );

  /// Bottom-sheet «Загрузить чек» — 150° диагональ, коралл почти плоский.
  /// Flutter не оперирует углом напрямую — задаём через alignments.
  static const LinearGradient receiptSheet = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [
      Color(0xFF0F766E),
      Color(0xFF073D3A),
    ],
    stops: [0.0, 1.0],
  );

  /// Обложка курса — коралл (бывш. «синяя»).
  static const LinearGradient courseCoverBlue = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [
      Color(0xFF073D3A),
      Color(0xFF149B8F),
    ],
  );

  /// Обложка курса — коралл (бывш. «зелёная»).
  static const LinearGradient courseCoverGreen = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [
      Color(0xFF0F766E),
      Color(0xFF63CFC2),
    ],
  );

  /// Монета — радиальная.
  static const RadialGradient coin = RadialGradient(
    radius: 0.35,
    colors: [
      Color(0xFF63CFC2),
      Color(0xFF149B8F),
      Color(0xFF073D3A),
    ],
    stops: [0.0, 0.6, 1.0],
  );
}
