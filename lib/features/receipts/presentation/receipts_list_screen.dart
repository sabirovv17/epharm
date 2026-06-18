import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_gradients.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/theme/app_radii.dart';
import '../../../core/theme/app_shadows.dart';
import '../../../core/theme/app_spacing.dart';
import '../../../core/widgets/pharma_logo.dart';
import '../../auth/application/auth_controller.dart';
import '../application/receipts_controller.dart';
import '../data/receipt_repository.dart';
import 'receipt_detail_sheet.dart';
import 'upload_prompt_sheet.dart';
import 'widgets/receipt_row.dart';

/// Экран истории чеков (содержимое таба «Чек» для авторизованного пользователя).
///
/// Структура (см. диаграмму «flow доказательства переключения»):
///   • Зелёный header с PharmaWordmark + заголовок «Мои чеки» + счётчики статусов.
///   • Список [ReceiptRow] со всеми чеками.
///   • Кнопка «Загрузить чек» внизу, прижатая к safe-area (FAB-style).
///
/// Камера и upload-flow подключатся в следующем этапе — сейчас onTap кнопки
/// показывает заглушку через [showAboutDialog].
class ReceiptsListScreen extends ConsumerWidget {
  const ReceiptsListScreen({super.key, this.onUploadTap});

  /// Колбэк на CTA «Загрузить чек». Сюда подключим UploadPromptSheet
  /// в следующем этапе.
  final VoidCallback? onUploadTap;

  /// Pull-to-refresh handler: haptic medium-impact (резкая короткая вибрация,
  /// как у обновления-сторис) + invalidate receiptListProvider (заставляет
  /// StreamProvider пересоздать поток и перечитать данные из repo).
  Future<void> _onRefresh(WidgetRef ref) async {
    HapticFeedback.mediumImpact();
    // Invalidate чтобы StreamProvider стартанул заново. Затем дожидаемся
    // первого emit чтобы спиннер RefreshIndicator'а отыграл visual feedback
    // полностью (без этого spinner моргает почти мгновенно — UX слабый).
    ref.invalidate(receiptListProvider);
    await ref.read(receiptListProvider.future);
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final receiptsAsync = ref.watch(receiptListProvider);

    // Scaffold обязателен — без него (когда экран был tab-body) DefaultTextStyle
    // приходил от родительского Scaffold у `_HomeScreenState`. После
    // превращения в push-route своего родителя нет, и Text-виджеты получают
    // дефолтный «yellow + underline» debug-стиль (видно как жёлтые подчёркнутые
    // надписи в header'е). Scaffold с paperCanvas закрывает этот баг.
    return Scaffold(
      backgroundColor: AppColors.paperCanvas,
      body: Stack(
        children: [
          Positioned.fill(
            child: Column(
              children: [
                _Header(),
                Expanded(
                  child: RefreshIndicator(
                    onRefresh: () => _onRefresh(ref),
                    color: AppColors.brandGreen600,
                    backgroundColor: Colors.white,
                    edgeOffset: 8,
                    displacement: 32,
                    child: receiptsAsync.when(
                      loading: () => const _LoadingList(),
                      error: (e, _) => _buildError(ref, e),
                      data: (list) {
                        if (list.isEmpty) {
                          return const _EmptyStateScrollable();
                        }
                        // Список в profile-стиле:
                        //   • H1 section title «Мои чеки» (как «Помощь» в Profile)
                        //   • ниже карточки ReceiptRow (white + shadow/card,
                        //     padding 12 между ними как в Profile menu)
                        return ListView.builder(
                          physics:
                              const AlwaysScrollableScrollPhysics(),
                          padding: const EdgeInsets.fromLTRB(
                            AppSpacing.screenEdge,
                            AppSpacing.s24,
                            AppSpacing.screenEdge,
                            120, // место под нижнюю CTA
                          ),
                          // +1 элемент = section title в начале.
                          itemCount: list.length + 1,
                          itemBuilder: (_, i) {
                            if (i == 0) {
                              return const Padding(
                                padding: EdgeInsets.only(bottom: 12),
                                child: Text(
                                  'Мои чеки',
                                  style: TextStyle(
                                    fontFamily: 'Manrope',
                                    fontFamilyFallback: [
                                      'Roboto',
                                      'sans-serif'
                                    ],
                                    // H1 26/800 ink/900 — тот же стиль что
                                    // «Помощь» / «О приложении» в Profile.
                                    fontSize: 26,
                                    fontWeight: FontWeight.w800,
                                    color: AppColors.ink900,
                                    height: 1.1,
                                  ),
                                ),
                              );
                            }
                            final r = list[i - 1];
                            return Padding(
                              padding: const EdgeInsets.only(bottom: 12),
                              child: ReceiptRow(
                                receipt: r,
                                onTap: () => showReceiptDetailSheet(context, r),
                              ),
                            );
                          },
                        );
                      },
                    ),
                  ),
                ),
              ],
            ),
          ),
          // CTA «Загрузить чек» прижатая к нижней safe-area.
          Positioned(
            left: AppSpacing.screenEdge,
            right: AppSpacing.screenEdge,
            bottom: 16,
            child: _UploadCta(
              onTap: onUploadTap ?? () => showUploadPromptSheet(context),
            ),
          ),
        ],
      ),
    );
  }

