import 'package:flutter/material.dart';

/// Градиенты Epharm. Источник: `_reference/design-tokens.md` §8.
abstract final class AppGradients {
  /// Welcome screen — коралловый, почти плоский (узкая дельта стопов).
  static const LinearGradient welcome = LinearGradient(
    begin: Alignment.topCenter,
    end: Alignment.bottomCenter,
    colors: [
      Color(0xFFDE8460),
      Color(0xFFD97757),
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
      Color(0xFFDC8460),
      Color(0xFFCE6B43),
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
      Color(0xFFF3ECE3),
      Color(0xFFFAF7F2),
    ],
  );

  /// Bottom-sheet «Загрузить чек» — 150° диагональ, коралл почти плоский.
  /// Flutter не оперирует углом напрямую — задаём через alignments.
  static const LinearGradient receiptSheet = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [
      Color(0xFFDE8460),
      Color(0xFFD97757),
    ],
    stops: [0.0, 1.0],
  );

  /// Обложка курса — коралл (бывш. «синяя»).
  static const LinearGradient courseCoverBlue = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [
      Color(0xFFC2562E),
      Color(0xFFE0916B),
    ],
  );

  /// Обложка курса — коралл (бывш. «зелёная»).
  static const LinearGradient courseCoverGreen = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [
      Color(0xFFC2562E),
      Color(0xFFDB7F57),
    ],
  );

  /// Монета — радиальная.
  static const RadialGradient coin = RadialGradient(
    radius: 0.35,
    colors: [
      Color(0xFFFFE07A),
      Color(0xFFF4B73A),
      Color(0xFFB97F11),
    ],
    stops: [0.0, 0.6, 1.0],
  );
}
