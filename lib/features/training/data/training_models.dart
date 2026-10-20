enum TrainingFormat { online, hybrid, offline }

enum TrainingAssignmentStatus {
  scheduled,
  notStarted,
  inProgress,
  waitingOnline,
  waitingTest,
  waitingExam,
  waitingEventSelection,
  waitingOffline,
  waitingAttendance,
  waitingReview,
  retakeRequired,
  completed,
  overdue,
  paused,
  cancelled,
}

enum TrainingPriority { low, normal, high, critical }

enum TrainingStageType {
  material,
  onlineCourse,
  test,
  aiExam,
  offlineEvent,
  manualReview,
}

enum TrainingStageStatus {
  locked,
  available,
  inProgress,
  waitingReview,
  completed,
  failed,
  skipped,
}

TrainingFormat _trainingFormat(Object? value) => switch (value) {
      'hybrid' => TrainingFormat.hybrid,
      'offline' => TrainingFormat.offline,
      _ => TrainingFormat.online,
    };

TrainingAssignmentStatus _assignmentStatus(Object? value) => switch (value) {
      'scheduled' => TrainingAssignmentStatus.scheduled,
      'in_progress' => TrainingAssignmentStatus.inProgress,
      'waiting_online' => TrainingAssignmentStatus.waitingOnline,
      'waiting_test' => TrainingAssignmentStatus.waitingTest,
      'waiting_exam' => TrainingAssignmentStatus.waitingExam,
      'waiting_event_selection' =>
        TrainingAssignmentStatus.waitingEventSelection,
      'waiting_offline' => TrainingAssignmentStatus.waitingOffline,
      'waiting_attendance' => TrainingAssignmentStatus.waitingAttendance,
      'waiting_review' => TrainingAssignmentStatus.waitingReview,
      'retake_required' => TrainingAssignmentStatus.retakeRequired,
      'completed' => TrainingAssignmentStatus.completed,
      'overdue' => TrainingAssignmentStatus.overdue,
      'paused' => TrainingAssignmentStatus.paused,
      'cancelled' => TrainingAssignmentStatus.cancelled,
      _ => TrainingAssignmentStatus.notStarted,
    };

TrainingPriority _trainingPriority(Object? value) => switch (value) {
      'low' => TrainingPriority.low,
      'high' => TrainingPriority.high,
      'critical' => TrainingPriority.critical,
      _ => TrainingPriority.normal,
    };

TrainingStageType _stageType(Object? value) => switch (value) {
      'online_course' => TrainingStageType.onlineCourse,
      'test' => TrainingStageType.test,
      'ai_exam' => TrainingStageType.aiExam,
      'offline_event' => TrainingStageType.offlineEvent,
      'manual_review' => TrainingStageType.manualReview,
      _ => TrainingStageType.material,
    };

TrainingStageStatus _stageStatus(Object? value) => switch (value) {
      'available' => TrainingStageStatus.available,
      'in_progress' => TrainingStageStatus.inProgress,
      'waiting_review' => TrainingStageStatus.waitingReview,
      'completed' => TrainingStageStatus.completed,
      'failed' => TrainingStageStatus.failed,
      'skipped' => TrainingStageStatus.skipped,
      _ => TrainingStageStatus.locked,
    };

DateTime? _date(Object? value) =>
    value is String ? DateTime.tryParse(value)?.toLocal() : null;

int _int(Object? value) => value is num ? value.toInt() : 0;

Map<String, dynamic>? _map(Object? value) =>
    value is Map<String, dynamic> ? value : null;

List<Map<String, dynamic>> _maps(Object? value) => value is List
    ? value.whereType<Map<String, dynamic>>().toList(growable: false)
    : const [];

class TrainingOverview {
  const TrainingOverview({
    required this.total,
    required this.inProgress,
    required this.completed,
    required this.overdue,
    required this.upcomingEvents,
    required this.assignments,
    required this.certificates,
    required this.notifications,
    required this.defaultFormat,
  });

