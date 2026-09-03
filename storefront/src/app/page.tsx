import { Hero } from "@/components/home/Hero";
import { ProblemCollections } from "@/components/home/ProblemCollections";
import { Features } from "@/components/home/Features";
import { QuickActions } from "@/components/home/QuickActions";
import { BestsellerFeature } from "@/components/home/BestsellerFeature";
import { CategoryGrid } from "@/components/home/CategoryGrid";
import { PromoGrid } from "@/components/home/PromoGrid";
import { ProductRow } from "@/components/home/ProductRow";
import { BrandStrip } from "@/components/home/BrandStrip";
import { LoyaltyBanner } from "@/components/home/LoyaltyBanner";
import { Reveal } from "@/components/ui/Reveal";
import { getBestsellers, getDeals, getNewArrivals, getBrands } from "@/lib/api";

export const dynamic = "force-dynamic";
export const revalidate = 0;

export default async function Home() {
  const [bestsellers, deals, newArrivals, brandList] = await Promise.all([getBestsellers(), getDeals(), getNewArrivals(), getBrands()]);

  return (
    <div className="space-y-14 pb-4 sm:space-y-16">
      <Hero />
      <Reveal><ProblemCollections /></Reveal>
      <Reveal><Features /></Reveal>
      <Reveal><QuickActions /></Reveal>
      <Reveal><CategoryGrid /></Reveal>
      <Reveal><PromoGrid /></Reveal>
      <Reveal><BestsellerFeature /></Reveal>
      <Reveal><ProductRow titleKey="home.best.title" subtitleKey="home.best.sub" products={bestsellers} /></Reveal>
      <Reveal><LoyaltyBanner /></Reveal>
      <Reveal>
        <ProductRow
          titleKey={deals.length > 0 ? "home.deals.title" : "home.catalog.title"}
          subtitleKey={deals.length > 0 ? "home.deals.sub" : "home.catalog.sub"}
          products={deals.length > 0 ? deals : newArrivals}
        />
      </Reveal>
      <Reveal><BrandStrip brands={brandList} /></Reveal>
    </div>
  );
}
