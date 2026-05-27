import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/app_colors.dart';
import '../../../../core/theme/app_radii.dart';
import '../../../../core/theme/app_shadows.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../../core/widgets/brand_icons.dart';

/// Заглушка для будущего раздела «Конкурсы». Открывается тапом по glass-pill
/// «Конкурсы» в _AuthedHeader.
///
/// В Этапе 5 здесь будет лидерборд, тек. место в рейтинге, призовой фонд,
/// список активных конкурсов и таймер до окончания.
class ContestsStubScreen extends StatelessWidget {
  const ContestsStubScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: systemUiOverlayStyleLight,
      child: Scaffold(
        backgroundColor: AppColors.paperCanvas,
        body: SafeArea(
          child: Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(8, 4, 8, 0),
                child: Row(
                  children: [
                    IconButton(
                      onPressed: () {
                        if (context.canPop()) {
                          context.pop();
                        } else {
                          context.go('/home');
                        }
                      },
                      icon: const Icon(
                        Icons.arrow_back_ios_new_rounded,
                        size: 22,
                        color: AppColors.ink900,
                      ),
                    ),
                    const Expanded(
                      child: Text(
                        'Конкурсы',
                        style: TextStyle(
                          fontFamily: 'Manrope',
                          fontFamilyFallback: ['Roboto', 'sans-serif'],
                          fontSize: 17,
                          fontWeight: FontWeight.w800,
                          color: AppColors.ink900,
                        ),
                      ),
                    ),
                    const SizedBox(width: 48),
                  ],
                ),
              ),
              Expanded(
                child: Center(
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 24),
                    child: Container(
                      padding: const EdgeInsets.all(24),
                      decoration: BoxDecoration(
                        color: Colors.white,
                        borderRadius: AppRadii.brXxl,
                        boxShadow: AppShadows.card,
                      ),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const TrophyEmojiGlyph(size: 56),
                          const SizedBox(height: 12),
                          const Text(
                            'Конкурсы скоро',
                            textAlign: TextAlign.center,
                            style: TextStyle(
                              fontFamily: 'Manrope',
                              fontFamilyFallback: ['Roboto', 'sans-serif'],
                              fontSize: 22,
                              fontWeight: FontWeight.w800,
                              color: AppColors.ink900,
                              height: 1.15,
                            ),
                          ),
                          const SizedBox(height: 8),
                          const Text(
                            'Здесь появятся розыгрыши призов, лидерборд '
                            'фармацевтов месяца и активные челленджи. '
                            'Загружайте чеки по конкурсным товарам — '
                            'каждый из них увеличит ваше место в рейтинге.',
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
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
