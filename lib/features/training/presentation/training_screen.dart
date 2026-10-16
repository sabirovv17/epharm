import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/network/api_client.dart';
import '../../../core/theme/app_radii.dart';
import '../../../core/theme/app_shadows.dart';
import '../../../core/theme/app_spacing.dart';
import '../../../core/theme/app_typography.dart';
import '../../../core/widgets/error_snackbar.dart';
import '../../../core/widgets/media_image.dart';
import '../../../core/widgets/primary_button.dart';
import '../../auth/application/auth_controller.dart';
import '../../profile_pages/presentation/profile_page_scaffold.dart';
import '../application/training_controller.dart';
import '../data/training_models.dart';
import 'training_assignment_screen.dart';
import 'training_qr_screen.dart';
import 'training_ui.dart';

enum _TrainingFilter { active, completed, all }

class TrainingScreen extends ConsumerStatefulWidget {
  const TrainingScreen({super.key, this.embedded = false});

  /// When true the screen is rendered inside the five-tab pharmacist shell.
  final bool embedded;

  @override
  ConsumerState<TrainingScreen> createState() => _TrainingScreenState();
}

class _TrainingScreenState extends ConsumerState<TrainingScreen> {
  _TrainingFilter _filter = _TrainingFilter.active;
  final TextEditingController _searchController = TextEditingController();
  String _query = '';

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final user = ref.watch(currentUserProvider);
    final content = user == null
        ? _LoginRequired(
            onLogin: () => context.go(
              Uri(
                path: '/auth/phone',
                queryParameters: const {'next': '/training'},
              ).toString(),
            ),
          )
        : _buildAuthenticated(user.fio);
    if (widget.embedded) {
      return ColoredBox(color: AppColors.paperCanvas, child: content);
    }
    return ProfilePageScaffold(title: 'Обучение', child: content);
  }

  Widget _buildAuthenticated(String fullName) {
    final overview = ref.watch(trainingOverviewProvider);
    return overview.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (error, _) => _TrainingError(
        error: error,
        onRetry: () => ref.invalidate(trainingOverviewProvider),
      ),
      data: (data) {
        final assignments = switch (_filter) {
          _TrainingFilter.active => data.assignments
              .where((item) => !item.isCompleted && !item.isCancelled)
              .toList(),
          _TrainingFilter.completed =>
            data.assignments.where((item) => item.isCompleted).toList(),
          _TrainingFilter.all => List<TrainingAssignment>.of(data.assignments),
        };
        assignments.sort(_compareAssignments);
        final visibleAssignments = _query.trim().isEmpty
            ? assignments
            : assignments.where(_matchesQuery).toList(growable: false);
        TrainingAssignment? primary;
        if (_query.trim().isEmpty && assignments.isNotEmpty) {
          primary = assignments.first;
          for (final assignment in assignments) {
            if (assignment.required) {
              primary = assignment;
              break;
            }
          }
        }
        final featuredAssignment = primary;
        final listAssignments =
            _query.trim().isEmpty && featuredAssignment != null
                ? visibleAssignments
                    .where((item) => item.id != featuredAssignment.id)
                    .toList()
                : visibleAssignments;
        final nameParts = fullName.trim().split(RegExp(r'\s+'));
        final firstName = nameParts.isEmpty || nameParts.first.isEmpty
            ? 'фармацевт'
            : nameParts.first;
        return Column(
          children: [
            if (widget.embedded)
              _TrainingHero(
                firstName: firstName,
                taskCount: assignments.length,
              ),
            Expanded(
              child: RefreshIndicator(
                color: AppColors.brandGreen700,
                onRefresh: () async {
                  ref.invalidate(trainingOverviewProvider);
                  await ref.read(trainingOverviewProvider.future);
                },
                child: ListView(
                  physics: const AlwaysScrollableScrollPhysics(),
                  padding: const EdgeInsets.fromLTRB(
                    AppSpacing.screenEdge,
                    AppSpacing.s24,
                    AppSpacing.screenEdge,
                    AppSpacing.s32,
                  ),
                  children: [
                    Row(
                      children: [
                        Expanded(
                            child: Text('Моё обучение',
                                style: AppTypography.h2())),
                        OutlinedButton.icon(
                          onPressed: _scanEventQr,
                          icon: const Icon(Icons.qr_code_scanner_rounded,
                              size: 19),
                          label: const Text('QR посещения'),
                        ),
                      ],
                    ),
                    if (featuredAssignment != null) ...[
                      const SizedBox(height: AppSpacing.s16),
                      _LearningJourneyCard(
                        assignment: featuredAssignment,
                        onOpen: () => _openAssignment(featuredAssignment.id),
                      ),
                    ],
                    if (data.notifications.isNotEmpty) ...[
                      const SizedBox(height: AppSpacing.s24),
                      const _SectionTitle(title: 'Уведомления'),
                      const SizedBox(height: AppSpacing.s12),
                      ...data.notifications.take(5).map(
                            (notification) => Padding(
                              padding:
                                  const EdgeInsets.only(bottom: AppSpacing.s8),
                              child: _NotificationCard(
                                notification: notification,
                                onTap: () => _openNotification(notification),
                              ),
                            ),
                          ),
                    ],
                    if (data.upcomingEvents.isNotEmpty) ...[
                      const SizedBox(height: AppSpacing.s24),
                      const _SectionTitle(title: 'Ближайшие события'),
                      const SizedBox(height: AppSpacing.s12),
                      ...data.upcomingEvents.map(
                        (event) => Padding(
                          padding: const EdgeInsets.only(bottom: AppSpacing.s8),
                          child: _EventCard(event: event),
                        ),
                      ),
                    ],
                    const SizedBox(height: AppSpacing.s24),
                    const _SectionTitle(title: 'Все программы'),
                    const SizedBox(height: AppSpacing.s12),
                    TextField(
                      controller: _searchController,
                      textInputAction: TextInputAction.search,
                      onChanged: (value) => setState(() => _query = value),
                      decoration: InputDecoration(
                        hintText: 'Найти программу',
                        prefixIcon: const Icon(Icons.search_rounded),
                        suffixIcon: _query.isEmpty
                            ? null
                            : IconButton(
                                tooltip: 'Очистить поиск',
                                onPressed: () {
                                  _searchController.clear();
                                  setState(() => _query = '');
                                },
                                icon: const Icon(Icons.close_rounded),
                              ),
                        filled: true,
                        fillColor: Colors.white,
                        border: OutlineInputBorder(
                          borderRadius: AppRadii.brLg,
                          borderSide: const BorderSide(
                            color: AppColors.borderHairline,
                          ),
                        ),
                        enabledBorder: OutlineInputBorder(
                          borderRadius: AppRadii.brLg,
                          borderSide: const BorderSide(
                            color: AppColors.borderHairline,
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(height: AppSpacing.s12),
                    SizedBox(
                      width: double.infinity,
                      child: SegmentedButton<_TrainingFilter>(
                        segments: const [
                          ButtonSegment(
                              value: _TrainingFilter.active,
                              label: Text('Активные')),
                          ButtonSegment(
                              value: _TrainingFilter.completed,
                              label: Text('Завершённые')),
                          ButtonSegment(
                              value: _TrainingFilter.all, label: Text('Все')),
                        ],
                        selected: {_filter},
                        showSelectedIcon: false,
                        onSelectionChanged: (selection) =>
                            setState(() => _filter = selection.single),
                        style: ButtonStyle(
                          visualDensity: VisualDensity.compact,
                          textStyle:
                              WidgetStatePropertyAll(AppTypography.caption()),
                        ),
                      ),
                    ),
                    const SizedBox(height: AppSpacing.s12),
                    if (listAssignments.isEmpty && featuredAssignment == null)
                      _EmptyPrograms(searching: _query.trim().isNotEmpty)
                    else
                      ...listAssignments.map(
                        (assignment) => Padding(
                          padding:
                              const EdgeInsets.only(bottom: AppSpacing.s12),
                          child: _AssignmentCard(
                            assignment: assignment,
                            onTap: () => _openAssignment(assignment.id),
                          ),
                        ),
                      ),
                    if (data.certificates.isNotEmpty) ...[
                      const SizedBox(height: AppSpacing.s16),
                      const _SectionTitle(title: 'Сертификаты'),
                      const SizedBox(height: AppSpacing.s12),
                      ...data.certificates.map(
                        (certificate) => Padding(
                          padding: const EdgeInsets.only(bottom: AppSpacing.s8),
                          child: _CertificateRow(
                            certificate: certificate,
                            onOpen: certificate.pdfUrl == null
                                ? null
                                : () => _openCertificate(certificate.pdfUrl!),
                          ),
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ],
        );
      },
    );
  }

  bool _matchesQuery(TrainingAssignment assignment) {
    final query = _query.trim().toLowerCase();
    return <String>[
      assignment.programName,
      assignment.shortDescription,
      assignment.pharmacyName,
      assignment.city,
    ].any((value) => value.toLowerCase().contains(query));
  }

  int _compareAssignments(
    TrainingAssignment left,
    TrainingAssignment right,
  ) {
    if (_filter == _TrainingFilter.completed) {
      final byCompletion = _dateDescending(
        left.completedAt,
        right.completedAt,
      );
      if (byCompletion != 0) return byCompletion;
    }

    final byStatus = _statusRank(left).compareTo(_statusRank(right));
    if (byStatus != 0) return byStatus;
    final byPriority = _priorityRank(left.priority).compareTo(
      _priorityRank(right.priority),
    );
    if (byPriority != 0) return byPriority;
    final byDeadline = _dateAscending(left.dueAt, right.dueAt);
    if (byDeadline != 0) return byDeadline;
    return left.programName.compareTo(right.programName);
  }

  int _statusRank(TrainingAssignment assignment) => switch (assignment.status) {
        TrainingAssignmentStatus.overdue => 0,
        TrainingAssignmentStatus.retakeRequired => 1,
        TrainingAssignmentStatus.inProgress ||
        TrainingAssignmentStatus.waitingOnline ||
        TrainingAssignmentStatus.waitingTest ||
        TrainingAssignmentStatus.waitingExam ||
        TrainingAssignmentStatus.waitingEventSelection ||
        TrainingAssignmentStatus.waitingOffline ||
        TrainingAssignmentStatus.waitingAttendance ||
        TrainingAssignmentStatus.waitingReview =>
          2,
        _ => 3,
      };

  int _priorityRank(TrainingPriority priority) => switch (priority) {
        TrainingPriority.critical => 0,
        TrainingPriority.high => 1,
        TrainingPriority.normal => 2,
        TrainingPriority.low => 3,
      };

  int _dateAscending(DateTime? left, DateTime? right) {
    if (left == null && right == null) return 0;
    if (left == null) return 1;
    if (right == null) return -1;
    return left.compareTo(right);
  }

  int _dateDescending(DateTime? left, DateTime? right) =>
      _dateAscending(right, left);

  Future<void> _openAssignment(String assignmentId) async {
    await Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => TrainingAssignmentScreen(assignmentId: assignmentId),
      ),
    );
    if (mounted) ref.invalidate(trainingOverviewProvider);
  }

  Future<void> _openCertificate(String rawUrl) async {
    final uri = Uri.tryParse(ref.read(apiClientProvider).resolveUrl(rawUrl));
    if (uri == null ||
        !await launchUrl(uri, mode: LaunchMode.externalApplication)) {
      if (mounted) {
        showErrorSnackBar(context, Exception('Сертификат не удалось открыть'));
      }
    }
  }

  Future<void> _openNotification(TrainingNotification notification) async {
    if (!notification.read) {
      try {
        await ref
            .read(trainingActionsProvider)
            .markNotificationRead(notification.id);
      } catch (error) {
        if (mounted) showErrorSnackBar(context, error);
        return;
      }
    }
    if (!mounted || notification.assignmentId == null) return;
    await _openAssignment(notification.assignmentId!);
  }

  Future<void> _scanEventQr() async {
    final assignment = await Navigator.of(context).push<TrainingAssignment>(
      MaterialPageRoute(builder: (_) => const TrainingQrScreen()),
    );
    if (!mounted || assignment == null) return;
    ref.invalidate(trainingOverviewProvider);
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('Посещение «${assignment.programName}» подтверждено'),
        behavior: SnackBarBehavior.floating,
      ),
    );
  }
}

class _TrainingHero extends StatelessWidget {
  const _TrainingHero({required this.firstName, required this.taskCount});

  final String firstName;
  final int taskCount;

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
                    Text('ePharm',
                        style: AppTypography.h2(color: Colors.white)),
                    const Spacer(),
                    Container(
                      width: 44,
                      height: 44,
                      alignment: Alignment.center,
                      decoration: const BoxDecoration(
                        color: AppColors.brandGreen100,
                        shape: BoxShape.circle,
                      ),
                      child: Text(
                        firstName.characters.first.toUpperCase(),
                        style: AppTypography.bodyStrong(
                            color: AppColors.brandGreen700),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: AppSpacing.s20),
                Text(
                  '$firstName, сегодня $taskCount ${_taskWord(taskCount)}',
                  style: AppTypography.h1(color: Colors.white),
                ),
                const SizedBox(height: AppSpacing.s8),
                Text(
                  'Продолжайте обучение и развивайтесь вместе с ePharm',
                  style: AppTypography.body14(
                    color: Colors.white.withValues(alpha: 0.78),
                  ),
                ),
              ],
            ),
          ),
        ),
      );

  static String _taskWord(int value) {
    final tail = value % 100;
    if (tail >= 11 && tail <= 14) return 'задач';
    return switch (value % 10) {
      1 => 'задача',
      2 || 3 || 4 => 'задачи',
      _ => 'задач',
    };
  }
}

class _LearningJourneyCard extends StatelessWidget {
  const _LearningJourneyCard({required this.assignment, required this.onOpen});

  final TrainingAssignment assignment;
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) {
    final stages = assignment.stages;
    final currentStageIndex = stages.indexWhere(
      (stage) =>
          stage.status != TrainingStageStatus.completed &&
          stage.status != TrainingStageStatus.skipped,
    );
    final currentNumber =
        currentStageIndex < 0 ? stages.length : currentStageIndex + 1;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (assignment.required || assignment.dueAt != null)
          Container(
            width: double.infinity,
            padding: const EdgeInsets.symmetric(
                horizontal: AppSpacing.s16, vertical: AppSpacing.s12),
            decoration: const BoxDecoration(
              color: AppColors.brandBlue100,
              borderRadius: AppRadii.brMd,
            ),
            child: Row(
              children: [
                const Icon(Icons.schedule_rounded,
                    color: AppColors.brandBlue700, size: 21),
                const SizedBox(width: AppSpacing.s8),
                Expanded(
                  child: Text(
                    assignment.required
                        ? 'Обязательный курс'
                        : 'Срок прохождения',
                    style: AppTypography.body14(color: AppColors.ink700),
                  ),
                ),
                if (assignment.dueAt != null)
                  Text(
                    'до ${trainingDate(assignment.dueAt)}',
                    style: AppTypography.caption(color: AppColors.brandBlue700),
                  ),
              ],
            ),
          ),
        const SizedBox(height: AppSpacing.s12),
        Container(
          padding: const EdgeInsets.all(AppSpacing.s20),
          decoration: BoxDecoration(
            color: AppColors.brandGreen100,
            borderRadius: AppRadii.brXl,
            border: Border.all(
                color: AppColors.brandGreen400.withValues(alpha: 0.35)),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'ТЕКУЩИЙ КУРС',
                style: AppTypography.micro(color: AppColors.brandGreen700)
                    .copyWith(letterSpacing: 1.1),
              ),
              const SizedBox(height: AppSpacing.s8),
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
              const SizedBox(height: AppSpacing.s12),
              Wrap(
                spacing: AppSpacing.s8,
                runSpacing: AppSpacing.s8,
                children: [
                  _Tag(
                    label: assignmentStatusLabel(assignment.status),
                    color: trainingStatusColor(assignment.status),
                  ),
                  if (stages.isNotEmpty)
                    _Tag(
                      label: 'Этап $currentNumber из ${stages.length}',
                      color: AppColors.brandGreen700,
                    ),
                ],
              ),
              const SizedBox(height: AppSpacing.s16),
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
                    style: AppTypography.bodyStrong(
                        color: AppColors.brandGreen700),
                  ),
                ],
              ),
              const SizedBox(height: AppSpacing.s20),
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  onPressed: onOpen,
                  child: Text(assignment.progressPct > 0
                      ? 'Продолжить'
                      : 'Начать обучение'),
                ),
              ),
            ],
          ),
        ),
        if (stages.isNotEmpty) ...[
          const SizedBox(height: AppSpacing.s12),
          Container(
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: AppRadii.brLg,
              border: Border.all(color: AppColors.borderHairline),
            ),
            child: Column(
              children: [
                for (var index = 0;
                    index < stages.length && index < 4;
                    index++) ...[
                  _JourneyStageRow(
                    stage: stages[index],
                    current: index == currentStageIndex,
                  ),
                  if (index < stages.length - 1 && index < 3) const Divider(),
                ],
              ],
            ),
          ),
        ],
      ],
    );
  }
}

