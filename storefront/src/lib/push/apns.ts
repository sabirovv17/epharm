import { createSign } from "node:crypto";
import { connect } from "node:http2";
import { applyNativeDeliveryResults, type NativePushDevice } from "./native-store";
import type { DeliveryResult, DeliverySummary, PushNotificationPayload } from "./types";

type Config = {
  host: string;
  keyId: string;
  teamId: string;
  bundleId: string;
  privateKey: string;
};

let cachedJwt: { value: string; issuedAt: number } | null = null;

function base64url(value: string | Buffer): string {
  return Buffer.from(value).toString("base64url");
}

function config(): Config {
  const keyId = process.env.APNS_KEY_ID?.trim() || "";
  const teamId = process.env.APNS_TEAM_ID?.trim() || "";
  const bundleId = process.env.APNS_BUNDLE_ID?.trim() || "";
  const encodedPrivateKey = process.env.APNS_PRIVATE_KEY_BASE64?.trim() || "";
  let privateKey = (process.env.APNS_PRIVATE_KEY || "").replace(/\\n/g, "\n").trim();
  if (!privateKey && encodedPrivateKey) {
    try {
      privateKey = Buffer.from(encodedPrivateKey, "base64").toString("utf8").trim();
    } catch {
      privateKey = "";
    }
  }
  if (!keyId || !teamId || !bundleId || !privateKey.includes("BEGIN PRIVATE KEY")) {
    throw new ApnsNotConfiguredError();
  }
  return {
    host: process.env.APNS_PRODUCTION === "false"
      ? "https://api.sandbox.push.apple.com"
      : "https://api.push.apple.com",
    keyId,
    teamId,
    bundleId,
    privateKey,
  };
}

export function apnsConfigured(): boolean {
  try {
    config();
    return true;
  } catch {
    return false;
  }
}

function jwt(c: Config): string {
  const now = Math.floor(Date.now() / 1000);
  if (cachedJwt && now - cachedJwt.issuedAt < 2_400) return cachedJwt.value;
  const header = base64url(JSON.stringify({ alg: "ES256", kid: c.keyId }));
  const claims = base64url(JSON.stringify({ iss: c.teamId, iat: now }));
  const unsigned = `${header}.${claims}`;
  const signer = createSign("SHA256");
  signer.update(unsigned);
  signer.end();
  const signature = signer.sign({ key: c.privateKey, dsaEncoding: "ieee-p1363" });
  cachedJwt = { value: `${unsigned}.${base64url(signature)}`, issuedAt: now };
  return cachedJwt.value;
}

function body(payload: PushNotificationPayload): Buffer {
  const value = {
    aps: {
      alert: { title: payload.title, body: payload.body },
      sound: "default",
      ...(payload.tag ? { "thread-id": payload.tag } : {}),
    },
    type: payload.type,
    url: payload.url,
    notificationId: payload.id,
    ...(payload.orderNumber ? { orderNumber: payload.orderNumber } : {}),
    ...(payload.status ? { status: payload.status } : {}),
  };
  const encoded = Buffer.from(JSON.stringify(value), "utf8");
  if (encoded.length > 4_096) throw new Error("apns_payload_too_large");
  return encoded;
}

function deliver(device: NativePushDevice, payload: PushNotificationPayload, c: Config): Promise<DeliveryResult> {
  return new Promise((resolve) => {
    const client = connect(c.host);
    const timeout = setTimeout(() => {
      client.destroy();
      resolve({ endpoint: device.id, ok: false, statusCode: 0, error: "apns_timeout", revoked: false });
    }, 10_000);
    let settled = false;
    const finish = (result: DeliveryResult) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      client.close();
      resolve(result);
    };
    client.once("error", (error) => finish({
      endpoint: device.id, ok: false, statusCode: 0,
      error: error.message.slice(0, 300), revoked: false,
    }));
    const request = client.request({
      ":method": "POST",
      ":path": `/3/device/${device.token}`,
      authorization: `bearer ${jwt(c)}`,
      "apns-topic": c.bundleId,
      "apns-push-type": "alert",
      "apns-priority": payload.type.startsWith("order.") ? "10" : "5",
      "apns-expiration": String(Math.floor(Date.now() / 1000) + (payload.type.startsWith("order.") ? 86_400 : 3_600)),
      ...(payload.tag ? { "apns-collapse-id": payload.tag.slice(0, 64) } : {}),
    });
    const chunks: Buffer[] = [];
    let statusCode = 0;
    request.on("response", (headers) => { statusCode = Number(headers[":status"] || 0); });
    request.on("data", (chunk: Buffer) => chunks.push(chunk));
    request.on("error", (error) => finish({
      endpoint: device.id, ok: false, statusCode,
      error: error.message.slice(0, 300), revoked: false,
    }));
    request.on("end", () => {
      const response = Buffer.concat(chunks).toString("utf8");
      let reason = "";
      try { reason = String((JSON.parse(response) as { reason?: unknown }).reason || ""); } catch {}
      finish({
        endpoint: device.id,
        ok: statusCode === 200,
        statusCode,
        error: statusCode === 200 ? undefined : (reason || `APNs HTTP ${statusCode}`).slice(0, 300),
        revoked: statusCode === 410 || reason === "BadDeviceToken" || reason === "Unregistered",
      });
    });
    request.end(body(payload));
  });
}

export class ApnsNotConfiguredError extends Error {
  constructor() {
    super("APNs token signing is not configured");
    this.name = "ApnsNotConfiguredError";
  }
}

export async function sendToNativeDevices(
  devices: NativePushDevice[],
  payload: PushNotificationPayload,
): Promise<{ summary: DeliverySummary; results: DeliveryResult[] }> {
  if (!devices.length) {
    return { summary: { targets: 0, sent: 0, failed: 0, revoked: 0 }, results: [] };
  }
  const c = config();
  const unique = [...new Map(devices.map((device) => [device.token, device])).values()];
  const results: DeliveryResult[] = [];
  for (let index = 0; index < unique.length; index += 20) {
    results.push(...await Promise.all(unique.slice(index, index + 20).map((device) => deliver(device, payload, c))));
  }
  await applyNativeDeliveryResults(results);
  return {
    summary: {
      targets: results.length,
      sent: results.filter((result) => result.ok).length,
      failed: results.filter((result) => !result.ok).length,
      revoked: results.filter((result) => result.revoked).length,
    },
    results,
  };
}
