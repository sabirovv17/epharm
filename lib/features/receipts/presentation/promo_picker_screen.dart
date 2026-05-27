import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_radii.dart';
import '../../../core/theme/app_shadows.dart';
import '../../../core/theme/app_spacing.dart';
import '../../../core/widgets/search_input.dart';
import '../../home/application/home_controller.dart';
import '../../home/data/home_repository.dart';
import '../application/receipts_controller.dart';

/// Экран выбора акций (товаров) для чека.
///
/// Источник: `_reference/recipe/review.jsx` → `PromoPickerScreen`.
///
/// Каталог идентичен Home (тот же `productListProvider`), но карточки имеют
/// inline-toggle «Добавить / Добавлено». Снизу — sticky CTA «Добавить · N».
class PromoPickerScreen extends ConsumerStatefulWidget {
  const PromoPickerScreen({super.key});

  @override
  ConsumerState<PromoPickerScreen> createState() =>
      _PromoPickerScreenState();
}

class _PromoPickerScreenState extends ConsumerState<PromoPickerScreen> {
  final _qCtrl = TextEditingController();
  String _q = '';
  late Set<int> _picked;

  @override
  void initState() {
    super.initState();
    // Pre-fill уже выбранными акциями (если возвращаемся в screen).
    _picked = ref.read(receiptDraftProvider).promos.map((p) => p.id).toSet();
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

  void _toggle(int id) {
    setState(() {
      if (_picked.contains(id)) {
        _picked.remove(id);
      } else {
        _picked.add(id);
      }
    });
  }

  void _submit(List<Product> filtered) {
    // Берём ВСЕ выбранные (не только из текущего фильтра), фильтруя
    // полный список по id чтобы выбор сохранился между фильтрами.
    final all = ref.read(productListProvider).valueOrNull ?? [];
    final picked =
        all.where((p) => _picked.contains(p.id)).toList(growable: false);
    ref.read(receiptDraftProvider.notifier).setPromos(picked);
    Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    final productsAsync = ref.watch(productListProvider);
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
              child: productsAsync.when(
                loading: () =>
                    const Center(child: CircularProgressIndicator()),
                error: (e, _) => Center(child: Text('Ошибка: $e')),
                data: (products) {
                  final filtered = products.where((p) {
                    if (_q.isEmpty) return true;
                    final lower = _q.toLowerCase();
                    return p.name.toLowerCase().contains(lower) ||
                        p.brand.toLowerCase().contains(lower);
                  }).toList();

                  return Stack(
                    children: [
                      ListView(
                        padding: const EdgeInsets.fromLTRB(
                          AppSpacing.screenEdge,
                          12,
                          AppSpacing.screenEdge,
                          140,
                        ),
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
                            hint: 'Поиск по названию или бренду',
                            onChanged: (v) => setState(() => _q = v),
                          ),
                          const SizedBox(height: 16),
                          GridView.builder(
                            shrinkWrap: true,
                            physics: const NeverScrollableScrollPhysics(),
                            gridDelegate:
                                const SliverGridDelegateWithFixedCrossAxisCount(
                              crossAxisCount: 2,
                              crossAxisSpacing: 12,
                              mainAxisSpacing: 12,
                              mainAxisExtent: 258,
                            ),
                            itemCount: filtered.length,
                            itemBuilder: (_, i) => _PickerCard(
                              product: filtered[i],
                              selected:
                                  _picked.contains(filtered[i].id),
                              onToggle: () => _toggle(filtered[i].id),
                            ),
                          ),
                          if (filtered.isEmpty)
                            const Padding(
                              padding: EdgeInsets.symmetric(vertical: 32),
                              child: Center(
                                child: Text(
                                  'Ничего не найдено',
                                  style: TextStyle(
                                    fontFamily: 'Manrope',
                                    fontFamilyFallback: [
                                      'Roboto',
                                      'sans-serif'
                                    ],
                                    fontSize: 14,
                                    fontWeight: FontWeight.w800,
                                    color: AppColors.ink400,
                                  ),
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
                              AppSpacing.screenEdge,
                              16,
                              AppSpacing.screenEdge,
                              28),
                          child: _ConfirmCta(
                            count: _picked.length,
                            onTap: () => _submit(filtered),
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
    required this.product,
    required this.selected,
    required this.onToggle,
  });

  final Product product;
  final bool selected;
  final VoidCallback onToggle;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color:
            selected ? AppColors.brandGreen100 : Colors.white,
        borderRadius: AppRadii.brXl,
        boxShadow: AppShadows.card,
        border: Border.all(
          color: selected
              ? AppColors.brandGreen600
              : Colors.transparent,
          width: 2,
        ),
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // Image
          SizedBox(
            height: 140,
            child: Stack(
              children: [
                Container(
                  decoration: BoxDecoration(gradient: product.pkg.background),
                  alignment: Alignment.center,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 8),
                    child: Text(
                      product.pkg.label,
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        fontFamily: 'Manrope',
                        fontFamilyFallback: const ['Roboto', 'sans-serif'],
                        fontSize: 13,
                        fontWeight: FontWeight.w800,
                        color: product.pkg.accent ?? Colors.white,
                        height: 1.15,
                      ),
                    ),
                  ),
                ),
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
                      product.period,
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
          // Body
          Expanded(
            child: Padding(
              padding: const EdgeInsets.all(10),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    product.name,
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