  /// Выбор экрана ошибки. Истёкшую сессию (401/UNAUTHORIZED) и запрет доступа
  /// (403/FORBIDDEN) показываем отдельным дружелюбным экраном с кнопкой входа —
  /// чтобы приложение само восстанавливалось перелогином по OTP (см. фикс
  /// ротации app.jwt.secret), а не пугало пользователя «Ошибкой сервера».
  Widget _buildError(WidgetRef ref, Object error) {
    if (error is ApiException) {
      final isUnauthorized =
          error.statusCode == 401 || error.code == 'UNAUTHORIZED';
      final isForbidden =
          error.statusCode == 403 || error.code == 'FORBIDDEN';
      if (isUnauthorized || isForbidden) {
        return _SessionExpired(forbidden: isForbidden);
      }
      // Прочие ошибки бэка — показываем его сообщение как есть.
      return _ErrorList(
        message: error.message,
        onRetry: () => ref.invalidate(receiptListProvider),
      );
    }
    // Не-ApiException (парсинг/неизвестное) — обобщённое сообщение о сети.
    return _ErrorList(
      message: 'Не удалось загрузить чеки. Проверьте соединение.',
      onRetry: () => ref.invalidate(receiptListProvider),
    );
  }
}

/// Скроллящийся пустой контейнер на время loading — чтобы RefreshIndicator
/// был доступен (нужен AlwaysScrollableScrollPhysics).
class _LoadingList extends StatelessWidget {
  const _LoadingList();
  @override
  Widget build(BuildContext context) {
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      children: const [
        SizedBox(height: 120),
        Center(child: CircularProgressIndicator()),
      ],
    );
  }
}

class _ErrorList extends StatelessWidget {
  const _ErrorList({required this.message, this.onRetry});
  final String message;
  final VoidCallback? onRetry;
  @override
  Widget build(BuildContext context) {
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      children: [
        const SizedBox(height: 100),
        Center(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: AppSpacing.s32),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.cloud_off_rounded, size: 48, color: AppColors.ink400),
                const SizedBox(height: 12),
                Text(
                  message,
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 14,
                    fontWeight: FontWeight.w700,
                    color: AppColors.ink500,
                    height: 1.4,
                  ),
                ),
                if (onRetry != null) ...[
                  const SizedBox(height: 16),
                  TextButton(
                    onPressed: onRetry,
                    child: const Text(
                      'Повторить',
                      style: TextStyle(
                        fontFamily: 'Manrope',
                        fontFamilyFallback: ['Roboto', 'sans-serif'],
                        fontSize: 15,
                        fontWeight: FontWeight.w800,
                        color: AppColors.brandGreen600,
                      ),
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
      ],
    );
  }
}

/// Экран «сессия истекла / доступ запрещён». Показывается когда история чеков
/// упала на 401 (UNAUTHORIZED) или 403 (FORBIDDEN). Вместо пугающей «Ошибки
/// сервера» — дружелюбное объяснение + кнопка входа: чистим сессию через
/// [CurrentUserNotifier.logout] (он же затирает токены) и уводим на /welcome.
/// Так приложение само-восстанавливается перелогином по OTP.
class _SessionExpired extends ConsumerWidget {
  const _SessionExpired({this.forbidden = false});

