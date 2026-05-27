import 'package:flutter/material.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_shadows.dart';
import 'profile_page_scaffold.dart';

/// Экран «Сотрудничество» — описание трёх форматов партнёрства + контакты + CTA.
///
/// См. `_reference/profile-pages/cooperation.jsx`.
class CooperationScreen extends StatelessWidget {
  const CooperationScreen({super.key});

  static const _cards = <_CoopCard>[
    _CoopCard(
      title: 'Аптечным сетям',
      body:
          'Подключите свои точки к Epharm и получите доступ к бонусной аналитике, новым клиентам и совместным кампаниям с производителями.',
      glyph: '⚕',
      gradient: LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [Color(0xFF21D17A), Color(0xFF16A65C)],
      ),
    ),
    _CoopCard(
      title: 'Производителям',
      body:
          'Запустите бонусные акции на свои препараты, отслеживайте чеки в реальном времени и платите за результат, а не за охват.',
      glyph: '⚗',
      gradient: LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [Color(0xFF5560FB), Color(0xFF2A2BE2)],
      ),
    ),
    _CoopCard(
      title: 'Дистрибьюторам',
      body:
          'Интегрируйте Epharm в собственные программы лояльности — приватный sandbox, API и поддержка инженеров на запуске.',
      glyph: '⇄',
      gradient: LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [Color(0xFFF4B73A), Color(0xFFD69010)],
      ),
    ),
  ];

  static const _contacts = <_ContactRow>[
    _ContactRow(
      icon: Icons.mail_outline_rounded,
      label: 'partners@pharmapay.kz',
      sub: 'Партнёрский отдел',
    ),
    _ContactRow(
      icon: Icons.phone_outlined,
      label: '+7 (700) 123-45-67',
      sub: 'Пн–Пт, 09:00–19:00 (Астана)',
    ),
    _ContactRow(
      icon: Icons.favorite_border_rounded,
      label: 'WhatsApp для партнёров',
      sub: 'Быстрый ответ в течение часа',
    ),
  ];

  @override
  Widget build(BuildContext context) {
    return ProfilePageScaffold(
      title: 'Сотрудничество',
      child: ListView(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 40),
        children: [
          // Intro line.
          const Padding(
            padding: EdgeInsets.symmetric(horizontal: 4),
            child: Text(
              'Epharm — независимая платформа, которая связывает аптеки, '
              'производителей и покупателей через фискальный чек. '
              'Открыты к партнёрству — выберите подходящий формат.',
              style: TextStyle(
                fontFamily: 'Manrope',
                fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 14,
                fontWeight: FontWeight.w700,
                color: AppColors.ink700,
                height: 1.4,
              ),
            ),
          ),
          const SizedBox(height: 20),

          // 3 partner cards.
          for (final card in _cards) ...[
            _CoopCardView(card: card),
            const SizedBox(height: 12),
          ],

          const SizedBox(height: 12),

          // Contact header.
          const Padding(
            padding: EdgeInsets.symmetric(horizontal: 4),
            child: Text(
              'Связаться с нами',
              style: TextStyle(
                fontFamily: 'Manrope',
                fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 18,
                fontWeight: FontWeight.w800,
                color: AppColors.ink900,
              ),
            ),
          ),
          const SizedBox(height: 12),

          // Contact rows.
          Container(
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(16),
              boxShadow: AppShadows.card,
            ),
            child: Column(
              children: [
                for (var i = 0; i < _contacts.length; i++) ...[
                  if (i > 0)
                    Container(
                      height: 1,
                      color: AppColors.paperInput,
                    ),
                  _ContactRowView(row: _contacts[i]),
                ],
              ],
            ),
          ),
          const SizedBox(height: 24),

          // CTA.
          Material(
            color: Colors.transparent,
            child: InkWell(
              onTap: () {},
              borderRadius: BorderRadius.circular(99),
              child: Container(
                height: 58,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: AppColors.brandBlue600,
                  borderRadius: BorderRadius.circular(99),
                  boxShadow: const [
                    BoxShadow(
                      color: Color(0x4D2A2BE2),
                      offset: Offset(0, 8),
                      blurRadius: 20,
                    ),
                  ],
                ),
                child: const Text(
                  'Написать в партнёрский отдел',
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 17,
                    fontWeight: FontWeight.w800,
                    color: Colors.white,
                  ),
                ),
              ),
            ),
          ),
          const SizedBox(height: 16),

          // Footer.
          const Text(
            'ТОО «Epharm» · БИН 230440007098 · Астана, Дінмұхамед Қонаев 12/1',
            textAlign: TextAlign.center,
            style: TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: ['Roboto', 'sans-serif'],
              fontSize: 12,
              fontWeight: FontWeight.w700,
              color: AppColors.ink400,
              height: 1.4,
            ),
          ),
        ],
      ),
    );
  }
}

class _CoopCard {
  const _CoopCard({
    required this.title,
    required this.body,
    required this.glyph,
    required this.gradient,
  });
  final String title;
  final String body;
  final String glyph;
  final LinearGradient gradient;
}

class _CoopCardView extends StatelessWidget {
  const _CoopCardView({required this.card});
  final _CoopCard card;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        gradient: card.gradient,
        borderRadius: BorderRadius.circular(16),
        boxShadow: const [
          BoxShadow(
            color: Color(0x1A0F1424),
            offset: Offset(0, 8),
            blurRadius: 18,
          ),
        ],
      ),
      child: Stack(
        children: [
          // Big translucent glyph in corner.
          Positioned(
            right: -8,
            bottom: -28,
            child: Text(
              card.glyph,
              style: TextStyle(
                fontFamily: 'Manrope',
                fontFamilyFallback: const ['Roboto', 'sans-serif'],
                fontSize: 96,
                fontWeight: FontWeight.w800,
                color: Colors.white.withValues(alpha: 0.2),
                height: 1.0,
              ),
            ),
          ),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                card.title,
                style: const TextStyle(
                  fontFamily: 'Manrope',
                  fontFamilyFallback: ['Roboto', 'sans-serif'],
                  fontSize: 20,
                  fontWeight: FontWeight.w800,
                  color: Colors.white,
                  height: 1.15,
                ),
              ),
              const SizedBox(height: 8),
              SizedBox(
                width: MediaQuery.of(context).size.width * 0.65,
                child: Text(
                  card.body,
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: const ['Roboto', 'sans-serif'],
                    fontSize: 13,
                    fontWeight: FontWeight.w700,
                    color: Colors.white.withValues(alpha: 0.9),
                    height: 1.4,
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _ContactRow {
  const _ContactRow({
    required this.icon,
    required this.label,
    required this.sub,
  });
  final IconData icon;
  final String label;
  final String sub;
}

class _ContactRowView extends StatelessWidget {
  const _ContactRowView({required this.row});
  final _ContactRow row;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
      child: Row(
        children: [
          Container(
            width: 40,
            height: 40,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: AppColors.brandBlue100,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Icon(row.icon, size: 20, color: AppColors.brandBlue600),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  row.label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 15,
                    fontWeight: FontWeight.w700,
                    color: AppColors.ink900,
                    height: 1.2,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  row.sub,
                  style: const TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 12,
                    fontWeight: FontWeight.w700,
                    color: AppColors.ink400,
                    height: 1.2,
                  ),
                ),
              ],
            ),
          ),
          const Icon(
            Icons.chevron_right_rounded,
            size: 22,
            color: AppColors.ink400,
          ),
        ],
      ),
    );
  }
}
