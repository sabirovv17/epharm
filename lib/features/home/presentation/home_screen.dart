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
import '../../../core/widgets/filter_chip_row.dart';
import '../../../core/widgets/glass_pill.dart';
import '../../../core/widgets/pharma_logo.dart';
import '../../../core/widgets/search_input.dart';
import '../../auth/application/auth_controller.dart';
import '../../catalog/application/catalog_controller.dart';
import '../../catalog/data/catalog_models.dart';
import '../../catalog/presentation/catalog_card.dart';
import '../../catalog/presentation/catalog_product_sheet.dart';
import '../../promotions/application/promotions_controller.dart';
import '../../promotions/data/promotion_models.dart';
import '../../promotions/presentation/promo_product_card.dart';
import '../../profile/application/profile_controller.dart';
import '../../receipts/presentation/receipts_list_screen.dart';
import '../application/home_controller.dart';
import '../../receipts/presentation/upload_prompt_sheet.dart';
import 'widgets/balance_card.dart';
import 'widgets/bottom_navigation.dart';
import 'widgets/brand_sheet.dart';
import 'widgets/category_sheet.dart';
import 'widgets/home_welcome_gate.dart';
import 'widgets/learning_stub_screen.dart';
import 'widgets/login_invite_card.dart';
import 'widgets/profile_row.dart';
import 'widgets/promo_carousel.dart';
import 'widgets/sort_sheet.dart';

/// Живая синхронизация с админкой: перетягивает данные главной из backend —
/// ленту акций ([promotionsProvider], то что заведено в админке), карусель
/// ([promoListProvider]) и баланс ([ProfileActions.refreshMe]).
///
/// [awaitData]=true — для pull-to-refresh: ЖДЁМ приход данных, чтобы спиннер
/// RefreshIndicator держался до конца. [awaitData]=false — fire-and-forget
/// (авто-рефреш при возврате в приложение): просто инвалидируем, UI обновится сам.
///
/// Ошибки глотаем — ошибочное состояние отрисует сам провайдер (error-ветка
/// `.when`), а спиннер не должен «зависнуть» или уронить экран.
Future<void> refreshHomeData(WidgetRef ref, {bool awaitData = true}) async {
  final loggedIn = ref.read(currentUserProvider) != null;
  if (!awaitData) {
    ref.invalidate(promotionsProvider);
    ref.invalidate(promoListProvider);
    if (loggedIn) ref.read(profileActionsProvider).refreshMe();
    return;
  }
  final futures = <Future<void>>[
    ref.refresh(promotionsProvider.future).then((_) {}),
    ref.refresh(promoListProvider.future).then((_) {}),
    if (loggedIn) ref.read(profileActionsProvider).refreshMe(),
  ];
  try {
    await Future.wait(futures);
  } catch (_) {
    // no-op: error-ветку покажут сами провайдеры; спиннер просто завершится.
  }
}

class HomeScreen extends ConsumerStatefulWidget {
  const HomeScreen({super.key});

  @override
  ConsumerState<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends ConsumerState<HomeScreen>
    with WidgetsBindingObserver {
  int _tab = 0;

  /// Последний авто-рефреш по возврату в приложение — для троттлинга (см. ниже).
  DateTime? _lastResumeRefresh;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    // При входе на Home подтягиваем свежий баланс из backend (в API-режиме).
    // В mock-режиме refreshMe — no-op, баланс остаётся из login-ответа.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (ref.read(currentUserProvider) != null) {
        ref.read(profileActionsProvider).refreshMe();
      }
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  /// Живая синхронизация с админкой: при возврате приложения на передний план
  /// (foreground) перетягиваем ленту акций и баланс — чтобы заведённые/изменённые
  /// в админке акции появлялись БЕЗ перезапуска приложения. Троттлинг 20с:
  /// частые свёртывания/развёртывания не дёргают сеть и не мигают лоадером.
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // mounted-guard: lifecycle-колбэк может прийти на грани dispose — не трогаем
    // ref у размонтированного State (идиома для WidgetsBindingObserver).
    if (state != AppLifecycleState.resumed || !mounted) return;
    final now = DateTime.now();
    final last = _lastResumeRefresh;
    if (last != null && now.difference(last) < const Duration(seconds: 20)) {
      return;
    }
    _lastResumeRefresh = now;
    refreshHomeData(ref, awaitData: false);
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
    final promotionsAsync = ref.watch(promotionsProvider);
    final brands = ref.watch(selectedBrandsProvider);
    final categories = ref.watch(selectedCategoriesProvider);
    final sort = ref.watch(homeSortProvider);
    final query = ref.watch(searchQueryProvider);
    final recoPool = ref.watch(homeRecoPoolProvider);

