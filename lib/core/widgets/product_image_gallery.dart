import 'package:flutter/material.dart';

import '../network/media.dart';
import '../theme/app_colors.dart';
import '../theme/app_radii.dart';

/// Листаемая галерея фото товара — единый виджет экосистемы (как в админке).
///
/// PageView + точки-индикатор + счётчик «i / N». Свайп листает фото. http-ссылки
/// Medusa проксируются (HTTPS) через [proxyMedia]. Битые кадры тихо выпадают
/// (errorBuilder → помечаем, фото исчезает из ленты); если не грузится ни одно —
/// аккуратная буква-плейсхолдер, а не «сломанная картинка».
class ProductImageGallery extends StatefulWidget {
  const ProductImageGallery({
    super.key,
    required this.images,
    required this.fallbackName,
    this.aspectRatio = 3 / 4,
  });

  /// Исходные URL фото (дубли/пустые отфильтруются). Порядок сохраняется.
  final List<String> images;

  /// Имя товара — для буквы-плейсхолдера, когда фото нет/не грузятся.
  final String fallbackName;

  final double aspectRatio;

  @override
  State<ProductImageGallery> createState() => _ProductImageGalleryState();
}

class _ProductImageGalleryState extends State<ProductImageGallery> {
  final _controller = PageController();
  final _broken = <String>{};
  int _page = 0;

  /// Кадры без дублей/пустых и без битых ссылок — то, что реально показываем.
  List<String> get _visible {
    final seen = <String>{};
    return widget.images
        .map((u) => u.trim())
        .where((u) => u.isNotEmpty && !_broken.contains(u) && seen.add(u))
        .toList();
  }

  void _markBroken(String url) {
    // errorBuilder зовётся во время build — setState откладываем на след. кадр.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || _broken.contains(url)) return;
      setState(() => _broken.add(url));
    });
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final visible = _visible;
    final page = visible.isEmpty ? 0 : _page.clamp(0, visible.length - 1);
    return ClipRRect(
      borderRadius: AppRadii.brXl,
      child: AspectRatio(
        aspectRatio: widget.aspectRatio,
        child: visible.isEmpty
            ? _placeholder()
            : Stack(
                fit: StackFit.expand,
                children: [
                  PageView.builder(
                    controller: _controller,
                    itemCount: visible.length,
                    onPageChanged: (i) => setState(() => _page = i),
                    itemBuilder: (_, i) {
                      final src = proxyMedia(visible[i]) ?? visible[i];
                      return Image.network(
                        src,
                        fit: BoxFit.cover,
                        width: double.infinity,
                        cacheWidth: 800,
                        loadingBuilder: (ctx, child, progress) =>
                            progress == null ? child : _loading(),
                        errorBuilder: (_, __, ___) {
                          _markBroken(visible[i]);
                          return _loading();
                        },
                      );
                    },
                  ),
                  if (visible.length > 1) ...[
                    Positioned(
                      top: 10,
                      right: 10,
                      child: _Counter(current: page + 1, total: visible.length),
                    ),
                    Positioned(
                      left: 0,
                      right: 0,
                      bottom: 10,
                      child: _Dots(count: visible.length, active: page),
                    ),
                  ],
                ],
              ),
      ),
    );
  }

  Widget _loading() => Container(color: AppColors.paperInput);

  Widget _placeholder() {
    final letter =
        widget.fallbackName.isNotEmpty ? widget.fallbackName.characters.first : '?';
    return Container(
      color: AppColors.brandGreen100,
      alignment: Alignment.center,
      child: Text(
        letter.toUpperCase(),
        style: const TextStyle(
          fontFamily: 'Manrope',
          fontFamilyFallback: ['Roboto', 'sans-serif'],
          fontSize: 44,
          fontWeight: FontWeight.w800,
          color: AppColors.brandGreen700,
        ),
      ),
    );
  }
}

/// Счётчик «i / N» — тёмная пилюля поверх фото.
class _Counter extends StatelessWidget {
  const _Counter({required this.current, required this.total});
  final int current;
  final int total;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.55),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        '$current / $total',
        style: const TextStyle(
          fontFamily: 'Manrope',
          fontFamilyFallback: ['Roboto', 'sans-serif'],
          fontSize: 12,
          fontWeight: FontWeight.w800,
          color: Colors.white,
        ),
      ),
    );
  }
}

/// Точки-индикатор: активная — белая и шире, остальные — полупрозрачные.
class _Dots extends StatelessWidget {
  const _Dots({required this.count, required this.active});
  final int count;
  final int active;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        for (var i = 0; i < count; i++)
          AnimatedContainer(
            duration: const Duration(milliseconds: 220),
            margin: const EdgeInsets.symmetric(horizontal: 3),
            width: i == active ? 18 : 7,
            height: 7,
            decoration: BoxDecoration(
              color: i == active
                  ? Colors.white
                  : Colors.white.withValues(alpha: 0.55),
              borderRadius: BorderRadius.circular(4),
              boxShadow: const [
                BoxShadow(color: Color(0x40000000), blurRadius: 3),
              ],
            ),
          ),
      ],
    );
  }
}
