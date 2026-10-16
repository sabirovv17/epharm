import 'training_models.dart';

TrainingAssignment demoTrainingAssignment() {
  final now = DateTime.now();
  return TrainingAssignment.fromJson(<String, dynamic>{
    'id': 'demo-required-course',
    'programId': 'demo-program',
    'programVersion': 1,
    'programName': 'Безопасная рекомендация препаратов',
    'programShortDescription':
        'Обязательный курс: материалы, проверка знаний и очное подтверждение.',
    'pharmacyName': 'Аптека ePharm',
    'city': 'Алматы',
    'format': 'hybrid',
    'status': 'in_progress',
    'priority': 'high',
    'required': true,
    'startsAt': now.subtract(const Duration(days: 1)).toIso8601String(),
    'dueAt': now.add(const Duration(days: 3)).toIso8601String(),
    'progressPct': 35,
    'startedAt': now.subtract(const Duration(hours: 2)).toIso8601String(),
    'stages': <Map<String, dynamic>>[
      <String, dynamic>{
        'id': 'demo-stage-course',
        'key': 'course',
        'type': 'online_course',
        'title': 'Онлайн-курс',
        'order': 1,
        'required': true,
        'status': 'in_progress',
        'progressPct': 50,
        'course': <String, dynamic>{
          'id': 'demo-course',
          'title': 'Безопасная рекомендация препаратов',
          'description': 'Короткие уроки с примерами для ежедневной работы.',
          'durationMin': 25,
          'lessons': <Map<String, dynamic>>[
            <String, dynamic>{
              'id': 'demo-lesson-1',
              'title': 'Проверка запроса покупателя',
              'description': 'Как уточнять симптомы и ограничения.',
              'content': 'Алгоритм вопросов перед рекомендацией препарата.',
              'kind': 'text',
              'required': true,
              'progressPct': 100,
              'durationMin': 8,
              'order': 1,
            },
            <String, dynamic>{
              'id': 'demo-lesson-2',
              'title': 'Безопасная консультация',
              'description': 'Видео и практические примеры.',
              'content': 'Разбор типовых ситуаций в аптеке.',
              'kind': 'video',
              'required': true,
              'minimumWatchPct': 80,
              'progressPct': 20,
              'durationMin': 12,
              'order': 2,
            },
          ],
        },
      },
      <String, dynamic>{
        'id': 'demo-stage-test',
        'key': 'test',
        'type': 'test',
        'title': 'Итоговый тест',
        'order': 2,
        'required': true,
        'status': 'available',
        'progressPct': 0,
        'passingScore': 80,
        'maxAttempts': 3,
      },
      <String, dynamic>{
        'id': 'demo-stage-event',
        'key': 'event',
        'type': 'offline_event',
        'title': 'Очное подтверждение',
        'order': 3,
        'required': true,
        'status': 'locked',
        'progressPct': 0,
      },
    ],
  });
}

List<TrainingEvent> demoTrainingEvents() => <TrainingEvent>[
      TrainingEvent.fromJson(<String, dynamic>{
        'id': 'demo-event',
        'title': 'Практикум ePharm',
        'startsAt': DateTime.now()
            .add(const Duration(days: 4))
            .toIso8601String(),
        'endsAt': DateTime.now()
            .add(const Duration(days: 4, hours: 2))
            .toIso8601String(),
        'timezone': 'Asia/Almaty',
        'city': 'Алматы',
        'address': 'Учебный центр ePharm',
        'capacity': 30,
        'occupied': 18,
        'status': 'scheduled',
      }),
    ];

TrainingOverview demoTrainingOverview() {
  final assignment = demoTrainingAssignment();
  return TrainingOverview(
    total: 2,
    inProgress: 1,
    completed: 1,
    overdue: 0,
    upcomingEvents: demoTrainingEvents(),
    assignments: <TrainingAssignment>[assignment],
    certificates: const <TrainingCertificate>[],
    notifications: <TrainingNotification>[
      TrainingNotification(
        id: 'demo-notification',
        eventType: 'training_deadline',
        title: 'Обязательное обучение',
        message: 'До завершения курса осталось 3 дня',
        assignmentId: assignment.id,
        eventId: null,
        read: false,
        scheduledAt: DateTime.now(),
        readAt: null,
      ),
    ],
    defaultFormat: TrainingFormat.hybrid,
  );
}