    // Header теперь — обычный sliver внутри CustomScrollView. Раньше он был
    // в Stack/Positioned поверх скролла (sticky), но это создавало впечатление
    // «зафиксированной зелёной плашки», под которой контент уезжал — UX
    // путаный, особенно на short-screen Android'ах. Сейчас вся страница
    // прокручивается единым flow: зелёная шапка → промо → каталог.
    return RefreshIndicator(
      // Pull-to-refresh: потянуть вниз → лента акций/карусель/баланс
      // перезапрашиваются из backend (живая синхронизация с админкой). Спиннер
      // держится до прихода данных — refreshHomeData ждёт futures.
      onRefresh: () => refreshHomeData(ref),
      color: AppColors.brandGreen700,
      child: CustomScrollView(
        // AlwaysScrollable — чтобы pull работал даже при коротком контенте
        // (иначе нет «места» для overscroll и RefreshIndicator не срабатывает).
        physics: const AlwaysScrollableScrollPhysics(),
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
                  height: 234,
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
                onChanged: (v) => ref.read(searchQueryProvider.notifier).set(v),
              ),
            ),
          ),

          // 4) Filter row: сортировка + Бренд + Категории.
          //    Раздел «Конкурсные» полностью убран (ДОП.3c).
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
                      active: sort != CatalogSort.nameAsc,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Center(
                    child: PharmaFilterChip(
                      label:
                          brands.isEmpty ? 'Бренд' : 'Бренд · ${brands.length}',
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
                      label: categories.isEmpty
                          ? 'Категории'
                          : 'Категории · ${categories.length}',
                      active: categories.isNotEmpty,
                      trailing: const Icon(
                        Icons.keyboard_arrow_down,
                        size: 16,
                        color: AppColors.ink900,
                      ),
                      onTap: () => showCategorySheet(context),
                    ),
                  ),
                  // Пилюли-разделы: «Альтернативы» (замены) и «Дополнения» (кросс-селл) —
                  // тумблеры, переключают ленту на пул продвигаемых товаров.
                  const SizedBox(width: 8),
                  Center(
                    child: PharmaFilterChip(
                      label: 'Альтернативы',
                      active: recoPool == RecoPool.alternatives,
                      leading: Icon(
                        Icons.swap_horiz_rounded,
                        size: 18,
                        color: recoPool == RecoPool.alternatives
                            ? Colors.white
                            : AppColors.ink900,
                      ),
                      onTap: () => ref
                          .read(homeRecoPoolProvider.notifier)
                          .toggle(RecoPool.alternatives),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Center(
                    child: PharmaFilterChip(
                      label: 'Дополнения',
                      active: recoPool == RecoPool.crosssells,
                      leading: Icon(
                        Icons.add_circle_outline_rounded,
                        size: 18,
                        color: recoPool == RecoPool.crosssells
                            ? Colors.white
                            : AppColors.ink900,
                      ),
                      onTap: () => ref
                          .read(homeRecoPoolProvider.notifier)
                          .toggle(RecoPool.crosssells),
                    ),
                  ),
                ],
              ),
            ),
          ),

          const SliverToBoxAdapter(child: SizedBox(height: 16)),

          // 5) Контент ленты: если активна пилюля «Альтернативы»/«Дополнения» —
          //    показываем пул продвигаемых товаров (замены/допы из правил кампаний),
          //    иначе обычную ленту акций. Поиск применяется к обоим режимам.
          if (recoPool != RecoPool.none)
            ref.watch(catalogRecommendationPoolsProvider).when(
              loading: () => const SliverToBoxAdapter(
                child: Padding(
                  padding: EdgeInsets.all(AppSpacing.s24),
                  child: Center(child: CircularProgressIndicator()),
                ),
              ),
              error: (e, _) => SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.all(AppSpacing.s24),
                  child: Center(
                    child: Text(
                      'Не удалось загрузить',
                      style: AppTypography.body14(color: AppColors.ink500),
                    ),
                  ),
                ),
              ),
              data: (pools) {
                final items = recoPool == RecoPool.alternatives
                    ? pools.alternatives
                    : pools.crosssells;
                return _CatalogPoolSliver(
                  items: _filterCatalog(items, query),
                  emptyLabel: recoPool == RecoPool.alternatives
                      ? 'Альтернативы пока не заведены'
                      : 'Дополнения пока не заведены',
                );
              },
            )
          else
            promotionsAsync.when(
            loading: () => const SliverToBoxAdapter(
              child: Padding(
                padding: EdgeInsets.all(AppSpacing.s24),
                child: Center(child: CircularProgressIndicator()),
              ),
            ),
            error: (e, _) => SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(
                  AppSpacing.screenEdge,
                  24,
                  AppSpacing.screenEdge,
                  24,
                ),
                child: Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(
                        Icons.wifi_off_rounded,
                        size: 40,
                        color: AppColors.ink300,
                      ),
                      const SizedBox(height: 8),
                      Text(
                        'Акции недоступны',
                        style: AppTypography.body14(color: AppColors.ink500),
                      ),
                      TextButton(
                        onPressed: () => ref.invalidate(promotionsProvider),
                        child: const Text(
                          'Повторить',
                          style: TextStyle(
                            fontFamily: 'Manrope',
                            fontFamilyFallback: ['Roboto', 'sans-serif'],
                            fontSize: 15,
                            fontWeight: FontWeight.w800,
                            color: AppColors.brandGreen700,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
            data: (items) {
              final filtered = applyPromotionFilters(
                items: items,
                brands: brands,
                categories: categories,
                query: query,
                sort: sort,
              );
              return _PromoSliver(items: filtered);
            },
          ),

          const SliverToBoxAdapter(child: SizedBox(height: 32)),
        ],
      ),
    );
  }
}

