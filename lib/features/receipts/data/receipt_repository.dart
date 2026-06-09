/// Доменная модель чека и его статус.
///
/// Flow «доказательства переключения» (из ТЗ, диаграмма «Загрузка и сверка чеков»):
///
///   ШАГ 1 (вне приложения) — POSM регистрирует переключение клиента на промо-SKU:
///         создаётся pending-bonus запись с фармацевтом, SKU, временем, аптекой
///         и ожидаемой суммой. На этом этапе у пользователя в истории появляется
///         чек со статусом [ReceiptStatus.awaitingReceipt].
///
///   ШАГ 2 (наше приложение, раздел «Чеки») — фармацевт фотографирует фискальный
///         чек и выбирает аптеку, где купил. Чек уходит на сервер и сверяется с
///         логом кассы (Стандарт-Н) и Excel-выгрузкой. Чек переходит в
///         [ReceiptStatus.inReview].
///
///   ШАГ 3 (админ-панель, вне нашего приложения) — авто-одобрение (две галочки:
///         лог + Excel) / ручная модерация (одна или ноль галочек) / анти-фрод.
///         Финал: [ReceiptStatus.confirmed] или [ReceiptStatus.rejected].
library;

enum ReceiptStatus {
  /// POSM создал pending-bonus, но чек ещё не загружен.
  awaitingReceipt,

  /// Чек загружен, идёт сверка с логом кассы и Excel / ручная модерация.
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
    this.photoUrl,
    this.pharmacy,
    this.cashier,
    this.sku,
    this.bonus,
    this.bonusCredited = 0,
  });

  final String id;
  final String title;
  final int amountKzt;
  final String dateLabel;
  final ReceiptStatus status;
  final String? rejectedReason;

  /// Локальный путь к фото чека (для только что загруженных, до синка с сервером).
  final String? photoPath;

  /// URL фото в хранилище (S3/MinIO) — приходит с API; используется в детали чека.
  final String? photoUrl;

  /// Аптека, выбранная фармацевтом при загрузке (где совершена покупка).
  final String? pharmacy;
  final String? cashier;
  final String? sku;

  /// Бонус по чеку: [bonus] — ожидаемый, [bonusCredited] — уже зачислено (>0 после подтверждения).
  final int? bonus;
  final int bonusCredited;
}

/// Контракт репозитория чеков. Реализации: [MockReceiptRepository] (офлайн-демо) и
/// ApiReceiptRepository (backend `/api/mobile/receipts`). Выбор — по ApiConfig.useApi.
abstract interface class ReceiptRepository {
  /// Поток-сигнал к перезагрузке наблюдателей (после submit).
  Stream<void> get changes;

  /// История чеков фармацевта (свежие первыми).
  Future<List<Receipt>> loadReceipts();

  /// Отправить чек на проверку (фото + выбранная аптека). Возвращает созданный чек.
  /// Mock кладёт его локально; API делает multipart-upload, backend создаёт запись
  /// и прогоняет её через ReconcileService (логи Стандарт-Н + Excel + ручная модерация).
  /// [pharmacyId]/[pharmacyName] — аптека, выбранная фармацевтом (где совершена покупка):
  /// передаётся на сервер и сохраняется в чеке (видна в детали и в админ-очереди).
  Future<Receipt> submitReceipt({
    required String title,
    String? photoPath,
    String? pharmacyId,
    String? pharmacyName,
  });
}
