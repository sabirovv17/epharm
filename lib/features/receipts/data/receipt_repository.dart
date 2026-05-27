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

import 'dart:async';
import 'dart:math' as math;

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

  /// Локальный путь к фото чека (для только что загруженных).
  final String? photoPath;

  /// Парсенные дополнительные поля (mock OCR / ОФД).
  final String? pharmacy;
  final String? cashier;
  final String? sku;
}

/// Stateful mock-репозиторий чеков. Хранит in-memory список который можно
/// расширять методом [addReceipt]. После реал-API заменим на HTTP-репозиторий
/// с тем же интерфейсом.
class ReceiptRepository {
  final List<Receipt> _state = List<Receipt>.from(_initialMock);
  final _changes = StreamController<void>.broadcast();

  /// Поток нотификаций — сигнал к перезагрузке наблюдателей.
  Stream<void> get changes => _changes.stream;

  Future<List<Receipt>> loadReceipts() async {
    await Future<void>.delayed(const Duration(milliseconds: 300));
    return List.unmodifiable(_state);
  }

  /// Добавить новый чек в начало списка. Используется после успешной отправки
  /// в Receipt Review.
  Future<void> addReceipt(Receipt r) async {
    await Future<void>.delayed(const Duration(milliseconds: 200));
    _state.insert(0, r);
    _changes.add(null);
  }

  /// Сгенерировать ID для нового чека.
  String newId() {
    final ts = DateTime.now().millisecondsSinceEpoch;
    final rnd = math.Random().nextInt(0xFFFF).toRadixString(16);
    return 'r$ts$rnd';
  }

  static const List<Receipt> _initialMock = [
    Receipt(
      id: 'r1',
      title: 'Larimide Lifting',
      amountKzt: 25000,
      dateLabel: '14.05 · 14:23',
      status: ReceiptStatus.confirmed,
    ),
    Receipt(
      id: 'r2',
      title: 'SelfieLab AHA',
      amountKzt: 3835,
      dateLabel: '14.05 · 12:15',
      status: ReceiptStatus.inReview,
    ),
    Receipt(
      id: 'r3',
      title: 'Ivatherm крем',
      amountKzt: 7500,
      dateLabel: '14.05 · 09:42',
      status: ReceiptStatus.awaitingReceipt,
    ),
    Receipt(
      id: 'r4',
      title: 'Deo 2-7',
      amountKzt: 18000,
      dateLabel: '13.05 · 17:42',
      status: ReceiptStatus.rejected,
      rejectedReason: 'Чек уже использован',
    ),
    Receipt(
      id: 'r5',
      title: 'АкваДетрим Форте',
      amountKzt: 4200,
      dateLabel: '12.05 · 18:11',
      status: ReceiptStatus.confirmed,
    ),
    Receipt(
      id: 'r6',
      title: 'Эссенциале форте Н',
      amountKzt: 12340,
      dateLabel: '11.05 · 11:08',
      status: ReceiptStatus.confirmed,
    ),
  ];
}