/// Лента акций: сетка в 2 колонки компактных [PromoGridCard], тап → detail-sheet
/// товара (по medusa product id). Lazy [SliverGrid] — строит только видимые.
class _PromoSliver extends StatelessWidget {
  const _PromoSliver({required this.items});
  final List<Promotion> items;

  @override
  Widget build(BuildContext context) {
    if (items.isEmpty) {
      return SliverPadding(
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
        sliver: SliverToBoxAdapter(
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 32),
            child: Text(
              'Акций не найдено',
              textAlign: TextAlign.center,
              style: AppTypography.body14(color: AppColors.ink500),
            ),
          ),
        ),
      );
    }
    return SliverPadding(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
      sliver: SliverGrid(
        // 2 колонки. childAspectRatio 0.46 (ячейка выше ширины): вмещает фото 3:4
        // (ДОП.4, высота = ширина×4/3 ≈ 1.33w) + блок названия/бренда/бонуса даже на
        // узких экранах (320px) без overflow.
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 2,
          crossAxisSpacing: 12,
          mainAxisSpacing: 12,
          childAspectRatio: 0.46,
        ),
        delegate: SliverChildBuilderDelegate(
          (ctx, i) {
            final p = items[i];
            return PromoGridCard(
              promo: p,
              onTap: () => showCatalogProductSheet(ctx, p.productId),
            );
          },
          childCount: items.length,
        ),
      ),
    );
  }
}

/// Клиентский фильтр пула рекомендаций по строке поиска (имя/бренд/МНН).
List<CatalogProduct> _filterCatalog(List<CatalogProduct> items, String query) {
  final q = query.trim().toLowerCase();
  if (q.isEmpty) return items;
  return items
      .where((p) =>
          p.name.toLowerCase().contains(q) ||
          (p.brand ?? '').toLowerCase().contains(q) ||
          (p.mnn ?? '').toLowerCase().contains(q))
      .toList();
}

/// Сетка товаров пула «Альтернативы»/«Дополнения» (2 колонки [CatalogCard]).
/// Тап → detail-sheet товара (по medusa id). Пусто → подсказка [emptyLabel].
class _CatalogPoolSliver extends StatelessWidget {
  const _CatalogPoolSliver({required this.items, required this.emptyLabel});
  final List<CatalogProduct> items;
  final String emptyLabel;

  @override
  Widget build(BuildContext context) {
    if (items.isEmpty) {
      return SliverPadding(
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
        sliver: SliverToBoxAdapter(
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 32),
            child: Text(
              emptyLabel,
              textAlign: TextAlign.center,
              style: AppTypography.body14(color: AppColors.ink500),
            ),
          ),
        ),
      );
    }
    return SliverPadding(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
      sliver: SliverGrid(
        // Та же геометрия, что у ленты акций (фото 3:4 + название/бренд/цена).
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 2,
          crossAxisSpacing: 12,
          mainAxisSpacing: 12,
          childAspectRatio: 0.46,
        ),
        delegate: SliverChildBuilderDelegate(
          (ctx, i) {
            final p = items[i];
            return CatalogCard(
              product: p,
              onTap: () => showCatalogProductSheet(ctx, p.id),
            );
          },
          childCount: items.length,
        ),
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
///   - authed → аватар + ФИО + телефон + glass-pill «История»
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
        // Кнопка истории чеков на всю ширину (раздел «Конкурсы» убран — ДОП.3c).
        GlassPill(
          icon: Icons.access_time_rounded,
          label: 'История',
          onTap: onHistoryTap,
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
