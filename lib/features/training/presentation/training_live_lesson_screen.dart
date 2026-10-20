import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_radii.dart';
import '../../../core/theme/app_spacing.dart';
import '../../../core/theme/app_typography.dart';
import '../../../core/widgets/error_snackbar.dart';
import '../application/training_controller.dart';
import '../data/training_models.dart';
import 'training_ui.dart';

class TrainingLiveLessonScreen extends ConsumerStatefulWidget {
  const TrainingLiveLessonScreen({
    super.key,
    required this.assignmentId,
    required this.stageId,
    required this.lesson,
  });

  final String assignmentId;
  final String stageId;
  final TrainingLesson lesson;

  @override
  ConsumerState<TrainingLiveLessonScreen> createState() =>
      _TrainingLiveLessonScreenState();
}

class _TrainingLiveLessonScreenState
    extends ConsumerState<TrainingLiveLessonScreen> {
  final _messageController = TextEditingController();
  final _scrollController = ScrollController();
  Timer? _refreshTimer;
  TrainingLiveRoom? _room;
  WebViewController? _webViewController;
  String? _loadedStreamUrl;
  Object? _error;
  bool _loading = true;
  bool _sending = false;
  bool _questionMode = false;
  int _sectionIndex = 0;

  @override
  void initState() {
    super.initState();
    _loadRoom();
    _refreshTimer = Timer.periodic(
      const Duration(seconds: 5),
      (_) => _loadRoom(silent: true),
    );
  }

  @override
  void dispose() {
    _refreshTimer?.cancel();
    _messageController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  Future<void> _loadRoom({bool silent = false}) async {
    if (!silent && mounted) {
      setState(() {
        _loading = true;
        _error = null;
      });
    }
    try {
      final room = await ref.read(trainingRepositoryProvider).loadLiveRoom(
            assignmentId: widget.assignmentId,
            stageId: widget.stageId,
            lessonId: widget.lesson.id,
          );
      if (!mounted) return;
      _setRoom(room);
    } catch (error) {
      if (!silent && mounted) setState(() => _error = error);
    } finally {
      if (!silent && mounted) setState(() => _loading = false);
    }
  }

  void _setRoom(TrainingLiveRoom room) {
    setState(() {
      _room = room;
      _loading = false;
      _error = null;
    });
    _prepareWebView(room);
  }

  void _prepareWebView(TrainingLiveRoom room) {
    final rawUrl = room.streamUrl;
    if (rawUrl == null || rawUrl.isEmpty || _loadedStreamUrl == rawUrl) return;
    final uri = Uri.tryParse(_embedUrl(rawUrl));
    if (uri == null || !uri.hasScheme) return;
    _loadedStreamUrl = rawUrl;
    _webViewController = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(const Color(0xFF081F24))
      ..setNavigationDelegate(
        NavigationDelegate(
          onNavigationRequest: (request) {
            final next = Uri.tryParse(request.url);
            if (next == null || !next.hasScheme) {
              return NavigationDecision.prevent;
            }
            return NavigationDecision.navigate;
          },
        ),
      )
      ..loadRequest(uri);
    if (mounted) setState(() {});
  }

  Future<void> _sendMessage() async {
    final body = _messageController.text.trim();
    if (body.isEmpty || _sending) return;
    setState(() => _sending = true);
    try {
      final room = await ref.read(trainingRepositoryProvider).postLiveMessage(
            assignmentId: widget.assignmentId,
            stageId: widget.stageId,
            lessonId: widget.lesson.id,
            body: body,
            kind: (_room?.chatEnabled == false && _room?.qaEnabled == true) ||
                    _questionMode
                ? 'question'
                : 'comment',
          );
      if (!mounted) return;
      _messageController.clear();
      _setRoom(room);
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (_scrollController.hasClients) {
          _scrollController.animateTo(
            _scrollController.position.maxScrollExtent,
            duration: const Duration(milliseconds: 250),
            curve: Curves.easeOut,
          );
        }
      });
    } catch (error) {
      if (mounted) showErrorSnackBar(context, error);
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  Future<void> _vote(TrainingLivePoll poll, String optionId) async {
    try {
      final room = await ref.read(trainingRepositoryProvider).voteInLivePoll(
            assignmentId: widget.assignmentId,
            stageId: widget.stageId,
            lessonId: widget.lesson.id,
            pollId: poll.id,
            optionId: optionId,
          );
      if (mounted) _setRoom(room);
    } catch (error) {
      if (mounted) showErrorSnackBar(context, error);
    }
  }

  Future<void> _react(String reaction) async {
    try {
      final room = await ref.read(trainingRepositoryProvider).reactToLiveLesson(
            assignmentId: widget.assignmentId,
            stageId: widget.stageId,
            lessonId: widget.lesson.id,
            reaction: reaction,
          );
      if (mounted) _setRoom(room);
    } catch (error) {
      if (mounted) showErrorSnackBar(context, error);
    }
  }

  Future<void> _openExternally() async {
    final uri = Uri.tryParse(_room?.streamUrl ?? '');
    if (uri == null ||
        !await launchUrl(uri, mode: LaunchMode.externalApplication)) {
      if (mounted) {
        showErrorSnackBar(context, Exception('Не удалось открыть эфир'));
      }
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        backgroundColor: AppColors.paperCanvas,
        appBar: AppBar(
          title: const Text('Прямой эфир'),
          actions: [
            IconButton(
              tooltip: 'Обновить',
              onPressed: () => _loadRoom(),
              icon: const Icon(Icons.refresh_rounded),
            ),
          ],
        ),
        body: _loading
            ? const Center(child: CircularProgressIndicator())
            : _error != null
                ? _LiveError(onRetry: _loadRoom)
                : _buildRoom(_room!),
      );

  Widget _buildRoom(TrainingLiveRoom room) => SafeArea(
        top: false,
        child: Column(
          children: [
            _LiveHeader(room: room),
            _StreamPlayer(
              room: room,
              controller: _webViewController,
              onOpenExternally: room.streamUrl == null ? null : _openExternally,
            ),
            if (room.reactionsEnabled)
              _ReactionBar(room: room, onReact: _react),
            Padding(
              padding: const EdgeInsets.fromLTRB(
                AppSpacing.s16,
                AppSpacing.s8,
                AppSpacing.s16,
                AppSpacing.s8,
              ),
              child: SegmentedButton<int>(
                segments: [
                  ButtonSegment(
                    value: 0,
                    icon: const Icon(Icons.forum_outlined, size: 18),
                    label: Text('Обсуждение · ${room.messages.length}'),
                  ),
                  ButtonSegment(
                    value: 1,
                    icon: const Icon(Icons.poll_outlined, size: 18),
                    label: Text('Опросы · ${room.polls.length}'),
                  ),
                ],
                selected: {_sectionIndex},
                onSelectionChanged: (selection) =>
                    setState(() => _sectionIndex = selection.first),
                showSelectedIcon: false,
              ),
            ),
            Expanded(
              child: _sectionIndex == 0
                  ? _LiveMessages(
                      controller: _scrollController,
                      room: room,
                    )
                  : _LivePolls(room: room, onVote: _vote),
            ),
            if (_sectionIndex == 0 &&
                (room.chatEnabled || room.qaEnabled) &&
                !room.isEnded)
              _MessageComposer(
                controller: _messageController,
                questionMode:
                    (!room.chatEnabled && room.qaEnabled) || _questionMode,
                questionsEnabled: room.chatEnabled && room.qaEnabled,
                sending: _sending,
                onQuestionModeChanged: (value) =>
                    setState(() => _questionMode = value),
                onSend: _sendMessage,
              ),
          ],
        ),
      );
}

class _LiveHeader extends StatelessWidget {
  const _LiveHeader({required this.room});

  final TrainingLiveRoom room;

  @override
  Widget build(BuildContext context) {
    final (label, color) = switch (room.status) {
      'live' => ('В ЭФИРЕ', const Color(0xFFD94747)),
      'ended' => ('ЗАВЕРШЁН', AppColors.ink500),
      _ => ('СКОРО', AppColors.brandGreen700),
    };
    return Padding(
      padding: const EdgeInsets.fromLTRB(
        AppSpacing.s16,
        AppSpacing.s12,
        AppSpacing.s16,
        AppSpacing.s8,
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
            decoration: BoxDecoration(
              color: color.withValues(alpha: 0.12),
              borderRadius: AppRadii.brFull,
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (room.isLive) ...[
                  Container(
                    width: 7,
                    height: 7,
                    decoration:
                        BoxDecoration(color: color, shape: BoxShape.circle),
                  ),
                  const SizedBox(width: 6),
                ],
                Text(label, style: AppTypography.captionSmall(color: color)),
              ],
            ),
          ),
          const SizedBox(width: AppSpacing.s12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(room.title, style: AppTypography.bodyStrong()),
                const SizedBox(height: 2),
                Text(
                  room.isEnded
                      ? 'Запись и обсуждение сохранены'
                      : '${trainingDate(room.startsAt, withTime: true)} · ${room.participantCount} участников',
                  style: AppTypography.captionSmall(),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _StreamPlayer extends StatelessWidget {
  const _StreamPlayer({
    required this.room,
    required this.controller,
    required this.onOpenExternally,
  });

  final TrainingLiveRoom room;
  final WebViewController? controller;
  final VoidCallback? onOpenExternally;

  @override
  Widget build(BuildContext context) => AspectRatio(
        aspectRatio: 16 / 9,
        child: ColoredBox(
          color: const Color(0xFF081F24),
          child: room.isLive && controller != null
              ? Stack(
                  children: [
                    WebViewWidget(controller: controller!),
                    Positioned(
                      right: AppSpacing.s8,
                      top: AppSpacing.s8,
                      child: IconButton.filledTonal(
                        tooltip: 'Открыть отдельно',
                        onPressed: onOpenExternally,
                        icon: const Icon(Icons.open_in_new_rounded, size: 18),
                      ),
                    ),
                  ],
                )
              : Center(
                  child: Padding(
                    padding: const EdgeInsets.all(AppSpacing.s24),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          room.isEnded
                              ? Icons.check_circle_outline_rounded
                              : Icons.live_tv_rounded,
                          color: Colors.white70,
                          size: 42,
                        ),
                        const SizedBox(height: AppSpacing.s12),
                        Text(
                          room.isEnded
                              ? 'Эфир завершён'
                              : 'Трансляция появится здесь после запуска',
                          textAlign: TextAlign.center,
                          style: AppTypography.body14(color: Colors.white),
                        ),
                        if (onOpenExternally != null) ...[
                          const SizedBox(height: AppSpacing.s12),
                          TextButton.icon(
                            onPressed: onOpenExternally,
                            icon: const Icon(Icons.open_in_new_rounded),
                            label: const Text('Открыть трансляцию'),
                            style: TextButton.styleFrom(
                                foregroundColor: Colors.white),
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
        ),
      );
}

class _ReactionBar extends StatelessWidget {
  const _ReactionBar({required this.room, required this.onReact});

  final TrainingLiveRoom room;
  final ValueChanged<String> onReact;

  static const reactions = <String, String>{
    'like': '👍',
    'heart': '❤️',
    'clap': '👏',
    'wow': '🔥',
    'question': '❓',
  };

  @override
  Widget build(BuildContext context) => SizedBox(
        height: 54,
        child: ListView(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.symmetric(
            horizontal: AppSpacing.s16,
            vertical: AppSpacing.s8,
          ),
          children: reactions.entries
              .map(
                (entry) => Padding(
                  padding: const EdgeInsets.only(right: AppSpacing.s8),
                  child: ActionChip(
                    onPressed: () => onReact(entry.key),
                    avatar: Text(entry.value),
                    label: Text('${room.reactions[entry.key] ?? 0}'),
                  ),
                ),
              )
              .toList(growable: false),
        ),
      );
}

class _LiveMessages extends StatelessWidget {
  const _LiveMessages({required this.controller, required this.room});

  final ScrollController controller;
  final TrainingLiveRoom room;

  @override
  Widget build(BuildContext context) {
    if (room.messages.isEmpty) {
      return const _EmptyState(
        icon: Icons.forum_outlined,
        title: 'Обсуждение ещё не началось',
        subtitle: 'Напишите первым или задайте вопрос ведущему.',
      );
    }
    return ListView.separated(
      controller: controller,
      padding: const EdgeInsets.fromLTRB(
        AppSpacing.s16,
        AppSpacing.s8,
        AppSpacing.s16,
        AppSpacing.s16,
      ),
      itemCount: room.messages.length,
      separatorBuilder: (_, __) => const SizedBox(height: AppSpacing.s8),
      itemBuilder: (context, index) {
        final message = room.messages[index];
        final accent = message.kind == 'question' || message.pinned;
        return Align(
          alignment:
              message.mine ? Alignment.centerRight : Alignment.centerLeft,
          child: Container(
            constraints: const BoxConstraints(maxWidth: 360),
            padding: const EdgeInsets.all(AppSpacing.s12),
            decoration: BoxDecoration(
              color: message.mine
                  ? AppColors.brandGreen100
                  : accent
                      ? const Color(0xFFFFF3E8)
                      : Colors.white,
              borderRadius: AppRadii.brMd,
              border: Border.all(
                color:
                    accent ? const Color(0x33D86C3A) : AppColors.borderHairline,
              ),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (message.kind == 'question') ...[
                      const Icon(Icons.help_outline_rounded, size: 15),
                      const SizedBox(width: 4),
                    ],
                    Flexible(
                      child: Text(
                        message.authorName,
                        overflow: TextOverflow.ellipsis,
                        style: AppTypography.captionSmall(
                          color: AppColors.brandGreen700,
                        ),
                      ),
                    ),
                    if (message.pinned) ...[
                      const SizedBox(width: 6),
                      const Icon(Icons.push_pin_outlined, size: 14),
                    ],
                  ],
                ),
                const SizedBox(height: 4),
                Text(message.body, style: AppTypography.body14()),
              ],
            ),
          ),
        );
      },
    );
  }
}

class _LivePolls extends StatelessWidget {
  const _LivePolls({required this.room, required this.onVote});

  final TrainingLiveRoom room;
  final Future<void> Function(TrainingLivePoll poll, String optionId) onVote;

  @override
  Widget build(BuildContext context) {
    if (room.polls.isEmpty) {
      return const _EmptyState(
        icon: Icons.poll_outlined,
        title: 'Активных опросов пока нет',
        subtitle: 'Когда ведущий запустит интерактив, он появится здесь.',
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.fromLTRB(
        AppSpacing.s16,
        AppSpacing.s8,
        AppSpacing.s16,
        AppSpacing.s24,
      ),
      itemCount: room.polls.length,
      separatorBuilder: (_, __) => const SizedBox(height: AppSpacing.s12),
      itemBuilder: (context, index) {
        final poll = room.polls[index];
        return Container(
          padding: const EdgeInsets.all(AppSpacing.s16),
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: AppRadii.brLg,
            border: Border.all(color: AppColors.borderHairline),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(poll.prompt, style: AppTypography.bodyStrong()),
              const SizedBox(height: AppSpacing.s4),
              Text(
                poll.active ? '${poll.totalVotes} ответов' : 'Опрос завершён',
                style: AppTypography.captionSmall(),
              ),
              const SizedBox(height: AppSpacing.s12),
              ...poll.options.map(
                (option) => Padding(
                  padding: const EdgeInsets.only(bottom: AppSpacing.s8),
                  child: InkWell(
                    onTap: poll.active ? () => onVote(poll, option.id) : null,
                    borderRadius: AppRadii.brMd,
                    child: Container(
                      padding: const EdgeInsets.all(AppSpacing.s12),
                      decoration: BoxDecoration(
                        color: poll.myOptionId == option.id
                            ? AppColors.brandGreen100
                            : AppColors.paperInput,
                        borderRadius: AppRadii.brMd,
                        border: Border.all(
                          color: poll.myOptionId == option.id
                              ? AppColors.brandGreen700
                              : Colors.transparent,
                        ),
                      ),
                      child: Column(
                        children: [
                          Row(
                            children: [
                              Expanded(child: Text(option.label)),
                              Text('${option.percent}%'),
                            ],
                          ),
                          const SizedBox(height: AppSpacing.s8),
                          LinearProgressIndicator(
                            value: option.percent / 100,
                            minHeight: 5,
                            borderRadius: AppRadii.brFull,
                            color: AppColors.brandGreen700,
                            backgroundColor: Colors.white,
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}

class _MessageComposer extends StatelessWidget {
  const _MessageComposer({
    required this.controller,
    required this.questionMode,
    required this.questionsEnabled,
    required this.sending,
    required this.onQuestionModeChanged,
    required this.onSend,
  });

  final TextEditingController controller;
  final bool questionMode;
  final bool questionsEnabled;
  final bool sending;
  final ValueChanged<bool> onQuestionModeChanged;
  final VoidCallback onSend;

  @override
  Widget build(BuildContext context) => Material(
        color: Colors.white,
        elevation: 12,
        child: Padding(
          padding: EdgeInsets.fromLTRB(
            AppSpacing.s12,
            AppSpacing.s8,
            AppSpacing.s12,
            AppSpacing.s8 + MediaQuery.viewInsetsOf(context).bottom,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (questionsEnabled)
                Align(
                  alignment: Alignment.centerLeft,
                  child: ChoiceChip(
                    selected: questionMode,
                    onSelected: onQuestionModeChanged,
                    avatar: const Icon(Icons.help_outline_rounded, size: 17),
                    label: const Text('Вопрос ведущему'),
                  ),
                ),
              Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: controller,
                      minLines: 1,
                      maxLines: 4,
                      maxLength: 2000,
                      textInputAction: TextInputAction.newline,
                      decoration: InputDecoration(
                        hintText:
                            questionMode ? 'Введите вопрос…' : 'Сообщение…',
                        counterText: '',
                      ),
                    ),
                  ),
                  const SizedBox(width: AppSpacing.s8),
                  IconButton.filled(
                    tooltip: 'Отправить',
                    onPressed: sending ? null : onSend,
                    icon: sending
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.send_rounded),
                  ),
                ],
              ),
            ],
          ),
        ),
      );
}

class _EmptyState extends StatelessWidget {
  const _EmptyState(
      {required this.icon, required this.title, required this.subtitle});

  final IconData icon;
  final String title;
  final String subtitle;

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.s24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, size: 40, color: AppColors.ink400),
              const SizedBox(height: AppSpacing.s12),
              Text(title,
                  textAlign: TextAlign.center,
                  style: AppTypography.bodyStrong()),
              const SizedBox(height: AppSpacing.s4),
              Text(subtitle,
                  textAlign: TextAlign.center, style: AppTypography.caption()),
            ],
          ),
        ),
      );
}

class _LiveError extends StatelessWidget {
  const _LiveError({required this.onRetry});

  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.s24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.wifi_off_rounded,
                  size: 42, color: AppColors.ink400),
              const SizedBox(height: AppSpacing.s12),
              Text('Комната эфира недоступна',
                  style: AppTypography.bodyStrong()),
              const SizedBox(height: AppSpacing.s12),
              FilledButton.icon(
                onPressed: onRetry,
                icon: const Icon(Icons.refresh_rounded),
                label: const Text('Повторить'),
              ),
            ],
          ),
        ),
      );
}

String _embedUrl(String rawUrl) {
  final uri = Uri.tryParse(rawUrl);
  if (uri == null) return rawUrl;
  final host = uri.host.toLowerCase();
  if (host.contains('youtu.be') && uri.pathSegments.isNotEmpty) {
    return 'https://www.youtube.com/embed/${uri.pathSegments.first}?playsinline=1';
  }
  if (host.contains('youtube.com')) {
    final videoId = uri.queryParameters['v'];
    if (videoId != null && videoId.isNotEmpty) {
      return 'https://www.youtube.com/embed/$videoId?playsinline=1';
    }
  }
  if (host.contains('vimeo.com') && uri.pathSegments.isNotEmpty) {
    return 'https://player.vimeo.com/video/${uri.pathSegments.last}';
  }
  return rawUrl;
}
