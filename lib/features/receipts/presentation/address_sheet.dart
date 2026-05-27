import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_radii.dart';
import '../../../core/theme/app_shadows.dart';
import '../../../core/theme/app_spacing.dart';
import '../../../core/widgets/search_input.dart';
import '../application/receipts_controller.dart';
import '../data/nearby_pharmacies.dart';

/// Pharmacy picker bottom-sheet. Открывается из `ReceiptReviewScreen` через
/// row «Адрес аптеки». Заполняет `receiptDraft.pharmacy` через
/// `ReceiptDraftNotifier.setPharmacy`.
///
/// Источник дизайна: `_reference/recipe/review.jsx` → `AddressSheet`.
Future<void> showAddressSheet(BuildContext context) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    barrierColor: const Color(0x730F1424), // overlay/scrim 0.45
    builder: (_) => const _AddressSheet(),
  );
}

class _AddressSheet extends ConsumerStatefulWidget {
  const _AddressSheet();

  @override
  ConsumerState<_AddressSheet> createState() => _AddressSheetState();
}

class _AddressSheetState extends ConsumerState<_AddressSheet> {
  final _qCtrl = TextEditingController();
  String _q = '';

  @override
  void dispose() {
    _qCtrl.dispose();
    super.dispose();
  }

  List<NearbyPharmacy> get _filtered {
    if (_q.isEmpty) return nearbyPharmacies;
    final lower = _q.toLowerCase();
    return nearbyPharmacies
        .where((p) =>
            (p.name + p.addr).toLowerCase().contains(lower))
        .toList();
  }

  @override
  Widget build(BuildContext context) {
    final current = ref.watch(receiptDraftProvider).pharmacy;
    return DraggableScrollableSheet(
      initialChildSize: 0.78,
      maxChildSize: 0.95,
      minChildSize: 0.5,
      expand: false,
      builder: (ctx, scrollCtrl) => Container(
        decoration: const BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.vertical(top: Radius.circular(AppRadii.xxl)),
        ),
        child: Column(
          children: [
            const SizedBox(height: 8),
            Container(
              width: 36,
              height: 4,
              decoration: BoxDecoration(
                color: AppColors.ink300,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            const SizedBox(height: 12),
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Text(
                  'Адрес аптеки',
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 22,
                    fontWeight: FontWeight.w800,
                    color: AppColors.ink900,
                    height: 1.15,
                  ),
                ),
              ),
            ),
            const SizedBox(height: 4),
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Text(
                  'Выберите аптеку из списка или найдите вручную',
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontSize: 13,
                    fontWeight: FontWeight.w700,
                    color: AppColors.ink500,
                  ),
                ),
              ),
            ),
            const SizedBox(height: 12),
            Padding(
              padding: const EdgeInsets.symmetric(
                  horizontal: AppSpacing.screenEdge),
              child: SearchInput(
                controller: _qCtrl,
                hint: 'Название аптеки или адрес',
                onChanged: (v) => setState(() => _q = v),
              ),
            ),
            const SizedBox(height: 12),
            Padding(
              padding: const EdgeInsets.symmetric(
                  horizontal: AppSpacing.screenEdge),
              child: _GeolocationBanner(count: nearbyPharmacies.length),
            ),
            const SizedBox(height: 12),
            Expanded(
              child: ListView.separated(
                controller: scrollCtrl,
                padding: const EdgeInsets.fromLTRB(
                  AppSpacing.screenEdge,
                  0,
                  AppSpacing.screenEdge,
                  AppSpacing.s24,
                ),
                itemCount: _filtered.length,
                separatorBuilder: (_, __) => const SizedBox(height: 8),
                itemBuilder: (_, i) => _PharmacyRow(
                  pharmacy: _filtered[i],
                  selected: current?.id == _filtered[i].id,
                  onTap: () {
                    ref
                        .read(receiptDraftProvider.notifier)
                        .setPharmacy(_filtered[i]);
                    Navigator.of(context).pop();
                  },
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _GeolocationBanner extends StatelessWidget {
  const _GeolocationBanner({required this.count});
  final int count;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: AppColors.brandGreen100, // green-100 / 50 tint
        borderRadius: AppRadii.brXl,
        border: Border.all(
          color: const Color(0xFFA9EBC6), // brand/green/200
          width: 1,
        ),
      ),
      child: Row(
        children: [
          Container(
            width: 36,
            height: 36,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: AppColors.brandGreen600,
              borderRadius: BorderRadius.circular(12),
              boxShadow: AppShadows.fab,
            ),
            child: const Icon(Icons.location_on_rounded,
                size: 20, color: Colors.white),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                const Text(
                  'Рядом с вами',
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 14,
                    fontWeight: FontWeight.w800,
                    color: AppColors.brandGreen700,
                    height: 1.15,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  'Алматы · найдено $count аптек поблизости',
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: const ['Roboto', 'sans-serif'],
                    fontSize: 12,
                    fontWeight: FontWeight.w700,
                    color: AppColors.brandGreen700.withValues(alpha: 0.7),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _PharmacyRow extends StatelessWidget {
  const _PharmacyRow({
    required this.pharmacy,
    required this.selected,
    required this.onTap,
  });

  final NearbyPharmacy pharmacy;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: AppRadii.brXl,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
          decoration: BoxDecoration(
            color: selected
                ? AppColors.brandGreen100
                : AppColors.paperInput.withValues(alpha: 0.7),
            borderRadius: AppRadii.brXl,
            border: Border.all(
              color: selected
                  ? const Color(0xFFA9EBC6)
                  : Colors.transparent,
              width: 1,
            ),
          ),
          child: Row(
            children: [
              Container(
                width: 40,
                height: 40,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: pharmacy.color,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Text(
                  pharmacy.chain.substring(0, 1),
                  style: const TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 14,
                    fontWeight: FontWeight.w800,
                    color: Colors.white,
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      pharmacy.name,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontFamily: 'Manrope',
                        fontFamilyFallback: ['Roboto', 'sans-serif'],
                        fontSize: 15,
                        fontWeight: FontWeight.w800,
                        color: AppColors.ink900,
                        height: 1.15,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      '${pharmacy.addr} · ${pharmacy.city}',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
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
              const SizedBox(width: 8),
              Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                decoration: BoxDecoration(
                  color: AppColors.brandGreen100,
                  borderRadius: BorderRadius.circular(999),
                ),
                child: Text(
                  pharmacy.distance,
                  style: const TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 11,
                    fontWeight: FontWeight.w800,
                    color: AppColors.brandGreen700,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
