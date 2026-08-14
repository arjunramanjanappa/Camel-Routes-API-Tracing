import { COUNTRIES } from '../countries';

/** The Country field — a fixed dropdown (SG / TH / MY / ID / VN), shared by every tab. */
export default function CountrySelect({ value, onChange, id }: { value: string; onChange: (c: string) => void; id?: string }) {
  return (
    <select id={id} value={value} onChange={(e) => onChange(e.target.value)}>
      {COUNTRIES.map((c) => <option key={c} value={c}>{c}</option>)}
    </select>
  );
}
