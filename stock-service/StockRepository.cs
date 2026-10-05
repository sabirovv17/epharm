using System.Globalization;
using Epharm.StockService.Source;
using Microsoft.Data.Sqlite;

namespace Epharm.StockService;

public sealed record PharmacyRow(
    long Id, string Name, string City, string Address, string PharmacyNumber,
    int? StockCount, DateTimeOffset? LastUpdatedAt,
    DateTimeOffset? LastErrorAt, string Status);

public sealed record StockRow(
    string SourceId, long PartId, string Name, string? ManufacturerBarcode, string? Barcode,
    decimal Quantity, decimal? Price, string? ExpiryDate, string? Series, string? Unit);

public sealed record CollectionStatus(
    int FreshnessTargetSeconds, int SweepIntervalSeconds,
    int PharmacyCount, int FreshCount, int StaleCount,
    int PendingCount, int ErrorCount, long? RunId, string? RunStatus,
    DateTimeOffset? RunStartedAt, DateTimeOffset? RunCompletedAt,
    DateTimeOffset? LastSuccessfulRunAt, int RunTotal, int RunSucceeded,
    int RunFailed);

public sealed record StockPage(
    PharmacyRow Pharmacy, IReadOnlyList<StockRow> Items, int Total,
    int Limit, int Offset, DateTimeOffset? AsOf)
{
    public string? SnapshotId => AsOf?.ToString("O");
}

public sealed class StockRepository(StockOptions options)
{
    private readonly string _connectionString = new SqliteConnectionStringBuilder
    {
        DataSource = options.DataPath,
        Mode = SqliteOpenMode.ReadWriteCreate,
        Cache = SqliteCacheMode.Shared,
    }.ToString();

    private SqliteConnection Open()
    {
        var connection = new SqliteConnection(_connectionString);
        connection.Open();
        using var timeout = connection.CreateCommand();
        timeout.CommandText = "PRAGMA busy_timeout = 5000";
        timeout.ExecuteNonQuery();
        return connection;
    }