  final int total;
  final int inProgress;
  final int completed;
  final int overdue;
  final List<TrainingEvent> upcomingEvents;
  final List<TrainingAssignment> assignments;
  final List<TrainingCertificate> certificates;
  final List<TrainingNotification> notifications;
  final TrainingFormat? defaultFormat;

  factory TrainingOverview.fromJson(Map<String, dynamic> json) =>
      TrainingOverview(
        total: _int(json['total']),
        inProgress: _int(json['inProgress']),
        completed: _int(json['completed']),
        overdue: _int(json['overdue']),
        upcomingEvents:
            _maps(json['upcomingEvents']).map(TrainingEvent.fromJson).toList(),
        assignments: _maps(json['assignments'])
            .map(TrainingAssignment.fromJson)
            .toList(),
        certificates: _maps(json['certificates'])
            .map(TrainingCertificate.fromJson)
            .toList(),
        notifications: _maps(json['notifications'])
            .map(TrainingNotification.fromJson)
            .toList(),
        defaultFormat: json['defaultFormat'] == null
            ? null
            : _trainingFormat(json['defaultFormat']),
      );
}

class TrainingAssignment {
  const TrainingAssignment({
    required this.id,
    required this.programId,
    required this.programVersion,
    required this.programName,
    required this.shortDescription,
    required this.coverUrl,
    required this.pharmacyName,
    required this.city,
    required this.format,
    required this.status,
    required this.priority,
    required this.required,
    required this.event,
    required this.startsAt,
    required this.dueAt,
    required this.progressPct,
    required this.score,
    required this.startedAt,
    required this.completedAt,
    required this.stages,
    required this.certificate,
    required this.reward,
  });

  final String id;
  final String programId;
  final int programVersion;
  final String programName;
  final String shortDescription;
  final String? coverUrl;
  final String pharmacyName;
  final String city;
  final TrainingFormat format;
  final TrainingAssignmentStatus status;
  final TrainingPriority priority;
  final bool required;
  final TrainingEvent? event;
  final DateTime? startsAt;
  final DateTime? dueAt;
  final int progressPct;
  final int? score;
  final DateTime? startedAt;
  final DateTime? completedAt;
  final List<TrainingStage> stages;
  final TrainingCertificate? certificate;
  final TrainingReward? reward;

  bool get isCompleted => status == TrainingAssignmentStatus.completed;
  bool get isCancelled => status == TrainingAssignmentStatus.cancelled;
  bool get canStart =>
      !isCompleted &&
      !isCancelled &&
      startedAt == null &&
      (startsAt == null || !startsAt!.isAfter(DateTime.now()));

  factory TrainingAssignment.fromJson(Map<String, dynamic> json) {
    final scoreValue = json['score'];
    return TrainingAssignment(
      id: json['id'] as String? ?? '',
      programId: json['programId'] as String? ?? '',
      programVersion: _int(json['programVersion']),
      programName: json['programName'] as String? ?? 'Программа обучения',
      shortDescription: json['programShortDescription'] as String? ?? '',
      coverUrl: json['coverUrl'] as String?,
      pharmacyName: json['pharmacyName'] as String? ?? '',
      city: json['city'] as String? ?? '',
      format: _trainingFormat(json['format']),
      status: _assignmentStatus(json['status']),
      priority: _trainingPriority(json['priority']),
      required: json['required'] as bool? ?? true,
      event: _map(json['event'])?.let(TrainingEvent.fromJson),
      startsAt: _date(json['startsAt']),
      dueAt: _date(json['dueAt']),
      progressPct: _int(json['progressPct']).clamp(0, 100),
      score: scoreValue is num ? scoreValue.toInt() : null,
      startedAt: _date(json['startedAt']),
      completedAt: _date(json['completedAt']),
      stages: _maps(json['stages']).map(TrainingStage.fromJson).toList(),
      certificate: _map(json['certificate'])?.let(TrainingCertificate.fromJson),
      reward: _map(json['reward'])?.let(TrainingReward.fromJson),
    );
  }
}

