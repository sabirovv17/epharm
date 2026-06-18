import 'package:flutter/material.dart';

import '../../../../core/theme/app_colors.dart';

/// Рендерит строку с inline-маркерами:
///   • `[link]…[/link]` → акцентный коралл-текст (#E0916B, w600, без подчёркивания)
///   • `**…**` → жирный фрагмент (w700)
///
/// См. `_reference/profile-pages/faq.jsx → FaqText`.
class InlineMarkerText extends StatelessWidget {
  const InlineMarkerText({
    super.key,
    required this.text,
    required this.baseStyle,
    this.textAlign,
  });

  final String text;
  final TextStyle baseStyle;
  final TextAlign? textAlign;

  @override
  Widget build(BuildContext context) {
    final spans = _parse(text, baseStyle);
    return Text.rich(
      TextSpan(children: spans),
      textAlign: textAlign,
    );
  }

  static List<TextSpan> _parse(String input, TextStyle base) {
    // Pattern matches both [link]…[/link] and **…**
    final re = RegExp(r'(\[link\].*?\[/link\]|\*\*.*?\*\*)');
    final spans = <TextSpan>[];
    int last = 0;
    for (final m in re.allMatches(input)) {
      if (m.start > last) {
        spans.add(TextSpan(text: input.substring(last, m.start), style: base));
      }
      final token = m.group(0)!;
      if (token.startsWith('[link]')) {
        spans.add(TextSpan(
          text: token.substring(6, token.length - 7),
          style: base.copyWith(
            color: const Color(0xFFE0916B),
            fontWeight: FontWeight.w700,
          ),
        ));
      } else {
        spans.add(TextSpan(
          text: token.substring(2, token.length - 2),
          style: base.copyWith(fontWeight: FontWeight.w800),
        ));
      }
      last = m.end;
    }
    if (last < input.length) {
      spans.add(TextSpan(text: input.substring(last), style: base));
    }
    return spans;
  }
}

/// Запасной цвет для inline-link (если нужно использовать снаружи).
const inlineLinkAccent = Color(0xFFE0916B);

/// Bullet glyph helper — 5×5 круг ink/900.
class BulletDot extends StatelessWidget {
  const BulletDot({super.key});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 5,
      height: 5,
      decoration: const BoxDecoration(
        color: AppColors.ink900,
        shape: BoxShape.circle,
      ),
    );
  }
}
