import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_radii.dart';
import '../../../core/theme/app_shadows.dart';
import '../../../core/theme/app_spacing.dart';
import '../../../core/widgets/search_input.dart';
import '../../promotions/application/promotions_controller.dart';
import '../../promotions/data/promotion_models.dart';
import '../application/receipts_controller.dart';

/// Экран выбора акций для чека.
///
/// Источник — тот же пул промо-кампаний, что и лента Home ([promotionsProvider]):
/// фармацевт выбирает из ПРЕДСТАВЛЕННЫХ акций те, что есть в его чеке. Карточки
/// имеют inline-toggle «Добавить / Добавлено». Снизу — sticky CTA «Добавить · N».
class PromoPickerScreen extends ConsumerStatefulWidget {
  const PromoPickerScreen({super.key});

  @override
  ConsumerState<PromoPickerScreen> createState() => _PromoPickerScreenState();
}

class _PromoPickerScreenState extends ConsumerState<PromoPickerScreen> {
  final _qCtrl = TextEditingController();
  String _q = '';

  // Выбранные акции: id → объект Promotion. Храним сам объект (а не только id),
  // чтобы сохранить РОВНО выбор пользователя, не пересобирая его из текущего пула
  // на момент подтверждения (иначе count CTA мог бы расходиться с сохранённым).
  late Map<String, Promotion> _picked;

  @override
  void initState() {
    super.initState();
    // Pre-fill уже выбранными акциями (если возвращаемся в screen).
    _picked = {for (final p in ref.read(receiptDraftProvider).promos) p.id: p};
  }

  @override
  void dispose() {
    _qCtrl.dispose();
    super.dispose();
  }

  String _pluralPromo(int n) {
    final m = n % 10;
    final h = n % 100;
    if (m == 1 && h != 11) return 'акция';
    if (m >= 2 && m <= 4 && (h < 10 || h >= 20)) return 'акции';
    return 'акций';
  }

  void _toggle(Promotion p) {
    setState(() {
      if (_picked.containsKey(p.id)) {
        _picked.remove(p.id);
      } else {
        _picked[p.id] = p;
      }
    });
  }

  void _submit() {
    // Сохраняем ровно выбранные объекты — без зависимости от текущего пула.
    ref.read(receiptDraftProvider.notifier).setPromos(_picked.values.toList());
    Navigator.of(context).pop();
  }

  bool _matches(Promotion p, String lower) {
    if (lower.isEmpty) return true;
    return p.name.toLowerCase().contains(lower) ||
        (p.brand?.toLowerCase().contains(lower) ?? false) ||
        (p.mnn?.toLowerCase().contains(lower) ?? false);
  }

