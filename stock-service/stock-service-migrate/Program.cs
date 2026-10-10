using System.Globalization;
using Epharm.StockService;
using Microsoft.Data.Sqlite;
using Npgsql;
using NpgsqlTypes;

if (args.Length == 1 && args[0] == "--init-schema")
{
    try
    {
        StockRepository.BootstrapSchema(PostgresConnection());
        Console.WriteLine("PostgreSQL cache schema is ready.");
        return 0;
    }
    catch (Exception error)
    {
        Console.Error.WriteLine($"Schema init failed: {error.GetType().Name}: {error.Message}");
        return 1;
    }
}

if (args.Length != 1 || !File.Exists(args[0]))
{
    Console.Error.WriteLine("Usage: StockCacheImporter <read-only SQLite backup file>");
    return 2;
}

try
{
    var options = new StockOptions { PostgresConnectionString = PostgresConnection() };
    var repository = new StockRepository(options);
    repository.Initialize();
    using var sqlite = new SqliteConnection(new SqliteConnectionStringBuilder
    {
        // A verified backup is immutable. This also allows a WAL-mode database
        // to open from a read-only bind mount without creating -shm sidecars.
        DataSource = new Uri(Path.GetFullPath(args[0])).AbsoluteUri + "?immutable=1",
        Mode = SqliteOpenMode.ReadOnly, Cache = SqliteCacheMode.Private,
    }.ToString());
    sqlite.Open();
    using var sqliteTransaction = sqlite.BeginTransaction(deferred: true);
    var hasStockRows = TableExists(sqlite, sqliteTransaction, "stock_rows");
    var hasLegacyStocks = TableExists(sqlite, sqliteTransaction, "stocks");
    if (!hasStockRows && !hasLegacyStocks)
        throw new InvalidDataException("SQLite backup has neither stock_rows nor stocks table");
    if (hasStockRows && hasLegacyStocks &&
        Count(sqlite, sqliteTransaction, "stock_rows") == 0 &&
        Count(sqlite, sqliteTransaction, "stocks") > 0)
        throw new InvalidDataException(
            "SQLite backup has empty stock_rows but populated legacy stocks; resolve the ambiguous source before import");
    var stockTable = hasStockRows ? "stock_rows" : "stocks";
    var sourcePharmacies = Count(sqlite, sqliteTransaction, "pharmacies");
    var sourceRows = Count(sqlite, sqliteTransaction, stockTable);
    var sourceAsOf = ReadAsOfSummary(sqlite, sqliteTransaction);
    var hasIsActive = ColumnExists(sqlite, sqliteTransaction, "pharmacies", "is_active");
    var sourceMetadata = ReadMetadataSummary(sqlite, sqliteTransaction, hasIsActive);

    var pgBuilder = new NpgsqlConnectionStringBuilder(options.PostgresConnectionString)
    {
        ApplicationName = "epharm-stock-cache-importer", Timeout = 10, CommandTimeout = 600,
    };
    using var pg = new NpgsqlConnection(pgBuilder.ConnectionString);
    pg.Open();
    using var pgTransaction = pg.BeginTransaction();
    using (var guard = new NpgsqlCommand("SELECT pg_advisory_xact_lock(45010671)", pg, pgTransaction))
        guard.ExecuteNonQuery();
    using (var marker = new NpgsqlCommand("""
        SELECT source_row_count, imported_row_count, source_pharmacy_count, imported_pharmacy_count
        FROM cache_migrations WHERE id = 1
        """, pg, pgTransaction))
    using (var reader = marker.ExecuteReader())
    {
        if (reader.Read())
        {
            if (reader.GetInt64(0) != sourceRows || reader.GetInt64(1) != sourceRows ||
                reader.GetInt32(2) != sourcePharmacies || reader.GetInt32(3) != sourcePharmacies)
                throw new InvalidDataException("Migration marker does not match SQLite backup counts");
            reader.Close();
            VerifyImported(pg, pgTransaction, sourcePharmacies, sourceRows, sourceAsOf, sourceMetadata);
            pgTransaction.Commit();
            Console.WriteLine($"Already imported and verified: {sourcePharmacies} pharmacies, {sourceRows} stock rows.");
            return 0;
        }
    }
    if (CountPg(pg, pgTransaction, "pharmacies") != 0 || CountPg(pg, pgTransaction, "stock_rows") != 0)
        throw new InvalidOperationException("Target PostgreSQL cache is not empty; import refused");

    using (var source = sqlite.CreateCommand())
    {
        source.Transaction = sqliteTransaction;
        source.CommandText = $"""
            SELECT id, name, city, address, pharmacy_number, stock_count,
                   last_updated_at, last_error_at, last_error,
                   {(hasIsActive ? "is_active" : "1")}
            FROM pharmacies ORDER BY id
            """;
        using var reader = source.ExecuteReader();
        using var writer = pg.BeginBinaryImport("""
            COPY pharmacies(id, name, city, address, pharmacy_number, stock_count,
                last_updated_at, last_error_at, last_error, is_active)
            FROM STDIN (FORMAT BINARY)
            """);
        while (reader.Read())
        {
            writer.StartRow();
            writer.Write(reader.GetInt64(0), NpgsqlDbType.Bigint);
            writer.Write(reader.GetString(1), NpgsqlDbType.Text);
            writer.Write(reader.GetString(2), NpgsqlDbType.Text);
            writer.Write(reader.GetString(3), NpgsqlDbType.Text);
            writer.Write(reader.GetString(4), NpgsqlDbType.Text);
            if (reader.IsDBNull(5)) writer.WriteNull();
            else writer.Write(reader.GetInt32(5), NpgsqlDbType.Integer);
            WriteDateTime(writer, reader, 6);
            WriteDateTime(writer, reader, 7);
            if (reader.IsDBNull(8)) writer.WriteNull();
            else writer.Write(reader.GetString(8), NpgsqlDbType.Text);
            writer.Write(reader.GetInt32(9) != 0, NpgsqlDbType.Boolean);
        }
        writer.Complete();
    }

    using (var source = sqlite.CreateCommand())
    {
        source.Transaction = sqliteTransaction;
        source.CommandText = hasStockRows
            ? """
                SELECT profile_id, source_id, part_id, name, manufacturer_barcode,
                       barcode, quantity, price, expiry_date, series, unit
                FROM stock_rows ORDER BY profile_id, source_id
                """
            : """
                SELECT profile_id, 'legacy:' || part_id, part_id, name, manufacturer_barcode,
                       barcode, quantity, price, expiry_date, series, unit
                FROM stocks ORDER BY profile_id, part_id
                """;
        using var reader = source.ExecuteReader();
        using var writer = pg.BeginBinaryImport("""
            COPY stock_rows(profile_id, source_id, part_id, name, manufacturer_barcode,
                barcode, quantity, price, expiry_date, series, unit)
            FROM STDIN (FORMAT BINARY)
            """);
        while (reader.Read())
        {
            writer.StartRow();
            writer.Write(reader.GetInt64(0), NpgsqlDbType.Bigint);
            writer.Write(reader.GetString(1), NpgsqlDbType.Text);
            writer.Write(reader.GetInt64(2), NpgsqlDbType.Bigint);
            writer.Write(reader.GetString(3), NpgsqlDbType.Text);
            WriteText(writer, reader, 4);
            WriteText(writer, reader, 5);
            writer.Write(decimal.Parse(reader.GetString(6), CultureInfo.InvariantCulture), NpgsqlDbType.Numeric);
            if (reader.IsDBNull(7)) writer.WriteNull();
            else writer.Write(decimal.Parse(reader.GetString(7), CultureInfo.InvariantCulture), NpgsqlDbType.Numeric);
            if (reader.IsDBNull(8)) writer.WriteNull();
            else writer.Write(DateOnly.ParseExact(reader.GetString(8), "yyyy-MM-dd", CultureInfo.InvariantCulture), NpgsqlDbType.Date);
            WriteText(writer, reader, 9);
            WriteText(writer, reader, 10);
        }
        writer.Complete();
    }

    using (var provenance = new NpgsqlCommand("""
        UPDATE pharmacies
        SET source_kind = 'central_legacy', source_observed_at = NULL,
            ingested_at = last_updated_at
        """, pg, pgTransaction))
        provenance.ExecuteNonQuery();

    VerifyImported(pg, pgTransaction, sourcePharmacies, sourceRows, sourceAsOf, sourceMetadata);
    using (var mark = new NpgsqlCommand("""
        INSERT INTO cache_migrations(id, applied_at, source_row_count,
            imported_row_count, source_pharmacy_count, imported_pharmacy_count)
        VALUES (1, clock_timestamp(), @rows, @rows, @pharmacies, @pharmacies)
        """, pg, pgTransaction))
    {
        mark.Parameters.AddWithValue("rows", sourceRows);
        mark.Parameters.AddWithValue("pharmacies", sourcePharmacies);
        mark.ExecuteNonQuery();
    }
    pgTransaction.Commit();
    sqliteTransaction.Commit();
    Console.WriteLine($"Imported and verified: {sourcePharmacies} pharmacies, {sourceRows} stock rows.");
    return 0;
}
catch (Exception error)
{
    Console.Error.WriteLine($"Stock cache import failed: {error.GetType().Name}: {error.Message}");
    return 1;
}

