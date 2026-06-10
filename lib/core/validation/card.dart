/// Валидация и форматирование номера банковской карты.
///
/// Зеркало backend `CardNumberUtil` (kz.epharm.shared): длина 13–19 цифр + алгоритм
/// Луна (контрольная сумма). Это математическая проверка — отсекает опечатки и мусор,
/// действующую карту не гарантирует (это уже эквайринг). Используется в CardSheet,
/// чтобы не привязывать заведомо некорректный номер.
library;

enum CardBrand { visa, mastercard, mir, amex, unionpay, unknown }

String _digitsOnly(String s) => s.replaceAll(RegExp(r'[^0-9]'), '');

/// true — номер проходит длину (13–19) и алгоритм Луна.
bool isValidCardNumber(String? raw) {
  final digits = _digitsOnly(raw ?? '');
  if (digits.length < 13 || digits.length > 19) return false;
  return _luhnValid(digits);
}

bool _luhnValid(String digits) {
  var sum = 0;
  var alternate = false;
  for (var i = digits.length - 1; i >= 0; i--) {
    var d = digits.codeUnitAt(i) - 0x30;
    if (alternate) {
      d *= 2;
      if (d > 9) d -= 9;
    }
    sum += d;
    alternate = !alternate;
  }
  return sum % 10 == 0;
}

/// Платёжная система по BIN (первым цифрам). Достаточно частичного ввода —
/// определяется по мере набора, для иконки/подписи на карте.
CardBrand detectCardBrand(String? raw) {
  final d = _digitsOnly(raw ?? '');
  if (d.isEmpty) return CardBrand.unknown;

  if (d.startsWith('4')) return CardBrand.visa;
  if (RegExp(r'^3[47]').hasMatch(d)) return CardBrand.amex;
  if (d.startsWith('62')) return CardBrand.unionpay;

  if (d.length >= 2) {
    final two = int.parse(d.substring(0, 2));
    if (two >= 51 && two <= 55) return CardBrand.mastercard;
  }
  if (d.length >= 4) {
    final four = int.parse(d.substring(0, 4));
    if (four >= 2200 && four <= 2204) return CardBrand.mir;          // МИР
    if (four >= 2221 && four <= 2720) return CardBrand.mastercard;   // Mastercard 2-серия
  }
  return CardBrand.unknown;
}

String cardBrandLabel(CardBrand b) {
  switch (b) {
    case CardBrand.visa:
      return 'VISA';
    case CardBrand.mastercard:
      return 'MASTERCARD';
    case CardBrand.mir:
      return 'МИР';
    case CardBrand.amex:
      return 'AMEX';
    case CardBrand.unionpay:
      return 'UNIONPAY';
    case CardBrand.unknown:
      return '';
  }
}

/// Группами по 4: «1234 5678 9012 3456».
String formatCardNumber(String input) {
  final d = _digitsOnly(input);
  final buf = StringBuffer();
  for (var i = 0; i < d.length; i++) {
    if (i > 0 && i % 4 == 0) buf.write(' ');
    buf.write(d[i]);
  }
  return buf.toString();
}
