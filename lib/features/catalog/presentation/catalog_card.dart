import 'package:flutter/material.dart';

import '../../../core/widgets/media_image.dart';
import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_radii.dart';
import '../data/catalog_models.dart';

/// Карточка товара реального каталога: фото (или плитка-заглушка с первой буквой) +
/// название + бренд + цена/«Цена в аптеке» + Rx-бейдж. Используется в ленте Home.
///
/// Каталог наполняется постепенно: у части товаров нет фото/цены — карточка
/// деградирует мягко (градиент-заглушка, «Цена в аптеке»).
class CatalogCard extends StatelessWidget {
  const CatalogCard({super.key, required this.product, required this.onTap});

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
              // Фото 3:4 (ширина:высота), как в ПИМ (ДОП.4).
              aspectRatio: 3 / 4,
              child: _CatalogCardImage(product: product),
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
                            catalogPriceLabel(
                              product.price,
                              currency: product.currency,
                            ),
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

class _CatalogCardImage extends StatelessWidget {
  const _CatalogCardImage({required this.product});
  final CatalogProduct product;

  @override
  Widget build(BuildContext context) {
    // MediaImage: декод под ширину ячейки (cacheWidth 400) + ДИСКОВЫЙ кэш — без
    // повторного фетча/декода из сети при скролле каталога. proxyMedia (http→https
    // прокси Medusa, cleartext в релизе запрещён) применяется внутри MediaImage.
    return MediaImage(
      url: product.imageUrl,
      placeholder: _placeholder,
      cacheWidth: 400,
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
