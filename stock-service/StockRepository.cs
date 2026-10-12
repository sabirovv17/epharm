using System.Data;
using System.Globalization;
using System.Security.Cryptography;
using System.Text.Json;
using Epharm.StockService.Source;
using Npgsql;
using NpgsqlTypes;

namespace Epharm.StockService;

public sealed record PharmacyRow(
    long Id, string Name, string City, string Address, string PharmacyNumber,
    int? StockCount, DateTimeOffset? LastUpdatedAt,
    DateTimeOffset? LastErrorAt, string Status,
    string SourceKind, DateTimeOffset? SourceObservedAt, DateTimeOffset? IngestedAt);

public sealed record StockRow(
    string SourceId, long PartId, string Name, string? ManufacturerBarcode, string? Barcode,
    decimal Quantity, decimal? Price, string? ExpiryDate, string? Series, string? Unit);

public sealed record CollectionStatus(
    int FreshnessTargetSeconds, int SweepIntervalSeconds,
    int PharmacyCount, int FreshCount, int StaleCount,
    int PendingCount, int ErrorCount, long? RunId, string? RunStatus,
    DateTimeOffset? RunStartedAt, DateTimeOffset? RunCompletedAt,
    DateTimeOffset? LastSuccessfulRunAt, int RunTotal, int RunSucceeded,
    int RunFailed, bool CollectionEnabled, int LegacyCount, int CashierCount);

public sealed record StockPage(
    PharmacyRow Pharmacy, IReadOnlyList<StockRow> Items, int Total,
    int Limit, int Offset, DateTimeOffset? AsOf)
{
    public string? SnapshotId => AsOf?.ToString("O");
    public string SourceKind => Pharmacy.SourceKind;
    public DateTimeOffset? SourceObservedAt => Pharmacy.SourceObservedAt;
    public DateTimeOffset? IngestedAt => Pharmacy.IngestedAt;
    public string QuantityBasis => "physical_warehouse";
    public bool SellableVerified => false;
}

/// <summary>
/// PostgreSQL cache. A pharmacy's stock rows and freshness metadata change in one
/// transaction; failed or older imports leave the previous complete snapshot intact.
/// </summary>
public sealed class StockRepository
{
    private readonly StockOptions _options;
    private readonly string _connectionString;

    public StockRepository(StockOptions options)
    {
        _options = options;
        if (string.IsNullOrWhiteSpace(options.PostgresConnectionString))
            throw new ArgumentException("PostgreSQL connection string is required", nameof(options));
        var builder = new NpgsqlConnectionStringBuilder(options.PostgresConnectionString)
        {
            Pooling = true,
            ApplicationName = "epharm-stock-service",
            Timeout = 5,
            CommandTimeout = 20,
        };
        builder.MaxPoolSize = Math.Min(builder.MaxPoolSize, 12);
        _connectionString = builder.ConnectionString;
    }

    private NpgsqlConnection Open()
    {
        var connection = new NpgsqlConnection(_connectionString);
        connection.Open();
        return connection;
    }

