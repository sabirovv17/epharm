import 'package:flutter/material.dart';

import '../../../../core/theme/app_colors.dart';
import '../../../../core/widgets/brand_icons.dart';

/// Bottom-sheet-заглушка для chip «Конкурсные» в ленте Home.
///
/// «Конкурсность» товара — это атрибут программы лояльности (какие позиции
/// участвуют в конкурсах/акциях с бонусом), а не каталога Medusa. Данных под
/// фильтр пока нет, поэтому chip присутствует для будущего раздела, но вместо
/// фильтрации показывает «скоро добавим».
Future<void> showContestsStubSheet(BuildContext context) {
  return showModalBottomSheet(
    context: context,
    backgroundColor: Colors.white,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
    ),
    builder: (_) => const _ContestsStubSheet(),
  );
}

class _ContestsStubSheet extends StatelessWidget {
  const _ContestsStubSheet();

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 36,
              height: 4,
              decoration: BoxDecoration(
                color: AppColors.ink300,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            const SizedBox(height: 24),
            const TrophyEmojiGlyph(size: 48),
            const SizedBox(height: 14),
            const Text(
              'Конкурсные товары',
              textAlign: TextAlign.center,
              style: TextStyle(
                fontFamily: 'Manrope',
                fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 20,
                fontWeight: FontWeight.w800,
                color: AppColors.ink900,
              ),
            ),
            const SizedBox(height: 8),
            const Text(
              'Добавим позже. Здесь появятся товары, участвующие в конкурсах '
              'и акциях — за их продажу начисляются повышенные бонусы.',
              textAlign: TextAlign.center,
              style: TextStyle(
                fontFamily: 'Manrope',
                fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 14,
                fontWeight: FontWeight.w700,
                color: AppColors.ink500,
                height: 1.4,
              ),
            ),
            const SizedBox(height: 20),
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                onPressed: () => Navigator.of(context).pop(),
                style: FilledButton.styleFrom(
                  backgroundColor: AppColors.brandGreen700,
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(14),
                  ),
                ),
                child: const Text(
                  'Понятно',
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 15,
                    fontWeight: FontWeight.w800,
                    color: Colors.white,
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