static bool TableExists(SqliteConnection connection, SqliteTransaction transaction, string table)
{
    using var command = new SqliteCommand("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = @name", connection, transaction);
    command.Parameters.AddWithValue("name", table);
    return command.ExecuteScalar() is not null;
}

static string PostgresConnection()
{
    var explicitConnection = Environment.GetEnvironmentVariable("STOCK_PG_CONNECTION_STRING");
    if (!string.IsNullOrWhiteSpace(explicitConnection)) return explicitConnection;
    var password = Environment.GetEnvironmentVariable("STOCK_PG_PASSWORD");
    if (string.IsNullOrEmpty(password))
        throw new InvalidOperationException("STOCK_PG_PASSWORD or STOCK_PG_CONNECTION_STRING is required");
    return new NpgsqlConnectionStringBuilder
    {
        Host = Environment.GetEnvironmentVariable("STOCK_PG_HOST") ?? "postgres",
        Database = Environment.GetEnvironmentVariable("STOCK_PG_DATABASE") ?? "stocks",
        Username = Environment.GetEnvironmentVariable("STOCK_PG_USER") ?? "stock_app",
        Password = password,
    }.ConnectionString;
}

static bool ColumnExists(SqliteConnection connection, SqliteTransaction transaction, string table, string column)
{
    using var command = new SqliteCommand($"PRAGMA table_info({table})", connection, transaction);
    using var reader = command.ExecuteReader();
    while (reader.Read()) if (reader.GetString(1) == column) return true;
    return false;
}

