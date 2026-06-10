import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_radii.dart';
import '../../../core/theme/app_spacing.dart';
import '../../../core/widgets/search_input.dart';
import '../application/catalog_controller.dart';
import '../data/catalog_models.dart';
import 'catalog_product_sheet.dart';

/// Экран реального каталога товаров (витрина inkar.kz через бэкенд-прокси Medusa).
/// Поиск по названию + бесконечный скролл. Цена/фото могут отсутствовать —
/// показываем «Цена в аптеке» и плитку-заглушку (каталог наполняется постепенно).
class CatalogScreen extends ConsumerStatefulWidget {
  const CatalogScreen({super.key});

  @override
  ConsumerState<CatalogScreen> createState() => _CatalogScreenState();
}

class _CatalogScreenState extends ConsumerState<CatalogScreen> {
  final _qCtrl = TextEditingController();
  final _scroll = ScrollController();
  Timer? _debounce;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _qCtrl.dispose();
    _scroll
      ..removeListener(_onScroll)
      ..dispose();
    super.dispose();
  }

  void _onScroll() {
    if (_scroll.position.pixels >= _scroll.position.maxScrollExtent - 400) {
      ref.read(catalogProvider.notifier).loadMore();
    }
  }

  void _onQueryChanged(String v) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 300), () {
      ref.read(catalogProvider.notifier).search(v);
    });
  }

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(catalogProvider);
    final state = async.valueOrNull;

    return Scaffold(
      backgroundColor: const Color(0xFFF4F6FA),
      body: SafeArea(
        child: Column(
          children: [
            _Header(),
            Padding(
              padding: const EdgeInsets.fromLTRB(
                AppSpacing.screenEdge,
                4,
                AppSpacing.screenEdge,
                12,
              ),
              child: SearchInput(
                controller: _qCtrl,
                hint: 'Поиск товара или действующего вещества',
                onChanged: _onQueryChanged,
              ),
            ),
            // Тонкий индикатор при новом поиске (старый список остаётся под ним).
            if (async.isLoading && state != null)
              const LinearProgressIndicator(
                minHeight: 2,
                color: AppColors.brandGreen600,
                backgroundColor: Colors.transparent,
              ),
            Expanded(child: _body(async, state)),
          ],
        ),
      ),
    );
  }

  Widget _body(AsyncValue<CatalogState> async, CatalogState? state) {
    if (state == null) {
      if (async.hasError) {
        return _CatalogMessage(
          icon: Icons.wifi_off_rounded,
          title: 'Каталог недоступен',
          subtitle: 'Проверьте соединение и попробуйте ещё раз',
          onRetry: () => ref.read(catalogProvider.notifier).refresh(),
        );
      }
      return const Center(child: CircularProgressIndicator());
    }
    if (state.items.isEmpty) {
      return _CatalogMessage(
        icon: Icons.search_off_rounded,
        title: 'Ничего не найдено',
        subtitle: state.query.isEmpty
            ? 'Каталог пока пуст'
            : 'По запросу «${state.query}» товаров нет',
      );
    }
    return GridView.builder(
      controller: _scroll,
      padding: const EdgeInsets.fromLTRB(
        AppSpacing.screenEdge,
        4,
        AppSpacing.screenEdge,
        AppSpacing.s24,
      ),
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 2,
        mainAxisSpacing: 12,
        crossAxisSpacing: 12,
        childAspectRatio: 0.62,
      ),
      // +1 ячейка под индикатор догрузки.
      itemCount: state.items.length + (state.hasMore ? 1 : 0),
      itemBuilder: (_, i) {
        if (i >= state.items.length) {
          return const Center(
            child: Padding(
              padding: EdgeInsets.all(16),
              child: CircularProgressIndicator(),
            ),
          );
        }
        final p = state.items[i];
        return _ProductCard(
          product: p,
          onTap: () => showCatalogProductSheet(context, p.id),
        );
      },
    );
  }
}

