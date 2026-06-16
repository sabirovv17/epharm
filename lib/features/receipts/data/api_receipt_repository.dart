import 'dart:async';
import 'dart:io';

import '../../../core/network/api_client.dart';
import 'receipt_repository.dart';

/// Реализация поверх backend `/api/mobile/receipts`.
/// submit → multipart-upload только фото (ДОП.8: аптека из профиля, акции — авто-матчинг);
/// backend создаёт чек и прогоняет его через ReconcileService. История — GET.
class ApiReceiptRepository implements ReceiptRepository {
  ApiReceiptRepository(this._client);

  final ApiClient _client;
  final _changes = StreamController<void>.broadcast();

  @override
  Stream<void> get changes => _changes.stream;

  @override
  Future<List<Receipt>> loadReceipts() async {
    final json = await _client.getJsonList('/api/mobile/receipts');
    return json.map((e) => _fromDto(e as Map<String, dynamic>)).toList();
  }

  @override
  Future<Receipt> submitReceipt({
    required String title,
    String? photoPath,
  }) async {
    List<int>? bytes;
    if (photoPath != null) {
      final file = File(photoPath);
      if (await file.exists()) bytes = await file.readAsBytes();
    }
    // ДОП.8: только фото. Аптеку backend берёт из профиля, акции матчит сам.
    final json = await _client.postMultipart(
      '/api/mobile/receipts',
      fields: const <String, String>{},
      fileBytes: bytes,
      fileField: bytes != null ? 'file' : null,
      fileName: 'receipt.jpg',
    );
    final receipt = _fromDto(json);
    _changes.add(null);
    return receipt;
  }

  // ── Маппинг MobileReceiptDto → Receipt ──────────────────────────────────────

  Receipt _fromDto(Map<String, dynamic> j) => Receipt(
        id: j['id'] as String,
        title: (j['productName'] as String?) ?? 'Чек',
        amountKzt: (j['amount'] as num?)?.toInt() ?? 0,
        dateLabel: _formatDate(j['createdAt'] as String?),
        status: _mapStatus(j['status'] as String?),
        rejectedReason: j['rejectedReason'] as String?,
        // Фото у API-чеков в S3 — в истории превью не рендерим (там status-иконка),
        // но в детали чека показываем по photoUrl.
        photoPath: null,
        photoUrl: j['photoUrl'] as String?,
        pharmacy: j['pharmacyName'] as String?,
        sku: j['sku'] as String?,
        bonus: (j['bonus'] as num?)?.toInt(),
        bonusCredited: (j['bonusCredited'] as num?)?.toInt() ?? 0,
      );

  ReceiptStatus _mapStatus(String? s) => switch (s) {
        'confirmed' => ReceiptStatus.confirmed,
        'rejected' => ReceiptStatus.rejected,
        _ => ReceiptStatus.inReview,
      };

  static String _formatDate(String? iso) {
    if (iso == null) return '';
    final dt = DateTime.tryParse(iso)?.toLocal();
    if (dt == null) return '';
    final dd = dt.day.toString().padLeft(2, '0');
    final mm = dt.month.toString().padLeft(2, '0');
    final hh = dt.hour.toString().padLeft(2, '0');
    final mi = dt.minute.toString().padLeft(2, '0');
    return '$dd.$mm · $hh:$mi';
  }
}
