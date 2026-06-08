namespace CustomerDisplay.Models.Posm
{
    /// <summary>
    /// Текущий релиз POSM-клиента из админки (GET /api/posm/app/version). Зеркалит
    /// kz.epharm.appupdate.dto.AppVersionDto. Current=false → обновляться не нужно.
    /// </summary>
    public sealed class AppVersionInfo
    {
        public bool Current { get; set; }
        public string Version { get; set; } = "";
        public string Url { get; set; } = "";
        public string Sha256 { get; set; } = "";
        public bool Mandatory { get; set; }
        public string Notes { get; set; } = "";
    }
}