static long Count(SqliteConnection connection, SqliteTransaction transaction, string table)
{
    using var command = new SqliteCommand($"SELECT COUNT(*) FROM {table}", connection, transaction);
    return (long)command.ExecuteScalar()!;
}

static long CountPg(NpgsqlConnection connection, NpgsqlTransaction transaction, string table)
{
    using var command = new NpgsqlCommand($"SELECT COUNT(*) FROM {table}", connection, transaction);
    return (long)command.ExecuteScalar()!;
}

static (long Count, DateTimeOffset? Min, DateTimeOffset? Max) ReadAsOfSummary(
    SqliteConnection connection, SqliteTransaction transaction)
{
    using var command = new SqliteCommand("""
        SELECT COUNT(last_updated_at), MIN(last_updated_at), MAX(last_updated_at) FROM pharmacies
        """, connection, transaction);
    using var reader = command.ExecuteReader();
    reader.Read();
    return ((long)reader.GetInt64(0),
        reader.IsDBNull(1) ? null : DateTimeOffset.Parse(reader.GetString(1), CultureInfo.InvariantCulture),
        reader.IsDBNull(2) ? null : DateTimeOffset.Parse(reader.GetString(2), CultureInfo.InvariantCulture));
}

static (long Active, long Pending, long ErrorTimestamps, long UnknownStockCounts) ReadMetadataSummary(
    SqliteConnection connection, SqliteTransaction transaction, bool hasIsActive)
{
    using var command = new SqliteCommand($"""
        SELECT SUM(CASE WHEN {(hasIsActive ? "is_active = 1" : "1 = 1")} THEN 1 ELSE 0 END),
               SUM(CASE WHEN last_updated_at IS NULL AND last_error_at IS NULL THEN 1 ELSE 0 END),
               COUNT(last_error_at),
               SUM(CASE WHEN stock_count IS NULL THEN 1 ELSE 0 END)
        FROM pharmacies
        """, connection, transaction);
    using var reader = command.ExecuteReader();
    reader.Read();
    return (reader.IsDBNull(0) ? 0 : reader.GetInt64(0),
        reader.IsDBNull(1) ? 0 : reader.GetInt64(1),
        reader.GetInt64(2),
        reader.IsDBNull(3) ? 0 : reader.GetInt64(3));
}

