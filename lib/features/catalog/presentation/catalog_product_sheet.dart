import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/media.dart';
import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_radii.dart';
import '../../../core/theme/app_shadows.dart';
import '../../../core/theme/app_spacing.dart';
import '../../../core/widgets/product_image_gallery.dart';
import '../../receipts/presentation/upload_prompt_sheet.dart';
import '../application/catalog_controller.dart';
import '../data/catalog_models.dart';

/// Все фото товара для галереи: обложка (imageUrl) + фото из Medusa, без дублей/пустых.
List<String> _galleryImages(CatalogProductDetail d) {
  final out = <String>[];
  void add(String? u) {
    final v = u?.trim();
    if (v != null && v.isNotEmpty && !out.contains(v)) out.add(v);
  }

  add(d.imageUrl);
  for (final u in d.images) {
    add(u);
  }
  return out;
}

/// Детальная карточка товара каталога. Данные грузятся по medusa-id через
/// [catalogDetailProvider]. Цена/фото/поля могут отсутствовать — секции рисуются
/// только при наличии данных.
Future<void> showCatalogProductSheet(BuildContext context, String id) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    barrierColor: const Color(0x73221C16),
    builder: (_) => _CatalogProductSheet(id: id),
  );
}

class _CatalogProductSheet extends ConsumerWidget {
  const _CatalogProductSheet({required this.id});
  final String id;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(catalogDetailProvider(id));
    return DraggableScrollableSheet(
      initialChildSize: 0.7,
      maxChildSize: 0.95,
      minChildSize: 0.5,
      expand: false,
      builder: (ctx, scrollCtrl) => Container(
        decoration: const BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.vertical(top: Radius.circular(AppRadii.xxl)),
        ),
        child: async.when(
          loading: () => ListView(
            controller: scrollCtrl,
            children: const [
              SizedBox(height: 120),
              Center(child: CircularProgressIndicator()),
            ],
          ),
          error: (_, __) => ListView(
            controller: scrollCtrl,
            padding: const EdgeInsets.all(AppSpacing.screenEdge),
            children: [
              const SizedBox(height: 60),
              const Icon(Icons.wifi_off_rounded,
                  size: 40, color: AppColors.ink300),
              const SizedBox(height: 12),
              const Text(
                'Не удалось загрузить товар',
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontFamily: 'Manrope',
                  fontFamilyFallback: ['Roboto', 'sans-serif'],
                  fontSize: 16,
                  fontWeight: FontWeight.w800,
                  color: AppColors.ink900,
                ),
              ),
              const SizedBox(height: 16),
              Center(
                child: TextButton(
                  onPressed: () => ref.invalidate(catalogDetailProvider(id)),
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
              ),
            ],
          ),
          data: (d) => _content(d, scrollCtrl),
        ),
      ),
    );
  }

  Widget _content(CatalogProductDetail d, ScrollController scrollCtrl) {
    return ListView(
      controller: scrollCtrl,
      padding: const EdgeInsets.fromLTRB(
        AppSpacing.screenEdge,
        8,
        AppSpacing.screenEdge,
        AppSpacing.s24,
      ),
      children: [
        Center(
          child: Container(
            width: 36,
            height: 4,
            decoration: BoxDecoration(
              color: AppColors.ink300,
              borderRadius: BorderRadius.circular(2),
            ),
          ),
        ),
        const SizedBox(height: 12),
        // Галерея фото товара: листаемые кадры из ПИМ/Medusa (как в админ-кампании).
        // Один кадр — без точек/счётчика; несколько — свайп + индикатор.
        ProductImageGallery(
          images: _galleryImages(d),
          fallbackName: d.name,
        ),
        const SizedBox(height: 14),
        Text(
          d.name,
          style: const TextStyle(
            fontFamily: 'Manrope',
            fontFamilyFallback: ['Roboto', 'sans-serif'],
            fontSize: 20,
            fontWeight: FontWeight.w800,
            color: AppColors.ink900,
            height: 1.2,
          ),
        ),
        if (d.brand != null) ...[
          const SizedBox(height: 4),
          Text(
            d.brand!,
            style: const TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: ['Roboto', 'sans-serif'],
              fontSize: 14,
              fontWeight: FontWeight.w700,
              color: AppColors.ink500,
            ),
          ),
        ],
        const SizedBox(height: 10),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            _Chip(
              text: d.isRx ? 'По рецепту' : 'Без рецепта',
              color: d.isRx ? const Color(0xFFE5484D) : AppColors.brandGreen700,
              bg: d.isRx ? const Color(0xFFFDE7E9) : AppColors.brandGreen100,
            ),
            if (d.category != null)
              _Chip(
                text: d.category!,
                color: AppColors.ink700,
                bg: AppColors.paperInput,
              ),
          ],
        ),
        const SizedBox(height: 12),
        Text(
          catalogPriceLabel(d.price, currency: d.currency),
          style: TextStyle(
            fontFamily: 'Manrope',
            fontFamilyFallback: const ['Roboto', 'sans-serif'],
            fontSize: d.price == null ? 15 : 22,
            fontWeight: FontWeight.w800,
            color: d.price == null ? AppColors.ink500 : AppColors.brandGreen700,
          ),
        ),
        // Бонус + «Получить бонус» (п.2). bonus != null означает: фармацевт авторизован
        // И у товара активная кампания. Аноним / товар без кампании → bonus == null,
        // блок не рисуем — карточка как обычно (хендлинг п.1).
        if (d.bonus != null) _BonusCta(detail: d),
        const SizedBox(height: 14),
        _InfoRow(label: 'Действующее вещество', value: d.mnn),
        _InfoRow(label: 'ATC', value: d.atc),
        _InfoRow(label: 'Производитель', value: d.manufacturer),
        _InfoRow(label: 'Страна', value: d.country),
        _InfoRow(label: 'Штрихкод', value: d.barcode),
        if (d.description != null && d.description!.isNotEmpty) ...[
          const SizedBox(height: 8),
          const _SectionTitle('Описание'),
          const SizedBox(height: 6),
          Text(
            d.description!,
            style: const TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: ['Roboto', 'sans-serif'],
              fontSize: 14,
              fontWeight: FontWeight.w600,
              color: AppColors.ink700,
              height: 1.4,
            ),
          ),
        ],
        if (d.keyFacts.isNotEmpty) ...[
          const SizedBox(height: 14),
          const _SectionTitle('Кратко'),
          const SizedBox(height: 6),
          ...d.keyFacts.map(_bullet),
        ],
        // Альтернативы / Дополнения (ДОП.3b) — ПЕРЕД Q&A (пожелание): рекомендации
        // важнее справки. Грузятся отдельным запросом, пустые секции скрыты.
        _RecommendationsSections(id: id),
        if (d.qa.isNotEmpty) ...[
          const SizedBox(height: 14),
          // Q&A — выдвижная секция: по умолчанию свёрнута в кнопку-заголовок,
          // по тапу плавно раскрывает все вопросы (не растягивает карточку).
          _QaSection(items: d.qa),
        ],
        if (d.marketplaceLinks.isNotEmpty) ...[
          const SizedBox(height: 14),
          const _SectionTitle('Цены на маркетплейсах'),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: d.marketplaceLinks
                .map((m) => _Chip(
                      text: m.price != null
                          ? '${_platformLabel(m.platform)} · ${catalogPriceLabel(m.price)}'
                          : _platformLabel(m.platform),
                      color: AppColors.ink700,
                      bg: AppColors.paperInput,
                    ))
                .toList(),
          ),
        ],
      ],
    );
  }

  Widget _bullet(String text) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Padding(
            padding: EdgeInsets.only(top: 6, right: 8),
            child: Icon(Icons.circle, size: 6, color: AppColors.brandGreen600),
          ),
          Expanded(
            child: Text(
              text,
              style: const TextStyle(
                fontFamily: 'Manrope',
                fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 14,
                fontWeight: FontWeight.w600,
                color: AppColors.ink700,
                height: 1.35,
              ),
            ),
          ),
        ],
      ),
    );
  }

  String _platformLabel(String p) {
    switch (p.toLowerCase()) {
      case 'kaspi':
        return 'Kaspi';
      case 'wb':
        return 'Wildberries';
      case 'ozon':
        return 'Ozon';
      default:
        return p;
    }
  }
}

