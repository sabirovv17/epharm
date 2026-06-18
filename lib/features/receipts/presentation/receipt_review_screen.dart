import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_radii.dart';
import '../../../core/theme/app_shadows.dart';
import '../../../core/theme/app_spacing.dart';
import '../../../core/theme/app_theme.dart';
import '../application/receipts_controller.dart';
import 'card_sheet.dart';
import 'success_screen.dart';

/// Экран после съёмки чека (ДОП.8): единственный пользовательский пункт — карта
/// для зачисления бонусов. Акции и аптеку система определяет САМА (аптека из профиля,
/// товары — POSM-замена на кассе / в будущем OCR). Ручного выбора акции/аптеки больше нет.
///
/// Явный UX: чек уже принят (banner «Чек загружен»), фармацевт указывает только карту
/// (обычно уже сохранена — ДОП.7). Подтверждение и сумму даёт сверка на сервере (лог кассы + Excel).
/// Флаг «идёт отправка чека» — защита от двойной отправки + блокировка CTA.
final _receiptSubmittingProvider = StateProvider.autoDispose<bool>((ref) => false);

class ReceiptReviewScreen extends ConsumerWidget {
  const ReceiptReviewScreen({super.key});

  Future<void> _submit(BuildContext context, WidgetRef ref) async {
    final draft = ref.read(receiptDraftProvider);
    if (!draft.isComplete) return;
    // Guard от двойного тапа: если отправка уже идёт — игнорируем.
    if (ref.read(_receiptSubmittingProvider)) return;
    ref.read(_receiptSubmittingProvider.notifier).state = true;

    final repo = ref.read(receiptRepositoryProvider);
    try {
      // Репозиторий делает multipart-upload только фото (ДОП.8). Аптеку backend берёт из
      // профиля, товары/акции матчит сам; сумму подтверждает сверка на сервере.
      // promoIds — тихая подсказка о заявленной акции (кнопка «Получить бонус» в карточке).
      await repo.submitReceipt(
        title: 'Чек',
        photoPath: draft.photoPath,
        promoIds: draft.promoIds,
      );
    } catch (_) {
      // Сеть/сервер недоступны — показываем ошибку, draft не теряем (можно повторить).
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Не удалось отправить чек. Проверьте соединение и попробуйте снова.')),
        );
      }
      return;
    } finally {
      // finally выполняется до навигации — виджет ещё на экране, ref валиден.
      ref.read(_receiptSubmittingProvider.notifier).state = false;
    }

    // После submit обнуляем photoPath, но card сохраняем (см. ReceiptDraftNotifier.reset).
    ref.read(receiptDraftProvider.notifier).reset();

    if (!context.mounted) return;
    Navigator.of(context).pushReplacement(
      MaterialPageRoute(builder: (_) => const ReceiptSuccessScreen()),
    );
  }

  String _maskCard(String card) {
    final d = card.replaceAll(' ', '');
    if (d.length < 16) return card;
    return '•••• •••• •••• ${d.substring(12)}';
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final draft = ref.watch(receiptDraftProvider);
    final submitting = ref.watch(_receiptSubmittingProvider);
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: systemUiOverlayStyleDark,
      child: Scaffold(
        backgroundColor: Colors.white,
        body: SafeArea(
          bottom: false,
          child: Column(
            children: [
              // Header
              SizedBox(
                height: 48,
                child: Stack(
                  children: [
                    Positioned(
                      left: AppSpacing.screenEdge - 8,
                      top: 0,
                      bottom: 0,
                      child: IconButton(
                        icon: const Icon(Icons.arrow_back_rounded,
                            size: 24, color: AppColors.ink900),
                        onPressed: () => Navigator.of(context).pop(),
                      ),
                    ),
                    const Center(
                      child: Text(
                        'Чек',
                        style: TextStyle(
                          fontFamily: 'Manrope',
                          fontFamilyFallback: ['Roboto', 'sans-serif'],
                          fontSize: 18,
                          fontWeight: FontWeight.w800,
                          color: AppColors.ink900,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              Expanded(
                child: ListView(
                  padding: const EdgeInsets.fromLTRB(
                    AppSpacing.screenEdge,
                    8,
                    AppSpacing.screenEdge,
                    24,
                  ),
                  children: [
                    SizedBox(
                      // Высота равна _ReceiptThumb (88) — баннер растягивается
                      // под него. Без явной height Row+stretch внутри ListView
                      // получает infinite cross-axis → RenderFlex assertion.
                      height: 88,
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          const _ReceiptThumb(),
                          const SizedBox(width: 12),
                          Expanded(child: _ReceiptUploadedBanner()),
                        ],
                      ),
                    ),
                    const SizedBox(height: 24),
                    // ДОП.8: акции и аптека определяются автоматически — без ручного выбора.
                    const _AutoDetectNote(),
                    const SizedBox(height: 16),
                    _ChecklistRow(
                      filled: draft.hasCard,
                      iconEmpty: Icons.credit_card_outlined,
                      labelEmpty: 'Добавить номер карты',
                      sublineEmpty: 'Для начисления бонусов',
                      labelFilled: draft.hasCard
                          ? _maskCard(draft.card!)
                          : '',
                      sublineFilled: 'Сохранена в профиле',
                      onTap: () => showCardSheet(context),
                    ),
                    const SizedBox(height: 24),
                    const _PrivacyNote(),
                  ],
                ),
              ),
              // Sticky CTA
              Container(
                padding: const EdgeInsets.fromLTRB(
                    AppSpacing.screenEdge, 12, AppSpacing.screenEdge, 24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    _ContinueCta(
                      ready: draft.isComplete && !submitting,
                      busy: submitting,
                      onTap: () => _submit(context, ref),
                    ),
                    if (!draft.isComplete) ...[
                      const SizedBox(height: 8),
                      const Text(
                        'Добавьте карту, чтобы продолжить',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          fontFamily: 'Manrope',
                          fontFamilyFallback: ['Roboto', 'sans-serif'],
                          fontSize: 12,
                          fontWeight: FontWeight.w800,
                          color: AppColors.ink400,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ReceiptUploadedBanner extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: AppColors.brandGreen100,
        borderRadius: AppRadii.brXl,
        border: Border.all(
          color: const Color(0xFFF0C8B4),
          width: 1,
        ),
      ),
      child: Row(
        children: [
          // composite glyph: receipt + check disc
          SizedBox(
            width: 36,
            height: 36,
            child: Stack(
              children: [
                Positioned.fill(
                  child: Icon(
                    Icons.receipt_long_outlined,
                    size: 32,
                    color: AppColors.brandGreen700.withValues(alpha: 0.85),
                  ),
                ),
                Positioned(
                  right: 0,
                  bottom: 0,
                  child: Container(
                    width: 16,
                    height: 16,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: AppColors.brandGreen600,
                      shape: BoxShape.circle,
                      border: Border.all(
                          color: AppColors.brandGreen100, width: 1.5),
                    ),
                    child: const Icon(Icons.check_rounded,
                        size: 11, color: Colors.white),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  'Чек загружен',
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: const ['Roboto', 'sans-serif'],
                    fontSize: 15,
                    fontWeight: FontWeight.w800,
                    color: AppColors.brandGreen700,
                    height: 1.15,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  'Акции и аптека определятся автоматически',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: const ['Roboto', 'sans-serif'],
                    fontSize: 12,
                    fontWeight: FontWeight.w800,
                    color: AppColors.brandGreen700.withValues(alpha: 0.7),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// Thumbnail чека. Если в draft есть реальный photoPath — показываем его
/// через `Image.file` (cover-fit). Иначе — paper-skeleton с зеброй
/// (placeholder, как в `review.jsx`).
class _ReceiptThumb extends ConsumerWidget {
  const _ReceiptThumb();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final photoPath = ref.watch(
      receiptDraftProvider.select((d) => d.photoPath),
    );

    return Container(
      width: 88,
      height: 88,
      padding: const EdgeInsets.all(6),
      decoration: BoxDecoration(
        color: AppColors.paperInput,
        borderRadius: AppRadii.brXl,
        boxShadow: AppShadows.card,
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(10),
        child: (photoPath != null && photoPath.isNotEmpty)
            ? Image.file(
                File(photoPath),
                fit: BoxFit.cover,
                width: double.infinity,
                height: double.infinity,
                errorBuilder: (_, __, ___) => const _ReceiptThumbSkeleton(),
              )
            : const _ReceiptThumbSkeleton(),
      ),
    );
  }
}

/// Fallback-скелетон для thumb (когда фото ещё не выбрано или не открылось).
class _ReceiptThumbSkeleton extends StatelessWidget {
  const _ReceiptThumbSkeleton();

  @override
  Widget build(BuildContext context) {
    return Container(
      color: Colors.white,
      padding: const EdgeInsets.all(6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Container(
            height: 3,
            decoration: BoxDecoration(
              color: AppColors.ink300.withValues(alpha: 0.6),
              borderRadius: BorderRadius.circular(2),
            ),
          ),
          const SizedBox(height: 2),
          Container(
            height: 3,
            decoration: BoxDecoration(
              color: AppColors.ink300.withValues(alpha: 0.4),
              borderRadius: BorderRadius.circular(2),
            ),
          ),
          Expanded(
            child: Center(
              child: Container(
                width: 28,
                height: 28,
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(2),
                  gradient: const LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [AppColors.ink900, Colors.white],
                    stops: [0.5, 0.5],
                  ),
                ),
              ),
            ),
          ),
          Container(
            height: 3,
            decoration: BoxDecoration(
              color: AppColors.ink300.withValues(alpha: 0.4),
              borderRadius: BorderRadius.circular(2),
            ),
          ),
        ],
      ),
    );
  }
}

class _ChecklistRow extends StatelessWidget {
  const _ChecklistRow({
    required this.filled,
    required this.iconEmpty,
    required this.labelEmpty,
    required this.sublineEmpty,
    required this.labelFilled,
    required this.sublineFilled,
    required this.onTap,
  });

  final bool filled;
  final IconData iconEmpty;
  final String labelEmpty;
  final String sublineEmpty;
  final String labelFilled;
  final String sublineFilled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: filled ? AppColors.brandGreen100 : Colors.white,
        borderRadius: AppRadii.brXl,
        boxShadow: AppShadows.card,
        border: Border.all(
          color: filled ? const Color(0xFFF0C8B4) : Colors.transparent,
          width: 1,
        ),
      ),
      clipBehavior: Clip.antiAlias,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Row(
              children: [
                Container(
                  width: 48,
                  height: 48,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: filled
                        ? AppColors.brandGreen600
                        : AppColors.brandGreen100,
                    borderRadius: AppRadii.brXl,
                    boxShadow: filled ? AppShadows.fab : null,
                  ),
                  child: Icon(
                    filled ? Icons.check_rounded : iconEmpty,
                    size: 22,
                    color:
                        filled ? Colors.white : AppColors.brandGreen700,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        filled ? labelFilled : labelEmpty,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontFamily: 'Manrope',
                          fontFamilyFallback: ['Roboto', 'sans-serif'],
                          fontSize: 17,
                          fontWeight: FontWeight.w800,
                          color: AppColors.ink900,
                          height: 1.2,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        filled ? sublineFilled : sublineEmpty,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontFamily: 'Manrope',
                          fontFamilyFallback: ['Roboto', 'sans-serif'],
                          fontSize: 12,
                          fontWeight: FontWeight.w800,
                          color: AppColors.ink500,
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 8),
                const Icon(Icons.chevron_right_rounded,
                    size: 22, color: AppColors.ink400),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Пояснение (ДОП.8): акции и аптека больше не выбираются вручную — система определяет
/// их сама из чека (POSM-замена на кассе / профиль фармацевта).
class _AutoDetectNote extends StatelessWidget {
  const _AutoDetectNote();

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: AppColors.brandGreen100,
        borderRadius: AppRadii.brXl,
        border: Border.all(color: const Color(0xFFF0C8B4), width: 1),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Padding(
            padding: EdgeInsets.only(top: 1),
            child: Icon(Icons.auto_awesome_rounded,
                size: 18, color: AppColors.brandGreen700),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                const Text(
                  'Акции и аптека — автоматически',
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 14,
                    fontWeight: FontWeight.w800,
                    color: AppColors.brandGreen700,
                    height: 1.2,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  'Мы сами определим акции и аптеку по вашему чеку — выбирать вручную не нужно.',
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: const ['Roboto', 'sans-serif'],
                    fontSize: 12,
                    fontWeight: FontWeight.w700,
                    color: AppColors.brandGreen700.withValues(alpha: 0.75),
                    height: 1.35,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _PrivacyNote extends StatelessWidget {
  const _PrivacyNote();

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: AppColors.paperInput.withValues(alpha: 0.7),
        borderRadius: AppRadii.brXl,
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Padding(
            padding: EdgeInsets.only(top: 2),
            child: Icon(Icons.lock_outline,
                size: 18, color: AppColors.ink400),
          ),
          const SizedBox(width: 12),
          const Expanded(
            child: Text(
              'Данные защищены — мы используем их только для зачисления выплат.',
              style: TextStyle(
                fontFamily: 'Manrope',
                fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 12,
                fontWeight: FontWeight.w700,
                color: AppColors.ink500,
                height: 1.4,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ContinueCta extends StatelessWidget {
  const _ContinueCta({required this.ready, required this.onTap, this.busy = false});
  final bool ready;
  final bool busy;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Opacity(
      opacity: ready ? 1.0 : 0.5,
      child: Material(
        color: AppColors.brandGreen600,
        borderRadius: AppRadii.brFull,
        child: InkWell(
          onTap: ready ? onTap : null,
          borderRadius: AppRadii.brFull,
          child: Container(
            height: 60,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              borderRadius: AppRadii.brFull,
              boxShadow: ready ? AppShadows.fab : null,
            ),
            child: busy
                ? const SizedBox(
                    width: 24,
                    height: 24,
                    child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white),
                  )
                : const Text(
                    'Продолжить',
                    style: TextStyle(
                      fontFamily: 'Manrope',
                      fontFamilyFallback: ['Roboto', 'sans-serif'],
                      fontSize: 18,
                      fontWeight: FontWeight.w800,
                      color: Colors.white,
                    ),
                  ),
          ),
        ),
      ),
    );
  }
}