static void VerifyImported(
    NpgsqlConnection connection, NpgsqlTransaction transaction,
    long sourcePharmacies, long sourceRows,
    (long Count, DateTimeOffset? Min, DateTimeOffset? Max) sourceAsOf,
    (long Active, long Pending, long ErrorTimestamps, long UnknownStockCounts) sourceMetadata)
{
    var pgPharmacies = CountPg(connection, transaction, "pharmacies");
    var pgRows = CountPg(connection, transaction, "stock_rows");
    if (pgPharmacies != sourcePharmacies || pgRows != sourceRows)
        throw new InvalidDataException($"Count mismatch: source {sourcePharmacies}/{sourceRows}, target {pgPharmacies}/{pgRows}");
    using var command = new NpgsqlCommand("""
        SELECT COUNT(last_updated_at), MIN(last_updated_at), MAX(last_updated_at)
        FROM pharmacies
        """, connection, transaction);
    using var reader = command.ExecuteReader();
    reader.Read();
    var count = reader.GetInt64(0);
    var min = reader.IsDBNull(1) ? (DateTimeOffset?)null : reader.GetFieldValue<DateTimeOffset>(1);
    var max = reader.IsDBNull(2) ? (DateTimeOffset?)null : reader.GetFieldValue<DateTimeOffset>(2);
    if (count != sourceAsOf.Count || !SameMicrosecond(min, sourceAsOf.Min) || !SameMicrosecond(max, sourceAsOf.Max))
        throw new InvalidDataException("Snapshot asOf timestamps changed during import");
    reader.Close();
    using var metadata = new NpgsqlCommand("""
        SELECT COUNT(*) FILTER (WHERE is_active),
               COUNT(*) FILTER (WHERE last_updated_at IS NULL AND last_error_at IS NULL),
               COUNT(last_error_at),
               COUNT(*) FILTER (WHERE stock_count IS NULL),
               COUNT(*) FILTER (WHERE source_kind <> 'central_legacy' OR source_observed_at IS NOT NULL)
        FROM pharmacies
        """, connection, transaction);
    using var metadataReader = metadata.ExecuteReader();
    metadataReader.Read();
    if (metadataReader.GetInt64(0) != sourceMetadata.Active ||
        metadataReader.GetInt64(1) != sourceMetadata.Pending ||
        metadataReader.GetInt64(2) != sourceMetadata.ErrorTimestamps ||
        metadataReader.GetInt64(3) != sourceMetadata.UnknownStockCounts ||
        metadataReader.GetInt64(4) != 0)
        throw new InvalidDataException("Pharmacy active/pending/error/provenance metadata changed during import");
}

static bool SameMicrosecond(DateTimeOffset? left, DateTimeOffset? right) =>
    left is null && right is null ||
    left is not null && right is not null &&
    Math.Abs((left.Value - right.Value).Ticks) <= 10;

static void WriteDateTime(NpgsqlBinaryImporter writer, SqliteDataReader reader, int index)
{
    if (reader.IsDBNull(index)) writer.WriteNull();
    else writer.Write(DateTimeOffset.Parse(reader.GetString(index), CultureInfo.InvariantCulture).ToUniversalTime(), NpgsqlDbType.TimestampTz);
}

static void WriteText(NpgsqlBinaryImporter writer, SqliteDataReader reader, int index)
{
    if (reader.IsDBNull(index)) writer.WriteNull();
    else writer.Write(reader.GetString(index), NpgsqlDbType.Text);
}
