using System.Collections.Generic;
using System.Linq;
using System.Threading;

namespace CustomerDisplay.Services;

/// <summary>Keeps a slow Firebird read from overwriting a newer cashier log event.</summary>
internal sealed class ReceiptObservationGate
{
    private long _revision;
    private int _emptyReads;

    public long Revision => Interlocked.Read(ref _revision);

    public void LogChanged()
    {
        Interlocked.Increment(ref _revision);
        _emptyReads = 0;
    }

    public bool Admit(long readRevision, bool hasReceipt)
    {
        if (readRevision != Revision) return false;
        _emptyReads = hasReceipt ? 0 : _emptyReads + 1;
        return true;
    }

    public bool ShouldClearMissingReceipt(bool hasDocument, bool hasItems) =>
        _emptyReads >= 2 && (hasDocument || hasItems);

    public void ReadFailed() => _emptyReads = 0;

    public static bool CanConfirmEmptyReceipt(
        IEnumerable<string?> sessionWorkstations, string? selectedWorkstation, string machineName)
    {
        var machine = StaffSessionResolver.CanonicalComputer(machineName);
        return machine.Length > 0 &&
               StaffSessionResolver.CanonicalComputer(selectedWorkstation) == machine &&
               sessionWorkstations.Count(name => StaffSessionResolver.CanonicalComputer(name) == machine) == 1;
    }
}
