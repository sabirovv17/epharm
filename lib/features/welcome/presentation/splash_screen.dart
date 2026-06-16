import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/app_gradients.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/widgets/pharma_logo.dart';
import '../application/app_start_controller.dart';

/// Экран-решатель на старте приложения: пока идёт восстановление сессии и чтение
/// флага онбординга — показываем брендовый сплеш, затем уходим на Home или Welcome.
///
/// Так залогиненный пользователь НИКОГДА не видит онбординг: решение принимается до
/// показа Welcome и опирается на персистнутый токен, без гонки с фоном (фикс бага).
class SplashScreen extends ConsumerWidget {
  const SplashScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Навигация — как side-effect по готовности решения. context.go в listen
    // безопасен (после кадра); guard на mounted — экран мог уйти.
    ref.listen(appStartProvider, (_, next) {
      next.whenData((dest) {
        if (!context.mounted) return;
        context.go(dest == StartDestination.home ? '/home' : '/welcome');
      });
    });

    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: systemUiOverlayStyleDark,
      child: Scaffold(
        body: Container(
          decoration: const BoxDecoration(gradient: AppGradients.welcome),
          child: const SafeArea(
            child: Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  PharmaWordmark(),
                  SizedBox(height: 24),
                  SizedBox(
                    width: 22,
                    height: 22,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