class _Header extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(8, 8, AppSpacing.screenEdge, 4),
      child: Row(
        children: [
          IconButton(
            onPressed: () => Navigator.of(context).maybePop(),
            icon: const Icon(Icons.arrow_back_rounded, color: AppColors.ink900),
            tooltip: 'Назад',
          ),
          const SizedBox(width: 2),
          const Text(
            'Каталог товаров',
            style: TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: ['Roboto', 'sans-serif'],
              fontSize: 22,
              fontWeight: FontWeight.w800,
              color: AppColors.ink900,
            ),
          ),
        ],
      ),
    );
  }
}

class _ProductCard extends StatelessWidget {
  const _ProductCard({required this.product, required this.onTap});
  final CatalogProduct product;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.white,
      borderRadius: AppRadii.brXl,
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            AspectRatio(
              aspectRatio: 1.4,
              child: _ProductImage(product: product),
            ),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(10, 8, 10, 10),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      product.name,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontFamily: 'Manrope',
                        fontFamilyFallback: ['Roboto', 'sans-serif'],
                        fontSize: 13,
                        fontWeight: FontWeight.w800,
                        color: AppColors.ink900,
                        height: 1.2,
                      ),
                    ),
                    if (product.brand != null) ...[
                      const SizedBox(height: 2),
                      Text(
                        product.brand!,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontFamily: 'Manrope',
                          fontFamilyFallback: ['Roboto', 'sans-serif'],
                          fontSize: 11,
                          fontWeight: FontWeight.w700,
                          color: AppColors.ink500,
                        ),
                      ),
                    ],
                    const Spacer(),
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            catalogPriceLabel(product.price,
                                currency: product.currency),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontFamily: 'Manrope',
                              fontFamilyFallback: const ['Roboto', 'sans-serif'],
                              fontSize: product.price == null ? 12 : 14,
                              fontWeight: FontWeight.w800,
                              color: product.price == null
                                  ? AppColors.ink500
                                  : AppColors.brandGreen700,
                            ),
                          ),
                        ),
                        if (product.isRx) const _RxBadge(),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ProductImage extends StatelessWidget {
  const _ProductImage({required this.product});
  final CatalogProduct product;

  @override
  Widget build(BuildContext context) {
    final url = product.imageUrl;
    if (url == null || url.isEmpty) return _placeholder();
    return Image.network(
      url,
      fit: BoxFit.cover,
      loadingBuilder: (ctx, child, progress) =>
          progress == null ? child : _placeholder(),
      errorBuilder: (_, __, ___) => _placeholder(),
    );
  }

  Widget _placeholder() {
    final letter = product.name.isNotEmpty ? product.name.characters.first : '?';
    return Container(
      color: AppColors.brandGreen100,
      alignment: Alignment.center,
      child: Text(
        letter.toUpperCase(),
        style: const TextStyle(
          fontFamily: 'Manrope',
          fontFamilyFallback: ['Roboto', 'sans-serif'],
          fontSize: 30,
          fontWeight: FontWeight.w800,
          color: AppColors.brandGreen700,
        ),
      ),
    );
  }
}

class _RxBadge extends StatelessWidget {
  const _RxBadge();

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(left: 6),
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: const Color(0xFFFDE7E9),
        borderRadius: BorderRadius.circular(6),
      ),
      child: const Text(
        'Rx',
        style: TextStyle(
          fontFamily: 'Manrope',
          fontFamilyFallback: ['Roboto', 'sans-serif'],
          fontSize: 10,
          fontWeight: FontWeight.w800,
          color: Color(0xFFE5484D),
        ),
      ),
    );
  }
}

class _CatalogMessage extends StatelessWidget {
  const _CatalogMessage({
    required this.icon,
    required this.title,
    required this.subtitle,
    this.onRetry,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.screenEdge),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 44, color: AppColors.ink300),
            const SizedBox(height: 12),
            Text(
              title,
              textAlign: TextAlign.center,
              style: const TextStyle(
                fontFamily: 'Manrope',
                fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 17,
                fontWeight: FontWeight.w800,
                color: AppColors.ink900,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              subtitle,
              textAlign: TextAlign.center,
              style: const TextStyle(
                fontFamily: 'Manrope',
                fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 13,
                fontWeight: FontWeight.w700,
                color: AppColors.ink500,
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
                    color: AppColors.brandGreen700,
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
