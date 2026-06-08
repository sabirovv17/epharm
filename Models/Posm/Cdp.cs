namespace CustomerDisplay.Models.Posm
{
    // CDP (профиль клиента лояльности, §5.6). Зеркалит kz.epharm.cdp.dto.*

    public sealed class CdpLookupRequestDto
    {
        public string Phone { get; set; } = "";
    }

    public sealed class CdpLookupResult
    {
        public bool Found { get; set; }
        public string? ProfileId { get; set; }
        public string Phone { get; set; } = "";
        public string? Name { get; set; }
        public string? Tier { get; set; }
    }

    public sealed class CdpRegisterRequestDto
    {
        public string Phone { get; set; } = "";
        public string? Name { get; set; }
        public string? PharmacistId { get; set; }
        public string? PharmacyId { get; set; }
    }

    public sealed class CdpProfile
    {
        public string Id { get; set; } = "";
        public string Phone { get; set; } = "";
        public string? Name { get; set; }
        public string Tier { get; set; } = "";
        public bool IsNew { get; set; }
    }
}
