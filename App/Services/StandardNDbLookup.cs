using System;
using System.Data;
using System.Globalization;
using System.IO;
using System.Text;
using CustomerDisplay.Config;
using FirebirdSql.Data.FirebirdClient;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// Read-only lookup into the local Standard-N Firebird database (ztrade).
    /// This service is strictly optional: every failure is swallowed and rate-limited in logs,
    /// because POSM must never block the cash desk when Firebird/path/credentials are unavailable.
    /// </summary>
    public sealed class StandardNDbLookup : IDisposable
    {
        private static readonly string[] DefaultDbPaths =
        {
            @"C:\Standart-N_DEMO\Apteka_KZ DEMO\db\ztrade",
            @"C:\Standart-N_DEMO\Apteka_KZ DEMO\db\ztrade.fdb",
            @"C:\Standart-N\db\ztrade",
            @"C:\Standart-N\db\ztrade.fdb",
        };

        private readonly EpharmConfig _cfg;
        private readonly Action<string> _log;
        private DateTimeOffset _lastErrorLog = DateTimeOffset.MinValue;
        private string? _resolvedDbPath;
        private bool _disposed;

        public StandardNDbLookup(EpharmConfig cfg, Action<string> log)
        {
            _cfg = cfg;
            _log = log;
            Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
        }

        public string Describe()
        {
            if (!_cfg.StandardNDbEnabled) return "выключена";
            var path = ResolveDbPath(logIfMissing: false) ?? "не найден";
            return $"{_cfg.StandardNDbHost}:{_cfg.StandardNDbPort}, db={path}";
        }

        public StandardNActivePharmacist? GetActivePharmacist()
        {
            if (!_cfg.StandardNDbEnabled) return null;

            return SafeQuery(() =>
            {
                using var conn = OpenConnection();
                using var cmd = conn.CreateCommand();
                cmd.CommandTimeout = TimeoutSec();
                cmd.CommandText = @"
                    select first 1
                        a.user_id,
                        a.username as active_username,
                        a.session_id,
                        u.username as user_login,
                        u.usercode,
                        u.username_n
                    from activeusers a
                    left join users u on u.id = a.user_id
                    order by a.session_id desc";

                using var reader = cmd.ExecuteReader();
                if (!reader.Read()) return null;

                var userId = ReadString(reader, "USER_ID");
                if (string.IsNullOrWhiteSpace(userId)) return null;

                return new StandardNActivePharmacist(
                    userId,
                    FirstNotEmpty(
                        ReadString(reader, "USERCODE"),
                        ReadString(reader, "USERNAME_N"),
                        ReadString(reader, "USER_LOGIN"),
                        ReadString(reader, "ACTIVE_USERNAME")),
                    ReadInt64(reader, "SESSION_ID"));
            });
        }

        public StandardNProductInfo? GetProduct(int partId, string? barcode)
        {
            if (!_cfg.StandardNDbEnabled || partId <= 0) return null;
            return SafeQuery(() => QueryProduct(partId, barcode));
        }

        private StandardNProductInfo? QueryProduct(int partId, string? barcode)
        {
            using var conn = OpenConnection();

            var byPart = QueryProductFromWarebase(conn, partId, null);
            if (byPart != null) return byPart;

            if (!string.IsNullOrWhiteSpace(barcode))
            {
                var byBarcode = QueryProductFromWarebase(conn, 0, barcode);
                if (byBarcode != null) return byBarcode;
            }

            using var cmd = conn.CreateCommand();
            cmd.CommandTimeout = TimeoutSec();
            cmd.CommandText = @"
                select first 1
                    p.part_id,
                    p.price,
                    cast(null as varchar(250)) as barcode,
                    cast(null as varchar(1024)) as sname
                from prices p
                where p.part_id = @partId
                  and p.price_type = 0
                  and p.price > 0
                order by p.id desc";
            cmd.Parameters.AddWithValue("@partId", partId);
            return ReadProduct(cmd, "PRICES");
        }

        private StandardNProductInfo? QueryProductFromWarebase(FbConnection conn, int partId, string? barcode)
        {
            using var cmd = conn.CreateCommand();
            cmd.CommandTimeout = TimeoutSec();
            if (partId > 0)
            {
                cmd.CommandText = @"
                    select first 1
                        v.part_id,
                        v.price,
                        v.barcode,
                        v.sname
                    from vw_warebase_kassa v
                    where v.part_id = @partId
                      and v.price > 0
                    order by v.updatedt desc";
                cmd.Parameters.AddWithValue("@partId", partId);
            }
            else
            {
                cmd.CommandText = @"
                    select first 1
                        v.part_id,
                        v.price,
                        v.barcode,
                        v.sname
                    from vw_warebase_kassa v
                    where (v.barcode = @barcode or v.barcode1 = @barcode)
                      and v.price > 0
                    order by v.updatedt desc";
                cmd.Parameters.AddWithValue("@barcode", barcode);
            }

            return ReadProduct(cmd, "VW_WAREBASE_KASSA");
        }

        private StandardNProductInfo? ReadProduct(FbCommand cmd, string source)
        {
            using var reader = cmd.ExecuteReader();
            if (!reader.Read()) return null;

            var partId = ReadInt32(reader, "PART_ID");
            var price = ReadDecimal(reader, "PRICE");
            if (price <= 0m) return null;

            return new StandardNProductInfo(
                partId,
                ReadString(reader, "BARCODE"),
                ReadString(reader, "SNAME"),
                price,
                source);
        }

        private FbConnection OpenConnection()
        {
            var dbPath = ResolveDbPath(logIfMissing: true);
            if (string.IsNullOrWhiteSpace(dbPath))
                throw new InvalidOperationException("ztrade не найден");

            var csb = new FbConnectionStringBuilder
            {
                DataSource = _cfg.StandardNDbHost,
                Port = _cfg.StandardNDbPort,
                Database = dbPath,
                UserID = _cfg.StandardNDbUser,
                Password = _cfg.StandardNDbPassword,
                Charset = "WIN1251",
                Pooling = true,
                ConnectionTimeout = TimeoutSec(),
            };
            var conn = new FbConnection(csb.ToString());
            conn.Open();
            return conn;
        }

        private string? ResolveDbPath(bool logIfMissing)
        {
            if (!string.IsNullOrWhiteSpace(_resolvedDbPath)) return _resolvedDbPath;

            // 1) Явный путь из конфига — высший приоритет.
            if (!string.IsNullOrWhiteSpace(_cfg.StandardNDbPath))
            {
                _resolvedDbPath = _cfg.StandardNDbPath.Trim();
                return _resolvedDbPath;
            }

            // 2) Авто-поиск из РОДНОГО options.ini кассы ([Connect] base=…\ztrade.fdb) — так путь
            //    находится на любой боевой установке Стандарт-Н, а не только по демо-путям.
            var fromIni = TryDiscoverFromOptionsIni();
            if (!string.IsNullOrWhiteSpace(fromIni))
            {
                _resolvedDbPath = fromIni;
                if (logIfMissing) _log($"БД Стандарт-Н найдена по options.ini: {fromIni}");
                return _resolvedDbPath;
            }

            // 3) Демо-пути (fallback для VM).
            foreach (var p in DefaultDbPaths)
            {
                try
                {
                    if (File.Exists(p)) { _resolvedDbPath = p; return _resolvedDbPath; }
                }
                catch { }
            }

            if (logIfMissing)
                LogErrorRateLimited("БД Стандарт-Н не найдена: укажи StandardNDbPath в posm.json");
            return null;
        }

        /// <summary>
        /// Ищет путь к ztrade.fdb, читая `Kassir\options.ini` кассы: секция [Connect], ключ base=.
        /// Перебирает типовые корни Стандарт-Н + верхнеуровневые папки C:\ с именами Standart/Apteka.
        /// Всё best-effort: любая ошибка → null (POSM не должен падать из-за поиска БД).
        /// </summary>
        private string? TryDiscoverFromOptionsIni()
        {
            try
            {
                var iniCandidates = new System.Collections.Generic.List<string>();
                var roots = new System.Collections.Generic.List<string>
                {
                    @"C:\Standart-N_DEMO", @"C:\Standart-N", @"C:\StandartN", @"C:\Standart_N",
                    @"C:\Program Files\Standart-N", @"C:\Program Files (x86)\Standart-N",
                };
                try
                {
                    foreach (var d in Directory.GetDirectories(@"C:\"))
                    {
                        var n = Path.GetFileName(d);
                        if (n.IndexOf("standart", StringComparison.OrdinalIgnoreCase) >= 0 ||
                            n.IndexOf("apteka", StringComparison.OrdinalIgnoreCase) >= 0 ||
                            n.IndexOf("kassir", StringComparison.OrdinalIgnoreCase) >= 0)
                            roots.Add(d);
                    }
                }
                catch { }

                foreach (var root in roots)
                {
                    try
                    {
                        if (!Directory.Exists(root)) continue;
                        iniCandidates.Add(Path.Combine(root, "Kassir", "options.ini"));
                        // Стандарт-Н часто ставится в подпапку («Apteka_KZ DEMO» и т.п.).
                        foreach (var sub in Directory.GetDirectories(root))
                            iniCandidates.Add(Path.Combine(sub, "Kassir", "options.ini"));
                    }
                    catch { }
                }

                var cp1251 = Encoding.GetEncoding(1251);
                foreach (var ini in iniCandidates)
                {
                    try
                    {
                        if (!File.Exists(ini)) continue;
                        foreach (var raw in File.ReadAllLines(ini, cp1251))
                        {
                            var line = raw.Trim();
                            if (!line.StartsWith("base", StringComparison.OrdinalIgnoreCase)) continue;
                            var eq = line.IndexOf('=');
                            if (eq < 0) continue;
                            var val = line.Substring(eq + 1).Trim();
                            // Формат может быть «host:C:\...\ztrade.fdb» — берём часть после последнего «:» с диском.
                            var colon = val.IndexOf(":\\", StringComparison.Ordinal);
                            if (colon > 0) val = val.Substring(colon - 1);
                            if (val.EndsWith(".fdb", StringComparison.OrdinalIgnoreCase) && File.Exists(val))
                                return val;
                        }
                    }
                    catch { }
                }
            }
            catch { }
            return null;
        }

        private T? SafeQuery<T>(Func<T?> query) where T : class
        {
            try
            {
                return query();
            }
            catch (Exception ex)
            {
                LogErrorRateLimited($"БД Стандарт-Н недоступна: {ex.Message}");
                return null;
            }
        }

        private void LogErrorRateLimited(string message)
        {
            var now = DateTimeOffset.UtcNow;
            if (now - _lastErrorLog < TimeSpan.FromSeconds(60)) return;
            _lastErrorLog = now;
            _log(message);
        }

        private int TimeoutSec() => Math.Max(1, (int)Math.Ceiling(_cfg.StandardNDbTimeoutMs / 1000.0));

        private static string? FirstNotEmpty(params string?[] values)
        {
            foreach (var v in values)
                if (!string.IsNullOrWhiteSpace(v))
                    return v.Trim();
            return null;
        }

        private static string? ReadString(IDataRecord reader, string name)
        {
            var value = ReadValue(reader, name);
            return value == null ? null : Convert.ToString(value, CultureInfo.InvariantCulture)?.Trim();
        }

        private static long? ReadInt64(IDataRecord reader, string name)
        {
            var value = ReadValue(reader, name);
            return value == null ? null : Convert.ToInt64(value, CultureInfo.InvariantCulture);
        }

        private static int ReadInt32(IDataRecord reader, string name)
        {
            var value = ReadValue(reader, name);
            return value == null ? 0 : Convert.ToInt32(value, CultureInfo.InvariantCulture);
        }

        private static decimal ReadDecimal(IDataRecord reader, string name)
        {
            var value = ReadValue(reader, name);
            return value == null ? 0m : Convert.ToDecimal(value, CultureInfo.InvariantCulture);
        }

        private static object? ReadValue(IDataRecord reader, string name)
        {
            try
            {
                var ordinal = reader.GetOrdinal(name);
                return reader.IsDBNull(ordinal) ? null : reader.GetValue(ordinal);
            }
            catch (IndexOutOfRangeException)
            {
                return null;
            }
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            try { FbConnection.ClearAllPools(); } catch { }
        }
    }

    public sealed record StandardNActivePharmacist(string Id, string? Name, long? SessionId);

    public sealed record StandardNProductInfo(
        int PartId,
        string? Barcode,
        string? Name,
        decimal Price,
        string Source);
}
