import 'package:flutter_test/flutter_test.dart';
import 'package:pharmacy/core/validation/card.dart';

void main() {
  group('isValidCardNumber', () {
    test('валидные по Луну проходят (с форматированием и без)', () {
      expect(isValidCardNumber('4242424242424242'), isTrue);
      expect(isValidCardNumber('5555 5555 5555 4444'), isTrue);
      expect(isValidCardNumber('4111-1111-1111-1111'), isTrue);
      expect(isValidCardNumber('378282246310005'), isTrue); // Amex 15
      expect(isValidCardNumber('4222222222222'), isTrue); // Visa 13
    });

    test('неверная контрольная сумма не проходит', () {
      expect(isValidCardNumber('4242424242424241'), isFalse);
      expect(isValidCardNumber('1234567812345678'), isFalse);
    });

    test('длина вне диапазона 13-19 не проходит', () {
      expect(isValidCardNumber('424242424242'), isFalse); // 12
      expect(isValidCardNumber('42424242424242424242'), isFalse); // 20
    });

    test('null, пусто и буквы — false', () {
      expect(isValidCardNumber(null), isFalse);
      expect(isValidCardNumber(''), isFalse);
      expect(isValidCardNumber('abcd efgh ijkl mnop'), isFalse);
    });
  });

  group('detectCardBrand', () {
    test('по BIN', () {
      expect(detectCardBrand('4242424242424242'), CardBrand.visa);
      expect(detectCardBrand('5555555555554444'), CardBrand.mastercard);
      expect(detectCardBrand('2221000000000009'), CardBrand.mastercard);
      expect(detectCardBrand('378282246310005'), CardBrand.amex);
      expect(detectCardBrand('2200000000000004'), CardBrand.mir);
      expect(detectCardBrand('6200000000000005'), CardBrand.unionpay);
      expect(detectCardBrand('9999'), CardBrand.unknown);
      expect(detectCardBrand(''), CardBrand.unknown);
    });

    test('определяется по частичному вводу', () {
      expect(detectCardBrand('4'), CardBrand.visa);
      expect(detectCardBrand('52'), CardBrand.mastercard);
      expect(detectCardBrand('37'), CardBrand.amex);
    });
  });

  group('formatCardNumber', () {
    test('группами по 4', () {
      expect(formatCardNumber('4242424242424242'), '4242 4242 4242 4242');
      expect(formatCardNumber('4242-4242'), '4242 4242');
      expect(formatCardNumber('424'), '424');
    });
  });
}