class _JourneyStageRow extends StatelessWidget {
  const _JourneyStageRow({required this.stage, required this.current});

  final TrainingStage stage;
  final bool current;

  @override
  Widget build(BuildContext context) {
    final completed = stage.status == TrainingStageStatus.completed ||
        stage.status == TrainingStageStatus.skipped;
    final locked = stage.status == TrainingStageStatus.locked;
    final color =
        completed || current ? AppColors.brandGreen700 : AppColors.ink400;
    return ListTile(
      minTileHeight: 68,
      leading: Container(
        width: 38,
        height: 38,
        decoration: BoxDecoration(
          color: completed || current
              ? AppColors.brandGreen100
              : AppColors.paperInput,
          shape: BoxShape.circle,
        ),
        alignment: Alignment.center,
        child: Icon(
          completed
              ? Icons.check_rounded
              : locked
                  ? Icons.lock_outline_rounded
                  : stageIcon(stage.type),
          color: color,
          size: 21,
        ),
      ),
      title: Text(stage.title,
          style: AppTypography.bodyStrong(
              color: locked ? AppColors.ink500 : AppColors.ink900)),
      subtitle: Text(
        current ? 'Текущий этап' : stageStatusLabel(stage.status),
        style: AppTypography.captionSmall(color: color),
      ),
      trailing: Icon(Icons.chevron_right_rounded,
          color: locked ? AppColors.ink300 : AppColors.ink400),
    );
  }
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle({required this.title});

