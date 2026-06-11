import 'package:flutter/material.dart';

import '../../../../core/theme/app_colors.dart';
import '../../../../core/theme/app_radii.dart';
import '../../../../core/theme/app_shadows.dart';
import '../../../../core/theme/app_spacing.dart';
import '../../data/home_repository.dart';

/// Горизонтальная карусель баннеров. Высота 260, ширина карточки 200.
///
/// Реальных баннеров пока нет — каждый слот рисуется заглушкой: серая карточка
/// с иконкой-картинкой и надписью «Баннер» по центру (макет под будущую
/// картинку баннера). Количество слотов = количеству промо из репозитория.
class PromoCarousel extends StatelessWidget {
  const PromoCarousel({super.key, required this.items});

  final List<Promo> items;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 260,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
        itemCount: items.length,
        separatorBuilder: (_, __) => const SizedBox(width: 12),
        itemBuilder: (_, __) => const _BannerPlaceholderCard(),
      ),
    );
  }
}

/// Заглушка баннера: серый прямоугольник 200×260 + «Баннер» по центру.
class _BannerPlaceholderCard extends StatelessWidget {
  const _BannerPlaceholderCard();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 200,
      height: 260,
      decoration: BoxDecoration(
        color: AppColors.ink300,
        borderRadius: AppRadii.brXl,
        boxShadow: AppShadows.card,
      ),
      alignment: Alignment.center,
      child: const Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.image_outlined, size: 40, color: AppColors.ink500),
          SizedBox(height: 10),
          Text(
            'Баннер',
            style: TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: ['Roboto', 'sans-serif'],
              fontSize: 18,
              fontWeight: FontWeight.w800,
              color: AppColors.ink700,
            ),
          ),
        ],
      ),
    );
  }
}
