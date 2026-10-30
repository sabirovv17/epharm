using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using CustomerDisplay.Models.Posm;
using Microsoft.Data.Sqlite;

namespace CustomerDisplay.Services
{
    /// <summary>Запись очереди исходящих (sale / outcome).</summary>
    public sealed class OutboxItem
    {
        public string Id { get; set; } = "";
        public string Kind { get; set; } = "";       // "sale" | "fiscal-sale" | "outcome" | "shown"
        public string Payload { get; set; } = "";     // JSON
        public int Attempts { get; set; }
    }

    /// <summary>
    /// Локальная SQLite-очередь гарантированной доставки на кассе (касса с нестабильным интернетом).
    /// Любое исходящее сначала пишется сюда, фоновый OutboxFlusher досылает с backoff. Идемпотентность —
    /// каждое исходящее имеет устойчивый id; для sale он детерминирован по аптеке и DOCS.ID.
    /// Файл: C:\Epharm\outbox.db.
    /// </summary>
    public sealed class OfflineOutbox
    {
        private readonly string _connString;
        private readonly object _lock = new();

        public OfflineOutbox(string dbPath)
        {
            var dir = Path.GetDirectoryName(dbPath);
            if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
            _connString = new SqliteConnectionStringBuilder { DataSource = dbPath }.ToString();
            Init();
        }