  final String title;

  @override
  Widget build(BuildContext context) => Text(title, style: AppTypography.h3());
}

class _AssignmentCard extends StatelessWidget {
  const _AssignmentCard({required this.assignment, required this.onTap});

  final TrainingAssignment assignment;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final statusColor = trainingStatusColor(assignment.status);
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
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (assignment.coverUrl != null)
                SizedBox(
                  height: 116,
                  width: double.infinity,
                  child: MediaImage(
                    url: assignment.coverUrl,
                    fit: BoxFit.cover,
                    cacheWidth: 900,
                    placeholder: () => const ColoredBox(
                      color: AppColors.brandGreen100,
                      child: Center(
                        child: Icon(Icons.school_outlined,
                            size: 38, color: AppColors.brandGreen700),
                      ),
                    ),
                  ),
                ),
              Padding(
                padding: const EdgeInsets.all(AppSpacing.s16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            assignment.programName,
                            style: AppTypography.bodyStrong(),
                          ),
                        ),
                        const SizedBox(width: AppSpacing.s8),
                        const Icon(Icons.chevron_right_rounded,
                            color: AppColors.ink400),
                      ],
                    ),
                    if (assignment.shortDescription.isNotEmpty) ...[
                      const SizedBox(height: AppSpacing.s4),
                      Text(
                        assignment.shortDescription,
                        style: AppTypography.caption(),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                    const SizedBox(height: AppSpacing.s12),
                    Wrap(
                      spacing: AppSpacing.s8,
                      runSpacing: AppSpacing.s8,
                      children: [
                        _Tag(
                          label: trainingFormatLabel(assignment.format),
                          color: AppColors.ink700,
                        ),
                        _Tag(
                          label: assignmentStatusLabel(assignment.status),
                          color: statusColor,
                        ),
                        if (assignment.required)
                          const _Tag(
                              label: 'Обязательно', color: Color(0xFF9A542E)),
                      ],
                    ),
                    const SizedBox(height: AppSpacing.s12),
                    ClipRRect(
                      borderRadius: AppRadii.brFull,
                      child: LinearProgressIndicator(
                        value: assignment.progressPct / 100,
                        minHeight: 8,
                        backgroundColor: AppColors.paperInput,
                        color: statusColor,
                      ),
                    ),
                    const SizedBox(height: AppSpacing.s8),
                    Row(
                      children: [
                        Text('${assignment.progressPct}%',
                            style: AppTypography.caption(color: statusColor)),
                        const Spacer(),
                        if (assignment.dueAt != null)
                          Text('до ${trainingDate(assignment.dueAt)}',
                              style: AppTypography.captionSmall()),
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _Tag extends StatelessWidget {
  const _Tag({required this.label, required this.color});

  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.1),
          borderRadius: AppRadii.brFull,
        ),
        child: Text(label, style: AppTypography.micro(color: color)),
      );
}

class _EventCard extends StatelessWidget {
  const _EventCard({required this.event});

  final TrainingEvent event;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(AppSpacing.s16),
        decoration: BoxDecoration(
          color: const Color(0xFFFFF3EA),
          borderRadius: AppRadii.brLg,
          border: Border.all(color: const Color(0x1FD86C3A)),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Icon(Icons.event_available_outlined,
                color: AppColors.brandGreen700),
            const SizedBox(width: AppSpacing.s12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(event.title, style: AppTypography.bodyStrong()),
                  const SizedBox(height: AppSpacing.s4),
                  Text(trainingDate(event.startsAt, withTime: true),
                      style: AppTypography.body14()),
                  if (event.city.isNotEmpty || event.address.isNotEmpty)
                    Text(
                      [event.city, event.address]
                          .where((part) => part.isNotEmpty)
                          .join(', '),
                      style: AppTypography.caption(),
                    ),
                  if (event.capacity > 0)
                    Text(
                      'Свободно мест: ${event.availableSeats} из ${event.capacity}',
                      style: AppTypography.captionSmall(),
                    ),
                ],
              ),
            ),
          ],
        ),
      );
}

