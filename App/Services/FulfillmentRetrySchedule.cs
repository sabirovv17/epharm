using System;

namespace CustomerDisplay.Services
{
    internal sealed class FulfillmentRetrySchedule
    {
        private static readonly TimeSpan MaximumDelay = TimeSpan.FromMinutes(5);
        private TimeSpan _delay = TimeSpan.FromSeconds(10);

        public DateTimeOffset NextAttemptAt { get; private set; } = DateTimeOffset.MinValue;

        public bool CanAttempt(DateTimeOffset now) => now >= NextAttemptAt;

        public TimeSpan RecordFailure(DateTimeOffset now, bool configurationBlocked)
        {
            var applied = configurationBlocked ? MaximumDelay : _delay;
            NextAttemptAt = now + applied;
            _delay = TimeSpan.FromSeconds(Math.Min(MaximumDelay.TotalSeconds, Math.Max(10, _delay.TotalSeconds * 2)));
            return applied;
        }

        public void Reset()
        {
            _delay = TimeSpan.FromSeconds(10);
            NextAttemptAt = DateTimeOffset.MinValue;
        }
    }
}
