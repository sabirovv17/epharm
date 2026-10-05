using System.Data;
using System.Globalization;
using System.Text;
using FirebirdSql.Data.FirebirdClient;

namespace Epharm.StockService.Source;

public sealed record SourcePharmacy(
    long ProfileId,
    string Name,
    string City,
    string Address,
    string PharmacyNumber);

// Each row is a source warehouse row. In particular, batches with different
// expiry dates or series must not be collapsed into one product here.
public sealed record SourceStock(
    long ProfileId,
    string SourceId,
    long PartId,
    string Name,
    string? ManufacturerBarcode,
    string? Barcode,
    decimal Quantity,
    decimal? Price,
    DateTime? ExpiryDate,
    string? Series,
    string? Unit);

/// <summary>
/// Bounded, read-only access to the Standard-N group database. The account must
/// also have SELECT-only grants; the transaction mode is a second safeguard.
/// </summary>
public interface IStockSource
{
    IReadOnlyList<SourcePharmacy> ReadPharmacies();
    IReadOnlyList<SourceStock> ReadStock(long profileId);
}

public sealed class StandardNSource : IStockSource
{
    private const FbTransactionBehavior ReadBehavior =
        FbTransactionBehavior.Read |
        FbTransactionBehavior.ReadCommitted |
        FbTransactionBehavior.RecVersion |
        FbTransactionBehavior.NoWait;

    private readonly string _connectionString;
    private readonly int _timeoutSeconds;

    public StandardNSource(
        string host,
        int port,
        string path,
        string user,
        string password,
        int timeoutSeconds)
    {
        if (string.IsNullOrWhiteSpace(host)) throw new ArgumentException("Firebird host is required.", nameof(host));
        if (port is < 1 or > 65535) throw new ArgumentOutOfRangeException(nameof(port));
        if (string.IsNullOrWhiteSpace(path)) throw new ArgumentException("Firebird database path is required.", nameof(path));
        if (string.IsNullOrWhiteSpace(user)) throw new ArgumentException("Firebird user is required.", nameof(user));
        if (string.IsNullOrWhiteSpace(password)) throw new ArgumentException("Firebird password is required.", nameof(password));
        if (timeoutSeconds < 1) throw new ArgumentOutOfRangeException(nameof(timeoutSeconds));

        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
        _timeoutSeconds = timeoutSeconds;
        _connectionString = new FbConnectionStringBuilder
        {
            DataSource = host.Trim(),
            Port = port,
            Database = path.Trim(),
            UserID = user.Trim(),
            Password = password,
            Charset = "WIN1251",
            ConnectionTimeout = timeoutSeconds,
            Pooling = true,
        }.ToString();
    }

    public IReadOnlyList<SourcePharmacy> ReadPharmacies()
    {
        using var connection = OpenConnection();
        using var transaction = connection.BeginTransaction(new FbTransactionOptions
        {
            TransactionBehavior = ReadBehavior,
        });
        try
        {
            using var command = connection.CreateCommand();
            command.Transaction = transaction;
            command.CommandTimeout = _timeoutSeconds;
            command.FetchSize = 4096;
            command.CommandText = """
                SELECT ID, CAPTION, SCITY, ADRESS, APTEKA_NUMBER
                FROM VW_G$PROFILES
                WHERE APTEKA_NUMBER IS NOT NULL
                """;

            var pharmacies = new List<SourcePharmacy>();
            using (var reader = command.ExecuteReader())
            {
                while (reader.Read())
                {
                    pharmacies.Add(new SourcePharmacy(
                        RequiredInt64(reader, 0),
                        Text(reader, 1) ?? string.Empty,
                        NormalizeCity(Text(reader, 2)),
                        Text(reader, 3) ?? string.Empty,
                        Text(reader, 4) ?? string.Empty));
                }
            }

            transaction.Rollback();
            return pharmacies;
        }
        catch
        {
            TryRollback(transaction);
            throw;
        }
    }

    public IReadOnlyList<SourceStock> ReadStock(long profileId)
    {
        if (profileId <= 0) throw new ArgumentOutOfRangeException(nameof(profileId));

        using var connection = OpenConnection();
        using var transaction = connection.BeginTransaction(new FbTransactionOptions
        {
            TransactionBehavior = ReadBehavior,
        });
        try
        {
            using var command = connection.CreateCommand();
            command.Transaction = transaction;
            command.CommandTimeout = _timeoutSeconds;
            command.FetchSize = 4096;
            command.CommandText = """
                SELECT D$UUID, PART_ID, G$PROFILE_ID, SNAME, BCODE_IZG, BARCODE,
                       QUANT, PRICE, GODENDO, SERIA, EDIZM
                FROM WAREBASE_G
                WHERE G$PROFILE_ID = @profileId AND QUANT > 0
                """;
            command.Parameters.AddWithValue("@profileId", profileId);

            var stocks = new List<SourceStock>();
            using (var reader = command.ExecuteReader())
            {
                while (reader.Read())
                {
                    stocks.Add(new SourceStock(
                        RequiredInt64(reader, 2),
                        Text(reader, 0) ?? throw new InvalidDataException("WAREBASE_G.D$UUID is empty"),
                        RequiredInt64(reader, 1),
                        Text(reader, 3) ?? string.Empty,
                        Text(reader, 4),
                        Text(reader, 5),
                        RequiredDecimal(reader, 6),
                        OptionalDecimal(reader, 7),
                        OptionalDateTime(reader, 8),
                        Text(reader, 9),
                        Text(reader, 10)));
                }
            }

            transaction.Rollback();
            return stocks;
        }
        catch
        {
            TryRollback(transaction);
            throw;
        }
    }

    private FbConnection OpenConnection()
    {
        var connection = new FbConnection(_connectionString);
        try
        {
            connection.Open();
            return connection;
        }
        catch
        {
            connection.Dispose();
            throw;
        }
    }

    private static void TryRollback(FbTransaction transaction)
    {
        try { transaction.Rollback(); }
        catch { /* Preserve the original query/rollback failure. */ }
    }

    private static long RequiredInt64(IDataRecord row, int ordinal) =>
        Convert.ToInt64(row.GetValue(ordinal), CultureInfo.InvariantCulture);

    private static decimal RequiredDecimal(IDataRecord row, int ordinal) =>
        Convert.ToDecimal(row.GetValue(ordinal), CultureInfo.InvariantCulture);

    private static decimal? OptionalDecimal(IDataRecord row, int ordinal) =>
        row.IsDBNull(ordinal) ? null : RequiredDecimal(row, ordinal);

    private static DateTime? OptionalDateTime(IDataRecord row, int ordinal) =>
        row.IsDBNull(ordinal)
            ? null
            : Convert.ToDateTime(row.GetValue(ordinal), CultureInfo.InvariantCulture);

    private static string? Text(IDataRecord row, int ordinal)
    {
        if (row.IsDBNull(ordinal)) return null;
        var value = Convert.ToString(row.GetValue(ordinal), CultureInfo.InvariantCulture)?.Trim();
        return string.IsNullOrEmpty(value) ? null : value;
    }

    private static string NormalizeCity(string? city) =>
        string.IsNullOrWhiteSpace(city) ? "Город не указан" : city;
}
