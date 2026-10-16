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

  Future<TrainingAssignment> updateLessonProgress({
    required String assignmentId,
    required String stageId,
    required String lessonId,
    required int progressPct,
    int positionSeconds = 0,
  }) async {
    final json = await _client.patchJson(
      '/api/mobile/training/assignments/$assignmentId/stages/$stageId/lessons/$lessonId',
      <String, dynamic>{
        'progressPct': progressPct,
        'positionSeconds': positionSeconds,
      },
    );
    return TrainingAssignment.fromJson(json);
  }

  Future<TrainingLiveRoom> loadLiveRoom({
    required String assignmentId,
    required String stageId,
    required String lessonId,
  }) async {
    final json = await _client.getJson(
      '/api/mobile/training/assignments/$assignmentId/stages/$stageId/lessons/$lessonId/live',
    );
    return TrainingLiveRoom.fromJson(json);
  }

  Future<TrainingLiveRoom> postLiveMessage({
    required String assignmentId,
    required String stageId,
    required String lessonId,
    required String body,
    required String kind,
  }) async {
    final json = await _client.postJson(
      '/api/mobile/training/assignments/$assignmentId/stages/$stageId/lessons/$lessonId/live/messages',
      <String, dynamic>{'body': body, 'kind': kind},
    );
    return TrainingLiveRoom.fromJson(json);
  }

  Future<TrainingLiveRoom> voteInLivePoll({
    required String assignmentId,
    required String stageId,
    required String lessonId,
    required String pollId,
    required String optionId,
  }) async {
    final json = await _client.postJson(
      '/api/mobile/training/assignments/$assignmentId/stages/$stageId/lessons/$lessonId/live/polls/$pollId/vote',
      <String, dynamic>{'optionId': optionId},
    );
    return TrainingLiveRoom.fromJson(json);
  }

  Future<TrainingLiveRoom> reactToLiveLesson({
    required String assignmentId,
    required String stageId,
    required String lessonId,
    required String reaction,
  }) async {
    final json = await _client.postJson(
      '/api/mobile/training/assignments/$assignmentId/stages/$stageId/lessons/$lessonId/live/reactions',
      <String, dynamic>{'reaction': reaction},
    );
    return TrainingLiveRoom.fromJson(json);
  }

  Future<TrainingAssignment> checkIn(String credential) async {
    final normalized = credential.trim();
    final isShortCode = RegExp(r'^\d{6}$').hasMatch(normalized);
    final json = await _client.postJson(
      isShortCode
          ? '/api/mobile/training/events/check-in-code/$normalized'
          : '/api/mobile/training/events/check-in/$normalized',
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