class TrainingStage {
  const TrainingStage({
    required this.id,
    required this.key,
    required this.type,
    required this.title,
    required this.order,
    required this.required,
    required this.status,
    required this.progressPct,
    required this.score,
    required this.attemptsUsed,
    required this.maxAttempts,
    required this.passingScore,
    required this.contentUrl,
    required this.course,
  });

  final String id;
  final String key;
  final TrainingStageType type;
  final String title;
  final int order;
  final bool required;
  final TrainingStageStatus status;
  final int progressPct;
  final int? score;
  final int attemptsUsed;
  final int? maxAttempts;
  final int? passingScore;
  final String? contentUrl;
  final TrainingCourseContent? course;

  bool get isAvailable => status == TrainingStageStatus.available;
  bool get isInProgress => status == TrainingStageStatus.inProgress;
  bool get isCompletableInApp =>
      type == TrainingStageType.material ||
      type == TrainingStageType.onlineCourse;

  factory TrainingStage.fromJson(Map<String, dynamic> json) {
    final scoreValue = json['score'];
    final maxAttemptsValue = json['maxAttempts'];
    final passingScoreValue = json['passingScore'];
    return TrainingStage(
      id: json['id'] as String? ?? '',
      key: json['key'] as String? ?? '',
      type: _stageType(json['type']),
      title: json['title'] as String? ?? 'Этап обучения',
      order: _int(json['order']),
      required: json['required'] as bool? ?? true,
      status: _stageStatus(json['status']),
      progressPct: _int(json['progressPct']).clamp(0, 100),
      score: scoreValue is num ? scoreValue.toInt() : null,
      attemptsUsed: _int(json['attemptsUsed']),
      maxAttempts: maxAttemptsValue is num ? maxAttemptsValue.toInt() : null,
      passingScore: passingScoreValue is num ? passingScoreValue.toInt() : null,
      contentUrl: json['contentUrl'] as String?,
      course: _map(json['course'])?.let(TrainingCourseContent.fromJson),
    );
  }
}

class TrainingCourseContent {
  const TrainingCourseContent({
    required this.id,
    required this.title,
    required this.description,
    required this.durationMin,
    required this.lessons,
  });

  final String id;
  final String title;
  final String description;
  final int durationMin;
  final List<TrainingLesson> lessons;

  factory TrainingCourseContent.fromJson(Map<String, dynamic> json) =>
      TrainingCourseContent(
        id: json['id'] as String? ?? '',
        title: json['title'] as String? ?? 'Онлайн-курс',
        description: json['description'] as String? ?? '',
        durationMin: _int(json['durationMin']),
        lessons: _maps(json['lessons']).map(TrainingLesson.fromJson).toList(),
      );
}

class TrainingLesson {
  const TrainingLesson({
    required this.id,
    required this.title,
    required this.description,
    required this.content,
    required this.kind,
    required this.videoUrl,
    this.externalUrl,
    this.liveProvider,
    this.liveStatus,
    this.liveStartAt,
    this.liveEndAt,
    this.liveChatEnabled = true,
    this.liveQaEnabled = true,
    this.liveReactionsEnabled = true,
    this.requiredLesson = true,
    this.minimumWatchPct,
    this.attachments = const [],
    this.progressPct = 0,
    this.lastPositionSeconds = 0,
    this.startedAt,
    this.completedAt,
    required this.durationMin,
    required this.order,
  });

  final String id;
  final String title;
  final String description;
  final String content;
  final String kind;
  final String? videoUrl;
  final String? externalUrl;
  final String? liveProvider;
  final String? liveStatus;
  final DateTime? liveStartAt;
  final DateTime? liveEndAt;
  final bool liveChatEnabled;
  final bool liveQaEnabled;
  final bool liveReactionsEnabled;
  final bool requiredLesson;
  final int? minimumWatchPct;
  final List<TrainingLessonAttachment> attachments;
  final int progressPct;
  final int lastPositionSeconds;
  final DateTime? startedAt;
  final DateTime? completedAt;
  final int durationMin;
  final int order;