class _NotificationCard extends StatelessWidget {
  const _NotificationCard({required this.notification, required this.onTap});

  final TrainingNotification notification;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Material(
        color: notification.read ? Colors.white : AppColors.brandGreen100,
        borderRadius: AppRadii.brLg,
        child: InkWell(
          onTap: onTap,
          borderRadius: AppRadii.brLg,
          child: Container(
            padding: const EdgeInsets.all(AppSpacing.s16),
            decoration: BoxDecoration(
              borderRadius: AppRadii.brLg,
              border: Border.all(
                color: notification.read
                    ? AppColors.paperInput
                    : AppColors.brandGreen700.withValues(alpha: 0.22),
              ),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(
                  notification.eventType == 'training_completed'
                      ? Icons.workspace_premium_outlined
                      : notification.eventType.startsWith('training_event')
                          ? Icons.event_outlined
                          : Icons.school_outlined,
                  color: AppColors.brandGreen700,
                ),
                const SizedBox(width: AppSpacing.s12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(notification.title,
                          style: AppTypography.bodyStrong()),
                      if (notification.message.isNotEmpty) ...[
                        const SizedBox(height: AppSpacing.s4),
                        Text(notification.message,
                            style: AppTypography.caption()),
                      ],
                      if (notification.scheduledAt != null) ...[
                        const SizedBox(height: AppSpacing.s4),
                        Text(
                          trainingDate(notification.scheduledAt,
                              withTime: true),
                          style: AppTypography.captionSmall(),
                        ),
                      ],
                    ],
                  ),
                ),
                if (!notification.read)
                  Container(
                    width: 8,
                    height: 8,
                    decoration: const BoxDecoration(
                      color: AppColors.brandGreen700,
                      shape: BoxShape.circle,
                    ),
                  ),
              ],
            ),
          ),
        ),
      );
}