/// Выдвижная секция «Вопросы и ответы»: по умолчанию свёрнута в кнопку-заголовок
/// (счётчик + шеврон), по тапу плавно раскрывает все Q&A. Длинный FAQ не растягивает
/// карточку — фармацевт раскрывает его осознанно (пожелание UX).
class _QaSection extends StatefulWidget {
  const _QaSection({required this.items});
  final List<CatalogQaItem> items;

  @override
  State<_QaSection> createState() => _QaSectionState();
}

class _QaSectionState extends State<_QaSection> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Кнопка-заголовок: тап раскрывает/сворачивает секцию.
        Material(
          color: Colors.transparent,
          child: InkWell(
            borderRadius: AppRadii.brLg,
            onTap: () => setState(() => _expanded = !_expanded),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: Row(
                children: [
                  const Icon(Icons.help_outline_rounded,
                      size: 18, color: AppColors.brandGreen700),
                  const SizedBox(width: 6),
                  const Expanded(child: _SectionTitle('Вопросы и ответы')),
                  Container(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                    decoration: BoxDecoration(
                      color: AppColors.paperInput,
                      borderRadius: BorderRadius.circular(999),
                    ),
                    child: Text(
                      '${widget.items.length}',
                      style: const TextStyle(
                        fontFamily: 'Manrope',
                        fontFamilyFallback: ['Roboto', 'sans-serif'],
                        fontSize: 12,
                        fontWeight: FontWeight.w800,
                        color: AppColors.ink500,
                      ),
                    ),
                  ),
                  const SizedBox(width: 4),
                  AnimatedRotation(
                    turns: _expanded ? 0.5 : 0,
                    duration: const Duration(milliseconds: 200),
                    child: const Icon(Icons.keyboard_arrow_down_rounded,
                        size: 22, color: AppColors.ink500),
                  ),
                ],
              ),
            ),
          ),
        ),
        // Контент: плавное раскрытие всех вопросов/ответов.
        AnimatedCrossFade(
          firstChild: const SizedBox(width: double.infinity),
          secondChild: Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [for (final it in widget.items) _qaItem(it)],
            ),
          ),
          crossFadeState: _expanded
              ? CrossFadeState.showSecond
              : CrossFadeState.showFirst,
          duration: const Duration(milliseconds: 220),
          sizeCurve: Curves.easeOutCubic,
        ),
      ],
    );
  }

  Widget _qaItem(CatalogQaItem item) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            item.q,
            style: const TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: ['Roboto', 'sans-serif'],
              fontSize: 14,
              fontWeight: FontWeight.w800,
              color: AppColors.ink900,
              height: 1.3,
            ),
          ),
          const SizedBox(height: 2),
          Text(
            item.a,
            style: const TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: ['Roboto', 'sans-serif'],
              fontSize: 14,
              fontWeight: FontWeight.w600,
              color: AppColors.ink700,
              height: 1.35,
            ),
          ),
        ],
      ),
    );
  }
}

