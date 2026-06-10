import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_gradients.dart';
import '../../../core/theme/app_radii.dart';
import '../../../core/theme/app_shadows.dart';
import '../../../core/theme/app_spacing.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/theme/app_typography.dart';
import '../../../core/widgets/brand_icons.dart';
import '../../../core/widgets/filter_chip_row.dart';
import '../../../core/widgets/glass_pill.dart';
import '../../../core/widgets/pharma_logo.dart';
import '../../../core/widgets/search_input.dart';
import '../../auth/application/auth_controller.dart';
import '../../profile/application/profile_controller.dart';
import '../../receipts/presentation/receipts_list_screen.dart';
import '../application/home_controller.dart';
import '../data/home_repository.dart';
import '../../receipts/presentation/upload_prompt_sheet.dart';
import 'widgets/balance_card.dart';
import 'widgets/bottom_navigation.dart';
import 'widgets/brand_sheet.dart';
import 'widgets/contests_stub_screen.dart';
import 'widgets/home_welcome_gate.dart';
import 'widgets/learning_stub_screen.dart';
import 'widgets/login_invite_card.dart';
import 'widgets/product_card.dart';
import 'widgets/product_detail_sheet.dart';
import 'widgets/profile_row.dart';
import 'widgets/promo_carousel.dart';
import 'widgets/sort_sheet.dart';

class HomeScreen extends ConsumerStatefulWidget {
  const HomeScreen({super.key});

  @override
  ConsumerState<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends ConsumerState<HomeScreen> {
  int _tab = 0;

  @override
  void initState() {
    super.initState();
    // При входе на Home подтягиваем свежий баланс из backend (в API-режиме).
    // В mock-режиме refreshMe — no-op, баланс остаётся из login-ответа.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (ref.read(currentUserProvider) != null) {
        ref.read(profileActionsProvider).refreshMe();
      }
    });
  }

  void _goToAuth() => context.go('/auth/phone');

  /// 2-tab nav: 0=Главная, 1=Профиль. Между ними — центральный FAB-камера,
  /// открывающий upload sheet (не таб, не меняет [_tab]).
  ///
  /// История: 4 таба → 3 таба → 2 таба. «Чек» удалён в Этапе 4 (pull-up sheet
  /// из BalanceCard). «Обучение» перенесено в Profile menu в Этапе 5 — раздел
  /// пока stub-only, не заслуживает отдельного таба.
  void _onTabTap(int i) {
    setState(() => _tab = i);
  }

  /// Открыть историю чеков как full-screen route (раньше переключали
  /// `_tab = 2` на Чек-таб, который сейчас удалён из bottom-nav). Вызывается
  /// из BalanceCard «История» и из glass-pill «История» в Profile.
  void _openReceiptsHistory() {
    final loggedIn = ref.read(currentUserProvider) != null;
    if (!loggedIn) {
      _goToAuth();
      return;
    }
    Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => const ReceiptsListScreen()),
    );
  }

  @override
  Widget build(BuildContext context) {
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: systemUiOverlayStyleDark,
      child: Scaffold(
        backgroundColor: AppColors.paperCanvas,
        body: switch (_tab) {
          0 => _HomeTab(
              onLogin: _goToAuth,
              onHistoryTap: _openReceiptsHistory,
            ),
          1 => _ProfileTab(
              onLogin: _goToAuth,
              onHistoryTap: _openReceiptsHistory,
            ),
          _ => const SizedBox.shrink(),
        },
        bottomNavigationBar: PharmaBottomNav(
          currentIndex: _tab,
          onTap: _onTabTap,
          onScanTap: () => showUploadPromptSheet(context),
        ),
      ),
    );
  }
}

class _HomeTab extends ConsumerWidget {
  const _HomeTab({required this.onLogin, required this.onHistoryTap});
  final VoidCallback onLogin;
  final VoidCallback onHistoryTap;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final promoAsync = ref.watch(promoListProvider);
    final productsAsync = ref.watch(productListProvider);
    final chip = ref.watch(homeChipProvider);
    final brands = ref.watch(selectedBrandsProvider);
    final sort = ref.watch(homeSortProvider);
    final query = ref.watch(searchQueryProvider);

