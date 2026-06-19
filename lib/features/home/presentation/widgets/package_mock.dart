import 'package:flutter/material.dart';

import '../../data/home_repository.dart';

/// Стилизованная коробка лекарства (150×170) — рендерится внутри hero strip
/// карточки продукта. Имитирует реальную фарм-упаковку: лицевая надпись,
/// MNN-подпись, водяной знак-волна, цветной ribbon слева, производитель снизу.
class PackageMock extends StatelessWidget {
  const PackageMock({super.key, required this.pkg, this.width = 150, this.height = 170});

  final ProductPackage pkg;
  final double width;
  final double height;

  @override
  Widget build(BuildContext context) {
    final accent = pkg.accent ?? Colors.white;
    final firstWord = pkg.label.split(' ').first;
    final restWords = pkg.label.contains(' ')
        ? pkg.label.split(' ').skip(1).join(' ')
        : null;

    return Container(
      width: width,
      height: height,
      clipBehavior: Clip.antiAlias,
      decoration: BoxDecoration(
        gradient: pkg.background,
        borderRadius: BorderRadius.circular(6),
        boxShadow: const [
          BoxShadow(
            color: Color(0x40000000),
            offset: Offset(0, 12),
            blurRadius: 24,
          ),
        ],
      ),
      child: Stack(
        children: [
          // License top-left
          Positioned(
            top: 6,
            left: 8,
            child: Text(
              'РК-ЛС-5№024187',
              style: TextStyle(
                fontFamily: 'Manrope',
                fontSize: 7,
                color: accent.withValues(alpha: 0.8),
                letterSpacing: 0.3,
              ),
            ),
          ),

          // Big label
          Positioned(
            top: 26,
            left: 8,
            right: 8,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  firstWord,
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontSize: 22,
                    fontWeight: FontWeight.w800,
                    color: accent,
                    height: 1.0,
                    letterSpacing: 0.5,
                  ),
                ),
                if (restWords != null) ...[
                  const SizedBox(height: 2),
                  Text(
                    restWords,
                    style: TextStyle(
                      fontFamily: 'Manrope',
                      fontSize: 11,
                      fontWeight: FontWeight.w800,
                      color: accent.withValues(alpha: 0.95),
                      height: 1.1,
                    ),
                  ),
                ],
                if (pkg.sub != null) ...[
                  const SizedBox(height: 6),
                  Container(
                    padding: const EdgeInsets.only(top: 4),
                    decoration: BoxDecoration(
                      border: Border(
                        top: BorderSide(
                          color: accent.withValues(alpha: 0.3),
                          width: 0.5,
                        ),
                      ),
                    ),
                    child: Text(
                      pkg.sub!,
                      style: TextStyle(
                        fontFamily: 'Manrope',
                        fontSize: 8.5,
                        fontWeight: FontWeight.w800,
                        color: accent.withValues(alpha: 0.85),
                        height: 1.15,
                      ),
                    ),
                  ),
                ],
              ],
            ),
          ),

          // Watermark wave
          Positioned(
            left: 0,
            right: 0,
            top: height * 0.55,
            child: Opacity(
              opacity: 0.25,
              child: CustomPaint(
                size: Size(width, 30),
                painter: _WavePainter(color: accent),
              ),
            ),
          ),

          // Maker footer (если есть)
          if (pkg.maker != null)
            Positioned(
              bottom: 6,
              left: 8,
              right: 8,
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Expanded(
                    child: Text(
                      pkg.maker!,
                      style: TextStyle(
                        fontFamily: 'Manrope',
                        fontSize: 9,
                        fontWeight: FontWeight.w800,
                        color: accent,
                        letterSpacing: 1.0,
                      ),
                    ),
                  ),
                  Text(
                    '1 таблетка,\nпокрытая пленочной\nоболочкой',
                    textAlign: TextAlign.right,
                    style: TextStyle(
                      fontFamily: 'Manrope',
                      fontSize: 6.5,
                      color: accent.withValues(alpha: 0.85),
                      height: 1.1,
                    ),
                  ),
                ],
              ),
            ),

          // Side color ribbon (4 цветных полоски)
          Positioned(
            top: 26,
            left: 0,
            child: Column(
              children: const [
                _RibbonStripe(color: Color(0xFFEE9A6F)),
                _RibbonStripe(color: Color(0xFF3A7CD9)),
                _RibbonStripe(color: Color(0xFFD94B4B)),
                _RibbonStripe(color: Color(0xFF6FB54B)),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _RibbonStripe extends StatelessWidget {
  const _RibbonStripe({required this.color});
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Container(width: 6, height: 28, color: color);
  }
}

class _WavePainter extends CustomPainter {
  _WavePainter({required this.color});
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = 3
      ..strokeCap = StrokeCap.round;
    final path = Path()
      ..moveTo(5, size.height / 2)
      ..cubicTo(
        size.width * 0.25, 0,
        size.width * 0.5, 0,
        size.width * 0.6, size.height / 2,
      )
      ..cubicTo(
        size.width * 0.85, size.height,
        size.width * 0.95, size.height,
        size.width - 5, size.height / 2,
      );
    canvas.drawPath(path, paint);
  }

  @override
  bool shouldRepaint(_) => false;
}