/// Блок рекомендаций (п.7): ТРИ секции в фиксированном порядке.
///   1) «Можно допродать» — кросс-селл на промоции (group=='crosssell_with_campaign'),
///      кликабельны (открывают карточку), с бонус-бейджем. Рисуется первой.
///   2) «Сопутствующие» — кросс-селл без кампании (group=='crosssell_no_campaign'),
///      инфо-карточки (фото+название), НЕ кликабельны, без бонуса.
///   3) «Альтернативы» — все замены, инфо-карточки, НЕ кликабельны, без бонуса.
/// Пустые секции скрыты (без лишних отступов). Грузится отдельным провайдером.
class _RecommendationsSections extends ConsumerWidget {
  const _RecommendationsSections({required this.id});
  final String id;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final recs = ref.watch(catalogRecommendationsProvider(id)).valueOrNull ??
        CatalogRecommendations.empty;
    if (recs.isEmpty) return const SizedBox.shrink();

    // Разбиваем кросс-селл на две группы по полю group.
    final upsell = recs.crosssells
        .where((r) => r.group == 'crosssell_with_campaign')
        .toList();
    final companions = recs.crosssells
        .where((r) => r.group == 'crosssell_no_campaign')
        .toList();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (upsell.isNotEmpty)
          _RecoSection(
            title: 'Можно допродать',
            subtitle: 'Тоже на промоции',
            icon: Icons.add_circle_outline_rounded,
            items: upsell,
            clickable: true, // открывают карточку
            showBonus: true,
          ),
        if (companions.isNotEmpty)
          _RecoSection(
            title: 'Сопутствующие',
            subtitle: 'Что предложить вместе',
            icon: Icons.inventory_2_outlined,
            items: companions,
            clickable: false, // инфо: только фото+название
            showBonus: false,
          ),
        if (recs.alternatives.isNotEmpty)
          _RecoSection(
            title: 'Альтернативы',
            subtitle: 'Чем можно заменить',
            icon: Icons.swap_horiz_rounded,
            items: recs.alternatives,
            clickable: false, // инфо: только фото+название
            showBonus: false,
          ),
      ],
    );
  }
}

