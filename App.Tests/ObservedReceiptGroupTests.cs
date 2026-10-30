using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class ObservedReceiptGroupTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "epharm-observed-" + Guid.NewGuid().ToString("N"));
    private ReceiptArtifactStore Store() => new(Path.Combine(_root, "receipts"),
        new FiscalReceiptInboxSource(Path.Combine(_root, "inbox"), ["ofd-api"]));

    [Fact]
    public void ObservedGroupSurvivesRestartWithOriginalSellerAndNoInventedPrint()
    {
        var group = Group(100);
        Assert.True(Store().SaveObservedGroup(group));
        var read = Assert.Single(Store().ReadObservedGroups());
        Assert.Equal(group.CheckoutSessionId, read.CheckoutSessionId);
        Assert.Equal(group.PharmacistId, read.PharmacistId);
        Assert.Equal(100, read.Snapshot.DocumentId);
        Assert.Equal(1, read.Snapshot.SessionId);
        Assert.Equal(100m, Assert.Single(read.Snapshot.Lines).SourceTotal);
        Assert.False(read.PrintObserved);
        Assert.Empty(read.PrintDocumentTimes);
        Assert.Empty(read.QueuedDocumentIds);
        Assert.Null(read.PendingPrint);
        Assert.False(Assert.Single(read.Snapshot.Documents!).FinalDetailsRead);
    }

    [Fact]
    public void BoundDoesNotEvictHeldGroupsButRetiredVerifiedGroupFreesCapacity()
    {
        var store = Store();
        for (var id = 1; id <= 32; id++) Assert.True(store.SaveObservedGroup(Group(id)));
        Assert.False(store.SaveObservedGroup(Group(33)));
        Assert.Equal(32, Store().ReadObservedGroups().Count);
        var first = Group(1);
        var at = DateTimeOffset.UtcNow;
        var posted = Assert.Single(first.Snapshot.Documents!) with
        { Status = 1, DeviceNumber = "synthetic-register", FiscalNumber = 1, FiscalShift = 1, FinalDetailsRead = true };
        var resolved = first with
        {
            PrintObserved = true, QueuedDocumentIds = new long[] { 1 },
            PrintDocumentTimes = new Dictionary<long, DateTimeOffset> { [1] = at },
            AnchoredPrintCueIds = new[] { Guid.NewGuid().ToString("N") },
            Snapshot = first.Snapshot with { Documents = new[] { posted } },
        };
        Assert.True(store.SaveObservedGroup(resolved));
        store.RetireObservedGroup(resolved);
        Assert.True(store.SaveObservedGroup(Group(33)));
        Assert.Equal(32, Store().ReadObservedGroups().Count);
        Assert.DoesNotContain(Store().ReadObservedGroups(), group => group.Snapshot.DocumentId == 1);
    }

    [Fact]
    public void UnknownOrUnpaidContextCannotBeRetiredOrReassigned()
    {
        var original = Group(100);
        var store = Store();
        Assert.True(store.SaveObservedGroup(original));
        Assert.Throws<InvalidDataException>(() => store.RetireObservedGroup(original));
        Assert.Throws<InvalidDataException>(() => store.SaveObservedGroup(original with { CheckoutSessionId = "sess_other" }));
        Assert.Throws<InvalidDataException>(() => store.SaveObservedGroup(original with { PharmacistName = "Other name" }));
        Assert.Single(Store().ReadObservedGroups());
    }

    [Fact]
    public void CorruptPrivateFileNeverGetsOverwrittenOrUsedAsAReceipt()
    {
        var original = Group(100);
        Assert.True(Store().SaveObservedGroup(original));
        var path = Assert.Single(Directory.GetFiles(Path.Combine(_root, "receipts", "observed-groups")));
        File.AppendAllText(path, "corruption");
        var corrupt = File.ReadAllBytes(path);
        Assert.ThrowsAny<Exception>(() => Store().ReadObservedGroups());
        Assert.ThrowsAny<Exception>(() => Store().SaveObservedGroup(original));
        Assert.Equal(corrupt, File.ReadAllBytes(path));
    }

    [Fact]
    public void ObservedGroupSymlinkCannotReadAnotherFileOrWriteOutsideStore()
    {
        var original = Group(100);
        Assert.True(Store().SaveObservedGroup(original));
        var path = Assert.Single(Directory.GetFiles(Path.Combine(_root, "receipts", "observed-groups")));
        var outside = Path.Combine(_root, "outside.json");
        File.WriteAllText(outside, "must remain unchanged");
        File.Delete(path);
        File.CreateSymbolicLink(path, outside);
        Assert.Throws<InvalidDataException>(() => Store().ReadObservedGroups());
        Assert.Throws<InvalidDataException>(() => Store().SaveObservedGroup(original));
        Assert.Equal("must remain unchanged", File.ReadAllText(outside));
    }

    internal static StandardNObservedReceiptGroup Group(long id)
    {
        var line = new StandardNReceiptLine(500 + id, 10, "4600000000012", "Synthetic item", 100m, 1m, 0m)
        { SourceDocumentId = id, SourceTotal = 100m, SignedQuantity = -1m, SignedAmount = -100m, SignedPrice = 100m };
        var snapshot = new StandardNReceiptSnapshot(id, 1, "CASH-1", null, new("cashier", "Synthetic pharmacist", 1), [line])
        { LinkId = id, Documents = [new(id, 1, id, 0, -100m, null, 0, 0, [line])] };
        return new(1, "synthetic-pharmacy", snapshot, "sess_" + id, "cashier", "Synthetic pharmacist", false, Array.Empty<long>())
        { ObservedActiveDocumentIds = new[] { id } };
    }

    public void Dispose() { if (Directory.Exists(_root)) Directory.Delete(_root, recursive: true); }
}
