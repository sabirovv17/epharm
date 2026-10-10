const controller = new AbortController();
const timeout = setTimeout(() => controller.abort(), 4_000);
try {
  const response = await fetch('http://127.0.0.1:8080/health', {
    headers: { authorization: `Bearer ${process.env.EXPORT_TOKEN || ''}` },
    signal: controller.signal,
  });
  process.exitCode = response.ok ? 0 : 1;
} catch {
  process.exitCode = 1;
} finally {
  clearTimeout(timeout);
}
