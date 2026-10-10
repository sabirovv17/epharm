using System.Security.Cryptography;
using System.Text;
using Npgsql;

namespace Epharm.StockService;

public sealed record CollectorIdentity(long ProfileId, string HqPharmacyId);

/// <summary>
/// A collector token is scoped to exactly one mapped pharmacy. Only its SHA-256
/// digest is stored in PostgreSQL; the read API key cannot authenticate uploads.
/// </summary>
public sealed class CollectorAuth
{
    private readonly string _connectionString;

    public CollectorAuth(StockOptions options)
    {
        var builder = new NpgsqlConnectionStringBuilder(options.PostgresConnectionString)
        {
            Pooling = true,
            MaxPoolSize = 8,
            Timeout = 5,
            CommandTimeout = 5,
            ApplicationName = "epharm-stock-collector-auth",
        };
        _connectionString = builder.ConnectionString;
    }

    public async Task<CollectorIdentity?> AuthenticateAsync(
        long profileId, string authorization, CancellationToken cancellationToken)
    {
        if (profileId <= 0 || !authorization.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase))
            return null;
        var token = authorization[7..].Trim();
        if (token.Length is < 32 or > 128 || token.Any(char.IsWhiteSpace))
            return null;

        var suppliedHash = SHA256.HashData(Encoding.UTF8.GetBytes(token));
        await using var connection = new NpgsqlConnection(_connectionString);
        await connection.OpenAsync(cancellationToken);
        await using var command = new NpgsqlCommand("""
            SELECT c.hq_pharmacy_id, c.token_hash
            FROM collector_credentials c
            JOIN pharmacies p ON p.id = c.profile_id AND p.is_active
            WHERE c.profile_id = @profile_id AND c.enabled
            """, connection);
        command.Parameters.AddWithValue("profile_id", profileId);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        if (!await reader.ReadAsync(cancellationToken)) return null;
        var expectedHash = reader.GetFieldValue<byte[]>(1);
        return expectedHash.Length == suppliedHash.Length &&
               CryptographicOperations.FixedTimeEquals(expectedHash, suppliedHash)
            ? new CollectorIdentity(profileId, reader.GetString(0))
            : null;
    }
}
