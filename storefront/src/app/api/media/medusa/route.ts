import { proxyMedusaMedia } from "../../../../lib/medusa-media-proxy.ts";

export const dynamic = "force-dynamic";
export const runtime = "nodejs";

export async function GET(request: Request): Promise<Response> {
  if (process.env.MEDUSA_ENABLED !== "true") {
    return Response.json({ error: "remote_media_disabled" }, { status: 410 });
  }
  return proxyMedusaMedia(request, "GET");
}

export async function HEAD(request: Request): Promise<Response> {
  if (process.env.MEDUSA_ENABLED !== "true") {
    return new Response(null, { status: 410 });
  }
  return proxyMedusaMedia(request, "HEAD");
}