class _RecoSection extends StatelessWidget {
  const _RecoSection({
    required this.title,
    required this.subtitle,
    required this.icon,
    required this.items,
    required this.clickable,
    required this.showBonus,
  });

  final String title;
  final String subtitle;
  final IconData icon;
  final List<CatalogRecommendation> items;

  /// Карточки секции открывают деталь товара по тапу (true) или это инфо-карточки (false).
  final bool clickable;

  /// Показывать ли бонус-бейдж в карточке (только для секции «Можно допродать»).
  final bool showBonus;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 18),
        Row(
          children: [
            Icon(icon, size: 18, color: AppColors.brandGreen700),
            const SizedBox(width: 6),
            _SectionTitle(title),
          ],
        ),
        const SizedBox(height: 2),
        Text(
          subtitle,
          style: const TextStyle(
            fontFamily: 'Manrope',
            fontFamilyFallback: ['Roboto', 'sans-serif'],
            fontSize: 12,
            fontWeight: FontWeight.w700,
            color: AppColors.ink500,
          ),
        ),
        const SizedBox(height: 10),
        SizedBox(
          height: 290,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            padding: EdgeInsets.zero,
            itemCount: items.length,
            separatorBuilder: (_, __) => const SizedBox(width: 12),
            itemBuilder: (_, i) => _RecoCard(
              item: items[i],
              clickable: clickable,
              showBonus: showBonus,
            ),
          ),
        ),
      ],
    );
  }
}

class _RecoCard extends StatelessWidget {
  const _RecoCard({
    required this.item,
    required this.clickable,
    required this.showBonus,
  });
  final CatalogRecommendation item;
  final bool clickable;
  final bool showBonus;

  @override
  Widget build(BuildContext context) {
    final p = item.product;
    // Тап только у кликабельной секции и при валидном id (битый JSON → пустой id,
    // иначе открылась бы карточка с пустым medusa-id и ошибкой).
    final onTap = (clickable && p.id.isNotEmpty)
        ? () => showCatalogProductSheet(context, p.id)
        : null;

    final card = Container(
      decoration: BoxDecoration(
        borderRadius: AppRadii.brXl,
        border: Border.all(color: AppColors.paperInput, width: 1),
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          AspectRatio(
            aspectRatio: 3 / 4,
            child: _RecoThumb(url: p.imageUrl, name: p.name),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(8, 8, 8, 10),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  p.name,
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
                const SizedBox(height: 4),
                Text(
                  catalogPriceLabel(p.price, currency: p.currency),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: const ['Roboto', 'sans-serif'],
                    fontSize: 13,
                    fontWeight: FontWeight.w800,
                    color: p.price == null
                        ? AppColors.ink500
                        : AppColors.brandGreen700,
                  ),
                ),
                if (showBonus && item.bonus != null) ...[
                  const SizedBox(height: 6),
                  _BonusBadge(bonus: item.bonus!),
                ],
              ],
            ),
          ),
        ],
      ),
    );

    return SizedBox(
      width: 132,
      // Некликабельные секции (сопутствующие/альтернативы) — это инфо-карточки
      // (фото+название): без ripple/InkWell, чтобы не намекать на действие.
      child: onTap == null
          ? card
          : Material(
              color: Colors.white,
              borderRadius: AppRadii.brXl,
              child: InkWell(
                borderRadius: AppRadii.brXl,
                onTap: onTap,
                child: card,
              ),
            ),
    );
  }
}

/// Блок бонуса в карточке товара (п.2): бейдж «Бонус N ₸» (заливка brandGreen600,
/// как _GridBonusPill) + крупная кнопка «Получить бонус». По нажатию:
///   • закрываем стопку sheet'ов (popUntil до корня навигатора);
///   • проставляем выбранную акцию в драфт чека (setClaimedPromo → promoIds=[promoId]);
///   • открываем загрузку чека (showUploadPromptSheet поверх корня).
/// Navigator/ref захватываем ДО pop — после закрытия sheet'а контекст невалиден.
class _BonusCta extends StatelessWidget {
  const _BonusCta({required this.detail});
  final CatalogProductDetail detail;