    /// <summary>Run only with the schema-owner role, before the application starts.</summary>
    public static void BootstrapSchema(string ownerConnectionString)
    {
        using var connection = new NpgsqlConnection(ownerConnectionString);
        connection.Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            CREATE TABLE IF NOT EXISTS pharmacies (
                id bigint PRIMARY KEY,
                name text NOT NULL,
                city text NOT NULL,
                address text NOT NULL,
                pharmacy_number text NOT NULL,
                is_active boolean NOT NULL DEFAULT true,
                stock_count integer,
                last_updated_at timestamptz,
                last_error_at timestamptz,
                last_error text,
                snapshot_digest bytea,
                source_kind text NOT NULL DEFAULT 'central_legacy'
                    CHECK (source_kind IN ('central_legacy', 'cashier_local')),
                source_observed_at timestamptz,
                ingested_at timestamptz
            );
            CREATE INDEX IF NOT EXISTS idx_pharmacies_active_city_name
                ON pharmacies(city, name, id) WHERE is_active;
            CREATE TABLE IF NOT EXISTS collector_credentials (
                profile_id bigint PRIMARY KEY REFERENCES pharmacies(id),
                token_hash bytea NOT NULL CHECK (octet_length(token_hash) = 32),
                hq_pharmacy_id text NOT NULL UNIQUE,
                enabled boolean NOT NULL DEFAULT false,
                rotated_at timestamptz NOT NULL
            );
            CREATE TABLE IF NOT EXISTS stock_rows (
                profile_id bigint NOT NULL REFERENCES pharmacies(id),
                source_id text NOT NULL,
                part_id bigint NOT NULL,
                name text NOT NULL,
                manufacturer_barcode text,
                barcode text,
                quantity numeric NOT NULL,
                price numeric,
                expiry_date date,
                series text,
                unit text,
                PRIMARY KEY (profile_id, source_id)
            );
            CREATE INDEX IF NOT EXISTS idx_stock_rows_page
                ON stock_rows(profile_id, name, part_id, source_id);
            CREATE TABLE IF NOT EXISTS collection_runs (
                id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                started_at timestamptz NOT NULL,
                completed_at timestamptz,
                status text NOT NULL,
                profile_total integer NOT NULL DEFAULT 0,
                succeeded integer NOT NULL DEFAULT 0,
                failed integer NOT NULL DEFAULT 0
            );
            CREATE INDEX IF NOT EXISTS idx_collection_runs_success
                ON collection_runs(completed_at DESC) WHERE status = 'succeeded';
            CREATE TABLE IF NOT EXISTS cache_migrations (
                id integer PRIMARY KEY,
                applied_at timestamptz NOT NULL,
                source_row_count bigint NOT NULL,
                imported_row_count bigint NOT NULL,
                source_pharmacy_count integer NOT NULL,
                imported_pharmacy_count integer NOT NULL
            );
            -- Transient rows are never the served snapshot. UNLOGGED avoids WAL for
            -- full incoming images; all durable changes remain logged and atomic.
            CREATE UNLOGGED TABLE IF NOT EXISTS stock_stage (
                ingest_id uuid NOT NULL,
                profile_id bigint NOT NULL,
                source_id text NOT NULL,
                part_id bigint NOT NULL,
                name text NOT NULL,
                manufacturer_barcode text,
                barcode text,
                quantity numeric NOT NULL,
                price numeric,
                expiry_date date,
                series text,
                unit text,
                PRIMARY KEY (ingest_id, source_id)
            );
            """;
        command.ExecuteNonQuery();
        string schema;
        using (var schemaCommand = new NpgsqlCommand("SELECT current_schema()", connection))
            schema = (string?)schemaCommand.ExecuteScalar()
                ?? throw new InvalidOperationException("No PostgreSQL schema is selected");
        var quotedSchema = new NpgsqlCommandBuilder().QuoteIdentifier(schema);
        using var grants = connection.CreateCommand();
        grants.CommandText = $"""
            GRANT USAGE ON SCHEMA {quotedSchema} TO stock_app;
            GRANT SELECT, INSERT, UPDATE, DELETE ON
                {quotedSchema}.pharmacies, {quotedSchema}.stock_rows,
                {quotedSchema}.collection_runs, {quotedSchema}.stock_stage,
                {quotedSchema}.cache_migrations TO stock_app;
            GRANT SELECT ON {quotedSchema}.collector_credentials TO stock_app;
            GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA {quotedSchema} TO stock_app;
            ALTER DEFAULT PRIVILEGES IN SCHEMA {quotedSchema}
                GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO stock_app;
            """;
        grants.ExecuteNonQuery();
    }

    public void Initialize()
    {
        using var connection = Open();
        using (var schema = new NpgsqlCommand("""
            SELECT to_regclass('pharmacies'), to_regclass('stock_rows'),
                   to_regclass('collection_runs'), to_regclass('stock_stage'),
                   to_regclass('cache_migrations'), to_regclass('collector_credentials')
            """, connection))
        using (var reader = schema.ExecuteReader())
        {
            reader.Read();
            for (var i = 0; i < 6; i++)
                if (reader.IsDBNull(i))
                    throw new InvalidOperationException("PostgreSQL cache schema is missing; run stock-db-init");
        }
        using var interrupted = connection.CreateCommand();
        interrupted.CommandText = """
            UPDATE collection_runs SET status = 'interrupted', completed_at = @now
            WHERE status = 'running'
            """;
        interrupted.Parameters.AddWithValue("now", DateTimeOffset.UtcNow);
        interrupted.ExecuteNonQuery();
    }

    public bool IsReady()
    {
        try
        {
            using var connection = Open();
            using var command = new NpgsqlCommand("SELECT 1", connection)
            {
                CommandTimeout = 2,
            };
            return (int)command.ExecuteScalar()! == 1;
        }
        catch (NpgsqlException)
        {
            return false;
        }
        catch (TimeoutException)
        {
            return false;
        }
    }

    public void UpsertPharmacies(IReadOnlyList<SourcePharmacy> pharmacies)
    {
        using var connection = Open();
        using var transaction = connection.BeginTransaction();
        using (var deactivate = new NpgsqlCommand("UPDATE pharmacies SET is_active = false", connection, transaction))
            deactivate.ExecuteNonQuery();
        using var command = new NpgsqlCommand("""
            INSERT INTO pharmacies(id, name, city, address, pharmacy_number, is_active)
            VALUES (@id, @name, @city, @address, @number, true)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name, city = excluded.city,
                address = excluded.address, pharmacy_number = excluded.pharmacy_number,
                is_active = true
            """, connection, transaction);
        var id = command.Parameters.Add("id", NpgsqlDbType.Bigint);
        var name = command.Parameters.Add("name", NpgsqlDbType.Text);
        var city = command.Parameters.Add("city", NpgsqlDbType.Text);
        var address = command.Parameters.Add("address", NpgsqlDbType.Text);
        var number = command.Parameters.Add("number", NpgsqlDbType.Text);
        foreach (var pharmacy in pharmacies)
        {
            id.Value = pharmacy.ProfileId;
            name.Value = pharmacy.Name;
            city.Value = pharmacy.City;
            address.Value = pharmacy.Address;
            number.Value = pharmacy.PharmacyNumber;
            command.ExecuteNonQuery();
        }
        transaction.Commit();
    }

    public IReadOnlyList<object> ListCities()
    {
        using var connection = Open();
        using var command = new NpgsqlCommand("""
            SELECT city, COUNT(*) FROM pharmacies WHERE is_active
            GROUP BY city ORDER BY city
            """, connection);
        using var reader = command.ExecuteReader();
        var result = new List<object>();
        while (reader.Read())
            result.Add(new { city = reader.GetString(0), count = checked((int)reader.GetInt64(1)) });
        return result;
    }

    public IReadOnlyList<PharmacyRow> ListPharmacies(string? city)
    {
        using var connection = Open();
        using var command = new NpgsqlCommand("""
            SELECT id, name, city, address, pharmacy_number, stock_count,
                   last_updated_at, last_error_at, source_kind,
                   source_observed_at, ingested_at
            FROM pharmacies
            WHERE is_active AND (@city IS NULL OR city = @city)
            ORDER BY city, name, id
            """, connection);
        command.Parameters.AddWithValue("city", NpgsqlDbType.Text, (object?)city ?? DBNull.Value);
        using var reader = command.ExecuteReader();
        var rows = new List<PharmacyRow>();
        while (reader.Read()) rows.Add(ReadPharmacy(reader));
        return rows;
    }

    public PharmacyRow? GetPharmacy(long id)
    {
        using var connection = Open();
        using var command = new NpgsqlCommand("""
            SELECT id, name, city, address, pharmacy_number, stock_count,
                   last_updated_at, last_error_at, source_kind,
                   source_observed_at, ingested_at
            FROM pharmacies WHERE id = @id AND is_active
            """, connection);
        command.Parameters.AddWithValue("id", id);
        using var reader = command.ExecuteReader();
        return reader.Read() ? ReadPharmacy(reader) : null;
    }

    private PharmacyRow ReadPharmacy(NpgsqlDataReader reader)
    {
        var captured = Date(reader, 6);
        var error = Date(reader, 7);
        var sourceKind = reader.GetString(8);
        var status = captured is null ? (error is null ? "pending" : "error")
            : error > captured ? "error"
            : sourceKind != "cashier_local" ? "stale"
            : DateTimeOffset.UtcNow - captured > TimeSpan.FromSeconds(_options.RefreshSeconds) ? "stale"
            : "fresh";
        return new PharmacyRow(
            reader.GetInt64(0), reader.GetString(1), reader.GetString(2),
            reader.GetString(3), reader.GetString(4),
            reader.IsDBNull(5) ? null : reader.GetInt32(5), captured, error, status,
            sourceKind, Date(reader, 9), Date(reader, 10));
    }

    private static DateTimeOffset? Date(NpgsqlDataReader reader, int index) =>
        reader.IsDBNull(index) ? null : reader.GetFieldValue<DateTimeOffset>(index);

    public IReadOnlyList<long> ListActiveProfileIds()
    {
        using var connection = Open();
        using var command = new NpgsqlCommand("""
            SELECT id FROM pharmacies WHERE is_active
            ORDER BY last_updated_at NULLS FIRST, id
            """, connection);
        using var reader = command.ExecuteReader();
        var ids = new List<long>();
        while (reader.Read()) ids.Add(reader.GetInt64(0));
        return ids;
    }

    public long StartRun(int profileTotal)
    {
        using var connection = Open();
        using var command = new NpgsqlCommand("""
            INSERT INTO collection_runs(started_at, status, profile_total)
            VALUES (@now, 'running', @total) RETURNING id
            """, connection);
        command.Parameters.AddWithValue("now", DateTimeOffset.UtcNow);
        command.Parameters.AddWithValue("total", profileTotal);
        return (long)command.ExecuteScalar()!;
    }

    public void SetRunTotal(long runId, int total)
    {
        using var connection = Open();
        using var command = new NpgsqlCommand("UPDATE collection_runs SET profile_total = @total WHERE id = @id", connection);
        command.Parameters.AddWithValue("total", total);
        command.Parameters.AddWithValue("id", runId);
        command.ExecuteNonQuery();
    }

    public void RecordRunResult(long runId, bool succeeded)
    {
        using var connection = Open();
        using var command = new NpgsqlCommand(succeeded
            ? "UPDATE collection_runs SET succeeded = succeeded + 1 WHERE id = @id"
            : "UPDATE collection_runs SET failed = failed + 1 WHERE id = @id", connection);
        command.Parameters.AddWithValue("id", runId);
        command.ExecuteNonQuery();
    }

    public void CompleteRun(long runId, bool interrupted)
    {
        using var connection = Open();
        using var command = new NpgsqlCommand("""
            UPDATE collection_runs
            SET completed_at = @now,
                status = CASE WHEN @interrupted THEN 'interrupted'
                              WHEN profile_total > 0 AND failed = 0 AND succeeded = profile_total THEN 'succeeded'
                              WHEN succeeded = 0 THEN 'failed'
                              ELSE 'partial' END
            WHERE id = @id
            """, connection);
        command.Parameters.AddWithValue("now", DateTimeOffset.UtcNow);
        command.Parameters.AddWithValue("interrupted", interrupted);
        command.Parameters.AddWithValue("id", runId);
        command.ExecuteNonQuery();
    }

    public CollectionStatus GetCollectionStatus()
    {
        using var connection = Open();
        using var transaction = connection.BeginTransaction(IsolationLevel.RepeatableRead);
        using var counts = new NpgsqlCommand("""
            SELECT COUNT(*),
              COUNT(*) FILTER (WHERE source_kind = 'cashier_local' AND last_updated_at >= @fresh AND
                (last_error_at IS NULL OR last_error_at <= last_updated_at)),
              COUNT(*) FILTER (WHERE last_updated_at IS NOT NULL AND
                (source_kind <> 'cashier_local' OR last_updated_at < @fresh) AND
                (last_error_at IS NULL OR last_error_at <= last_updated_at)),
              COUNT(*) FILTER (WHERE last_updated_at IS NULL AND last_error_at IS NULL),
              COUNT(*) FILTER (WHERE last_error_at IS NOT NULL AND
                (last_updated_at IS NULL OR last_error_at > last_updated_at)),
              COUNT(*) FILTER (WHERE source_kind = 'central_legacy'),
              COUNT(*) FILTER (WHERE source_kind = 'cashier_local')
            FROM pharmacies WHERE is_active
            """, connection, transaction);
        counts.Parameters.AddWithValue("fresh", DateTimeOffset.UtcNow.AddSeconds(-_options.RefreshSeconds));
        using var countReader = counts.ExecuteReader();
        countReader.Read();
        var pharmacyCount = checked((int)countReader.GetInt64(0));
        var fresh = checked((int)countReader.GetInt64(1));
        var stale = checked((int)countReader.GetInt64(2));
        var pending = checked((int)countReader.GetInt64(3));
        var error = checked((int)countReader.GetInt64(4));
        var legacyCount = checked((int)countReader.GetInt64(5));
        var cashierCount = checked((int)countReader.GetInt64(6));
        countReader.Close();
        using var run = new NpgsqlCommand("""
            SELECT id, status, started_at, completed_at, profile_total, succeeded, failed,
              (SELECT MAX(completed_at) FROM collection_runs WHERE status = 'succeeded')
            FROM collection_runs ORDER BY id DESC LIMIT 1
            """, connection, transaction);
        using var runReader = run.ExecuteReader();
        if (!runReader.Read())
        {
            runReader.Close();
            transaction.Commit();
            return new CollectionStatus(_options.RefreshSeconds, _options.SweepIntervalSeconds,
                pharmacyCount, fresh, stale, pending, error, null, null, null, null, null,
                0, 0, 0, _options.CollectionEnabled, legacyCount, cashierCount);
        }
        var status = new CollectionStatus(_options.RefreshSeconds, _options.SweepIntervalSeconds,
            pharmacyCount, fresh, stale, pending, error, runReader.GetInt64(0), runReader.GetString(1),
            Date(runReader, 2), Date(runReader, 3), Date(runReader, 7), runReader.GetInt32(4),
            runReader.GetInt32(5), runReader.GetInt32(6), _options.CollectionEnabled,
            legacyCount, cashierCount);
        runReader.Close();
        transaction.Commit();
        return status;
    }

    /// <summary>
    /// Publishes an observed full image. Older observations cannot overwrite newer
    /// ones. Zero and negative quantities are absent from the served snapshot.
    /// Returns false only when the observation is older than the current snapshot.
    /// </summary>
    public bool TryReplaceSnapshot(long profileId, IReadOnlyList<SourceStock> stocks,
        DateTimeOffset observedAt, string sourceKind = "cashier_local")
    {
        if (sourceKind is not ("cashier_local" or "central_legacy"))
            throw new ArgumentOutOfRangeException(nameof(sourceKind));
        if (observedAt.Offset != TimeSpan.Zero) observedAt = observedAt.ToUniversalTime();
        observedAt = new DateTimeOffset(observedAt.Ticks - observedAt.Ticks % 10, TimeSpan.Zero);
        if (observedAt > DateTimeOffset.UtcNow.AddMinutes(5))
            throw new InvalidDataException("Stock observation time is too far in the future");
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var stock in stocks)
        {
            if (stock.ProfileId != profileId || string.IsNullOrWhiteSpace(stock.SourceId))
                throw new InvalidDataException("Stock row has invalid source identity");
            if (!seen.Add(stock.SourceId))
                throw new InvalidDataException("Stock snapshot contains a duplicate source identity");
        }
        var positive = stocks.Where(stock => stock.Quantity > 0).ToArray();
        var digest = SHA256.HashData(JsonSerializer.SerializeToUtf8Bytes(
            positive.OrderBy(stock => stock.SourceId, StringComparer.Ordinal)));
        using var connection = Open();
        using var transaction = connection.BeginTransaction();
        DateTimeOffset? existingAsOf;
        byte[]? existingDigest;
        using (var lockPharmacy = new NpgsqlCommand("""
            SELECT last_updated_at, snapshot_digest FROM pharmacies
            WHERE id = @id AND is_active FOR UPDATE
            """, connection, transaction))
        {
            lockPharmacy.Parameters.AddWithValue("id", profileId);
            using var reader = lockPharmacy.ExecuteReader();
            if (!reader.Read()) throw new InvalidDataException("Unknown pharmacy profile");
            existingAsOf = Date(reader, 0);
            existingDigest = reader.IsDBNull(1) ? null : reader.GetFieldValue<byte[]>(1);
        }
        if (existingAsOf is not null && observedAt < existingAsOf)
        {
            transaction.Commit();
            return false;
        }
        if (existingAsOf is not null && observedAt == existingAsOf &&
            existingDigest is not null && !existingDigest.AsSpan().SequenceEqual(digest))
            throw new InvalidDataException("Conflicting stock images have the same observation time");
        if (existingDigest is null || !existingDigest.AsSpan().SequenceEqual(digest))
            ApplyChangedSnapshot(connection, transaction, profileId, positive);
        using (var update = new NpgsqlCommand("""
            UPDATE pharmacies
            SET stock_count = @count, last_updated_at = @observed,
                snapshot_digest = @digest, last_error = NULL, last_error_at = NULL,
                source_kind = @source_kind,
                source_observed_at = CASE WHEN @source_kind = 'cashier_local' THEN @observed ELSE NULL END,
                ingested_at = clock_timestamp()
            WHERE id = @id
            """, connection, transaction))
        {
            update.Parameters.AddWithValue("count", positive.Length);
            update.Parameters.AddWithValue("observed", observedAt);
            update.Parameters.AddWithValue("digest", NpgsqlDbType.Bytea, digest);
            update.Parameters.AddWithValue("source_kind", sourceKind);
            update.Parameters.AddWithValue("id", profileId);
            update.ExecuteNonQuery();
        }
        transaction.Commit();
        return true;
    }

    private static void ApplyChangedSnapshot(
        NpgsqlConnection connection, NpgsqlTransaction transaction,
        long profileId, IReadOnlyList<SourceStock> stocks)
    {
        var ingestId = Guid.NewGuid();
        if (stocks.Count > 0)
        {
            using var writer = connection.BeginBinaryImport("""
                COPY stock_stage (ingest_id, profile_id, source_id, part_id, name,
                    manufacturer_barcode, barcode, quantity, price, expiry_date, series, unit)
                FROM STDIN (FORMAT BINARY)
                """);
            foreach (var stock in stocks)
            {
                writer.StartRow();
                writer.Write(ingestId, NpgsqlDbType.Uuid);
                writer.Write(profileId, NpgsqlDbType.Bigint);
                writer.Write(stock.SourceId, NpgsqlDbType.Text);
                writer.Write(stock.PartId, NpgsqlDbType.Bigint);
                writer.Write(stock.Name, NpgsqlDbType.Text);
                WriteNullable(writer, stock.ManufacturerBarcode, NpgsqlDbType.Text);
                WriteNullable(writer, stock.Barcode, NpgsqlDbType.Text);
                writer.Write(stock.Quantity, NpgsqlDbType.Numeric);
                WriteNullable(writer, stock.Price, NpgsqlDbType.Numeric);
                if (stock.ExpiryDate is null) writer.WriteNull();
                else writer.Write(DateOnly.FromDateTime(stock.ExpiryDate.Value), NpgsqlDbType.Date);
                WriteNullable(writer, stock.Series, NpgsqlDbType.Text);
                WriteNullable(writer, stock.Unit, NpgsqlDbType.Text);
            }
            writer.Complete();
            writer.Dispose();
            using var upsert = new NpgsqlCommand("""
                INSERT INTO stock_rows(profile_id, source_id, part_id, name, manufacturer_barcode,
                    barcode, quantity, price, expiry_date, series, unit)
                SELECT profile_id, source_id, part_id, name, manufacturer_barcode,
                    barcode, quantity, price, expiry_date, series, unit
                FROM stock_stage WHERE ingest_id = @ingest
                ON CONFLICT (profile_id, source_id) DO UPDATE SET
                    part_id = EXCLUDED.part_id,
                    name = EXCLUDED.name,
                    manufacturer_barcode = EXCLUDED.manufacturer_barcode,
                    barcode = EXCLUDED.barcode,
                    quantity = EXCLUDED.quantity,
                    price = EXCLUDED.price,
                    expiry_date = EXCLUDED.expiry_date,
                    series = EXCLUDED.series,
                    unit = EXCLUDED.unit
                WHERE (stock_rows.part_id, stock_rows.name, stock_rows.manufacturer_barcode,
                       stock_rows.barcode, stock_rows.quantity, stock_rows.price,
                       stock_rows.expiry_date, stock_rows.series, stock_rows.unit)
                  IS DISTINCT FROM
                      (EXCLUDED.part_id, EXCLUDED.name, EXCLUDED.manufacturer_barcode,
                       EXCLUDED.barcode, EXCLUDED.quantity, EXCLUDED.price,
                       EXCLUDED.expiry_date, EXCLUDED.series, EXCLUDED.unit)
                """, connection, transaction);
            upsert.Parameters.AddWithValue("ingest", ingestId);
            upsert.ExecuteNonQuery();
        }
        using (var delete = new NpgsqlCommand("""
            DELETE FROM stock_rows AS current
            WHERE current.profile_id = @profile
              AND NOT EXISTS (
                SELECT 1 FROM stock_stage AS incoming
                WHERE incoming.ingest_id = @ingest AND incoming.source_id = current.source_id)
            """, connection, transaction))
        {
            delete.Parameters.AddWithValue("profile", profileId);
            delete.Parameters.AddWithValue("ingest", ingestId);
            delete.ExecuteNonQuery();
        }
        if (stocks.Count > 0)
        {
            using var cleanup = new NpgsqlCommand("DELETE FROM stock_stage WHERE ingest_id = @ingest", connection, transaction);
            cleanup.Parameters.AddWithValue("ingest", ingestId);
            cleanup.ExecuteNonQuery();
        }
    }

    private static void WriteNullable<T>(NpgsqlBinaryImporter writer, T? value, NpgsqlDbType type)
    {
        if (value is null) writer.WriteNull();
        else writer.Write(value, type);
    }

    public void MarkError(long profileId, string error)
    {
        using var connection = Open();
        using var command = new NpgsqlCommand("""
            UPDATE pharmacies
            SET last_error_at = GREATEST(clock_timestamp(),
                COALESCE(last_updated_at + interval '1 microsecond', '-infinity'::timestamptz)),
                last_error = @error
            WHERE id = @id
            """, connection);
        command.Parameters.AddWithValue("error", error[..Math.Min(error.Length, 500)]);
        command.Parameters.AddWithValue("id", profileId);
        command.ExecuteNonQuery();
    }

    public StockPage? GetStocks(long profileId, string? search, int limit, int offset)
    {
        using var connection = Open();
        using var transaction = connection.BeginTransaction(IsolationLevel.RepeatableRead);
        var pharmacy = GetPharmacyInTransaction(connection, transaction, profileId);
        if (pharmacy is null) return null;
        var pattern = string.IsNullOrWhiteSpace(search) ? null : $"%{EscapeLike(search.Trim())}%";
        const string predicate = """
            profile_id = @id AND (@q IS NULL OR name ILIKE @q ESCAPE '\'
                OR manufacturer_barcode ILIKE @q ESCAPE '\' OR barcode ILIKE @q ESCAPE '\')
            """;
        using var countCommand = new NpgsqlCommand($"SELECT COUNT(*) FROM stock_rows WHERE {predicate}", connection, transaction);
        countCommand.Parameters.AddWithValue("id", profileId);
        countCommand.Parameters.AddWithValue("q", NpgsqlDbType.Text, (object?)pattern ?? DBNull.Value);
        var total = checked((int)(long)countCommand.ExecuteScalar()!);
        using var itemsCommand = new NpgsqlCommand($"""
            SELECT source_id, part_id, name, manufacturer_barcode, barcode, quantity, price,
                   expiry_date, series, unit
            FROM stock_rows WHERE {predicate}
            ORDER BY name, part_id, source_id LIMIT @limit OFFSET @offset
            """, connection, transaction);
        itemsCommand.Parameters.AddWithValue("id", profileId);
        itemsCommand.Parameters.AddWithValue("q", NpgsqlDbType.Text, (object?)pattern ?? DBNull.Value);
        itemsCommand.Parameters.AddWithValue("limit", limit);
        itemsCommand.Parameters.AddWithValue("offset", offset);
        using var reader = itemsCommand.ExecuteReader();
        var rows = new List<StockRow>();
        while (reader.Read()) rows.Add(ReadStock(reader));
        reader.Close();
        transaction.Commit();
        return new StockPage(pharmacy, rows, total, limit, offset, pharmacy.LastUpdatedAt);
    }

    private PharmacyRow? GetPharmacyInTransaction(
        NpgsqlConnection connection, NpgsqlTransaction transaction, long id)
    {
        using var command = new NpgsqlCommand("""
            SELECT id, name, city, address, pharmacy_number, stock_count,
                   last_updated_at, last_error_at, source_kind,
                   source_observed_at, ingested_at
            FROM pharmacies WHERE id = @id AND is_active
            """, connection, transaction);
        command.Parameters.AddWithValue("id", id);
        using var reader = command.ExecuteReader();
        return reader.Read() ? ReadPharmacy(reader) : null;
    }

    private static StockRow ReadStock(NpgsqlDataReader reader) => new(
        reader.GetString(0), reader.GetInt64(1), reader.GetString(2),
        reader.IsDBNull(3) ? null : reader.GetString(3),
        reader.IsDBNull(4) ? null : reader.GetString(4),
        reader.GetDecimal(5), reader.IsDBNull(6) ? null : reader.GetDecimal(6),
        reader.IsDBNull(7) ? null : reader.GetFieldValue<DateOnly>(7).ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
        reader.IsDBNull(8) ? null : reader.GetString(8),
        reader.IsDBNull(9) ? null : reader.GetString(9));

    private static string EscapeLike(string value) => value
        .Replace("\\", "\\\\", StringComparison.Ordinal)
        .Replace("%", "\\%", StringComparison.Ordinal)
        .Replace("_", "\\_", StringComparison.Ordinal);
}