    // Header теперь — обычный sliver внутри CustomScrollView. Раньше он был
    // в Stack/Positioned поверх скролла (sticky), но это создавало впечатление
    // «зафиксированной зелёной плашки», под которой контент уезжал — UX
    // путаный, особенно на short-screen Android'ах. Сейчас вся страница
    // прокручивается единым flow: зелёная шапка → промо → каталог.
    return CustomScrollView(
      // Чтобы overflow от тяжёлых теней (promo cards) не обрезался.
      clipBehavior: Clip.none,
      slivers: [
        // 1) Зелёный header — часть скролла, не sticky.
        SliverToBoxAdapter(
          child: _Header(
            user: user,
            onLogin: onLogin,
            onHistoryTap: onHistoryTap,
          ),
        ),

        // 2) Промо-карусель.
        SliverToBoxAdapter(
          child: Padding(
            padding: const EdgeInsets.only(top: 16, bottom: 8),
            child: promoAsync.when(
              loading: () => const SizedBox(
                height: 260,
                child: Center(child: CircularProgressIndicator()),
              ),
              error: (e, _) => Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: AppSpacing.screenEdge,
                ),
                child: Text('Ошибка: $e'),
              ),
              data: (promos) => PromoCarousel(items: promos),
            ),
          ),
        ),

        // 3) Search.
        SliverToBoxAdapter(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(
              AppSpacing.screenEdge,
              8,
              AppSpacing.screenEdge,
              AppSpacing.s16,
            ),
            child: SearchInput(
              onChanged: (v) =>
                  ref.read(searchQueryProvider.notifier).set(v),
            ),
          ),
        ),

        // 3.5) Вход в реальный каталог товаров (витрина inkar.kz через
        // бэкенд-прокси Medusa). Отдельно от промо-акций выше — это полный
        // каталог аптечных товаров с поиском.
        SliverToBoxAdapter(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(
              AppSpacing.screenEdge,
              0,
              AppSpacing.screenEdge,
              AppSpacing.s16,
            ),
            child: Container(
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: AppRadii.brXl,
                boxShadow: AppShadows.card,
              ),
              child: Material(
                color: Colors.transparent,
                borderRadius: AppRadii.brXl,
                child: InkWell(
                  borderRadius: AppRadii.brXl,
                  onTap: () => context.push('/catalog'),
                  child: Padding(
                    padding: const EdgeInsets.all(14),
                    child: Row(
                      children: [
                        Container(
                          width: 44,
                          height: 44,
                          alignment: Alignment.center,
                          decoration: BoxDecoration(
                            color: AppColors.brandGreen600,
                            borderRadius: BorderRadius.circular(12),
                          ),
                          child: const Icon(
                            Icons.storefront_outlined,
                            size: 22,
                            color: Colors.white,
                          ),
                        ),
                        const SizedBox(width: 12),
                        const Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Text(
                                'Каталог товаров',
                                style: TextStyle(
                                  fontFamily: 'Manrope',
                                  fontFamilyFallback: ['Roboto', 'sans-serif'],
                                  fontSize: 16,
                                  fontWeight: FontWeight.w800,
                                  color: AppColors.ink900,
                                ),
                              ),
                              SizedBox(height: 2),
                              Text(
                                'Поиск по реальному каталогу аптек',
                                style: TextStyle(
                                  fontFamily: 'Manrope',
                                  fontFamilyFallback: ['Roboto', 'sans-serif'],
                                  fontSize: 12,
                                  fontWeight: FontWeight.w700,
                                  color: AppColors.ink500,
                                ),
                              ),
                            ],
                          ),
                        ),
                        const Icon(
                          Icons.chevron_right_rounded,
                          color: AppColors.ink400,
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),

        // 4) Filter row.
        SliverToBoxAdapter(
          child: SizedBox(
            height: 56,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(
                horizontal: AppSpacing.screenEdge,
              ),
              children: [
                Center(
                  child: SortChipButton(
                    onTap: () => showSortSheet(context),
                    active: sort != SortOption.newestFirst,
                  ),
                ),
                const SizedBox(width: 8),
                Center(
                  child: PharmaFilterChip(
                    label: brands.isEmpty
                        ? 'Бренд'
                        : 'Бренд · ${brands.length}',
                    active: brands.isNotEmpty,
                    trailing: const Icon(
                      Icons.keyboard_arrow_down,
                      size: 16,
                      color: AppColors.ink900,
                    ),
                    onTap: () => showBrandSheet(context),
                  ),
                ),
                const SizedBox(width: 8),
                Center(
                  child: PharmaFilterChip(
                    label: 'Все',
                    active: chip == HomeChip.all,
                    onTap: () => ref
                        .read(homeChipProvider.notifier)
                        .set(HomeChip.all),
                  ),
                ),
                const SizedBox(width: 8),
                Center(
                  child: PharmaFilterChip(
                    label: 'Новинки',
                    active: chip == HomeChip.isNew,
                    onTap: () => ref
                        .read(homeChipProvider.notifier)
                        .set(HomeChip.isNew),
                  ),
                ),
                const SizedBox(width: 8),
                Center(
                  child: PharmaFilterChip(
                    label: 'Конкурсные',
                    active: chip == HomeChip.contest,
                    leading: const TrophyEmojiGlyph(size: 22),
                    onTap: () => ref
                        .read(homeChipProvider.notifier)
                        .set(HomeChip.contest),
                  ),
                ),
              ],
            ),
          ),
        ),

        const SliverToBoxAdapter(child: SizedBox(height: 16)),

        // 5) Products.
        productsAsync.when(
          loading: () => const SliverToBoxAdapter(
            child: Padding(
              padding: EdgeInsets.all(AppSpacing.s24),
              child: Center(child: CircularProgressIndicator()),
            ),
          ),
          error: (e, _) => SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.symmetric(
                horizontal: AppSpacing.screenEdge,
              ),
              child: Text('Ошибка: $e'),
            ),
          ),
          data: (products) {
            final filtered = applyHomeFilters(
              products: products,
              chip: chip,
              brands: brands,
              query: query,
              sort: sort,
            );
            final featured =
                filtered.where((p) => p.featured).firstOrNull;
            final rest =
                filtered.where((p) => p != featured).toList();
            return _ProductsSliver(
              featured: featured,
              rest: rest,
              empty: filtered.isEmpty,
            );
          },
        ),

        const SliverToBoxAdapter(child: SizedBox(height: 32)),
      ],
    );
  }
}

