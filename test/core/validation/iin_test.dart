import 'package:flutter_test/flutter_test.dart';
import 'package:pharmacy/core/validation/iin.dart';

void main() {
  test('валидные ИИН проходят', () {
    for (final iin in [
      '900115300013',
      '850615400016',
      '990303500014',
      '781122300017',
      '951212500015',
    ]) {
      expect(isValidIin(iin), isTrue, reason: iin);
    }
  });

  test('неверная контрольная сумма отклоняется', () {
    expect(isValidIin('900115300010'), isFalse);
    expect(isValidIin('990101300123'), isFalse); // старый невалидный seed
  });

  test('неверный формат отклоняется', () {
    expect(isValidIin(null), isFalse);
    expect(isValidIin(''), isFalse);
    expect(isValidIin('12345'), isFalse);
    expect(isValidIin('9001153000130'), isFalse); // 13 цифр
    expect(isValidIin('90011530001a'), isFalse); // буква
  });

  test('невалидная дата рождения отклоняется', () {
    expect(isValidIin('901315300013'), isFalse); // месяц 13
    expect(isValidIin('900132300013'), isFalse); // день 32
    expect(isValidIin('900229300013'), isFalse); // 1990 не високосный
  });

  test('невалидная цифра века-пола отклоняется', () {
    expect(isValidIin('900115000013'), isFalse); // 0
    expect(isValidIin('900115700013'), isFalse); // 7
  });

  test('пробелы обрезаются', () {
    expect(isValidIin('  900115300013  '), isTrue);
  });
}