    public void Initialize()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(options.DataPath) ?? ".");
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            PRAGMA journal_mode = WAL;
            CREATE TABLE IF NOT EXISTS pharmacies (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                city TEXT NOT NULL,
                address TEXT NOT NULL,
                pharmacy_number TEXT NOT NULL,
                stock_count INTEGER,
                last_updated_at TEXT,
                last_error_at TEXT,
                last_error TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_pharmacies_city ON pharmacies(city, name);
            CREATE TABLE IF NOT EXISTS stock_rows (
                profile_id INTEGER NOT NULL,
                source_id TEXT NOT NULL,
                part_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                manufacturer_barcode TEXT,
                barcode TEXT,
                quantity TEXT NOT NULL,
                price TEXT,
                expiry_date TEXT,
                series TEXT,
                unit TEXT,
                PRIMARY KEY (profile_id, source_id),
                FOREIGN KEY (profile_id) REFERENCES pharmacies(id)
            );
            CREATE TABLE IF NOT EXISTS collection_runs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at TEXT NOT NULL,
                completed_at TEXT,
                status TEXT NOT NULL,
                profile_total INTEGER NOT NULL DEFAULT 0,
                succeeded INTEGER NOT NULL DEFAULT 0,
                failed INTEGER NOT NULL DEFAULT 0
            );
            """;
        command.ExecuteNonQuery();
        using var columns = connection.CreateCommand();
        columns.CommandText = "PRAGMA table_info(pharmacies)";
        using var reader = columns.ExecuteReader();
        var hasActive = false;
        while (reader.Read())
            if (reader.GetString(1) == "is_active") hasActive = true;
        reader.Close();
        if (!hasActive)
        {
            using var alter = connection.CreateCommand();
            alter.CommandText = "ALTER TABLE pharmacies ADD COLUMN is_active INTEGER NOT NULL DEFAULT 1";
            alter.ExecuteNonQuery();
        }
        using var oldTable = connection.CreateCommand();
        oldTable.CommandText = "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'stocks'";
        if (Convert.ToInt32(oldTable.ExecuteScalar(), CultureInfo.InvariantCulture) != 0)
        {
            using var migrate = connection.CreateCommand();
            migrate.CommandText = """
                INSERT INTO stock_rows(profile_id, source_id, part_id, name,
                    manufacturer_barcode, barcode, quantity, price, expiry_date, series, unit)
                SELECT profile_id, 'legacy:' || part_id, part_id, name,
                    manufacturer_barcode, barcode, quantity, price, expiry_date, series, unit
                FROM stocks
                WHERE NOT EXISTS (SELECT 1 FROM stock_rows LIMIT 1)
                """;
            migrate.ExecuteNonQuery();
        }
        using var interrupted = connection.CreateCommand();
        interrupted.CommandText = "UPDATE collection_runs SET status = 'interrupted', completed_at = @now WHERE status = 'running'";
        interrupted.Parameters.AddWithValue("@now", DateTimeOffset.UtcNow.ToString("O"));
        interrupted.ExecuteNonQuery();
    }

    public void UpsertPharmacies(IReadOnlyList<SourcePharmacy> pharmacies)
    {
        using var connection = Open();
        using var transaction = connection.BeginTransaction();
        using (var deactivate = connection.CreateCommand())
        {
            deactivate.Transaction = transaction;
            deactivate.CommandText = "UPDATE pharmacies SET is_active = 0";
            deactivate.ExecuteNonQuery();
        }
        using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
            INSERT INTO pharmacies(id, name, city, address, pharmacy_number, is_active)
            VALUES (@id, @name, @city, @address, @number, 1)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name, city = excluded.city,
                address = excluded.address, pharmacy_number = excluded.pharmacy_number,
                is_active = 1
            """;
        var id = command.Parameters.Add("@id", SqliteType.Integer);
        var name = command.Parameters.Add("@name", SqliteType.Text);
        var city = command.Parameters.Add("@city", SqliteType.Text);
        var address = command.Parameters.Add("@address", SqliteType.Text);
        var number = command.Parameters.Add("@number", SqliteType.Text);
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
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT city, COUNT(*) FROM pharmacies WHERE is_active = 1 GROUP BY city ORDER BY city";
        using var reader = command.ExecuteReader();
        var result = new List<object>();
        while (reader.Read())
            result.Add(new { city = reader.GetString(0), count = reader.GetInt32(1) });
        return result;
    }

    public IReadOnlyList<PharmacyRow> ListPharmacies(string? city)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT id, name, city, address, pharmacy_number, stock_count,
                   last_updated_at, last_error_at
            FROM pharmacies
            WHERE is_active = 1 AND (@city IS NULL OR city = @city)
            ORDER BY city, name, id
            """;
        command.Parameters.AddWithValue("@city", (object?)city ?? DBNull.Value);
        using var reader = command.ExecuteReader();
        var rows = new List<PharmacyRow>();
        while (reader.Read()) rows.Add(ReadPharmacy(reader));
        return rows;
    }

    public PharmacyRow? GetPharmacy(long id)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT id, name, city, address, pharmacy_number, stock_count,
                   last_updated_at, last_error_at
            FROM pharmacies WHERE id = @id AND is_active = 1
            """;
        command.Parameters.AddWithValue("@id", id);
        using var reader = command.ExecuteReader();
        return reader.Read() ? ReadPharmacy(reader) : null;
    }

    private PharmacyRow ReadPharmacy(SqliteDataReader reader)
    {
        var captured = Date(reader, 6);
        var error = Date(reader, 7);
        var status = captured is null ? (error is null ? "pending" : "error")
            : error > captured ? "error"
            : DateTimeOffset.UtcNow - captured > TimeSpan.FromSeconds(options.RefreshSeconds) ? "stale"
            : "fresh";
        return new PharmacyRow(
            reader.GetInt64(0), reader.GetString(1), reader.GetString(2),
            reader.GetString(3), reader.GetString(4),
            reader.IsDBNull(5) ? null : reader.GetInt32(5), captured, error, status);
    }

    private static DateTimeOffset? Date(SqliteDataReader reader, int index) =>
        reader.IsDBNull(index) ? null : DateTimeOffset.Parse(reader.GetString(index), CultureInfo.InvariantCulture);

    public IReadOnlyList<long> ListActiveProfileIds()
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT id FROM pharmacies WHERE is_active = 1 ORDER BY COALESCE(last_updated_at, ''), id";
        using var reader = command.ExecuteReader();
        var ids = new List<long>();
        while (reader.Read()) ids.Add(reader.GetInt64(0));
        return ids;
    }

    public long StartRun(int profileTotal)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            INSERT INTO collection_runs(started_at, status, profile_total)
            VALUES (@now, 'running', @total)
            RETURNING id
            """;
        command.Parameters.AddWithValue("@now", DateTimeOffset.UtcNow.ToString("O"));
        command.Parameters.AddWithValue("@total", profileTotal);
        return Convert.ToInt64(command.ExecuteScalar(), CultureInfo.InvariantCulture);
    }

    public void SetRunTotal(long runId, int total)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "UPDATE collection_runs SET profile_total = @total WHERE id = @id";
        command.Parameters.AddWithValue("@total", total);
        command.Parameters.AddWithValue("@id", runId);
        command.ExecuteNonQuery();
    }

    public void RecordRunResult(long runId, bool succeeded)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = succeeded
            ? "UPDATE collection_runs SET succeeded = succeeded + 1 WHERE id = @id"
            : "UPDATE collection_runs SET failed = failed + 1 WHERE id = @id";
        command.Parameters.AddWithValue("@id", runId);
        command.ExecuteNonQuery();
    }

    public void CompleteRun(long runId, bool interrupted)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            UPDATE collection_runs
            SET completed_at = @now,
                status = CASE WHEN @interrupted = 1 THEN 'interrupted'
                              WHEN profile_total > 0 AND failed = 0 AND succeeded = profile_total THEN 'succeeded'
                              WHEN succeeded = 0 THEN 'failed'
                              ELSE 'partial' END
            WHERE id = @id
            """;
        command.Parameters.AddWithValue("@now", DateTimeOffset.UtcNow.ToString("O"));
        command.Parameters.AddWithValue("@interrupted", interrupted ? 1 : 0);
        command.Parameters.AddWithValue("@id", runId);
        command.ExecuteNonQuery();
    }

    public CollectionStatus GetCollectionStatus()
    {
        using var connection = Open();
        using var transaction = connection.BeginTransaction(deferred: true);
        using var counts = connection.CreateCommand();
        counts.Transaction = transaction;
        counts.CommandText = """
            SELECT COUNT(*),
              COALESCE(SUM(CASE WHEN last_updated_at >= @fresh AND
                (last_error_at IS NULL OR last_error_at <= last_updated_at) THEN 1 ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN last_updated_at < @fresh AND
                (last_error_at IS NULL OR last_error_at <= last_updated_at) THEN 1 ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN last_updated_at IS NULL AND last_error_at IS NULL THEN 1 ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN last_error_at IS NOT NULL AND
                (last_updated_at IS NULL OR last_error_at > last_updated_at) THEN 1 ELSE 0 END), 0)
            FROM pharmacies WHERE is_active = 1
            """;
        counts.Parameters.AddWithValue("@fresh", DateTimeOffset.UtcNow.AddSeconds(-options.RefreshSeconds).ToString("O"));
        using var countReader = counts.ExecuteReader();
        countReader.Read();
        var pharmacyCount = countReader.GetInt32(0);
        var fresh = countReader.GetInt32(1);
        var stale = countReader.GetInt32(2);
        var pending = countReader.GetInt32(3);
        var error = countReader.GetInt32(4);
        countReader.Close();
        using var run = connection.CreateCommand();
        run.Transaction = transaction;
        run.CommandText = """
            SELECT id, status, started_at, completed_at, profile_total, succeeded, failed,
              (SELECT MAX(completed_at) FROM collection_runs WHERE status = 'succeeded')
            FROM collection_runs ORDER BY id DESC LIMIT 1
            """;
        using var runReader = run.ExecuteReader();
        if (!runReader.Read())
            return new CollectionStatus(options.RefreshSeconds, options.SweepIntervalSeconds, pharmacyCount, fresh, stale,
                pending, error, null, null, null, null, null, 0, 0, 0);
        var status = new CollectionStatus(options.RefreshSeconds, options.SweepIntervalSeconds, pharmacyCount, fresh, stale,
            pending, error, runReader.GetInt64(0), runReader.GetString(1),
            DateTimeOffset.Parse(runReader.GetString(2), CultureInfo.InvariantCulture),
            Date(runReader, 3), Date(runReader, 7), runReader.GetInt32(4),
            runReader.GetInt32(5), runReader.GetInt32(6));
        runReader.Close();
        transaction.Commit();
        return status;
    }

    public void ReplaceSnapshot(long profileId, IReadOnlyList<SourceStock> stocks)
    {
        using var connection = Open();
        using var transaction = connection.BeginTransaction();
        using (var delete = connection.CreateCommand())
        {
            delete.Transaction = transaction;
            delete.CommandText = "DELETE FROM stock_rows WHERE profile_id = @id";
            delete.Parameters.AddWithValue("@id", profileId);
            delete.ExecuteNonQuery();
        }
        using (var insert = connection.CreateCommand())
        {
            insert.Transaction = transaction;
            insert.CommandText = """
                INSERT INTO stock_rows(profile_id, source_id, part_id, name, manufacturer_barcode, barcode,
                                   quantity, price, expiry_date, series, unit)
                VALUES (@profile, @source, @part, @name, @manufacturer, @barcode,
                        @quantity, @price, @expiry, @series, @unit)
                """;
            var profile = insert.Parameters.Add("@profile", SqliteType.Integer);
            var source = insert.Parameters.Add("@source", SqliteType.Text);
            var part = insert.Parameters.Add("@part", SqliteType.Integer);
            var name = insert.Parameters.Add("@name", SqliteType.Text);
            var manufacturer = insert.Parameters.Add("@manufacturer", SqliteType.Text);
            var barcode = insert.Parameters.Add("@barcode", SqliteType.Text);
            var quantity = insert.Parameters.Add("@quantity", SqliteType.Text);
            var price = insert.Parameters.Add("@price", SqliteType.Text);
            var expiry = insert.Parameters.Add("@expiry", SqliteType.Text);
            var series = insert.Parameters.Add("@series", SqliteType.Text);
            var unit = insert.Parameters.Add("@unit", SqliteType.Text);
            foreach (var stock in stocks)
            {
                if (stock.ProfileId != profileId || string.IsNullOrWhiteSpace(stock.SourceId))
                    throw new InvalidDataException("Stock row has invalid source identity");
                profile.Value = profileId;
                source.Value = stock.SourceId;
                part.Value = stock.PartId;
                name.Value = stock.Name;
                manufacturer.Value = (object?)stock.ManufacturerBarcode ?? DBNull.Value;
                barcode.Value = (object?)stock.Barcode ?? DBNull.Value;
                quantity.Value = stock.Quantity.ToString(CultureInfo.InvariantCulture);
                price.Value = stock.Price?.ToString(CultureInfo.InvariantCulture) ?? (object)DBNull.Value;
                expiry.Value = stock.ExpiryDate?.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture) ?? (object)DBNull.Value;
                series.Value = (object?)stock.Series ?? DBNull.Value;
                unit.Value = (object?)stock.Unit ?? DBNull.Value;
                insert.ExecuteNonQuery();
            }
        }
        using (var update = connection.CreateCommand())
        {
            update.Transaction = transaction;
            update.CommandText = """
                UPDATE pharmacies
                SET stock_count = @count, last_updated_at = @now,
                    last_error = NULL, last_error_at = NULL
                WHERE id = @id
                """;
            update.Parameters.AddWithValue("@count", stocks.Count);
            update.Parameters.AddWithValue("@now", DateTimeOffset.UtcNow.ToString("O"));
            update.Parameters.AddWithValue("@id", profileId);
            update.ExecuteNonQuery();
        }
        transaction.Commit();
    }

    public void MarkError(long profileId, string error)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            UPDATE pharmacies SET last_error_at = @now, last_error = @error WHERE id = @id
            """;
        command.Parameters.AddWithValue("@now", DateTimeOffset.UtcNow.ToString("O"));
        command.Parameters.AddWithValue("@error", error[..Math.Min(error.Length, 500)]);
        command.Parameters.AddWithValue("@id", profileId);
        command.ExecuteNonQuery();
    }

    public StockPage? GetStocks(long profileId, string? search, int limit, int offset)
    {
        using var connection = Open();
        using var transaction = connection.BeginTransaction(deferred: true);
        var pharmacy = GetPharmacyInTransaction(connection, transaction, profileId);
        if (pharmacy is null) return null;
        var pattern = string.IsNullOrWhiteSpace(search) ? null : $"%{EscapeLike(search.Trim())}%";
        const string predicate = "profile_id = @id AND (@q IS NULL OR name LIKE @q ESCAPE '\\' OR manufacturer_barcode LIKE @q ESCAPE '\\' OR barcode LIKE @q ESCAPE '\\')";
        using var countCommand = connection.CreateCommand();
        countCommand.Transaction = transaction;
        countCommand.CommandText = $"SELECT COUNT(*) FROM stock_rows WHERE {predicate}";
        countCommand.Parameters.AddWithValue("@id", profileId);
        countCommand.Parameters.AddWithValue("@q", (object?)pattern ?? DBNull.Value);
        var total = Convert.ToInt32(countCommand.ExecuteScalar(), CultureInfo.InvariantCulture);
        using var itemsCommand = connection.CreateCommand();
        itemsCommand.Transaction = transaction;
        itemsCommand.CommandText = $"""
            SELECT source_id, part_id, name, manufacturer_barcode, barcode, quantity, price,
                   expiry_date, series, unit
            FROM stock_rows WHERE {predicate}
            ORDER BY name, part_id, source_id LIMIT @limit OFFSET @offset
            """;
        itemsCommand.Parameters.AddWithValue("@id", profileId);
        itemsCommand.Parameters.AddWithValue("@q", (object?)pattern ?? DBNull.Value);
        itemsCommand.Parameters.AddWithValue("@limit", limit);
        itemsCommand.Parameters.AddWithValue("@offset", offset);
        using var reader = itemsCommand.ExecuteReader();
        var rows = new List<StockRow>();
        while (reader.Read()) rows.Add(ReadStock(reader));
        reader.Close();
        transaction.Commit();
        return new StockPage(pharmacy, rows, total, limit, offset, pharmacy.LastUpdatedAt);
    }

    private PharmacyRow? GetPharmacyInTransaction(
        SqliteConnection connection, SqliteTransaction transaction, long id)
    {
        using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
            SELECT id, name, city, address, pharmacy_number, stock_count,
                   last_updated_at, last_error_at
            FROM pharmacies WHERE id = @id AND is_active = 1
            """;
        command.Parameters.AddWithValue("@id", id);
        using var reader = command.ExecuteReader();
        return reader.Read() ? ReadPharmacy(reader) : null;
    }

    private static StockRow ReadStock(SqliteDataReader reader) => new(
        reader.GetString(0), reader.GetInt64(1), reader.GetString(2),
        reader.IsDBNull(3) ? null : reader.GetString(3),
        reader.IsDBNull(4) ? null : reader.GetString(4),
        decimal.Parse(reader.GetString(5), CultureInfo.InvariantCulture),
        reader.IsDBNull(6) ? null : decimal.Parse(reader.GetString(6), CultureInfo.InvariantCulture),
        reader.IsDBNull(7) ? null : reader.GetString(7),
        reader.IsDBNull(8) ? null : reader.GetString(8),
        reader.IsDBNull(9) ? null : reader.GetString(9));

    private static string EscapeLike(string value) => value
        .Replace("\\", "\\\\", StringComparison.Ordinal)
        .Replace("%", "\\%", StringComparison.Ordinal)
        .Replace("_", "\\_", StringComparison.Ordinal);
}