class _ProductsSliver extends StatelessWidget {
  const _ProductsSliver({
    required this.featured,
    required this.rest,
    required this.empty,
  });

  final Product? featured;
  final List<Product> rest;
  final bool empty;

  @override
  Widget build(BuildContext context) {
    return SliverPadding(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
      sliver: SliverList(
        delegate: SliverChildListDelegate.fixed([
          if (empty)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 32),
              child: Text(
                'Ничего не найдено по этому фильтру',
                textAlign: TextAlign.center,
                style: AppTypography.body14(color: AppColors.ink500),
              ),
            ),
          if (featured != null) ...[
            Builder(
              builder: (ctx) => BigProductCard(
                product: featured!,
                onTap: () => showProductDetailSheet(ctx, featured!),
              ),
            ),
            // Минимальный gap между featured-карточкой и 2-col сеткой:
            // shadow/card у BigProductCard сам создаёт визуальный воздух,
            // поэтому SizedBox можно держать совсем небольшим. Раньше 12 —
            // в сочетании с «лишней» высотой mainAxisExtent давало большую
            // пустоту между секциями.
            const SizedBox(height: 4),
          ],
          if (rest.isNotEmpty) _SmallProductGrid(items: rest),
        ]),
      ),
    );
  }
}

class _SmallProductGrid extends StatelessWidget {
  const _SmallProductGrid({required this.items});
  final List<Product> items;

