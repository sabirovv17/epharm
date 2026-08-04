import '../../../core/network/api_client.dart';
import 'training_models.dart';

class ApiTrainingRepository {
  ApiTrainingRepository(this._client);

  final ApiClient _client;

  Future<TrainingOverview> loadOverview() async {
    final json = await _client.getJson('/api/mobile/training');
    return TrainingOverview.fromJson(json);
  }

  Future<TrainingAssignment> loadAssignment(String assignmentId) async {
    final json = await _client.getJson(
      '/api/mobile/training/assignments/$assignmentId',
    );
    return TrainingAssignment.fromJson(json);
  }

  Future<List<TrainingEvent>> loadAvailableEvents(String assignmentId) async {
    final json = await _client.getJsonList(
      '/api/mobile/training/assignments/$assignmentId/events',
    );
    return json
        .whereType<Map<String, dynamic>>()
        .map(TrainingEvent.fromJson)
        .toList(growable: false);
  }

  Future<TrainingAssignment> startAssignment(String assignmentId) async {
    final json = await _client.postJson(
      '/api/mobile/training/assignments/$assignmentId/start',
      const <String, dynamic>{},
    );
    return TrainingAssignment.fromJson(json);
  }

  Future<TrainingAssignment> updateStageProgress({
    required String assignmentId,
    required String stageId,
    required int progressPct,
  }) async {
    final json = await _client.patchJson(
      '/api/mobile/training/assignments/$assignmentId/stages/$stageId',
      <String, dynamic>{'progressPct': progressPct},
    );
    return TrainingAssignment.fromJson(json);
  }

  Future<TrainingAssignment> checkIn(String qrToken) async {
    final json = await _client.postJson(
      '/api/mobile/training/events/check-in/$qrToken',
      const <String, dynamic>{},
    );
    return TrainingAssignment.fromJson(json);
  }

  Future<TrainingAssignment> selectEvent({
    required String assignmentId,
    required String eventId,
  }) async {
    final json = await _client.postJson(
      '/api/mobile/training/assignments/$assignmentId/events/$eventId',
      const <String, dynamic>{},
    );
    return TrainingAssignment.fromJson(json);
  }

  Future<TrainingNotification> markNotificationRead(
    String notificationId,
  ) async {
    final json = await _client.patchJson(
      '/api/mobile/training/notifications/$notificationId/read',
      const <String, dynamic>{},
    );
    return TrainingNotification.fromJson(json);
  }
}
