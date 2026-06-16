import 'package:flutter/material.dart';

import '../../../../core/theme/app_colors.dart';
import '../../../../core/theme/app_gradients.dart';
import '../../../../core/theme/app_radii.dart';
import '../../../../core/theme/app_shadows.dart';
import '../../../../core/theme/app_spacing.dart';
import '../../data/home_repository.dart';

/// Горизонтальная карусель баннеров. Высота 234, ширина карточки 180 (−10%).
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
      height: 234,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
        itemCount: items.length,
        separatorBuilder: (_, __) => const SizedBox(width: 12),
        itemBuilder: (ctx, __) => _BannerPlaceholderCard(
          // Тап → баннер на весь экран (пока с плейсхолдером «контент появится позже»).
          onTap: () => Navigator.of(ctx).push(
            MaterialPageRoute(builder: (_) => const _BannerFullscreenScreen()),
          ),
        ),
      ),
    );
  }
}

/// Заглушка баннера: серый прямоугольник 180×234 (−10%) + «Баннер» по центру.
/// Кликабельна — открывает баннер на весь экран.
class _BannerPlaceholderCard extends StatelessWidget {
  const _BannerPlaceholderCard({required this.onTap});

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 180,
      height: 234,
      // Мягкая светлая подложка с лёгким градиентом + тень на внешнем контейнере.
      // Контент (иконка/подпись) приглушён — спокойный плейсхолдер без резкого контраста.
      decoration: BoxDecoration(
        gradient: AppGradients.bannerPlaceholder,
        borderRadius: AppRadii.brXl,
        boxShadow: AppShadows.card,
      ),
      clipBehavior: Clip.antiAlias,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          child: const Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.image_outlined, size: 40, color: AppColors.ink300),
                SizedBox(height: 10),
                Text(
                  'Баннер',
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 18,
                    fontWeight: FontWeight.w800,
                    color: AppColors.ink400,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Баннер на весь экран. Реальных баннеров пока нет → показываем плейсхолдер
/// «контент появится позже». Когда появятся настоящие баннеры — сюда ляжет картинка.
class _BannerFullscreenScreen extends StatelessWidget {
  const _BannerFullscreenScreen();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      body: SafeArea(
        child: Column(
          children: [
            // Шапка: кнопка «назад» слева.
            SizedBox(
              height: 48,
              child: Align(
                alignment: Alignment.centerLeft,
                child: IconButton(
                  icon: const Icon(Icons.arrow_back_rounded, color: AppColors.ink900),
                  onPressed: () => Navigator.of(context).maybePop(),
                ),
              ),
            ),
            Expanded(
              child: Center(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Container(
                        width: 96,
                        height: 96,
                        decoration: BoxDecoration(
                          color: AppColors.paperInput,
                          borderRadius: AppRadii.brXl,
                        ),
                        child: const Icon(Icons.image_outlined,
                            size: 44, color: AppColors.ink500),
                      ),
                      const SizedBox(height: 20),
                      const Text(
                        'Баннер',
                        style: TextStyle(
                          fontFamily: 'Manrope',
                          fontFamilyFallback: ['Roboto', 'sans-serif'],
                          fontSize: 22,
                          fontWeight: FontWeight.w800,
                          color: AppColors.ink900,
                        ),
                      ),
                      const SizedBox(height: 8),
                      const Text(
                        'Контент появится позже',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          fontFamily: 'Manrope',
                          fontFamilyFallback: ['Roboto', 'sans-serif'],
                          fontSize: 15,
                          fontWeight: FontWeight.w700,
                          color: AppColors.ink500,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
