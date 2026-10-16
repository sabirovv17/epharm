import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/config/api_config.dart';
import '../../../core/network/api_client.dart';
import '../../profile/application/profile_controller.dart';
import '../data/api_training_repository.dart';
import '../data/demo_training_data.dart';
import '../data/training_models.dart';

final trainingRepositoryProvider = Provider<ApiTrainingRepository>(
  (ref) => ApiTrainingRepository(ref.read(apiClientProvider)),
);

final trainingOverviewProvider = FutureProvider.autoDispose<TrainingOverview>(
  (ref) => ApiConfig.useApi
      ? ref.watch(trainingRepositoryProvider).loadOverview()
      : Future<TrainingOverview>.value(demoTrainingOverview()),
);

final trainingAssignmentProvider =
    FutureProvider.autoDispose.family<TrainingAssignment, String>(
  (ref, assignmentId) =>
      ApiConfig.useApi
          ? ref.watch(trainingRepositoryProvider).loadAssignment(assignmentId)
          : Future<TrainingAssignment>.value(demoTrainingAssignment()),
);

final availableTrainingEventsProvider =
    FutureProvider.autoDispose.family<List<TrainingEvent>, String>(
  (ref, assignmentId) =>
      ApiConfig.useApi
          ? ref.watch(trainingRepositoryProvider).loadAvailableEvents(assignmentId)
          : Future<List<TrainingEvent>>.value(demoTrainingEvents()),
);

class TrainingActions {
  TrainingActions(this._ref);

  final Ref _ref;

  ApiTrainingRepository get _repository =>
      _ref.read(trainingRepositoryProvider);

  Future<TrainingAssignment> start(String assignmentId) async {
    final assignment = await _repository.startAssignment(assignmentId);
    _refresh(assignment);
    return assignment;
  }

  Future<TrainingAssignment> completeStage({
    required String assignmentId,
    required String stageId,
  }) async {
    final assignment = await _repository.updateStageProgress(
      assignmentId: assignmentId,
      stageId: stageId,
      progressPct: 100,
    );
    _refresh(assignment);
    return assignment;
  }

  Future<TrainingAssignment> startLesson({
    required String assignmentId,
    required String stageId,
    required String lessonId,
    required int currentProgressPct,
    required int currentPositionSeconds,
  }) async {
    final assignment = await _repository.updateLessonProgress(
      assignmentId: assignmentId,
      stageId: stageId,
      lessonId: lessonId,
      progressPct: currentProgressPct,
      positionSeconds: currentPositionSeconds,
    );
    _refresh(assignment);
    return assignment;
  }

  Future<TrainingAssignment> completeLesson({
    required String assignmentId,
    required String stageId,
    required String lessonId,
    int positionSeconds = 0,
  }) async {
    final assignment = await _repository.updateLessonProgress(
      assignmentId: assignmentId,
      stageId: stageId,
      lessonId: lessonId,
      progressPct: 100,
      positionSeconds: positionSeconds,
    );
    _refresh(assignment);
    return assignment;
  }

  Future<TrainingAssignment> checkIn(String qrToken) async {
    final assignment = await _repository.checkIn(qrToken);
    _refresh(assignment);
    return assignment;
  }

  Future<TrainingAssignment> selectEvent({
    required String assignmentId,
    required String eventId,
  }) async {
    final assignment = await _repository.selectEvent(
      assignmentId: assignmentId,
      eventId: eventId,
    );
    _ref.invalidate(availableTrainingEventsProvider(assignmentId));
    _refresh(assignment);
    return assignment;
  }

  Future<void> markNotificationRead(String notificationId) async {
    await _repository.markNotificationRead(notificationId);
    _ref.invalidate(trainingOverviewProvider);
  }

  void _refresh(TrainingAssignment assignment) {
    _ref.invalidate(trainingOverviewProvider);
    _ref.invalidate(trainingAssignmentProvider(assignment.id));
    if (assignment.isCompleted && assignment.reward != null) {
      _ref.read(profileActionsProvider).refreshMe();
    }
  }
}

final trainingActionsProvider = Provider<TrainingActions>(TrainingActions.new);