  void _claim(BuildContext context) {
    final rootNav = Navigator.of(context, rootNavigator: true);
    final rootContext = rootNav.context;
    final promoId = detail.promoId;
    // Сворачиваем все модальные sheet'ы (карточка + вложенные карточки) до корня
    // и открываем загрузку чека, передав заявленную акцию РОВНО для этого потока.
    // showUploadPromptSheet сам проставит/очистит promoId в драфте — акция не
    // «переживёт» отмену и не уйдёт со следующим, не связанным с ней чеком.
    rootNav.popUntil((route) => route.isFirst);
    showUploadPromptSheet(rootContext, claimedPromoId: promoId);
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Бейдж «Бонус N ₸» — стиль _GridBonusPill (заливка основным зелёным).
          Container(
            padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 12),
            decoration: BoxDecoration(
              color: AppColors.brandGreen600,
              borderRadius: BorderRadius.circular(10),
            ),
            child: Text(
              'Бонус ${catalogPriceLabel(detail.bonus!)}',
              style: const TextStyle(
                fontFamily: 'Manrope',
                fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 14,
                fontWeight: FontWeight.w800,
                color: Colors.white,
              ),
            ),
          ),
          const SizedBox(height: 10),
          // Крупная кнопка «Получить бонус» — стиль основной CTA (как _ContinueCta).
          Material(
            color: AppColors.brandGreen600,
            borderRadius: AppRadii.brFull,
            child: InkWell(
              borderRadius: AppRadii.brFull,
              onTap: () => _claim(context),
              child: Container(
                height: 56,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  borderRadius: AppRadii.brFull,
                  boxShadow: AppShadows.fab,
                ),
                child: const Text(
                  'Получить бонус',
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 17,
                    fontWeight: FontWeight.w800,
                    color: Colors.white,
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// Бонус-бейдж «+520 ₸» в карточке рекомендации (мотивация фармацевта).
class _BonusBadge extends StatelessWidget {
  const _BonusBadge({required this.bonus});
  final int bonus;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: AppColors.brandGreen100,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        '+${catalogPriceLabel(bonus)}',
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(
          fontFamily: 'Manrope',
          fontFamilyFallback: ['Roboto', 'sans-serif'],
          fontSize: 11,
          fontWeight: FontWeight.w800,
          color: AppColors.brandGreen700,
        ),
      ),
    );
  }
}

class _RecoThumb extends StatelessWidget {
  const _RecoThumb({required this.url, required this.name});
  final String? url;
  final String name;

  @override
  Widget build(BuildContext context) {
    final u = url;
    if (u == null || u.isEmpty) return _placeholder();
    // cacheWidth — декодируем уменьшенную картинку (карточка ~132px): меньше памяти,
    // нет re-decode при скролле горизонтальной ленты рекомендаций.
    return Image.network(
      proxyMedia(u) ?? u,
      fit: BoxFit.cover,
      cacheWidth: 320,
      loadingBuilder: (ctx, child, progress) =>
          progress == null ? child : _placeholder(),
      errorBuilder: (_, __, ___) => _placeholder(),
    );
  }

  Widget _placeholder() {
    final letter = name.isNotEmpty ? name.characters.first : '?';
    return Container(
      color: AppColors.brandGreen100,
      alignment: Alignment.center,
      child: Text(
        letter.toUpperCase(),
        style: const TextStyle(
          fontFamily: 'Manrope',
          fontFamilyFallback: ['Roboto', 'sans-serif'],
          fontSize: 28,
          fontWeight: FontWeight.w800,
          color: AppColors.brandGreen700,
        ),
      ),
    );
  }
}

class _InfoRow extends StatelessWidget {
  const _InfoRow({required this.label, required this.value});
  final String label;
  final String? value;

  @override
  Widget build(BuildContext context) {
    if (value == null || value!.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 150,
            child: Text(
              label,
              style: const TextStyle(
                fontFamily: 'Manrope',
                fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 13,
                fontWeight: FontWeight.w700,
                color: AppColors.ink500,
              ),
            ),
          ),
          Expanded(
            child: Text(
              value!,
              style: const TextStyle(
                fontFamily: 'Manrope',
                fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 13,
                fontWeight: FontWeight.w800,
                color: AppColors.ink900,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.text);
  final String text;

  @override
  Widget build(BuildContext context) {
    return Text(
      text,
      style: const TextStyle(
        fontFamily: 'Manrope',
        fontFamilyFallback: ['Roboto', 'sans-serif'],
        fontSize: 15,
        fontWeight: FontWeight.w800,
        color: AppColors.ink900,
      ),
    );
  }
}

class _Chip extends StatelessWidget {
  const _Chip({required this.text, required this.color, required this.bg});
  final String text;
  final Color color;
  final Color bg;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        text,
        style: TextStyle(
          fontFamily: 'Manrope',
          fontFamilyFallback: const ['Roboto', 'sans-serif'],
          fontSize: 12,
          fontWeight: FontWeight.w800,
          color: color,
        ),
      ),
    );
  }
}
