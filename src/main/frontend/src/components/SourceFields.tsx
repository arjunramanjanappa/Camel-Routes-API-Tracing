import type { SourceType } from '../types';

export interface SourceState {
  sourceType: SourceType;
  sourceDir: string;
  repo: string;
  branch: string;
}

/** Is the source fully specified for the current mode? (Trace / Load / Compare gate.) */
export function sourceValid(s: SourceState): boolean {
  return s.sourceType === 'bitbucket'
    ? !!(s.repo && s.repo.trim() && s.branch && s.branch.trim())
    : !!(s.sourceDir && s.sourceDir.trim());
}

/** Only the active mode's fields are sent — never both (the backend picks Bitbucket when repo is set). */
export function sourceParams(s: SourceState): { sourceDir?: string; repo?: string; branch?: string } {
  return s.sourceType === 'bitbucket'
    ? { repo: s.repo, branch: s.branch, sourceDir: '' }
    : { sourceDir: s.sourceDir, repo: '', branch: '' };
}

interface Props {
  value: SourceState;
  onChange: (patch: Partial<SourceState>) => void;
  /** true → widths for the horizontal context-bar (Release Test / Release Impact); false → full-width stack (Release Scope). */
  bar?: boolean;
}

const REQ = <span style={{ color: '#dc2626' }}>*</span>;

/** The Local-path / Bitbucket-branch source selector, shared by all three tabs. */
export default function SourceFields({ value, onChange, bar = false }: Props) {
  const s = value;
  const repoStyle = bar ? { minWidth: 260, flex: 1 } : undefined;
  const branchStyle = bar ? { width: 150 } : undefined;
  const dirStyle = bar ? { minWidth: 230, flex: 1 } : undefined;
  return (
    <>
      <div>
        <label>Source</label>
        <div className="seg">
          <button className={s.sourceType === 'local' ? 'on' : ''} onClick={() => onChange({ sourceType: 'local' })}>Local path</button>
          <button className={s.sourceType === 'bitbucket' ? 'on' : ''} onClick={() => onChange({ sourceType: 'bitbucket' })}>Bitbucket branch</button>
        </div>
      </div>
      {s.sourceType === 'bitbucket' ? (
        <>
          <div style={repoStyle}>
            <label>Bitbucket repo {REQ}</label>
            <input value={s.repo} placeholder="https://bitbucket.internal/scm/PROJ/repo.git"
                   onChange={(e) => onChange({ repo: e.target.value })} />
          </div>
          <div style={branchStyle}>
            <label>Branch / Tag {REQ}</label>
            <input value={s.branch} placeholder="release/9.18"
                   onChange={(e) => onChange({ branch: e.target.value })} />
          </div>
        </>
      ) : (
        <div style={dirStyle}>
          <label>Source directory {REQ}</label>
          <input value={s.sourceDir} placeholder="path to the framework source"
                 onChange={(e) => onChange({ sourceDir: e.target.value })} />
        </div>
      )}
    </>
  );
}