  bool get isVideo => kind == 'video';
  bool get isLive => kind == 'live';
  bool get hasVideo => videoUrl?.isNotEmpty ?? false;
  bool get hasExternalMaterial => externalUrl?.isNotEmpty ?? false;
  int get completionThreshold =>
      isVideo ? (minimumWatchPct ?? 80).clamp(1, 100) : 100;
  bool get isCompleted =>
      completedAt != null || progressPct >= completionThreshold;

  factory TrainingLesson.fromJson(Map<String, dynamic> json) => TrainingLesson(
        id: json['id'] as String? ?? '',
        title: json['title'] as String? ?? 'Урок',
        description: json['description'] as String? ?? '',
        content: json['content'] as String? ?? '',
        kind: json['kind'] as String? ?? 'text',
        videoUrl: json['videoUrl'] as String?,
        externalUrl: json['externalUrl'] as String?,
        liveProvider: json['liveProvider'] as String?,
        liveStatus: json['liveStatus'] as String?,
        liveStartAt: _date(json['liveStartAt']),
        liveEndAt: _date(json['liveEndAt']),
        liveChatEnabled: json['liveChatEnabled'] as bool? ?? true,
        liveQaEnabled: json['liveQaEnabled'] as bool? ?? true,
        liveReactionsEnabled: json['liveReactionsEnabled'] as bool? ?? true,
        requiredLesson: json['required'] as bool? ?? true,
        minimumWatchPct: json['minimumWatchPct'] == null
            ? null
            : _int(json['minimumWatchPct']).clamp(0, 100),
        attachments: _maps(json['attachments'])
            .map(TrainingLessonAttachment.fromJson)
            .toList(growable: false),
        progressPct: _int(json['progressPct']).clamp(0, 100),
        lastPositionSeconds: _int(json['lastPositionSeconds']).clamp(0, 86400),
        startedAt: _date(json['startedAt']),
        completedAt: _date(json['completedAt']),
        durationMin: _int(json['durationMin']),
        order: _int(json['order']),
      );
}

class TrainingLiveRoom {
  const TrainingLiveRoom({
    required this.lessonId,
    required this.title,
    required this.description,
    required this.provider,
    required this.status,
    required this.startsAt,
    required this.endsAt,
    required this.streamUrl,
    required this.chatEnabled,
    required this.qaEnabled,
    required this.reactionsEnabled,
    required this.participantCount,
    required this.messages,
    required this.polls,
    required this.reactions,
    required this.serverTime,
  });

  final String lessonId;
  final String title;
  final String description;
  final String provider;
  final String status;
  final DateTime? startsAt;
  final DateTime? endsAt;
  final String? streamUrl;
  final bool chatEnabled;
  final bool qaEnabled;
  final bool reactionsEnabled;
  final int participantCount;
  final List<TrainingLiveMessage> messages;
  final List<TrainingLivePoll> polls;
  final Map<String, int> reactions;
  final DateTime? serverTime;

  bool get isLive => status == 'live';
  bool get isEnded => status == 'ended';

  factory TrainingLiveRoom.fromJson(Map<String, dynamic> json) =>
      TrainingLiveRoom(
        lessonId: json['lessonId'] as String? ?? '',
        title: json['title'] as String? ?? 'Прямой эфир',
        description: json['description'] as String? ?? '',
        provider: json['provider'] as String? ?? 'custom',
        status: json['status'] as String? ?? 'scheduled',
        startsAt: _date(json['startsAt']),
        endsAt: _date(json['endsAt']),
        streamUrl: json['streamUrl'] as String?,
        chatEnabled: json['chatEnabled'] as bool? ?? true,
        qaEnabled: json['qaEnabled'] as bool? ?? true,
        reactionsEnabled: json['reactionsEnabled'] as bool? ?? true,
        participantCount: _int(json['participantCount']),
        messages: _maps(json['messages'])
            .map(TrainingLiveMessage.fromJson)
            .toList(growable: false),
        polls: _maps(json['polls'])
            .map(TrainingLivePoll.fromJson)
            .toList(growable: false),
        reactions: (json['reactions'] as Map<String, dynamic>? ?? const {})
            .map((key, value) => MapEntry(key, _int(value))),
        serverTime: _date(json['serverTime']),
      );
}

