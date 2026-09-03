import { randomUUID } from "node:crypto";
import { createPushPayload } from "./send";
import { sendToNativeDevices } from "./apns";
import { nativeDevicesForOrder } from "./native-store";
import { appendHistory } from "./store";

export async function notifyNativeOrderCreated(orderNumber: string): Promise<void> {
  const targets = await nativeDevicesForOrder(orderNumber);
  if (!targets.length) return;
  const id = randomUUID();
  const payload = createPushPayload(
    "order.created",
    { orderNumber, tag: `order-${orderNumber}` },
    {
      title: `Заказ ${orderNumber} оформлен`,
      body: "Мы приняли заказ и скоро начнём его собирать.",
      url: "/account/orders",
      tag: `order-${orderNumber}`,
    },
    id,
  );
  const { summary } = await sendToNativeDevices(targets, payload);
  await appendHistory({
    id,
    kind: "event",
    event: "order.created",
    orderNumber,
    payload,
    delivery: summary,
    createdAt: payload.createdAt,
  });
}