  @override
  Widget build(BuildContext context) {
    final promotionsAsync = ref.watch(promotionsProvider);
    return Scaffold(
      backgroundColor: Colors.white,
      body: SafeArea(
        bottom: false,
        child: Column(
          children: [
            // Header — back chevron + центрированный заголовок
            SizedBox(
              height: 48,
              child: Stack(
                children: [
                  Positioned(
                    left: AppSpacing.screenEdge - 8,
                    top: 0,
                    bottom: 0,
                    child: SizedBox(
                      width: 40,
                      child: IconButton(
                        icon: const Icon(Icons.arrow_back_rounded,
                            size: 24, color: AppColors.ink900),
                        onPressed: () => Navigator.of(context).pop(),
                      ),
                    ),
                  ),
                  const Center(
                    child: Text(
                      'Добавить акции',
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
              child: promotionsAsync.when(
                loading: () =>
                    const Center(child: CircularProgressIndicator()),
                // Ошибка — НЕ тупик: «Повторить» инвалидирует провайдер (как на Home).
                error: (e, _) => _ErrorState(
                  onRetry: () => ref.invalidate(promotionsProvider),
                ),
                data: (promos) {
                  final lower = _q.trim().toLowerCase();
                  final filtered =
                      promos.where((p) => _matches(p, lower)).toList();

                  return Stack(
                    children: [
                      // Единый sliver-скролл: SliverGrid строит ТОЛЬКО видимые
                      // карточки (ленивый Image.network вместо «все разом»).
                      CustomScrollView(
                        slivers: [
                          SliverPadding(
                            padding: const EdgeInsets.fromLTRB(
                                AppSpacing.screenEdge, 12, AppSpacing.screenEdge, 0),
                            sliver: SliverToBoxAdapter(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  const Text(
                                    'Акции',
                                    style: TextStyle(
                                      fontFamily: 'Manrope',
                                      fontFamilyFallback: ['Roboto', 'sans-serif'],
                                      fontSize: 28,
                                      fontWeight: FontWeight.w800,
                                      color: AppColors.ink900,
                                      height: 1.15,
                                    ),
                                  ),
                                  const SizedBox(height: 16),
                                  SearchInput(
                                    controller: _qCtrl,
                                    hint: 'Поиск по названию, бренду или МНН',
                                    onChanged: (v) => setState(() => _q = v),
                                  ),
                                  const SizedBox(height: 16),
                                ],
                              ),
                            ),
                          ),
                          if (filtered.isEmpty)
                            SliverToBoxAdapter(
                              child: Padding(
                                padding: const EdgeInsets.symmetric(vertical: 32),
                                child: Center(
                                  child: Text(
                                    promos.isEmpty
                                        ? 'Акции появятся, когда менеджер их добавит'
                                        : 'Ничего не найдено',
                                    textAlign: TextAlign.center,
                                    style: const TextStyle(
                                      fontFamily: 'Manrope',
                                      fontFamilyFallback: ['Roboto', 'sans-serif'],
                                      fontSize: 14,
                                      fontWeight: FontWeight.w800,
                                      color: AppColors.ink400,
                                    ),
                                  ),
                                ),
                              ),
                            )
                          else
                            SliverPadding(
                              padding: const EdgeInsets.fromLTRB(
                                  AppSpacing.screenEdge, 0, AppSpacing.screenEdge, 140),
                              sliver: SliverGrid(
                                gridDelegate:
                                    const SliverGridDelegateWithFixedCrossAxisCount(
                                  crossAxisCount: 2,
                                  crossAxisSpacing: 12,
                                  mainAxisSpacing: 12,
                                  mainAxisExtent: 258,
                                ),
                                delegate: SliverChildBuilderDelegate(
                                  (_, i) => _PickerCard(
                                    promo: filtered[i],
                                    selected:
                                        _picked.containsKey(filtered[i].id),
                                    onToggle: () => _toggle(filtered[i]),
                                  ),
                                  childCount: filtered.length,
                                ),
                              ),
                            ),
                        ],
                      ),
                      Positioned(
                        left: 0,
                        right: 0,
                        bottom: 0,
                        child: Container(
                          decoration: const BoxDecoration(
                            gradient: LinearGradient(
                              begin: Alignment.topCenter,
                              end: Alignment.bottomCenter,
                              colors: [Color(0x00FFFFFF), Colors.white],
                            ),
                          ),
                          padding: const EdgeInsets.fromLTRB(
                              AppSpacing.screenEdge, 16, AppSpacing.screenEdge, 28),
                          child: _ConfirmCta(
                            count: _picked.length,
                            onTap: _submit,
                            pluralLabel: _pluralPromo,
                          ),
                        ),
                      ),
                    ],
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Экран ошибки загрузки пула акций с кнопкой повтора (паритет с Home-лентой).
class _ErrorState extends StatelessWidget {
  const _ErrorState({required this.onRetry});
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.wifi_off_rounded, size: 40, color: AppColors.ink400),
            const SizedBox(height: 12),
            const Text(
              'Не удалось загрузить акции.\nПроверьте соединение и попробуйте снова.',
              textAlign: TextAlign.center,
              style: TextStyle(
                fontFamily: 'Manrope',
                fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 14,
                fontWeight: FontWeight.w700,
                color: AppColors.ink500,
              ),
            ),
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
                  color: AppColors.brandGreen700,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ConfirmCta extends StatelessWidget {
  const _ConfirmCta({
    required this.count,
    required this.onTap,
    required this.pluralLabel,
  });
  final int count;
  final VoidCallback onTap;
  final String Function(int) pluralLabel;

  @override
  Widget build(BuildContext context) {
    final ready = count > 0;
    final label = ready ? 'Добавить · $count' : 'Выберите акции';
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
            child: Text(
              label,
              style: const TextStyle(
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

class _PickerCard extends StatelessWidget {
  const _PickerCard({
    required this.promo,
    required this.selected,
    required this.onToggle,
  });

  final Promotion promo;
  final bool selected;
  final VoidCallback onToggle;

  @override
  Widget build(BuildContext context) {
    // Один семантический узел на карточку: кнопка-toggle с состоянием selected
    // и названием акции (VoiceOver/TalkBack читают «Выбрано/Не выбрано»).
    return Semantics(
      button: true,
      selected: selected,
      label: promo.name,
      onTap: onToggle,
      child: ExcludeSemantics(
        child: Container(
          decoration: BoxDecoration(
            color: selected ? AppColors.brandGreen100 : Colors.white,
            borderRadius: AppRadii.brXl,
            boxShadow: AppShadows.card,
            border: Border.all(
              color: selected ? AppColors.brandGreen600 : Colors.transparent,
              width: 2,
            ),
          ),
          clipBehavior: Clip.antiAlias,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // Фото товара (network) с плейсхолдером-заглушкой при отсутствии/ошибке.
              SizedBox(
                height: 140,
                child: Stack(
                  children: [
                    Positioned.fill(child: _PickerThumb(promo: promo)),
                    if (promo.dateLabel != null)
                      Positioned(
                        bottom: 8,
                        left: 8,
                        child: Container(
                          padding: const EdgeInsets.symmetric(
                              horizontal: 8, vertical: 2),
                          decoration: BoxDecoration(
                            color: AppColors.brandGreen600,
                            borderRadius: BorderRadius.circular(6),
                          ),
                          child: Text(
                            promo.dateLabel!,
                            style: const TextStyle(
                              fontFamily: 'Manrope',
                              fontFamilyFallback: ['Roboto', 'sans-serif'],
                              fontSize: 11,
                              fontWeight: FontWeight.w800,
                              color: Colors.white,
                            ),
                          ),
                        ),
                      ),
                    if (selected)
                      Positioned(
                        top: 8,
                        right: 8,
                        child: Container(
                          width: 28,
                          height: 28,
                          alignment: Alignment.center,
                          decoration: BoxDecoration(
                            color: AppColors.brandGreen600,
                            shape: BoxShape.circle,
                            border: Border.all(color: Colors.white, width: 2),
                            boxShadow: AppShadows.fab,
                          ),
                          child: const Icon(Icons.check_rounded,
                              size: 14, color: Colors.white),
                        ),
                      ),
                  ],
                ),
              ),
              // Тело: название + toggle
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.all(10),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        promo.name,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontFamily: 'Manrope',
                          fontFamilyFallback: ['Roboto', 'sans-serif'],
                          fontSize: 14,
                          fontWeight: FontWeight.w800,
                          color: AppColors.ink900,
                          height: 1.15,
                        ),
                      ),
                      const Spacer(),
                      _ToggleButton(selected: selected, onTap: onToggle),
                    ],
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

/// Фото товара акции для карточки пикера. Network-image с плейсхолдером
/// (буква названия на брендовом фоне) — тот же подход, что в PromoProductCard.
class _PickerThumb extends StatelessWidget {
  const _PickerThumb({required this.promo});
  final Promotion promo;

  @override
  Widget build(BuildContext context) {
    Widget placeholder() => Container(
          color: AppColors.brandGreen100,
          alignment: Alignment.center,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 8),
            child: Text(
              promo.name.isNotEmpty
                  ? promo.name.characters.first.toUpperCase()
                  : '?',
              textAlign: TextAlign.center,
              style: const TextStyle(
                fontFamily: 'Manrope',
                fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 34,
                fontWeight: FontWeight.w800,
                color: AppColors.brandGreen700,
              ),
            ),
          ),
        );

    final url = promo.imageUrl;
    if (url == null || url.isEmpty) return placeholder();
    return Image.network(
      url,
      fit: BoxFit.cover,
      width: double.infinity,
      height: double.infinity,
      loadingBuilder: (_, child, p) => p == null ? child : placeholder(),
      errorBuilder: (_, __, ___) => placeholder(),
    );
  }
}

class _ToggleButton extends StatelessWidget {
  const _ToggleButton({required this.selected, required this.onTap});
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: selected ? AppColors.brandGreen600 : AppColors.brandGreen100,
      borderRadius: AppRadii.brFull,
      child: InkWell(
        onTap: onTap,
        borderRadius: AppRadii.brFull,
        child: Container(
          height: 36,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            borderRadius: AppRadii.brFull,
            boxShadow: selected ? AppShadows.fab : null,
          ),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                selected ? 'Добавлено' : 'Добавить',
                style: TextStyle(
                  fontFamily: 'Manrope',
                  fontFamilyFallback: const ['Roboto', 'sans-serif'],
                  fontSize: 14,
                  fontWeight: FontWeight.w800,
                  color: selected ? Colors.white : AppColors.brandGreen700,
                ),
              ),
              const SizedBox(width: 6),
              Icon(
                selected ? Icons.check_rounded : Icons.add_rounded,
                size: 16,
                color: selected ? Colors.white : AppColors.brandGreen700,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
