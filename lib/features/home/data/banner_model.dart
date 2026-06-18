/// Баннер главной из `GET /api/mobile/banners` (публичный): картинка-промо,
/// заголовок/подзаголовок и опциональный детальный текст. Тап по карусели ведёт
/// ТОЛЬКО на детальный экран (без перехода на товар/URL — так решено).
class BannerModel {
  const BannerModel({
    required this.id,
    required this.title,
    this.subtitle,
    required this.imageUrl,
    this.detailMd,
  });

  final String id;
  final String title;
  final String? subtitle;
  final String imageUrl;

  /// Текст детального экрана (markdown-разметка опциональна). Рендерим как
  /// обычный текст — markdown не обязателен.
  final String? detailMd;

  factory BannerModel.fromJson(Map<String, dynamic> j) => BannerModel(
        id: j['id'] as String? ?? '',
        title: j['title'] as String? ?? '',
        subtitle: j['subtitle'] as String?,
        imageUrl: j['imageUrl'] as String? ?? '',
        detailMd: j['detailMd'] as String?,
      );
}