class _CertificateRow extends StatelessWidget {
  const _CertificateRow({required this.certificate, required this.onOpen});

  final TrainingCertificate certificate;
  final VoidCallback? onOpen;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(AppSpacing.s16),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: AppRadii.brLg,
          boxShadow: AppShadows.card,
        ),
        child: Row(
          children: [
            const Icon(Icons.workspace_premium_outlined,
                color: AppColors.brandGreen700, size: 30),
            const SizedBox(width: AppSpacing.s12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(certificate.programName,
                      style: AppTypography.bodyStrong()),
                  Text(
                    '№ ${certificate.number} · ${trainingDate(certificate.issuedAt)}',
                    style: AppTypography.caption(),
                  ),
                ],
              ),
            ),
            if (onOpen != null)
              IconButton(
                tooltip: 'Открыть сертификат',
                onPressed: onOpen,
                icon: const Icon(Icons.open_in_new_rounded,
                    color: AppColors.ink700),
              ),
          ],
        ),
      );
}

class _EmptyPrograms extends StatelessWidget {
  const _EmptyPrograms({this.searching = false});

  final bool searching;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(AppSpacing.s24),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: AppRadii.brLg,
        ),
        child: Column(
          children: [
            const Icon(Icons.school_outlined,
                size: 36, color: AppColors.ink400),
            const SizedBox(height: AppSpacing.s8),
            Text(
                searching
                    ? 'По вашему запросу ничего не найдено'
                    : 'В этой группе программ пока нет',
                style: AppTypography.body14(),
                textAlign: TextAlign.center),
          ],
        ),
      );
}

