package kz.epharm.training.domain

enum class TrainingFormat { online, hybrid, offline }

enum class TrainingProgramStatus { draft, review, scheduled, published, paused, completed, archived }

enum class TrainingStageType { material, online_course, test, ai_exam, offline_event, manual_review }

enum class OfflineEventStatus { draft, registration, scheduled, in_progress, completed, cancelled, archived }

enum class TrainingAssignmentStatus {
    scheduled,
    not_started,
    in_progress,
    waiting_online,
    waiting_test,
    waiting_exam,
    waiting_event_selection,
    waiting_offline,
    waiting_attendance,
    waiting_review,
    retake_required,
    completed,
    overdue,
    paused,
    cancelled,
}

enum class TrainingPriority { low, normal, high, critical }

enum class TrainingStageStatus { locked, available, in_progress, waiting_review, completed, failed, skipped }

enum class EventParticipantStatus { registered, confirmed, attended, late, no_show, excused, cancelled, waitlisted }

enum class AttendanceMethod { manual, qr }

enum class CertificateStatus { valid, expired, revoked, replaced }

enum class TrainingRewardStatus { issued, reversed }

enum class DuplicateAssignmentPolicy { skip, update_deadline, repeat }
