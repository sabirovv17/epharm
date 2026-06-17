import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

/// Логотип Epharm — брендовый глиф «чек-штамп» (белый чек + синий ⊕, зелёный
/// контур). Единый знак для мобильного приложения и админ-панели (source-of-truth —
/// `admin-panel/frontend/src/layout/Logo.tsx`).
///
/// Текстовый wordmark «Epharm» убран по требованию (название бренда ещё не
/// финализировано) — показываем только знак. Глиф читается и на зелёном фоне
/// (белая заливка чека + синий ⊕), и на белом (зелёный контур/линии видны).
/// Размер задаётся [size] — глиф квадратный (viewBox 64×64).
class PharmaLogo extends StatelessWidget {
  const PharmaLogo({super.key, this.size = 44});

  final double size;

  // Идентичен admin Logo.tsx — один знак на обеих платформах.
  static const _svg = '''
<svg viewBox="0 0 64 64" xmlns="http://www.w3.org/2000/svg">
<path d="M16 12a3 3 0 0 1 3-3h26a3 3 0 0 1 3 3v40l-4-3-4 3-4-3-4 3-4-3-4 3-4-3-4 3-4-3-4 3z" fill="#FFFFFF" stroke="#16C97A" stroke-width="3" stroke-linejoin="round"/>
<rect x="22" y="32" width="20" height="2.5" rx="1.2" fill="#16C97A" opacity="0.55"/>
<rect x="22" y="38" width="14" height="2.5" rx="1.2" fill="#16C97A" opacity="0.55"/>
<rect x="22" y="44" width="17" height="2.5" rx="1.2" fill="#16C97A" opacity="0.55"/>
<g transform="translate(32 14)">
<circle r="11" fill="#2A2BE2"/>
<rect x="-1.5" y="-7" width="3" height="14" rx="0.8" fill="#FFFFFF"/>
<rect x="-7" y="-1.5" width="14" height="3" rx="0.8" fill="#FFFFFF"/>
</g>
</svg>''';

  @override
  Widget build(BuildContext context) {
    return SvgPicture.string(
      _svg,
      width: size,
      height: size,
      fit: BoxFit.contain,
    );
  }
}
