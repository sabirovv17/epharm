import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_radii.dart';
import '../../../core/theme/app_spacing.dart';
import '../../../core/theme/app_typography.dart';
import '../../auth/application/auth_controller.dart';
import '../../training/application/training_controller.dart';
import '../../training/data/training_models.dart';
import '../../training/presentation/training_assignment_screen.dart';
import '../../training/presentation/training_qr_screen.dart';
import '../../training/presentation/training_ui.dart';

class PharmacistDashboardTab extends ConsumerWidget {
  const PharmacistDashboardTab({
    super.key,
    required this.onOpenCatalog,
    required this.onOpenReceipts,
    required this.onOpenTraining,
  });

  final VoidCallback onOpenCatalog;
  final VoidCallback onOpenReceipts;
  final VoidCallback onOpenTraining;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    if (user == null) {
      return _GuestDashboard(onLogin: () => context.go('/auth/phone'));
    }

    final overview = ref.watch(trainingOverviewProvider);
    final nameParts = user.fio.trim().split(RegExp(r'\s+'));
    final firstName = nameParts.isEmpty || nameParts.first.isEmpty
        ? 'фармацевт'
        : nameParts.first;
    return RefreshIndicator(
      color: AppColors.brandGreen600,
      onRefresh: () async {
        ref.invalidate(trainingOverviewProvider);
        await ref.read(trainingOverviewProvider.future);
      },
      child: CustomScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        slivers: [
          SliverToBoxAdapter(child: _DashboardHeader(firstName: firstName)),
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(
              AppSpacing.screenEdge,
              AppSpacing.s24,
              AppSpacing.screenEdge,
              AppSpacing.s32,
            ),
            sliver: SliverList.list(
              children: [
                Row(
                  children: [
                    Expanded(child: Text('Сегодня', style: AppTypography.h2())),
                    TextButton(
                      onPressed: onOpenTraining,
                      child: const Text('Все курсы'),
                    ),
                  ],
                ),
                const SizedBox(height: AppSpacing.s12),
                overview.when(
                  loading: () => const _DashboardLoading(),
                  error: (error, _) => _DashboardError(
                    onRetry: () => ref.invalidate(trainingOverviewProvider),
                  ),
                  data: (data) {
                    final assignment = _primaryAssignment(data.assignments);
                    if (assignment == null) {
                      return _NoCurrentTraining(onOpenTraining: onOpenTraining);
                    }
                    return _CurrentTrainingCard(
                      assignment: assignment,
                      onOpen: () =>
                          _openAssignment(context, ref, assignment.id),
                    );
                  },
                ),
                const SizedBox(height: AppSpacing.s24),
                Text('Быстрые действия', style: AppTypography.h3()),
                const SizedBox(height: AppSpacing.s12),
                _QuickActions(
                  onQr: () => _scanQr(context, ref),
                  onCatalog: onOpenCatalog,
                  onReceipts: onOpenReceipts,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  TrainingAssignment? _primaryAssignment(List<TrainingAssignment> items) {
    final active = items
        .where((item) => !item.isCompleted && !item.isCancelled)
        .toList(growable: false);
    if (active.isEmpty) return null;
    for (final item in active) {
      if (item.required && item.status == TrainingAssignmentStatus.overdue) {
        return item;
      }
    }
    for (final item in active) {
      if (item.required) return item;
    }
    return active.first;
  }

  Future<void> _openAssignment(
    BuildContext context,
    WidgetRef ref,
    String assignmentId,
  ) async {
    await Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => TrainingAssignmentScreen(assignmentId: assignmentId),
      ),
    );
    ref.invalidate(trainingOverviewProvider);
  }

  Future<void> _scanQr(BuildContext context, WidgetRef ref) async {
    final result = await Navigator.of(context).push<TrainingAssignment>(
      MaterialPageRoute(builder: (_) => const TrainingQrScreen()),
    );
    if (result == null) return;
    ref.invalidate(trainingOverviewProvider);
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('Посещение «${result.programName}» подтверждено')),
    );
  }
}

class _DashboardHeader extends StatelessWidget {
  const _DashboardHeader({required this.firstName});

  final String firstName;