  @override
  Widget build(BuildContext context) {
    return GridView.builder(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 2,
        crossAxisSpacing: 12,
        mainAxisSpacing: 12,
        // mainAxisExtent = 226: snug fit для карточек с restrictions
        // (≈ 223 содержание: padding 24 + image 140 + gap 8 + name 32 (2-line) +
        // gap 4 + restrictions 15 (1-line)) + 3px буфер.
        // Раньше было 240 — оставляло ~36px пустого белого внизу карточек без
        // restrictions, создавая визуальную «дыру» под featured-карточкой.
        // Промежуточная попытка 218 переoверфлоулилась на карточках с
        // restrictions (см. RenderFlex overflow 18 px). 226 — sweet spot.
        mainAxisExtent: 226,
      ),
      itemCount: items.length,
      itemBuilder: (ctx, i) => SmallProductCard(
        product: items[i],
        onTap: () => showProductDetailSheet(ctx, items[i]),
      ),
    );
  }
}

class _Header extends StatelessWidget {
  const _Header({
    required this.user,
    required this.onLogin,
    required this.onHistoryTap,
  });
  final dynamic user;
  final VoidCallback onLogin;
  final VoidCallback onHistoryTap;

  @override
  Widget build(BuildContext context) {
    final loggedIn = user != null;
    return Container(
      decoration: const BoxDecoration(
        gradient: AppGradients.header,
        borderRadius: BorderRadius.only(
          bottomLeft: Radius.circular(AppRadii.xxl),
          bottomRight: Radius.circular(AppRadii.xxl),
        ),
        // Лёгкая тень — отделяет sticky-header от прокручивающегося контента.
        boxShadow: [
          BoxShadow(
            color: Color(0x1F0F1424),
            offset: Offset(0, 4),
            blurRadius: 16,
          ),
        ],
      ),
      child: SafeArea(
        bottom: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(
            AppSpacing.screenEdge,
            8,
            AppSpacing.screenEdge,
            20,
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const PharmaWordmark(),
              const SizedBox(height: AppSpacing.s16),
              if (loggedIn)
                BalanceCard(
                  balanceKzt: user.balanceKzt as int,
                  onHistory: onHistoryTap,
                  onUpload: () => showUploadPromptSheet(context),
                )
              else
                HomeWelcomeGate(onLogin: onLogin),
            ],
          ),
        ),
      ),
    );
  }
}

/// Профиль (2-й таб в новой 2-tab nav).
///
/// Структура (см. `_reference/.../screens/profile.jsx`):
/// • Зелёный header (grad-blueHead) с PharmaWordmark и:
///   - unauthed → LoginInviteCard «Войдите в аккаунт»
///   - authed → аватар + ФИО + телефон + 2 glass-pill (История, Конкурсы)
/// • Тело на paperCanvas: секции «Помощь» (4 row) и «О приложении» (2 row),
///   кнопка «Выйти» (только authed), footer версии приложения.
class _ProfileTab extends ConsumerWidget {
  const _ProfileTab({required this.onLogin, required this.onHistoryTap});
  final VoidCallback onLogin;
  final VoidCallback onHistoryTap;

  /// Items секции «Помощь». «Обучение» добавлено в Этапе 5 — раньше был
  /// отдельный таб в bottom-nav, перенесли сюда (см. [LearningStubScreen]).
  /// Школьная иконка та же, что была в bottom-nav.
  static const _helpItems = <_ProfileMenuItem>[
    _ProfileMenuItem(
      Icons.school_outlined,
      'Обучение',
      // pushScreen — обработка ниже в build (через Navigator.push, не go_router,
      // т.к. learning_stub живёт вне router-tree).
      pushScreen: _PushTarget.learning,
    ),
    _ProfileMenuItem(
      Icons.favorite_border_rounded,
      'Служба поддержки в WhatsApp',
      route: null, // внешняя ссылка — пока заглушка
    ),
    _ProfileMenuItem(
      Icons.help_outline_rounded,
      'Вопросы и ответы',
      route: '/profile/faq',
    ),
    _ProfileMenuItem(
      Icons.content_copy_outlined,
      'Подробная инструкция',
      route: '/profile/instruction',
    ),
    _ProfileMenuItem(
      Icons.assignment_turned_in_outlined,
      'Сотрудничество',
      route: '/profile/cooperation',
    ),
  ];

