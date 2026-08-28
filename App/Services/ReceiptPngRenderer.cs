using System;
using System.Globalization;
using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// Реконструированная PNG-копия состава чека Epharm. Это намеренно не копия фискального
    /// документа: POSM не подменяет драйвер ККМ и не заявляет отсутствующие фискальные реквизиты.
    /// </summary>
    public sealed class ReceiptPngRenderer : IReceiptArtifactRenderer
    {
        private const int Width = 720;
        private const double Margin = 42;
        private static readonly Typeface Regular = new("Segoe UI");
        private static readonly Typeface Bold = new(new FontFamily("Segoe UI"), FontStyles.Normal, FontWeights.SemiBold, FontStretches.Normal);
        private static readonly Brush Ink = new SolidColorBrush(Color.FromRgb(33, 30, 27));
        private static readonly Brush Muted = new SolidColorBrush(Color.FromRgb(104, 98, 91));
        private static readonly Pen Rule = new(new SolidColorBrush(Color.FromRgb(218, 213, 207)), 1);

        public void Render(SaleReport sale, string outputPath)
        {
            var itemHeight = 86d;
            // Header/footer need about 400px together. Keep a small bottom safety margin so the
            // sale id and capture source are never clipped by RenderTargetBitmap.
            var height = (int)Math.Ceiling(420 + Math.Max(1, sale.Items.Count) * itemHeight);
            var visual = new DrawingVisual();
            using (var dc = visual.RenderOpen())
            {
                dc.DrawRectangle(Brushes.White, null, new Rect(0, 0, Width, height));
                var y = 34d;
                Draw(dc, "EPHARM", 28, Bold, Ink, Margin, y, Width - Margin * 2);
                y += 42;
                Draw(dc, "КОПИЯ СОСТАВА ЧЕКА", 20, Bold, Ink, Margin, y, Width - Margin * 2);
                y += 34;
                Draw(dc, "Не является фискальным чеком", 15, Regular, Muted, Margin, y, Width - Margin * 2);
                y += 36;
                dc.DrawLine(Rule, new Point(Margin, y), new Point(Width - Margin, y));
                y += 20;

                Draw(dc, $"Аптека: {sale.PharmacyId}", 15, Regular, Ink, Margin, y, Width - Margin * 2);
                y += 25;
                Draw(dc, $"Документ Standard-N: {sale.SourceDocumentId?.ToString() ?? "не определён"}", 15, Regular, Ink, Margin, y, Width - Margin * 2);
                y += 25;
                var seller = string.IsNullOrWhiteSpace(sale.PharmacistName) ? sale.PharmacistId : sale.PharmacistName;
                Draw(dc, $"Фармацевт: {(string.IsNullOrWhiteSpace(seller) ? "не определён" : seller)}", 15, Regular, Ink, Margin, y, Width - Margin * 2);
                y += 25;
                Draw(dc, $"Завершён: {sale.PrintedAt.ToLocalTime():dd.MM.yyyy HH:mm:ss}", 15, Regular, Ink, Margin, y, Width - Margin * 2);
                y += 34;
                dc.DrawLine(Rule, new Point(Margin, y), new Point(Width - Margin, y));
                y += 14;

                foreach (var item in sale.Items)
                {
                    Draw(dc, item.Name, 17, Bold, Ink, Margin, y, Width - Margin * 2, maxLines: 2);
                    y += 42;
                    var key = !string.IsNullOrWhiteSpace(item.Barcode)
                        ? $"EAN {item.Barcode}"
                        : $"iPartID {item.Sku ?? "—"}";
                    Draw(dc, key, 13, Regular, Muted, Margin, y, 280);
                    DrawRight(dc, $"{item.Qty:0.###} × {item.Price:N0} = {item.Total:N0} KZT", 15, Bold, Ink, Width - Margin, y);
                    y += 30;
                    dc.DrawLine(Rule, new Point(Margin, y), new Point(Width - Margin, y));
                    y += 14;
                }

                y += 6;
                Draw(dc, "ИТОГО", 18, Bold, Ink, Margin, y, 200);
                DrawRight(dc, $"{sale.TotalAmount:N0} KZT", 24, Bold, Ink, Width - Margin, y - 4);
                y += 54;
                Draw(dc, $"ID: {sale.SaleId}", 12, Regular, Muted, Margin, y, Width - Margin * 2);
                y += 22;
                Draw(dc, $"Источник: {sale.CaptureSource ?? "unknown"}", 12, Regular, Muted, Margin, y, Width - Margin * 2);
            }

            var bitmap = new RenderTargetBitmap(Width, height, 96, 96, PixelFormats.Pbgra32);
            bitmap.Render(visual);
            var encoder = new PngBitmapEncoder();
            encoder.Frames.Add(BitmapFrame.Create(bitmap));
            using var stream = new FileStream(outputPath, FileMode.CreateNew, FileAccess.Write, FileShare.None);
            encoder.Save(stream);
            stream.Flush(flushToDisk: true);
        }

        private static void Draw(
            DrawingContext dc,
            string text,
            double size,
            Typeface typeface,
            Brush brush,
            double x,
            double y,
            double maxWidth,
            int maxLines = 1)
        {
            var formatted = new FormattedText(
                text ?? "",
                CultureInfo.GetCultureInfo("ru-RU"),
                FlowDirection.LeftToRight,
                typeface,
                size,
                brush,
                1.0)
            {
                MaxTextWidth = Math.Max(1, maxWidth),
                MaxLineCount = Math.Max(1, maxLines),
                Trimming = TextTrimming.CharacterEllipsis,
            };
            dc.DrawText(formatted, new Point(x, y));
        }

        private static void DrawRight(DrawingContext dc, string text, double size, Typeface typeface, Brush brush, double right, double y)
        {
            var formatted = new FormattedText(
                text,
                CultureInfo.GetCultureInfo("ru-RU"),
                FlowDirection.LeftToRight,
                typeface,
                size,
                brush,
                1.0);
            dc.DrawText(formatted, new Point(right - formatted.WidthIncludingTrailingWhitespace, y));
        }
    }
}