  @override
  Widget build(BuildContext context) => ColoredBox(
        color: AppColors.brandGreen700,
        child: SafeArea(
          bottom: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(
              AppSpacing.screenEdge,
              AppSpacing.s16,
              AppSpacing.screenEdge,
              AppSpacing.s24,
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Text(
                      'ePharm',
                      style: AppTypography.h2(color: Colors.white),
                    ),
                    const Spacer(),
                    Container(
                      width: 44,
                      height: 44,
                      decoration: const BoxDecoration(
                        color: AppColors.brandGreen100,
                        shape: BoxShape.circle,
                      ),
                      alignment: Alignment.center,
                      child: Text(
                        firstName.characters.first.toUpperCase(),
                        style: AppTypography.bodyStrong(
                          color: AppColors.brandGreen700,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: AppSpacing.s24),
                Text(
                  '$firstName, продолжим обучение?',
                  style: AppTypography.h1(color: Colors.white),
                ),
                const SizedBox(height: AppSpacing.s8),
                Text(
                  'Все важные задачи на сегодня — в одном месте',
                  style: AppTypography.body14(
                    color: Colors.white.withValues(alpha: 0.78),
                  ),
                ),
              ],
            ),
          ),
        ),
      );
}

class _CurrentTrainingCard extends StatelessWidget {
  const _CurrentTrainingCard({required this.assignment, required this.onOpen});

  final TrainingAssignment assignment;
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) {
    final stages = assignment.stages;
    final nextIndex = stages.indexWhere(
      (stage) =>
          stage.status != TrainingStageStatus.completed &&
          stage.status != TrainingStageStatus.skipped,
    );
    final currentIndex = nextIndex < 0 ? stages.length : nextIndex + 1;
    final deadlineColor = assignment.status == TrainingAssignmentStatus.overdue
        ? AppColors.brandBlue700
        : AppColors.ink500;
    return Container(
      padding: const EdgeInsets.all(AppSpacing.s20),
      decoration: BoxDecoration(
        color: AppColors.brandGreen100,
        borderRadius: AppRadii.brXl,
        border:
            Border.all(color: AppColors.brandGreen400.withValues(alpha: 0.35)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              _StatusPill(required: assignment.required),
              const Spacer(),
              if (assignment.dueAt != null)
                Text(
                  'до ${trainingDate(assignment.dueAt)}',
                  style: AppTypography.captionSmall(color: deadlineColor),
                ),
            ],
          ),
          const SizedBox(height: AppSpacing.s16),
          Text(assignment.programName, style: AppTypography.h2()),
          if (assignment.shortDescription.isNotEmpty) ...[
            const SizedBox(height: AppSpacing.s4),
            Text(
              assignment.shortDescription,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: AppTypography.body14(color: AppColors.ink500),
            ),
          ],
          const SizedBox(height: AppSpacing.s16),
          if (stages.isNotEmpty)
            Text(
              'Этап $currentIndex из ${stages.length}',
              style: AppTypography.caption(color: AppColors.brandGreen700),
            ),
          const SizedBox(height: AppSpacing.s8),
          Row(
            children: [
              Expanded(
                child: ClipRRect(
                  borderRadius: AppRadii.brFull,
                  child: LinearProgressIndicator(
                    value: assignment.progressPct / 100,
                    minHeight: 8,
                  ),
                ),
              ),
              const SizedBox(width: AppSpacing.s12),
              Text(
                '${assignment.progressPct}%',
                style: AppTypography.bodyStrong(color: AppColors.brandGreen700),
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.s20),
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: onOpen,
              icon: const Icon(Icons.play_arrow_rounded),
              label: const Text('Продолжить'),
            ),
          ),
        ],
      ),
    );
  }
}

class _StatusPill extends StatelessWidget {
  const _StatusPill({required this.required});

  final bool required;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        decoration: BoxDecoration(
          color: required ? AppColors.brandBlue100 : Colors.white,
          borderRadius: AppRadii.brFull,
        ),
        child: Text(
          required ? 'ОБЯЗАТЕЛЬНО' : 'РЕКОМЕНДОВАНО',
          style: AppTypography.micro(
            color: required ? AppColors.brandBlue700 : AppColors.brandGreen700,
          ),
        ),
      );
}

class _QuickActions extends StatelessWidget {
  const _QuickActions({
    required this.onQr,
    required this.onCatalog,
    required this.onReceipts,
  });

  final VoidCallback onQr;
  final VoidCallback onCatalog;
  final VoidCallback onReceipts;