  static const _aboutItems = <_ProfileMenuItem>[
    _ProfileMenuItem(
      Icons.description_outlined,
      'Пользовательское соглашение',
      route: '/profile/terms',
    ),
    _ProfileMenuItem(
      Icons.description_outlined,
      'Политика конфиденциальности',
      route: '/profile/privacy',
    ),
  ];

  /// Резолвит onTap для пункта меню: go_router-маршрут, push на stub-экран
  /// или null (для внешних ссылок-заглушек как WhatsApp).
  VoidCallback? _resolveOnTap(BuildContext context, _ProfileMenuItem item) {
    if (item.route != null) {
      return () => context.push(item.route!);
    }
    if (item.pushScreen != null) {
      return () {
        switch (item.pushScreen!) {
          case _PushTarget.learning:
            Navigator.of(context).push(
              MaterialPageRoute(
                builder: (_) => const LearningStubScreen(),
              ),
            );
        }
      };
    }
    return null;
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);

    return ListView(
      padding: EdgeInsets.zero,
      children: [
        // Зелёный header с welcome-gate / профилем пользователя.
        Container(
          decoration: const BoxDecoration(
            gradient: AppGradients.header,
            borderRadius: BorderRadius.only(
              bottomLeft: Radius.circular(AppRadii.xxl),
              bottomRight: Radius.circular(AppRadii.xxl),
            ),
          ),
          child: SafeArea(
            bottom: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(
                AppSpacing.screenEdge,
                AppSpacing.s8,
                AppSpacing.screenEdge,
                AppSpacing.s32,
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const PharmaWordmark(),
                  const SizedBox(height: AppSpacing.s20),
                  if (user == null)
                    LoginInviteCard(onLogin: onLogin)
                  else
                    _AuthedHeader(
                      fio: user.fio,
                      phone: user.phone,
                      onHistoryTap: onHistoryTap,
                    ),
                ],
              ),
            ),
          ),
        ),

        // Тело: секция Помощь.
        const Padding(
          padding: EdgeInsets.fromLTRB(
            AppSpacing.screenEdge,
            AppSpacing.s24,
            AppSpacing.screenEdge,
            AppSpacing.s12,
          ),
          child: Text(
            'Помощь',
            style: TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: ['Roboto', 'sans-serif'],
              // Section title в Profile = H1 role per Fonts.md §6 → 26/800.
              fontSize: 26,
              fontWeight: FontWeight.w800,
              color: AppColors.ink900,
              height: 1.1,
            ),
          ),
        ),
        for (final item in _helpItems)
          Padding(
            padding: const EdgeInsets.fromLTRB(
              AppSpacing.screenEdge,
              0,
              AppSpacing.screenEdge,
              AppSpacing.s12,
            ),
            child: ProfileRow(
              icon: item.icon,
              label: item.label,
              onTap: _resolveOnTap(context, item),
            ),
          ),

        // Тело: секция О приложении.
        const Padding(
          padding: EdgeInsets.fromLTRB(
            AppSpacing.screenEdge,
            AppSpacing.s12,
            AppSpacing.screenEdge,
            AppSpacing.s12,
          ),
          child: Text(
            'О приложении',
            style: TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: ['Roboto', 'sans-serif'],
              // Section title в Profile = H1 role per Fonts.md §6 → 26/800.
              fontSize: 26,
              fontWeight: FontWeight.w800,
              color: AppColors.ink900,
              height: 1.1,
            ),
          ),
        ),
        for (final item in _aboutItems)
          Padding(
            padding: const EdgeInsets.fromLTRB(
              AppSpacing.screenEdge,
              0,
              AppSpacing.screenEdge,
              AppSpacing.s12,
            ),
            child: ProfileRow(
              icon: item.icon,
              label: item.label,
              onTap: _resolveOnTap(context, item),
            ),
          ),

        // Кнопка «Выйти» — только для залогиненного.
        if (user != null)
          Padding(
            padding: const EdgeInsets.fromLTRB(
              AppSpacing.screenEdge,
              AppSpacing.s16,
              AppSpacing.screenEdge,
              0,
            ),
            child: _LogoutButton(
              onTap: () => ref.read(currentUserProvider.notifier).logout(),
            ),
          ),

        // Footer.
        const Padding(
          padding: EdgeInsets.fromLTRB(
            AppSpacing.screenEdge,
            AppSpacing.s24,
            AppSpacing.screenEdge,
            AppSpacing.s32,
          ),
          child: Text(
            'Epharm · v 1.0.4 (2026)',
            textAlign: TextAlign.center,
            style: TextStyle(
              fontFamily: 'Manrope',
              fontSize: 12,
              fontWeight: FontWeight.w800,
              color: AppColors.ink400,
            ),
          ),
        ),
      ],
    );
  }
}

