/// Доменная модель чека и его статус.
///
/// Flow «доказательства переключения» (из ТЗ, диаграмма «Загрузка и сверка чеков»):
///
///   ШАГ 1 (вне приложения) — POSM регистрирует переключение клиента на промо-SKU:
///         создаётся pending-bonus запись с фармацевтом, SKU, временем, аптекой
///         и ожидаемой суммой. На этом этапе у пользователя в истории появляется
///         чек со статусом [ReceiptStatus.awaitingReceipt].
///
///   ШАГ 2 (наше приложение, раздел «Чеки») — фармацевт загружает фискальный чек
///         (фото или QR), OCR + ОФД парсят его, данные сопоставляются с pending
///         записью. Чек переходит в [ReceiptStatus.inReview].
///
///   ШАГ 3 (админ-панель, вне нашего приложения) — авто-одобрение / ручная
///         модерация / анти-фрод. Финал: [ReceiptStatus.confirmed] или
///         [ReceiptStatus.rejected].
library;

enum ReceiptStatus {
  /// POSM создал pending-bonus, но чек ещё не загружен.
  awaitingReceipt,

  /// Чек загружен, идёт проверка (OCR / ОФД / pending-бонус mismatch).
  inReview,

  /// Подтверждён, бонус зачислен (или зачислится в ближайшую выплату).
  confirmed,

  /// Отклонён (дубль, чужая аптека, поддельный, аномалия).
  rejected,
}

extension ReceiptStatusLabel on ReceiptStatus {
  /// UPPER-кейс метка для бейджа справа в строке истории.
  String get label => switch (this) {
        ReceiptStatus.awaitingReceipt => 'ОЖИДАЕТ ЧЕКА',
        ReceiptStatus.inReview => 'ПРОВЕРКА',
        ReceiptStatus.confirmed => 'ПОДТВЕРЖДЁН',
        ReceiptStatus.rejected => 'ОТКЛОНЁН',
      };
}

/// Один чек в истории фармацевта.
class Receipt {
  const Receipt({
    required this.id,
    required this.title,
    required this.amountKzt,
    required this.dateLabel,
    required this.status,
    this.rejectedReason,
    this.photoPath,
    this.pharmacy,
    this.cashier,
    this.sku,
  });

  final String id;
  final String title;
  final int amountKzt;
  final String dateLabel;
  final ReceiptStatus status;
  final String? rejectedReason;

  /// Локальный путь к фото чека (для только что загруженных; у API-чеков null —
  /// фото лежит в S3 и в истории не превью-рендерится).
  final String? photoPath;

  /// Парсенные дополнительные поля (mock OCR / ОФД).
  final String? pharmacy;
  final String? cashier;
  final String? sku;
}

/// Контракт репозитория чеков. Реализации: [MockReceiptRepository] (офлайн-демо) и
/// ApiReceiptRepository (backend `/api/mobile/receipts`). Выбор — по ApiConfig.useApi.
abstract interface class ReceiptRepository {
  /// Поток-сигнал к перезагрузке наблюдателей (после submit).
  Stream<void> get changes;

  /// История чеков фармацевта (свежие первыми).
  Future<List<Receipt>> loadReceipts();

  /// Отправить чек на проверку (фото + контекст). Возвращает созданный чек.
  /// Mock кладёт его локально; API делает multipart-upload, backend создаёт запись
  /// и прогоняет её через ReconcileService (логи Стандарт-Н + Excel + ручная модерация).
  Future<Receipt> submitReceipt({
    required String title,
    String? photoPath,
    String? pharmacyName,
  });
}