class TrainingLiveMessage {
  const TrainingLiveMessage({
    required this.id,
    required this.authorName,
    required this.authorType,
    required this.kind,
    required this.body,
    required this.pinned,
    required this.mine,
    required this.createdAt,
  });

  final String id;
  final String authorName;
  final String authorType;
  final String kind;
  final String body;
  final bool pinned;
  final bool mine;
  final DateTime? createdAt;

  factory TrainingLiveMessage.fromJson(Map<String, dynamic> json) =>
      TrainingLiveMessage(
        id: json['id'] as String? ?? '',
        authorName: json['authorName'] as String? ?? 'Участник',
        authorType: json['authorType'] as String? ?? 'pharmacist',
        kind: json['kind'] as String? ?? 'comment',
        body: json['body'] as String? ?? '',
        pinned: json['pinned'] as bool? ?? false,
        mine: json['mine'] as bool? ?? false,
        createdAt: _date(json['createdAt']),
      );
}

class TrainingLivePoll {
  const TrainingLivePoll({
    required this.id,
    required this.prompt,
    required this.active,
    required this.options,
    required this.totalVotes,
    required this.myOptionId,
  });

  final String id;
  final String prompt;
  final bool active;
  final List<TrainingLivePollOption> options;
  final int totalVotes;
  final String? myOptionId;

  factory TrainingLivePoll.fromJson(Map<String, dynamic> json) =>
      TrainingLivePoll(
        id: json['id'] as String? ?? '',
        prompt: json['prompt'] as String? ?? '',
        active: json['active'] as bool? ?? false,
        options: _maps(json['options'])
            .map(TrainingLivePollOption.fromJson)
            .toList(growable: false),
        totalVotes: _int(json['totalVotes']),
        myOptionId: json['myOptionId'] as String?,
      );
}

class TrainingLivePollOption {
  const TrainingLivePollOption({
    required this.id,
    required this.label,
    required this.votes,
    required this.percent,
  });

  final String id;
  final String label;
  final int votes;
  final int percent;

  factory TrainingLivePollOption.fromJson(Map<String, dynamic> json) =>
      TrainingLivePollOption(
        id: json['id'] as String? ?? '',
        label: json['label'] as String? ?? '',
        votes: _int(json['votes']),
        percent: _int(json['percent']).clamp(0, 100),
      );
}

class TrainingLessonAttachment {
  const TrainingLessonAttachment({
    required this.id,
    required this.title,
    required this.fileName,
    required this.contentType,
    required this.mediaUrl,
    required this.sizeBytes,
    required this.kind,
  });

  final String id;
  final String title;
  final String fileName;
  final String contentType;
  final String mediaUrl;
  final int sizeBytes;
  final String kind;

  String get displayName => title.isNotEmpty ? title : fileName;

  factory TrainingLessonAttachment.fromJson(Map<String, dynamic> json) =>
      TrainingLessonAttachment(
        id: json['id'] as String? ?? '',
        title: json['title'] as String? ?? '',
        fileName: json['fileName'] as String? ?? 'Материал',
        contentType: json['contentType'] as String? ?? '',
        mediaUrl: json['mediaUrl'] as String? ?? '',
        sizeBytes: _int(json['sizeBytes']),
        kind: json['kind'] as String? ?? 'document',
      );
}

class TrainingEvent {
  const TrainingEvent({
    required this.id,
    required this.title,
    required this.startsAt,
    required this.endsAt,
    required this.timezone,
    required this.city,
    required this.address,
    required this.mapUrl,
    required this.capacity,
    required this.occupied,
    required this.status,
  });