  /// 403/FORBIDDEN — текст про запрет доступа; иначе — про истёкшую сессию.
  final bool forbidden;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final title = forbidden ? 'Доступ запрещён' : 'Сессия истекла';
    final subtitle = forbidden
        ? 'Недостаточно прав для просмотра чеков. Войдите под своим аккаунтом.'
        : 'Похоже, вы давно не заходили. Войдите снова, чтобы увидеть свои чеки.';

    void onLogin() {
      // Захватываем router ДО смены состояния — logout перерисует дерево.
      final router = GoRouter.of(context);
      ref.read(currentUserProvider.notifier).logout();
      router.go('/welcome');
    }

    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      children: [
        const SizedBox(height: 100),
        Center(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: AppSpacing.s32),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  forbidden
                      ? Icons.lock_outline_rounded
                      : Icons.lock_clock_outlined,
                  size: 48,
                  color: AppColors.ink400,
                ),
                const SizedBox(height: 12),
                Text(
                  title,
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 18,
                    fontWeight: FontWeight.w800,
                    color: AppColors.ink900,
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  subtitle,
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 14,
                    fontWeight: FontWeight.w700,
                    color: AppColors.ink500,
                    height: 1.4,
                  ),
                ),
                const SizedBox(height: 20),
                // Primary-кнопка входа в стиле CTA (brandGreen600, pill).
                Material(
                  color: Colors.transparent,
                  child: InkWell(
                    onTap: onLogin,
                    borderRadius: BorderRadius.circular(99),
                    child: Container(
                      height: 52,
                      alignment: Alignment.center,
                      padding: const EdgeInsets.symmetric(horizontal: 32),
                      decoration: BoxDecoration(
                        color: AppColors.brandGreen600,
                        borderRadius: BorderRadius.circular(99),
                        boxShadow: AppShadows.fab,
                      ),
                      child: const Text(
                        'Войти снова',
                        style: TextStyle(
                          fontFamily: 'Manrope',
                          fontFamilyFallback: ['Roboto', 'sans-serif'],
                          fontSize: 16,
                          fontWeight: FontWeight.w800,
                          color: Colors.white,
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

/// Empty-state, но в ListView (чтобы pull-to-refresh работал).
class _EmptyStateScrollable extends StatelessWidget {
  const _EmptyStateScrollable();
  @override
  Widget build(BuildContext context) {
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      children: const [
        SizedBox(height: 60),
        _EmptyState(),
      ],
    );
  }
}

class _Header extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final receiptsAsync = ref.watch(receiptListProvider);
    final counts = receiptsAsync.maybeWhen(
      data: (list) {
        var confirmed = 0;
        var inReview = 0;
        var awaiting = 0;
        for (final r in list) {
          switch (r.status) {
            case ReceiptStatus.confirmed:
              confirmed++;
            case ReceiptStatus.inReview:
              inReview++;
            case ReceiptStatus.awaitingReceipt:
              awaiting++;
            case ReceiptStatus.rejected:
              break;
          }
        }
        return (confirmed, inReview, awaiting);
      },
      orElse: () => (0, 0, 0),
    );

    return Container(
      decoration: const BoxDecoration(
        gradient: AppGradients.header,
        borderRadius: BorderRadius.only(
          bottomLeft: Radius.circular(AppRadii.xxl),
          bottomRight: Radius.circular(AppRadii.xxl),
        ),
        boxShadow: [
          BoxShadow(
            color: Color(0x1F221C16),
            offset: Offset(0, 4),
            blurRadius: 16,
          ),
        ],
      ),
      child: SafeArea(
        bottom: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(
            AppSpacing.screenEdge,
            8,
            AppSpacing.screenEdge,
            20,
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Back-кнопка + wordmark. «Мои чеки» перенесён вниз на canvas
              // как H1 section title (см. ListView item index 0), чтобы
              // экран следовал той же структуре что и Profile («Помощь» /
               // «О приложении» — section titles на canvas, не в header'е).
              Row(
                children: [
                  _HeaderBackButton(
                    onTap: () => Navigator.of(context).pop(),
                  ),
                  const SizedBox(width: 8),
                  const PharmaLogo(size: 44),
                ],
              ),
              const SizedBox(height: AppSpacing.s16),
              // Три stat-чипа: подтверждено / на проверке / ожидает чека.
              // Стиль GlassPill (см. § 6.1 design-tokens), такой же как у
              // pills в _AuthedHeader Profile и в BalanceCard.
              Row(
                children: [
                  _StatChip(
                    label: 'Подтверждено',
                    value: counts.$1,
                  ),
                  const SizedBox(width: 8),
                  _StatChip(
                    label: 'На проверке',
                    value: counts.$2,
                  ),
                  const SizedBox(width: 8),
                  _StatChip(
                    label: 'Ожидает',
                    value: counts.$3,
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Stat-chip со счётчиком одного статуса. Приведён к стилю GlassPill
/// (используется на Profile header'е): заливка **`brandGreen500 @ 55%`** +
/// 1 px белая граница 40 %, без shadow — на gradient-header glow смотрелся
/// бы перепеком. Шрифт счётчика 22/800 (раньше 18) — крупный, как primary
/// metric. Label 12/800 white @ 92 %.
class _StatChip extends StatelessWidget {
  const _StatChip({required this.label, required this.value});
  final String label;
  final int value;

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: Container(
        height: 64,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        decoration: BoxDecoration(
          color: AppColors.brandGreen500.withValues(alpha: 0.55),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(
            color: Colors.white.withValues(alpha: 0.40),
            width: 1,
          ),
        ),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '$value',
              style: const TextStyle(
                fontFamily: 'Manrope',
                fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 22,
                fontWeight: FontWeight.w800,
                color: Colors.white,
                height: 1.05,
              ),
            ),
            const SizedBox(height: 2),
            Text(
              label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontFamily: 'Manrope',
                fontFamilyFallback: const ['Roboto', 'sans-serif'],
                fontSize: 12,
                fontWeight: FontWeight.w800,
                color: Colors.white.withValues(alpha: 0.92),
                height: 1.2,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _UploadCta extends StatelessWidget {
  const _UploadCta({this.onTap});
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(99),
        child: Container(
          height: 60,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: AppColors.brandGreen600,
            borderRadius: BorderRadius.circular(99),
            boxShadow: AppShadows.fab,
          ),
          child: const Row(
            mainAxisAlignment: MainAxisAlignment.center,
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.photo_camera_outlined, color: Colors.white, size: 22),
              SizedBox(width: 10),
              Text(
                'Загрузить чек',
                style: TextStyle(
                  fontFamily: 'Manrope',
                  fontFamilyFallback: ['Roboto', 'sans-serif'],
                  fontSize: 17,
                  fontWeight: FontWeight.w800,
                  color: Colors.white,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _EmptyState extends StatelessWidget {
  const _EmptyState();

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.s32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.receipt_long_outlined,
              size: 56,
              color: AppColors.ink400,
            ),
            const SizedBox(height: 12),
            const Text(
              'Здесь будут ваши чеки',
              textAlign: TextAlign.center,
              style: TextStyle(
                fontFamily: 'Manrope',
                fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 18,
                fontWeight: FontWeight.w800,
                color: AppColors.ink900,
              ),
            ),
            const SizedBox(height: 6),
            const Text(
              'Сфотографируйте фискальный чек — '
              'после проверки бонус зачислится на ваш баланс.',
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
    );
  }
}

/// Круглая back-кнопка для зелёного header'а ReceiptsListScreen. 36×36
/// white-12 surface + 1 px white-25 border, чтобы читалась на gradient'е.
class _HeaderBackButton extends StatelessWidget {
  const _HeaderBackButton({required this.onTap});
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      shape: const CircleBorder(),
      child: InkWell(
        onTap: onTap,
        customBorder: const CircleBorder(),
        child: Container(
          width: 36,
          height: 36,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: Colors.white.withValues(alpha: 0.18),
            shape: BoxShape.circle,
            border: Border.all(
              color: Colors.white.withValues(alpha: 0.40),
              width: 1,
            ),
          ),
          child: const Icon(
            Icons.arrow_back_rounded,
            size: 20,
            color: Colors.white,
          ),
        ),
      ),
    );
  }
}
