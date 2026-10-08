/** Keeps only the last path segment and drops control characters; null when nothing remains. */
function basename(name: string): string | null {
  // eslint-disable-next-line no-control-regex
  const cleaned = name.replace(/[\u0000-\u001f\u007f]/g, '');
  const base = cleaned.slice(Math.max(cleaned.lastIndexOf('/'), cleaned.lastIndexOf('\\')) + 1).trim();
  return base === '' ? null : base;
}

/** The file name a response suggests (RFC 6266: filename* wins over filename), or null. */
export function filenameOf(header: string | null): string | null {
  if (!header) {
    return null;
  }
  const extended = /(?:^|;)\s*filename\*\s*=\s*UTF-8''([^;]+)/i.exec(header);
  if (extended) {
    try {
      const name = basename(decodeURIComponent(extended[1].trim()));
      if (name) {
        return name;
      }
    } catch {
      // fall through to the plain parameter
    }
  }
  const plain = /(?:^|;)\s*filename\s*=\s*("([^"]*)"|[^;]+)/i.exec(header);
  if (!plain) {
    return null;
  }
  return basename(plain[2] ?? plain[1]);
}

/** Hands a downloaded file to the browser through a temporary object URL. */
export function saveBlob(blob: Blob, name: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  // some browsers yield an empty download when the URL is revoked while the download starts
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

/** Tag check instead of instanceof: a Blob from another realm (jsdom tests) is still a Blob. */
export function isBlob(value: unknown): value is Blob {
  return Object.prototype.toString.call(value) === '[object Blob]';
}
