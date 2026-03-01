export function fmtDate(iso: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('en-GB', {
    dateStyle: 'short',
    timeStyle: 'medium',
  });
}