class _LoginRequired extends StatelessWidget {
  const _LoginRequired({required this.onLogin});

  final VoidCallback onLogin;

  @override
  Widget build(BuildContext context) => ListView(
        padding: const EdgeInsets.all(AppSpacing.screenEdge),
        children: [
          Container(
            padding: const EdgeInsets.all(AppSpacing.s24),
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: AppRadii.brLg,
              boxShadow: AppShadows.card,
            ),
            child: Column(
              children: [
                const Icon(Icons.lock_outline_rounded,
                    size: 42, color: AppColors.brandGreen700),
                const SizedBox(height: AppSpacing.s12),
                Text('Войдите, чтобы открыть обучение',
                    style: AppTypography.h3(), textAlign: TextAlign.center),
                const SizedBox(height: AppSpacing.s8),
                Text(
                  'Назначенные программы, события и сертификаты привязаны к профилю фармацевта.',
                  style: AppTypography.body14(),
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: AppSpacing.s20),
                PrimaryButton(label: 'Войти', onPressed: onLogin),
              ],
            ),
          ),
        ],
      );
}

class _TrainingError extends StatelessWidget {
  const _TrainingError({required this.error, required this.onRetry});

  final Object error;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) => ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(AppSpacing.screenEdge),
        children: [
          const SizedBox(height: AppSpacing.s32),
          const Icon(Icons.cloud_off_outlined,
              size: 42, color: AppColors.ink400),
          const SizedBox(height: AppSpacing.s12),
          Text(messageFromError(error),
              style: AppTypography.body14(), textAlign: TextAlign.center),
          const SizedBox(height: AppSpacing.s20),
          PrimaryButton(label: 'Повторить', onPressed: onRetry),
        ],
      );
}
