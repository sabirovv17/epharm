using Epharm.StockService;

namespace stock_service_tests;

public class CollectorSnapshotTests
{
    private static readonly CollectorIdentity Identity = new(432, "hq-abaya-150");

    [Fact]
    public void CompleteEmptySnapshotIsValidConfirmedZero()
    {
        var now = DateTimeOffset.UtcNow;
        var snapshot = new CollectorSnapshot(Identity.HqPharmacyId, now, 0, []);

        Assert.Null(snapshot.Validate(Identity, now));
        Assert.Empty(snapshot.ToSourceStocks(Identity.ProfileId));
    }

    [Fact]
    public void MismatchedPharmacyCannotUploadAnotherPharmacySnapshot()
    {
        var now = DateTimeOffset.UtcNow;
        var snapshot = new CollectorSnapshot("other-pharmacy", now, 0, []);

        Assert.Equal("pharmacy_identity_mismatch", snapshot.Validate(Identity, now));
    }

    [Fact]
    public void OldOrTruncatedSnapshotIsRejected()
    {
        var now = DateTimeOffset.UtcNow;
        var old = new CollectorSnapshot(Identity.HqPharmacyId, now.AddMinutes(-11), 0, []);
        var truncated = new CollectorSnapshot(Identity.HqPharmacyId, now, 1, []);

        Assert.Equal("invalid_observation_time", old.Validate(Identity, now));
        Assert.Equal("invalid_row_count", truncated.Validate(Identity, now));
    }
}