  final String id;
  final String title;
  final DateTime? startsAt;
  final DateTime? endsAt;
  final String timezone;
  final String city;
  final String address;
  final String? mapUrl;
  final int capacity;
  final int occupied;
  final String status;

  int get availableSeats => (capacity - occupied).clamp(0, capacity);

  factory TrainingEvent.fromJson(Map<String, dynamic> json) => TrainingEvent(
        id: json['id'] as String? ?? '',
        title: json['title'] as String? ?? 'Очное обучение',
        startsAt: _date(json['startsAt']),
        endsAt: _date(json['endsAt']),
        timezone: json['timezone'] as String? ?? 'Asia/Almaty',
        city: json['city'] as String? ?? '',
        address: json['address'] as String? ?? '',
        mapUrl: json['mapUrl'] as String?,
        capacity: _int(json['capacity']),
        occupied: _int(json['occupied']),
        status: json['status'] as String? ?? 'scheduled',
      );
}

class TrainingCertificate {
  const TrainingCertificate({
    required this.id,
    required this.number,
    required this.assignmentId,
    required this.programName,
    required this.format,
    required this.issuedAt,
    required this.expiresAt,
    required this.score,
    required this.status,
    required this.pdfUrl,
  });

  final String id;
  final String number;
  final String assignmentId;
  final String programName;
  final TrainingFormat format;
  final DateTime? issuedAt;
  final DateTime? expiresAt;
  final int? score;
  final String status;
  final String? pdfUrl;

  factory TrainingCertificate.fromJson(Map<String, dynamic> json) {
    final scoreValue = json['score'];
    return TrainingCertificate(
      id: json['id'] as String? ?? '',
      number: json['number'] as String? ?? '',
      assignmentId: json['assignmentId'] as String? ?? '',
      programName: json['programName'] as String? ?? '',
      format: _trainingFormat(json['format']),
      issuedAt: _date(json['issuedAt']),
      expiresAt: _date(json['expiresAt']),
      score: scoreValue is num ? scoreValue.toInt() : null,
      status: json['status'] as String? ?? 'valid',
      pdfUrl: json['pdfUrl'] as String?,
    );
  }
}

class TrainingReward {
  const TrainingReward({
    required this.id,
    required this.amount,
    required this.reason,
    required this.status,
    required this.issuedAt,
  });

  final String id;
  final int amount;
  final String reason;
  final String status;
  final DateTime? issuedAt;

  factory TrainingReward.fromJson(Map<String, dynamic> json) => TrainingReward(
        id: json['id'] as String? ?? '',
        amount: _int(json['amount']),
        reason: json['reason'] as String? ?? '',
        status: json['status'] as String? ?? 'issued',
        issuedAt: _date(json['issuedAt']),
      );
}

class TrainingNotification {
  const TrainingNotification({
    required this.id,
    required this.eventType,
    required this.title,
    required this.message,
    required this.assignmentId,
    required this.eventId,
    required this.read,
    required this.scheduledAt,
    required this.readAt,
  });

  final String id;
  final String eventType;
  final String title;
  final String message;
  final String? assignmentId;
  final String? eventId;
  final bool read;
  final DateTime? scheduledAt;
  final DateTime? readAt;

  factory TrainingNotification.fromJson(Map<String, dynamic> json) =>
      TrainingNotification(
        id: json['id'] as String? ?? '',
        eventType: json['eventType'] as String? ?? '',
        title: json['title'] as String? ?? 'Обучение',
        message: json['message'] as String? ?? '',
        assignmentId: json['assignmentId'] as String?,
        eventId: json['eventId'] as String?,
        read: json['read'] as bool? ?? false,
        scheduledAt: _date(json['scheduledAt']),
        readAt: _date(json['readAt']),
      );
}

extension _NullableMapTransform on Map<String, dynamic> {
  T let<T>(T Function(Map<String, dynamic>) transform) => transform(this);
}
