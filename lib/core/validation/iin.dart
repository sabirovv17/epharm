/// Валидация ИИН РК (физлицо) — формат + дата рождения + контрольная сумма.
///
/// Зеркало backend `IinUtil` (kz.epharm.shared). Используется в форме регистрации,
/// чтобы не отправлять заведомо некорректный ИИН на сервер (там та же проверка @Iin).
///
/// Структура (12 цифр): [0..5] ГГММДД, [6] век+пол (1,2→XIX; 3,4→XX; 5,6→XXI),
/// [7..10] порядковый, [11] контрольная цифра.
library;

const List<int> _weights1 = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11];
const List<int> _weights2 = [3, 4, 5, 6, 7, 8, 9, 10, 11, 1, 2];

bool isValidIin(String? raw) {
  final iin = raw?.trim() ?? '';
  if (!RegExp(r'^\d{12}$').hasMatch(iin)) return false;
  if (!_hasValidBirthDate(iin)) return false;

  var control = _checksum(iin, _weights1);
  if (control == 10) control = _checksum(iin, _weights2);
  if (control == 10) return false;
  return control == (iin.codeUnitAt(11) - 0x30);
}

int _checksum(String iin, List<int> weights) {
  var sum = 0;
  for (var i = 0; i < 11; i++) {
    sum += (iin.codeUnitAt(i) - 0x30) * weights[i];
  }
  return sum % 11;
}

bool _hasValidBirthDate(String iin) {
  final yy = int.parse(iin.substring(0, 2));
  final mm = int.parse(iin.substring(2, 4));
  final dd = int.parse(iin.substring(4, 6));
  final centuryDigit = iin.codeUnitAt(6) - 0x30;

  final int century;
  switch (centuryDigit) {
    case 1:
    case 2:
      century = 1800;
    case 3:
    case 4:
      century = 1900;
    case 5:
    case 6:
      century = 2000;
    default:
      return false; // 0/7/8/9 в 7-й позиции — не ИИН физлица
  }
  if (mm < 1 || mm > 12) return false;
  final int daysInMonth;
  switch (mm) {
    case 1:
    case 3:
    case 5:
    case 7:
    case 8:
    case 10:
    case 12:
      daysInMonth = 31;
    case 4:
    case 6:
    case 9:
    case 11:
      daysInMonth = 30;
    case 2:
      daysInMonth = _isLeapYear(century + yy) ? 29 : 28;
    default:
      return false;
  }
  return dd >= 1 && dd <= daysInMonth;
}

bool _isLeapYear(int year) =>
    (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
