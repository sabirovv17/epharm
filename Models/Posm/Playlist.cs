using System.Collections.Generic;

namespace CustomerDisplay.Models.Posm
{
    /// <summary>Слайд активного плейлиста (Stage 3). Зеркалит kz.epharm.screens.dto.ActiveSlideDto.</summary>
    public sealed class ActiveSlide
    {
        public string Url { get; set; } = "";
        public string Kind { get; set; } = "";   // video | image
        public int DurationSec { get; set; }
        public string Title { get; set; } = "";
    }

    /// <summary>
    /// Активный плейлист 2-го монитора, заданный из админки. POSM-клиент крутит Slides в цикле
    /// вместо захардкоженного promo.mp4. Если backend недоступен — остаётся текущий/последний
    /// закешированный локальный плейлист.
    /// </summary>
    public sealed class ActivePlaylist
    {
        public string? PlaylistId { get; set; }
        public string Name { get; set; } = "";
        public List<ActiveSlide> Slides { get; set; } = new();
    }
}
