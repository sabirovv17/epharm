using System;
using System.Collections.Generic;
using System.IO;
using Microsoft.Data.Sqlite;

namespace CustomerDisplay.Services
{
    /// <summary>Запись очереди исходящих (sale / outcome).</summary>
    public sealed class OutboxItem
    {
        public string Id { get; set; } = "";
        public string Kind { get; set; } = "";       // "sale" | "outcome"
        public string Payload { get; set; } = "";     // JSON
        public int Attempts { get; set; }
    }

    /// <summary>
    /// Локальная SQLite-очередь гарантированной доставки на кассе (касса с нестабильным интернетом).
    /// Любое исходящее сначала пишется сюда, фоновый OutboxFlusher досылает с backoff. Идемпотентность —
    /// каждое исходящее несёт client-GUID, backend апсертит по нему. Файл: C:\Epharm\outbox.db.
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
                );";
            cmd.ExecuteNonQuery();
        }

        public void Enqueue(string id, string kind, string payloadJson)
        {
            lock (_lock)
            {
                using var conn = Open();
                using var cmd = conn.CreateCommand();
                cmd.CommandText = @"INSERT OR IGNORE INTO outbox (id, kind, payload, created_at, attempts, next_retry_at)
                                    VALUES ($id, $kind, $payload, $now, 0, $now);";
                var now = DateTimeOffset.UtcNow.ToString("o");
                cmd.Parameters.AddWithValue("$id", id);
                cmd.Parameters.AddWithValue("$kind", kind);
                cmd.Parameters.AddWithValue("$payload", payloadJson);
                cmd.Parameters.AddWithValue("$now", now);
                cmd.ExecuteNonQuery();
            }
        }

        /// <summary>Готовые к отправке записи (next_retry_at в прошлом), старые первыми.</summary>
        public IReadOnlyList<OutboxItem> DequeueReady(int limit = 20)
        {
            lock (_lock)
            {
                var result = new List<OutboxItem>();
                using var conn = Open();
                using var cmd = conn.CreateCommand();
                cmd.CommandText = @"SELECT id, kind, payload, attempts FROM outbox
                                    WHERE next_retry_at <= $now ORDER BY created_at LIMIT $limit;";
                cmd.Parameters.AddWithValue("$now", DateTimeOffset.UtcNow.ToString("o"));
                cmd.Parameters.AddWithValue("$limit", limit);
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
