using CustomerDisplay.Models.Posm;
using Xunit;

namespace CustomerDisplay.Tests;

public sealed class RecommendationPopupModelTests
{
    [Fact]
    public void Build_PreservesExactCountsAndCapsEachKindAtFive()
    {
        var source = Enumerable.Range(1, 7)
            .Select(i => Recommendation("substitution", $"Replacement {i}", "Trigger A", 1_000 + i))
            .Concat(Enumerable.Range(1, 6)
                .Select(i => Recommendation("crosssell", $"Cross-sell {i}", "Trigger B", 2_000 + i)))
            .ToList();

        var model = RecommendationPopupModelBuilder.Build(source);

        Assert.Equal(5, model.Substitutions.Rows.Count);
        Assert.Equal(5, model.CrossSells.Rows.Count);
        Assert.Equal(10, model.TotalCount);
        Assert.Equal("Replacement 1", model.Substitutions.Rows[0].Name);
        Assert.Equal("Cross-sell 5", model.CrossSells.Rows[4].Name);
    }

    [Fact]
    public void Build_UsesSharedTriggerAndTengeFormatting()
    {
        var model = RecommendationPopupModelBuilder.Build(new[]
        {
            Recommendation("substitution", "Аналог 1", "Исходный", 12_490),
            Recommendation("substitution", "Аналог 2", "Исходный", 8_900),
        });

        Assert.Equal("Исходный · 20 мг", model.Substitutions.TriggerText);
        Assert.All(model.Substitutions.Rows, row => Assert.Equal("", row.TriggerContext));
        Assert.Equal("12 490 ₸", model.Substitutions.Rows[0].Price);
        Assert.Equal("+350 ₸ вам", model.Substitutions.Rows[0].Bonus);
    }

    [Fact]
    public void Build_LabelsEachRowWhenSectionHasDifferentTriggers()
    {
        var model = RecommendationPopupModelBuilder.Build(new[]
        {
            Recommendation("crosssell", "Допродажа 1", "Товар A", 700),
            Recommendation("crosssell", "Допродажа 2", "Товар B", 800),
        });

        Assert.False(model.CrossSells.HasSharedTrigger);
        Assert.Equal("К товару: Товар A · 20 мг", model.CrossSells.Rows[0].TriggerContext);
        Assert.Equal("К товару: Товар B · 20 мг", model.CrossSells.Rows[1].TriggerContext);
    }

    private static Recommendation Recommendation(string kind, string name, string trigger, int price) => new()
    {
        Kind = kind,
        RecommendName = name,
        RecommendVendor = "INCAR",
        RecommendVolume = "30 табл.",
        RecommendPrice = price,
        Bonus = 350,
        TriggerName = trigger,
        TriggerVolume = "20 мг",
        Script = "Короткий аргумент",
    };
}