  @override
  Widget build(BuildContext context) => Container(
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: AppRadii.brLg,
          border: Border.all(color: AppColors.borderHairline),
        ),
        child: Column(
          children: [
            _QuickActionRow(
              icon: Icons.qr_code_scanner_rounded,
              title: 'QR посещения',
              subtitle: 'Подтвердить очное обучение',
              onTap: onQr,
            ),
            const Divider(),
            _QuickActionRow(
              icon: Icons.medication_outlined,
              title: 'Каталог препаратов',
              subtitle: 'Акции и полезные материалы',
              onTap: onCatalog,
            ),
            const Divider(),
            _QuickActionRow(
              icon: Icons.receipt_long_outlined,
              title: 'Мои чеки',
              subtitle: 'История и загрузка чеков',
              onTap: onReceipts,
            ),
          ],
        ),
      );
}

class _QuickActionRow extends StatelessWidget {
  const _QuickActionRow({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.onTap,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => ListTile(
        onTap: onTap,
        minTileHeight: 72,
        leading: Container(
          width: 44,
          height: 44,
          decoration: const BoxDecoration(
            color: AppColors.brandGreen100,
            borderRadius: AppRadii.brSm,
          ),
          alignment: Alignment.center,
          child: Icon(icon, color: AppColors.brandGreen700),
        ),
        title: Text(title, style: AppTypography.bodyStrong()),
        subtitle: Text(subtitle, style: AppTypography.captionSmall()),
        trailing:
            const Icon(Icons.chevron_right_rounded, color: AppColors.ink400),
      );
}

class _NoCurrentTraining extends StatelessWidget {
  const _NoCurrentTraining({required this.onOpenTraining});

  final VoidCallback onOpenTraining;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(AppSpacing.s20),
        decoration: const BoxDecoration(
          color: AppColors.brandGreen100,
          borderRadius: AppRadii.brXl,
        ),
        child: Column(
          children: [
            const Icon(Icons.task_alt_rounded,
                size: 36, color: AppColors.brandGreen700),
            const SizedBox(height: AppSpacing.s8),
            Text('На сегодня всё выполнено', style: AppTypography.h3()),
            const SizedBox(height: AppSpacing.s4),
            Text(
              'Новые назначения появятся здесь автоматически',
              textAlign: TextAlign.center,
              style: AppTypography.body14(color: AppColors.ink500),
            ),
            const SizedBox(height: AppSpacing.s16),
            OutlinedButton(
                onPressed: onOpenTraining,
                child: const Text('Открыть обучение')),
          ],
        ),
      );
}

class _DashboardLoading extends StatelessWidget {
  const _DashboardLoading();

  @override
  Widget build(BuildContext context) => const SizedBox(
        height: 220,
        child: Center(child: CircularProgressIndicator()),
      );
}

class _DashboardError extends StatelessWidget {
  const _DashboardError({required this.onRetry});

  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(AppSpacing.s20),
        decoration: const BoxDecoration(
          color: Colors.white,
          borderRadius: AppRadii.brLg,
        ),
        child: Column(
          children: [
            Text('Не удалось загрузить обучение',
                style: AppTypography.bodyStrong()),
            const SizedBox(height: AppSpacing.s8),
            TextButton(onPressed: onRetry, child: const Text('Повторить')),
          ],
        ),
      );
}

class _GuestDashboard extends StatelessWidget {
  const _GuestDashboard({required this.onLogin});

  final VoidCallback onLogin;

  @override
  Widget build(BuildContext context) => ListView(
        padding: EdgeInsets.zero,
        children: [
          const _DashboardHeader(firstName: 'фармацевт'),
          Padding(
            padding: const EdgeInsets.all(AppSpacing.screenEdge),
            child: Container(
              padding: const EdgeInsets.all(AppSpacing.s24),
              decoration: const BoxDecoration(
                color: Colors.white,
                borderRadius: AppRadii.brXl,
              ),
              child: Column(
                children: [
                  const Icon(Icons.lock_outline_rounded,
                      size: 40, color: AppColors.brandGreen700),
                  const SizedBox(height: AppSpacing.s12),
                  Text('Войдите в кабинет фармацевта',
                      style: AppTypography.h3()),
                  const SizedBox(height: AppSpacing.s8),
                  Text(
                    'После входа здесь появятся обязательные курсы и прогресс.',
                    textAlign: TextAlign.center,
                    style: AppTypography.body14(),
                  ),
                  const SizedBox(height: AppSpacing.s20),
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton(
                        onPressed: onLogin, child: const Text('Войти')),
                  ),
                ],
              ),
            ),
          ),
        ],
      );
}
