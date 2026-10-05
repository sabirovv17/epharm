using System.Globalization;
using Epharm.StockService.Source;
using Microsoft.Data.Sqlite;

namespace Epharm.StockService;

public sealed record PharmacyRow(
    long Id, string Name, string City, string Address, string PharmacyNumber,
    int? StockCount, DateTimeOffset? LastUpdatedAt,
    DateTimeOffset? LastErrorAt, string Status);

public sealed record StockRow(
    long PartId, string Name, string? ManufacturerBarcode, string? Barcode,
    decimal Quantity, decimal? Price, string? ExpiryDate, string? Series, string? Unit);

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
                last_requested_at TEXT,
                last_error_at TEXT,
                last_error TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_pharmacies_city ON pharmacies(city, name);
            CREATE INDEX IF NOT EXISTS idx_pharmacies_due ON pharmacies(last_requested_at, last_updated_at);
            CREATE TABLE IF NOT EXISTS stocks (
                profile_id INTEGER NOT NULL,
                part_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                manufacturer_barcode TEXT,
                barcode TEXT,
                quantity TEXT NOT NULL,
                price TEXT,
                expiry_date TEXT,
                series TEXT,
                unit TEXT,
                PRIMARY KEY (profile_id, part_id),
                FOREIGN KEY (profile_id) REFERENCES pharmacies(id)
            );
            """;
        command.ExecuteNonQuery();
    }

    public void UpsertPharmacies(IReadOnlyList<SourcePharmacy> pharmacies)
    {
        using var connection = Open();
        using var transaction = connection.BeginTransaction();
        using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
            INSERT INTO pharmacies(id, name, city, address, pharmacy_number)
            VALUES (@id, @name, @city, @address, @number)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name, city = excluded.city,
                address = excluded.address, pharmacy_number = excluded.pharmacy_number
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
        command.CommandText = "SELECT city, COUNT(*) FROM pharmacies GROUP BY city ORDER BY city";
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
            WHERE @city IS NULL OR city = @city
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
            FROM pharmacies WHERE id = @id
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

    public void MarkRequested(long profileId)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "UPDATE pharmacies SET last_requested_at = @now WHERE id = @id";
        command.Parameters.AddWithValue("@now", DateTimeOffset.UtcNow.ToString("O"));
        command.Parameters.AddWithValue("@id", profileId);
        command.ExecuteNonQuery();
    }

    public IReadOnlyList<long> DueRecentlyRequested(int limit)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT id FROM pharmacies
            WHERE last_requested_at >= @active_since
              AND (last_updated_at IS NULL OR last_updated_at < @due_before)
              AND (last_error_at IS NULL OR last_error_at < @retry_before)
            ORDER BY COALESCE(last_updated_at, ''), id
            LIMIT @limit
            """;
        command.Parameters.AddWithValue("@active_since", DateTimeOffset.UtcNow.AddHours(-24).ToString("O"));
        command.Parameters.AddWithValue("@due_before", DateTimeOffset.UtcNow.AddSeconds(-options.RefreshSeconds).ToString("O"));
        command.Parameters.AddWithValue("@retry_before", DateTimeOffset.UtcNow.AddMinutes(-2).ToString("O"));
        command.Parameters.AddWithValue("@limit", limit);
        using var reader = command.ExecuteReader();
        var ids = new List<long>();
        while (reader.Read()) ids.Add(reader.GetInt64(0));
        return ids;
    }

    public void ReplaceSnapshot(long profileId, IReadOnlyList<SourceStock> stocks)
    {
        using var connection = Open();
        using var transaction = connection.BeginTransaction();
        using (var delete = connection.CreateCommand())
        {
            delete.Transaction = transaction;
            delete.CommandText = "DELETE FROM stocks WHERE profile_id = @id";
            delete.Parameters.AddWithValue("@id", profileId);
            delete.ExecuteNonQuery();
        }
        using (var insert = connection.CreateCommand())
        {
            insert.Transaction = transaction;
            insert.CommandText = """
                INSERT INTO stocks(profile_id, part_id, name, manufacturer_barcode, barcode,
                                   quantity, price, expiry_date, series, unit)
                VALUES (@profile, @part, @name, @manufacturer, @barcode,
                        @quantity, @price, @expiry, @series, @unit)
                """;
            var profile = insert.Parameters.Add("@profile", SqliteType.Integer);
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
                profile.Value = profileId;
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
        countCommand.CommandText = $"SELECT COUNT(*) FROM stocks WHERE {predicate}";
        countCommand.Parameters.AddWithValue("@id", profileId);
        countCommand.Parameters.AddWithValue("@q", (object?)pattern ?? DBNull.Value);
        var total = Convert.ToInt32(countCommand.ExecuteScalar(), CultureInfo.InvariantCulture);
        using var itemsCommand = connection.CreateCommand();
        itemsCommand.Transaction = transaction;
        itemsCommand.CommandText = $"""
            SELECT part_id, name, manufacturer_barcode, barcode, quantity, price,
                   expiry_date, series, unit
            FROM stocks WHERE {predicate}
            ORDER BY name, part_id LIMIT @limit OFFSET @offset
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

    public (PharmacyRow Pharmacy, IReadOnlyList<StockRow> Rows)? ExportSnapshot(long profileId)
    {
        using var connection = Open();
        using var transaction = connection.BeginTransaction(deferred: true);
        var pharmacy = GetPharmacyInTransaction(connection, transaction, profileId);
        if (pharmacy is null) return null;
        using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
            SELECT part_id, name, manufacturer_barcode, barcode, quantity, price,
                   expiry_date, series, unit
            FROM stocks WHERE profile_id = @id ORDER BY name, part_id
            """;
        command.Parameters.AddWithValue("@id", profileId);
        using var reader = command.ExecuteReader();
        var rows = new List<StockRow>();
        while (reader.Read()) rows.Add(ReadStock(reader));
        reader.Close();
        transaction.Commit();
        return (pharmacy, rows);
    }

    private PharmacyRow? GetPharmacyInTransaction(
        SqliteConnection connection, SqliteTransaction transaction, long id)
    {
        using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
            SELECT id, name, city, address, pharmacy_number, stock_count,
                   last_updated_at, last_error_at
            FROM pharmacies WHERE id = @id
            """;
        command.Parameters.AddWithValue("@id", id);
        using var reader = command.ExecuteReader();
        return reader.Read() ? ReadPharmacy(reader) : null;
    }

    private static StockRow ReadStock(SqliteDataReader reader) => new(
        reader.GetInt64(0), reader.GetString(1),
        reader.IsDBNull(2) ? null : reader.GetString(2),
        reader.IsDBNull(3) ? null : reader.GetString(3),
        decimal.Parse(reader.GetString(4), CultureInfo.InvariantCulture),
        reader.IsDBNull(5) ? null : decimal.Parse(reader.GetString(5), CultureInfo.InvariantCulture),
        reader.IsDBNull(6) ? null : reader.GetString(6),
        reader.IsDBNull(7) ? null : reader.GetString(7),
        reader.IsDBNull(8) ? null : reader.GetString(8));

    private static string EscapeLike(string value) => value
        .Replace("\\", "\\\\", StringComparison.Ordinal)
        .Replace("%", "\\%", StringComparison.Ordinal)
        .Replace("_", "\\_", StringComparison.Ordinal);
}
