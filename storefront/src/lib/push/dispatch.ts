import { ApnsNotConfiguredError, sendToNativeDevices } from "./apns";
import { sendToSubscribers, PushNotConfiguredError } from "./send";
import type { NativePushDevice } from "./native-store";
import type {
  DeliverySummary,
  PushNotificationPayload,
  StoredPushSubscription,
} from "./types";

const empty = (): DeliverySummary => ({ targets: 0, sent: 0, failed: 0, revoked: 0 });

function add(a: DeliverySummary, b: DeliverySummary): DeliverySummary {
  return {
    targets: a.targets + b.targets,
    sent: a.sent + b.sent,
    failed: a.failed + b.failed,
    revoked: a.revoked + b.revoked,
  };
}

export async function sendAcrossPushChannels(input: {
  web: StoredPushSubscription[];
  native: NativePushDevice[];
  payload: PushNotificationPayload;
}): Promise<{
  summary: DeliverySummary;
  channels: { web: DeliverySummary; ios: DeliverySummary };
  unavailable: string[];
}> {
  let web = empty();
  let ios = empty();
  const unavailable: string[] = [];

  if (input.web.length) {
    try {
      web = (await sendToSubscribers(input.web, input.payload)).summary;
    } catch (error) {
      if (!(error instanceof PushNotConfiguredError)) throw error;
      unavailable.push("web");
    }
  }
  if (input.native.length) {
    try {
      ios = (await sendToNativeDevices(input.native, input.payload)).summary;
    } catch (error) {
      if (!(error instanceof ApnsNotConfiguredError)) throw error;
      unavailable.push("ios");
    }
  }

  if (input.web.length + input.native.length > 0
      && unavailable.length === (input.web.length > 0 ? 1 : 0) + (input.native.length > 0 ? 1 : 0)) {
    throw new PushNotConfiguredError();
  }
  return { summary: add(web, ios), channels: { web, ios }, unavailable };
}
