/** The supported countries, in display order — the Country field is a fixed dropdown (not free entry). */
export const COUNTRIES = ['SG', 'TH', 'MY', 'ID', 'VN'];

/** The default country, selected when nothing valid is stored. */
export const DEFAULT_COUNTRY = 'SG';

/** Coerce a stored/entered value to a valid dropdown option, falling back to the default (SG). */
export function initialCountry(stored: string | null | undefined): string {
  const c = (stored || '').trim().toUpperCase();
  return COUNTRIES.includes(c) ? c : DEFAULT_COUNTRY;
}
