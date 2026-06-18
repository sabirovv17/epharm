import 'package:flutter/material.dart';

import '../../../../core/theme/app_colors.dart';
import '../../../../core/theme/app_gradients.dart';
import '../../../../core/theme/app_radii.dart';
import '../../../../core/theme/app_spacing.dart';
import '../../data/banner_model.dart';

/// Горизонтальная карусель баннеров. Высота 234, ширина карточки 180 (−10%).
///
/// Каждый слот — реальная карточка баннера (картинка из API + заголовок +
/// подзаголовок). Тап → детальный экран баннера. Пустой список карусель не
/// рендерит (вызывающий код в home_screen.dart показывает shrink).
class PromoCarousel extends StatelessWidget {
  const PromoCarousel({super.key, required this.items});

  final List<BannerModel> items;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 234,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
        itemCount: items.length,
        separatorBuilder: (_, __) => const SizedBox(width: 12),
        itemBuilder: (ctx, i) {
          final banner = items[i];
          return _BannerCard(
            banner: banner,
            // Тап → баннер на весь экран (картинка + title + subtitle + detailMd).
            onTap: () => Navigator.of(ctx).push(
              MaterialPageRoute(
                builder: (_) => _BannerFullscreenScreen(banner: banner),
              ),
            ),
          );
        },
      ),
    );
  }
}

/// Карточка баннера 180×234: картинка из API сверху + title/subtitle снизу.
/// При ошибке загрузки картинки — мягкий плейсхолдер с иконкой.
class _BannerCard extends StatelessWidget {
  const _BannerCard({required this.banner, required this.onTap});

  final BannerModel banner;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 180,
      height: 234,
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: AppRadii.brXl,
        boxShadow: const [
          BoxShadow(
            color: Color(0x140F1424),
            offset: Offset(0, 4),
            blurRadius: 16,
          ),
        ],
      ),
      clipBehavior: Clip.antiAlias,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Картинка баннера: занимает верхнюю часть карточки.
              Expanded(
                child: _BannerImage(url: banner.imageUrl),
              ),
              // Подпись: заголовок (+ подзаголовок) на белой подложке.
              Padding(
                padding: const EdgeInsets.fromLTRB(10, 8, 10, 10),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      banner.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontFamily: 'Manrope',
                        fontFamilyFallback: ['Roboto', 'sans-serif'],
                        fontSize: 14,
                        fontWeight: FontWeight.w800,
                        color: AppColors.ink900,
                        height: 1.2,
                      ),
                    ),
                    if (banner.subtitle != null &&
                        banner.subtitle!.trim().isNotEmpty) ...[
                      const SizedBox(height: 2),
                      Text(
                        banner.subtitle!,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontFamily: 'Manrope',
                          fontFamilyFallback: ['Roboto', 'sans-serif'],
                          fontSize: 12,
                          fontWeight: FontWeight.w700,
                          color: AppColors.ink500,
                          height: 1.2,
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

/// Картинка баннера с производительным декодом (cacheWidth) и мягким fallback
/// при ошибке/пустом url.
class _BannerImage extends StatelessWidget {
  const _BannerImage({required this.url});

  final String url;

  @override
  Widget build(BuildContext context) {
    if (url.trim().isEmpty) return const _BannerImageFallback();
    return Image.network(
      url,
      fit: BoxFit.cover,
      width: double.infinity,
      // Ширина карточки 180 logical px; декодируем под ×2 DPR — без оверсемплинга
      // огромных исходников, экономит память на карусели.
      cacheWidth: 360,
      errorBuilder: (_, __, ___) => const _BannerImageFallback(),
    );
  }
}

/// Плейсхолдер картинки баннера (битый url / ошибка сети): мягкая подложка
/// с иконкой — без жёсткого контраста.
class _BannerImageFallback extends StatelessWidget {
  const _BannerImageFallback();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      decoration: const BoxDecoration(gradient: AppGradients.bannerPlaceholder),
      child: const Center(
        child: Icon(Icons.image_outlined, size: 40, color: AppColors.ink300),
      ),
    );
  }
}

/// Баннер на весь экран: картинка сверху + заголовок, подзаголовок и детальный
/// текст (detailMd рендерим как обычный текст — markdown не обязателен).
class _BannerFullscreenScreen extends StatelessWidget {
  const _BannerFullscreenScreen({required this.banner});

  final BannerModel banner;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Шапка: кнопка «назад» слева.
            SizedBox(
              height: 48,
              child: Align(
                alignment: Alignment.centerLeft,
                child: IconButton(
                  icon: const Icon(Icons.arrow_back_rounded,
                      color: AppColors.ink900),
                  onPressed: () => Navigator.of(context).maybePop(),
                ),
              ),
            ),
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.fromLTRB(
                  AppSpacing.screenEdge,
                  0,
                  AppSpacing.screenEdge,
                  AppSpacing.s24,
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // Картинка баннера — крупная, со скруглением.
                    ClipRRect(
                      borderRadius: AppRadii.brXl,
                      child: AspectRatio(
                        aspectRatio: 4 / 3,
                        child: _BannerImage(url: banner.imageUrl),
                      ),
                    ),
                    const SizedBox(height: AppSpacing.s20),
                    Text(
                      banner.title,
                      style: const TextStyle(
                        fontFamily: 'Manrope',
                        fontFamilyFallback: ['Roboto', 'sans-serif'],
                        fontSize: 24,
                        fontWeight: FontWeight.w800,
                        color: AppColors.ink900,
                        height: 1.15,
                      ),
                    ),
                    if (banner.subtitle != null &&
                        banner.subtitle!.trim().isNotEmpty) ...[
                      const SizedBox(height: AppSpacing.s8),
                      Text(
                        banner.subtitle!,
                        style: const TextStyle(
                          fontFamily: 'Manrope',
                          fontFamilyFallback: ['Roboto', 'sans-serif'],
                          fontSize: 16,
                          fontWeight: FontWeight.w700,
                          color: AppColors.ink500,
                          height: 1.3,
                        ),
                      ),
                    ],
                    if (banner.detailMd != null &&
                        banner.detailMd!.trim().isNotEmpty) ...[
                      const SizedBox(height: AppSpacing.s16),
                      Text(
                        banner.detailMd!,
                        style: const TextStyle(
                          fontFamily: 'Manrope',
                          fontFamilyFallback: ['Roboto', 'sans-serif'],
                          fontSize: 15,
                          fontWeight: FontWeight.w500,
                          color: AppColors.ink900,
                          height: 1.5,
                        ),
                      ),
                    ],
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
