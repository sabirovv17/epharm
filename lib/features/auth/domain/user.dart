/// Доменная модель пользователя-фармацевта.
///
/// Простая иммутабельная value-class — пока без freezed,
/// чтобы не блокироваться на code generation до установки SDK.
class User {
  const User({
    required this.fio,
    required this.phone,
    required this.iin,
    this.balanceKzt = 0,
  });

  /// Полное имя, минимум 2 whitespace-токена.
  final String fio;

  /// `+7 (XXX) XXX-XX-XX` — канонический формат.
  final String phone;

  /// ИИН Казахстана — ровно 12 цифр.
  final String iin;

  /// Текущий бонусный баланс в тенге. После регистрации = 0.
  final int balanceKzt;

  User copyWith({
    String? fio,
    String? phone,
    String? iin,
    int? balanceKzt,
  }) =>
      User(
        fio: fio ?? this.fio,
        phone: phone ?? this.phone,
        iin: iin ?? this.iin,
        balanceKzt: balanceKzt ?? this.balanceKzt,
      );

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is User &&
          fio == other.fio &&
          phone == other.phone &&
          iin == other.iin &&
          balanceKzt == other.balanceKzt;

  @override
  int get hashCode => Object.hash(fio, phone, iin, balanceKzt);
}