/// Цели push-навигации для пунктов меню, у которых нет go_router-маршрута
/// (stub-экраны живут вне router-tree).
enum _PushTarget { learning }

class _ProfileMenuItem {
  const _ProfileMenuItem(
    this.icon,
    this.label, {
    this.route,
    this.pushScreen,
  });

  final IconData icon;
  final String label;

  /// go_router-маршрут (если есть). Например '/profile/faq'.
  final String? route;

  /// Альтернатива route — push экрана через Navigator.push (для stub-экранов
  /// которые не зарегистрированы в go_router).
  final _PushTarget? pushScreen;
}

class _AuthedHeader extends StatelessWidget {
  const _AuthedHeader({
    required this.fio,
    required this.phone,
    required this.onHistoryTap,
  });

  final String fio;
  final String phone;
  final VoidCallback onHistoryTap;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        // Аватар + ФИО + телефон.
        Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            Container(
              width: 56,
              height: 56,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                border: Border.all(
                  color: Colors.white.withValues(alpha: 0.6),
                  width: 2,
                ),
              ),
              child: const Icon(
                Icons.person_rounded,
                size: 32,
                color: Colors.white,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    fio,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontFamily: 'Manrope',
                      fontFamilyFallback: ['Roboto', 'sans-serif'],
                      // Profile header name = H2 24/800 — крупнее «Hello»-карточки,
                      // самый яркий элемент шапки.
                      fontSize: 24,
                      fontWeight: FontWeight.w800,
                      color: Colors.white,
                      height: 1.15,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    phone,
                    style: TextStyle(
                      fontFamily: 'Manrope',
                      fontFamilyFallback: const ['Roboto', 'sans-serif'],
                      // Phone под именем = Body-strong 15/700 — чтобы был
                      // считаемый и не терялся на gradient header.
                      fontSize: 15,
                      fontWeight: FontWeight.w800,
                      color: Colors.white.withValues(alpha: 0.9),
                      height: 1.15,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
        const SizedBox(height: AppSpacing.s16),
        // Две glass-pill кнопки: История + Конкурсы.
        Row(
          children: [
            Expanded(
              child: GlassPill(
                icon: Icons.access_time_rounded,
                label: 'История',
                onTap: onHistoryTap,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: GlassPill(
                icon: Icons.emoji_events_outlined,
                label: 'Конкурсы',
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute(
                    builder: (_) => const ContestsStubScreen(),
                  ),
                ),
              ),
            ),
          ],
        ),
      ],
    );
  }
}

class _LogoutButton extends StatelessWidget {
  const _LogoutButton({required this.onTap});
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    // Чистая белая кнопка + лёгкая тень. Структура такая же как у ProfileRow:
    // Container задаёт surface+shadow, Material поверх — только для ripple.
    return Container(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: AppRadii.brLg,
        boxShadow: AppShadows.card,
      ),
      clipBehavior: Clip.antiAlias,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          child: const SizedBox(
            height: 60,
            child: Row(
              mainAxisSize: MainAxisSize.max,
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(
                  Icons.logout_rounded,
                  size: 22,
                  color: Color(0xFFEF4444),
                ),
                SizedBox(width: 8),
                Text(
                  'Выйти',
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 18,
                    fontWeight: FontWeight.w800,
                    color: Color(0xFFEF4444),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
