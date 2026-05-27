import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/app_colors.dart';
import '../../../../core/theme/app_radii.dart';
import '../../../../core/theme/app_shadows.dart';
import '../../../../core/theme/app_theme.dart';

/// Заглушка для раздела «Обучение». Открывается из Profile menu (раньше был
/// отдельный таб в bottom-nav, перенесён в Profile вместе с поддержкой и FAQ).
///
/// В Этапе будущий — LMS-курсы, видео-инструкции, AI-экзамен (Whisper + Claude
/// + ElevenLabs), сертификаты.
class LearningStubScreen extends StatelessWidget {
  const LearningStubScreen({super.key});

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
                        'Обучение',
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
                          Container(
                            width: 72,
                            height: 72,
                            alignment: Alignment.center,
                            decoration: const BoxDecoration(
                              color: AppColors.brandGreen100,
                              shape: BoxShape.circle,
                            ),
                            child: const Icon(
                              Icons.school_rounded,
                              size: 40,
                              color: AppColors.brandGreen700,
                            ),
                          ),
                          const SizedBox(height: 12),
                          const Text(
                            'Обучение скоро',
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
                            'Здесь появятся курсы по продуктам, '
                            'видео-инструкции и AI-экзамен с голосовым '
                            'ассистентом. После сертификации откроется '
                            'доступ к расширенным конкурсам.',
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
