import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/widgets.dart';

import '../network/image_cache_config.dart';
import '../network/media.dart';

/// Сетевое фото витрины с ДИСКОВЫМ кэшем — единая замена голому `Image.network`
/// во всех скроллящихся списках/гридах витрины (лента, каталог, карусель, ленты
/// рекомендаций).
///
/// Что делает (и сохраняет от старого `Image.network`):
///  - прогоняет url через [proxyMedia] (http→https-прокси /api/media/img) —
///    логика проксирования НЕ дублируется по местам вызова;
///  - [cacheWidth] → `memCacheWidth`: декод под размер ячейки (та же экономия
///    памяти/GPU, что и раньше) — НЕ держим в памяти полноразмерные фото;
///  - кадр кэшируется на ДИСК ([MediaCache]) → при возврате плитки во вьюпорт и
///    после рестарта приложения нет повторного сетевого запроса и пере-декода
///    из сети — это и убирает микро-лаги скролла;
///  - [placeholder] показывается на время загрузки и при ошибке (тот же мягкий
///    фолбэк, что был в `loadingBuilder`/`errorBuilder`);
///  - короткий fade-in только при ПЕРВОЙ загрузке; из кэша фото появляется
///    мгновенно и без мелькания placeholder при ребилде грида (аналог прежнего
///    `gaplessPlayback: true`).
class MediaImage extends StatelessWidget {
  const MediaImage({
    super.key,
    required this.url,
    required this.placeholder,
    this.width,
    this.height,
    this.fit = BoxFit.cover,
    this.cacheWidth,
  });

  /// Исходный url фото (до проксирования). null/пусто → сразу [placeholder].
  final String? url;

  /// Билдер заглушки (буква-плейсхолдер / нейтральная подложка). Используется
  /// и во время загрузки, и при ошибке.
  final Widget Function() placeholder;

  final double? width;
  final double? height;
  final BoxFit fit;

  /// Ширина декода в пикселях (как прежний `cacheWidth`): обычно ширина ячейки×DPR.
  final int? cacheWidth;

  @override
  Widget build(BuildContext context) {
    // width=cacheWidth → прокси отдаёт превью ровно под ячейку (быстрая первая
    // загрузка, мало трафика). Декод тоже под эту ширину (memCacheWidth ниже).
    final resolved = proxyMedia(url, width: cacheWidth);
    if (resolved == null || resolved.isEmpty) return placeholder();
    return CachedNetworkImage(
      imageUrl: resolved,
      cacheManager: MediaCache.instance,
      width: width,
      height: height,
      fit: fit,
      memCacheWidth: cacheWidth, // декод под ячейку (= прежний cacheWidth)
      fadeInDuration: const Duration(milliseconds: 150),
      fadeOutDuration: Duration.zero,
      placeholder: (_, __) => placeholder(),
      errorWidget: (_, __, ___) => placeholder(),
    );
  }
}