        private void Init()
        {
            using var conn = Open();
            // WAL + synchronous=NORMAL — устойчивость к внезапному обесточиванию/крашу кассы
            // (форс-мажор): незавершённая транзакция откатывается, БД не бьётся, очередь переживает
            // power-loss. WAL также не блокирует чтение во время записи.
            using (var pragma = conn.CreateCommand())
            {
                pragma.CommandText = "PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;";
                pragma.ExecuteNonQuery();
            }
            using var cmd = conn.CreateCommand();
            cmd.CommandText = @"
                CREATE TABLE IF NOT EXISTS outbox (
                    id            TEXT PRIMARY KEY,
                    kind          TEXT NOT NULL,
                    payload       TEXT NOT NULL,
                    created_at    TEXT NOT NULL,
                    attempts      INTEGER NOT NULL DEFAULT 0,
                    next_retry_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS outbox_legacy_sale_evidence (
                    id TEXT PRIMARY KEY,
                    kind TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    attempts INTEGER NOT NULL,
                    next_retry_at TEXT NOT NULL,
                    archived_at TEXT NOT NULL
                );";
            cmd.ExecuteNonQuery();
        }

        public void Enqueue(string id, string kind, string payloadJson)
        {
            lock (_lock)
            {
                using var conn = Open();
                using var transaction = conn.BeginTransaction();
                using var cmd = conn.CreateCommand();
                cmd.Transaction = transaction;
                cmd.CommandText = @"INSERT OR IGNORE INTO outbox (id, kind, payload, created_at, attempts, next_retry_at)
                                    VALUES ($id, $kind, $payload, $now, 0, $now);";
                var now = DateTimeOffset.UtcNow.ToString("o");
                cmd.Parameters.AddWithValue("$id", id);
                cmd.Parameters.AddWithValue("$kind", kind);
                cmd.Parameters.AddWithValue("$payload", payloadJson);
                cmd.Parameters.AddWithValue("$now", now);
                if (cmd.ExecuteNonQuery() == 0 && kind == "sale")
                    UpgradeConfirmedLegacySale(conn, transaction, id, payloadJson, now);
                transaction.Commit();
            }
        }

        private static void UpgradeConfirmedLegacySale(
            SqliteConnection conn, SqliteTransaction transaction, string id, string payloadJson, string now)
        {
            SaleReport? confirmed;
            try { confirmed = JsonSerializer.Deserialize<SaleReport>(payloadJson, EpharmJson.Options); }
            catch (JsonException) { return; } // An invalid duplicate can never replace preserved evidence.
            if (confirmed == null || !ReceiptFinalizationRules.IsConfirmedPrint(confirmed.CaptureSource)) return;

            using var read = conn.CreateCommand();
            read.Transaction = transaction;
            read.CommandText = "SELECT payload FROM outbox WHERE id=$id AND kind='sale';";
            read.Parameters.AddWithValue("$id", id);
            if (read.ExecuteScalar() is not string original) return;
            SaleReport? preserved;
            try { preserved = JsonSerializer.Deserialize<SaleReport>(original, EpharmJson.Options); }
            catch (JsonException) { return; }
            if (preserved == null || !ReceiptFinalizationRules.IsKnownUnconfirmedLegacySource(preserved.CaptureSource)) return;
            if (!string.Equals(id, confirmed.SaleId, StringComparison.Ordinal) ||
                !ReceiptFinalizationRules.IsSamePhysicalSaleConfirmedAfterLegacyFailure(preserved, confirmed))
                throw new InvalidDataException("Confirmed sale does not match preserved physical receipt identity.");

            // The old queue row remains recoverable in full. Any conflicting archive aborts
            // this transaction; neither evidence nor the canonical row is silently replaced.
            using var archive = conn.CreateCommand();
            archive.Transaction = transaction;
            archive.CommandText = @"INSERT INTO outbox_legacy_sale_evidence
                (id,kind,payload,created_at,attempts,next_retry_at,archived_at)
                SELECT id,kind,payload,created_at,attempts,next_retry_at,$now FROM outbox WHERE id=$id;";
            archive.Parameters.AddWithValue("$id", id);
            archive.Parameters.AddWithValue("$now", now);
            archive.ExecuteNonQuery();
            using var update = conn.CreateCommand();
            update.Transaction = transaction;
            update.CommandText = @"UPDATE outbox SET payload=$payload,attempts=0,next_retry_at=$now
                                   WHERE id=$id AND kind='sale';";
            update.Parameters.AddWithValue("$id", id);
            update.Parameters.AddWithValue("$payload", payloadJson);
            update.Parameters.AddWithValue("$now", now);
            update.ExecuteNonQuery();
        }

        /// <summary>
        /// Готовые записи, старые первыми. Известные неподтверждённые legacy sale сохраняются,
        /// но исключаются ДО LIMIT: они не должны отправляться или задерживать остальную очередь.
        /// </summary>
        public IReadOnlyList<OutboxItem> DequeueReady(int limit = 20)
        {
            lock (_lock)
            {
                var result = new List<OutboxItem>();
                using var conn = Open();
                using var cmd = conn.CreateCommand();
                cmd.CommandText = @"SELECT id, kind, payload, attempts FROM outbox
                    WHERE next_retry_at <= $now
                      AND NOT (kind = 'sale' AND EXISTS (
                        SELECT 1 FROM json_each(CASE WHEN json_valid(payload) THEN payload ELSE '{}' END)
                        WHERE key COLLATE NOCASE = 'captureSource' AND type = 'text'
                          AND value IN ($documentSwitch, $disappearance)
                      ))
                    ORDER BY created_at LIMIT $limit;";
                cmd.Parameters.AddWithValue("$now", DateTimeOffset.UtcNow.ToString("o"));
                cmd.Parameters.AddWithValue("$limit", limit);
                cmd.Parameters.AddWithValue("$documentSwitch", ReceiptFinalizationRules.UnconfirmedDocumentSwitchSource);
                cmd.Parameters.AddWithValue("$disappearance", ReceiptFinalizationRules.UnconfirmedDisappearanceSource);
                using var rd = cmd.ExecuteReader();
                while (rd.Read())
                {
                    result.Add(new OutboxItem
                    {
                        Id = rd.GetString(0),
                        Kind = rd.GetString(1),
                        Payload = rd.GetString(2),
                        Attempts = rd.GetInt32(3),
                    });
                }
                return result;
            }
        }

        public void Remove(string id)
        {
            lock (_lock)
            {
                using var conn = Open();
                using var cmd = conn.CreateCommand();
                cmd.CommandText = "DELETE FROM outbox WHERE id = $id;";
                cmd.Parameters.AddWithValue("$id", id);
                cmd.ExecuteNonQuery();
            }
        }

        /// <summary>
        /// Сбросить расписание: все записи становятся «к отправке сейчас» (next_retry_at = now).
        /// Зовётся при ВОЗВРАТЕ сети — причина backoff (нет интернета) исчезла, поэтому копившиеся
        /// в офлайне sale/shown досылаем немедленно, не дожидаясь окна экспоненциального backoff
        /// (которое могло вырасти до 30 минут). attempts не трогаем — если снова не доедет, backoff
        /// продолжится с того же места.
        /// </summary>
        public void MarkAllDue()
        {
            lock (_lock)
            {
                using var conn = Open();
                using var cmd = conn.CreateCommand();
                cmd.CommandText = "UPDATE outbox SET next_retry_at = $now;";
                cmd.Parameters.AddWithValue("$now", DateTimeOffset.UtcNow.ToString("o"));
                cmd.ExecuteNonQuery();
            }
        }

        /// <summary>Экспоненциальный backoff: 2^attempts минут, максимум 30 минут.</summary>
        public void Reschedule(string id, int attempts)
        {
            lock (_lock)
            {
                var delayMin = Math.Min(30, Math.Pow(2, Math.Min(attempts, 5)));
                var next = DateTimeOffset.UtcNow.AddMinutes(delayMin).ToString("o");
                using var conn = Open();
                using var cmd = conn.CreateCommand();
                cmd.CommandText = "UPDATE outbox SET attempts = attempts + 1, next_retry_at = $next WHERE id = $id;";
                cmd.Parameters.AddWithValue("$next", next);
                cmd.Parameters.AddWithValue("$id", id);
                cmd.ExecuteNonQuery();
            }
        }

        private SqliteConnection Open()
        {
            var conn = new SqliteConnection(_connString);
            conn.Open();
            return conn;
        }
    }
}
