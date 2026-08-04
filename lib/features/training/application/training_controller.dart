import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_client.dart';
import '../../profile/application/profile_controller.dart';
import '../data/api_training_repository.dart';
import '../data/training_models.dart';

final trainingRepositoryProvider = Provider<ApiTrainingRepository>(
  (ref) => ApiTrainingRepository(ref.read(apiClientProvider)),
);

final trainingOverviewProvider = FutureProvider.autoDispose<TrainingOverview>(
  (ref) => ref.watch(trainingRepositoryProvider).loadOverview(),
);

final trainingAssignmentProvider =
    FutureProvider.autoDispose.family<TrainingAssignment, String>(
  (ref, assignmentId) =>
      ref.watch(trainingRepositoryProvider).loadAssignment(assignmentId),
);

final availableTrainingEventsProvider =
    FutureProvider.autoDispose.family<List<TrainingEvent>, String>(
  (ref, assignmentId) =>
      ref.watch(trainingRepositoryProvider).loadAvailableEvents(assignmentId),
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
