import 'dart:async';
import 'dart:math' as math;

import 'receipt_repository.dart';

/// In-memory реализация: хранит список чеков, submit кладёт новый в начало.
/// Сохраняется как офлайн-демо при USE_API=false.
class MockReceiptRepository implements ReceiptRepository {
  final List<Receipt> _state = List<Receipt>.from(_initialMock);
  final _changes = StreamController<void>.broadcast();

  @override
  Stream<void> get changes => _changes.stream;

  @override
  Future<List<Receipt>> loadReceipts() async {
    await Future<void>.delayed(const Duration(milliseconds: 300));
    return List.unmodifiable(_state);
  }

  @override
  Future<Receipt> submitReceipt({
    required String title,
    String? photoPath,
    String? pharmacyName,
  }) async {
    await Future<void>.delayed(const Duration(milliseconds: 200));
    final now = DateTime.now();
    final receipt = Receipt(
      id: _newId(),
      title: title,
      amountKzt: 0, // сумму на mock не считаем — на сервере OCR
      dateLabel: _formatNow(now),
      status: ReceiptStatus.inReview,
      photoPath: photoPath,
      pharmacy: pharmacyName,
    );
    _state.insert(0, receipt);
    _changes.add(null);
    return receipt;
  }

  String _newId() {
    final ts = DateTime.now().millisecondsSinceEpoch;
    final rnd = math.Random().nextInt(0xFFFF).toRadixString(16);
    return 'r$ts$rnd';
  }

  static String _formatNow(DateTime now) {
    final dd = now.day.toString().padLeft(2, '0');
    final mm = now.month.toString().padLeft(2, '0');
    final hh = now.hour.toString().padLeft(2, '0');
    final mi = now.minute.toString().padLeft(2, '0');
    return '$dd.$mm · $hh:$mi';
  }

  static const List<Receipt> _initialMock = [
    Receipt(id: 'r1', title: 'Larimide Lifting', amountKzt: 25000, dateLabel: '14.05 · 14:23', status: ReceiptStatus.confirmed),
    Receipt(id: 'r2', title: 'SelfieLab AHA', amountKzt: 3835, dateLabel: '14.05 · 12:15', status: ReceiptStatus.inReview),
    Receipt(id: 'r3', title: 'Ivatherm крем', amountKzt: 7500, dateLabel: '14.05 · 09:42', status: ReceiptStatus.awaitingReceipt),
    Receipt(
      id: 'r4',
      title: 'Deo 2-7',
      amountKzt: 18000,
      dateLabel: '13.05 · 17:42',
      status: ReceiptStatus.rejected,
      rejectedReason: 'Чек уже использован',
    ),
    Receipt(id: 'r5', title: 'АкваДетрим Форте', amountKzt: 4200, dateLabel: '12.05 · 18:11', status: ReceiptStatus.confirmed),
    Receipt(id: 'r6', title: 'Эссенциале форте Н', amountKzt: 12340, dateLabel: '11.05 · 11:08', status: ReceiptStatus.confirmed),
  ];
}
