export default function Legend() {
  return (
    <div className="legend">
      <div><span className="dot" style={{ background: '#2563eb' }} />API</div>
      <div><span className="dot" style={{ background: '#059669' }} />Versioned route</div>
      <div><span className="dot" style={{ background: '#d97706' }} />BASE route</div>
      <div><span className="dot" style={{ background: '#0891b2' }} />Shared / host route</div>
      <div><span className="dot" style={{ background: '#ea580c' }} />Backend API</div>
      <div><span className="dot ring" />Entry route</div>
      <div><span className="dot vring" />Matches client version</div>
      <div><span className="dot barrel" />Host call (CamelHttpUri)</div>
    </div>
  );
}
