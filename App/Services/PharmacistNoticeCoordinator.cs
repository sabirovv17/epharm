using System;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// Serializes non-activating pharmacist notices. Scan recommendations have priority;
    /// fulfillment events are counted and replayed after the recommendation closes.
    /// </summary>
    internal sealed class PharmacistNoticeCoordinator
    {
        private int _visibleFulfillmentCount;
        private int _pendingFulfillmentCount;
        private bool _recommendationVisible;

        public bool TryShowFulfillment(int count, out int totalCount)
        {
            totalCount = 0;
            if (count <= 0) return false;
            if (_recommendationVisible)
            {
                _pendingFulfillmentCount = SaturatingAdd(_pendingFulfillmentCount, count);
                return false;
            }

            _visibleFulfillmentCount = SaturatingAdd(_visibleFulfillmentCount, count);
            totalCount = _visibleFulfillmentCount;
            return true;
        }

        public void RecommendationStarting()
        {
            _recommendationVisible = true;
            _pendingFulfillmentCount = SaturatingAdd(_pendingFulfillmentCount, _visibleFulfillmentCount);
            _visibleFulfillmentCount = 0;
        }

        public int RecommendationClosed()
        {
            _recommendationVisible = false;
            var pending = _pendingFulfillmentCount;
            _pendingFulfillmentCount = 0;
            return pending;
        }

        public void FulfillmentDismissed() => _visibleFulfillmentCount = 0;

        private static int SaturatingAdd(int left, int right)
        {
            var sum = (long)Math.Max(0, left) + Math.Max(0, right);
            return sum > int.MaxValue ? int.MaxValue : (int)sum;
        }
    }
}
