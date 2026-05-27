import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_gradients.dart';
import '../../../core/theme/app_radii.dart';
import '../../../core/theme/app_shadows.dart';
import '../../../core/theme/app_theme.dart';
import 'receipts_list_screen.dart';
import 'upload_prompt_sheet.dart';

/// Финальный экран после успешной отправки чека.
///
/// На зелёном grad-welcome фоне: синий диск с галкой, белая карточка
/// «Чек успешно отправлен», 2 CTA:
///   • «История и статусы» → возврат к корню (Home) + push на
///     [ReceiptsListScreen] (история со статусами).
///   • «Отправить ещё раз» → возврат к корню + сразу открыть
///     [showUploadPromptSheet] (камера / галерея / QR) для новой
///     загрузки.
class ReceiptSuccessScreen extends StatelessWidget {
  const ReceiptSuccessScreen({super.key});

  /// Pop to root (Home) + push ReceiptsListScreen. Microtask нужна чтобы
  /// гарантировать что popUntil завершился до push'а (Navigator API
  /// фактически синхронен в этом случае, но safety-net микротаски
  /// исключает edge cases в release-mode после оптимизации).
  void _toHistory(BuildContext context) {
    final nav = Navigator.of(context);
    nav.popUntil((r) => r.isFirst);
    nav.push(
      MaterialPageRoute(builder: (_) => const ReceiptsListScreen()),
    );
  }

  /// Pop to root + сразу открыть UploadPromptSheet — пользователь сразу
  /// продолжает грузить следующий чек (card в draft persists, см.
  /// `ReceiptDraftNotifier.reset(keepCard: true)`).
  void _again(BuildContext context) {
    Navigator.of(context).popUntil((r) => r.isFirst);
    showUploadPromptSheet(context);
  }

  @override
  Widget build(BuildContext context) {
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: systemUiOverlayStyleDark,
      child: Scaffold(
        body: Container(
          decoration: const BoxDecoration(gradient: AppGradients.welcome),
          child: SafeArea(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24),
              child: Column(
                children: [
                  const Spacer(),
                  // Blue check disc.
                  Container(
                    width: 120,
                    height: 120,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: AppColors.brandBlue600,
                      shape: BoxShape.circle,
                      boxShadow: AppShadows.elevated,
                      border: Border.all(
                        color: Colors.white.withValues(alpha: 0.25),
                        width: 8,
                      ),
                    ),
                    child: const Icon(
                      Icons.check_rounded,
                      size: 64,
                      color: Colors.white,
                    ),
                  ),
                  const SizedBox(height: 32),
                  // White info card.
                  Container(
                    padding: const EdgeInsets.all(20),
                    decoration: BoxDecoration(
                      color: Colors.white,
                      borderRadius: AppRadii.brXxl,
                      boxShadow: AppShadows.elevated,
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        const Text(
                          'Чек успешно отправлен',
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            fontFamily: 'Manrope',
                            fontFamilyFallback: ['Roboto', 'sans-serif'],
                            fontSize: 22,
                            fontWeight: FontWeight.w800,
                            color: AppColors.brandGreen600,
                            height: 1.2,
                          ),
                        ),
                        const SizedBox(height: 8),
                        const Text(
                          'Бонусы можно отследить в разделе «История и статусы». '
                          'На карту они придут по графику выплат.',
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
                        const SizedBox(height: 16),
                        _Cta(
                          label: 'История и статусы',
                          fg: AppColors.brandGreen600,
                          bg: AppColors.brandGreen100,
                          onTap: () => _toHistory(context),
                        ),
                        const SizedBox(height: 8),
                        _Cta(
                          label: 'Отправить ещё раз',
                          fg: AppColors.ink900,
                          bg: AppColors.paperInput,
                          onTap: () => _again(context),
                        ),
                      ],
                    ),
                  ),
                  const Spacer(),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _Cta extends StatelessWidget {
  const _Cta({
    required this.label,
    required this.fg,
    required this.bg,
    required this.onTap,
  });
  final String label;
  final Color fg;
  final Color bg;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(99),
        child: Container(
          height: 50,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: bg,
            borderRadius: BorderRadius.circular(99),
          ),
          child: Text(
            label,
            style: TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: const ['Roboto', 'sans-serif'],
              fontSize: 16,
              fontWeight: FontWeight.w800,
              color: fg,
            ),
          ),
        ),
      ),
    );
  }
}
