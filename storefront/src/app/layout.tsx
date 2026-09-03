import type { Metadata } from "next";
// Самохостинг шрифтов (offline-сборка, без Google Fonts). index.css включает все
// нужные подмножества — latin + cyrillic (через unicode-range).
import "@fontsource-variable/inter";
import "@fontsource-variable/manrope";
import "./globals.css";
import { TopBar } from "@/components/layout/TopBar";
import { Header } from "@/components/layout/Header";
import { Footer } from "@/components/layout/Footer";
import { CartProvider } from "@/lib/cart/CartContext";
import { CartDrawer } from "@/components/cart/CartDrawer";
import { AuthProvider } from "@/lib/auth/AuthContext";
import { FavoritesProvider } from "@/lib/favorites/FavoritesContext";
import { AuthModal } from "@/components/auth/AuthModal";
import { ToastProvider } from "@/lib/ui/ToastContext";
import { ToastViewport } from "@/components/ui/ToastViewport";
import { ScannerProvider } from "@/lib/ui/ScannerContext";
import { ScannerModal } from "@/components/scanner/ScannerModal";
import { PharmaBackdrop } from "@/components/layout/PharmaBackdrop";
import { WelcomeModal } from "@/components/layout/WelcomeModal";
import { LanguageProvider } from "@/lib/i18n/LanguageContext";
import { CityProvider } from "@/lib/location/CityContext";
import { ContentProvider } from "@/lib/content/ContentContext";
import { CatalogDataProvider } from "@/lib/content/CatalogData";
import { PushProvider } from "@/components/push/PushProvider";

// Семейства из @fontsource-variable; CSS-переменные задаём на <html> ниже.
const FONT_VARS = {
  "--font-inter": '"Inter Variable"',
  "--font-manrope": '"Manrope Variable"',
} as React.CSSProperties;

export const metadata: Metadata = {
  title: "Аптека со склада — лекарства и косметика с доставкой за 30 минут",
  description:
    "«Аптека со склада»: лекарства, витамины, косметика и уход с доставкой за 30 минут. Кэшбэк бонусами и оригинальная продукция по Казахстану.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ru" style={FONT_VARS} data-scroll-behavior="smooth">
      <body className="flex min-h-screen flex-col overflow-x-clip bg-white font-sans text-slate-900 antialiased">
        <PharmaBackdrop />
        <LanguageProvider>
        <CityProvider>
        <ContentProvider>
        <CatalogDataProvider>
        <ToastProvider>
          <ScannerProvider>
            <AuthProvider>
              <PushProvider>
              <FavoritesProvider>
                <CartProvider>
                  <TopBar />
                  <Header />
                  <main className="flex-1">{children}</main>
                  <Footer />
                  <CartDrawer />
                  <AuthModal />
                  <ScannerModal />
                  <ToastViewport />
                  <WelcomeModal />
                </CartProvider>
              </FavoritesProvider>
              </PushProvider>
            </AuthProvider>
          </ScannerProvider>
        </ToastProvider>
        </CatalogDataProvider>
        </ContentProvider>
        </CityProvider>
        </LanguageProvider>
      </body>
    </html>
  );
}
