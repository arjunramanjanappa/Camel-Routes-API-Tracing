// Repackage the pptxgenjs output with JSZip: drop empty-directory stub entries and
// DEFLATE-compress every part. JSZip writes a standard OPC zip that Office/Microsoft 365
// opens reliably (unlike a hand-rolled .NET zip, which Office can reject).
const fs = require('fs');
const JSZip = require('jszip');

(async () => {
  const file = process.argv[2] || 'TraceGuard.pptx';
  const zin = await JSZip.loadAsync(fs.readFileSync(file));
  const zout = new JSZip();
  const names = Object.keys(zin.files).filter((n) => !zin.files[n].dir);
  // [Content_Types].xml first (conventional for OPC).
  names.sort((a, b) => (a === '[Content_Types].xml' ? -1 : b === '[Content_Types].xml' ? 1 : a.localeCompare(b)));
  for (const name of names) {
    zout.file(name, await zin.files[name].async('nodebuffer'));
  }
  const out = await zout.generateAsync({
    type: 'nodebuffer',
    compression: 'DEFLATE',
    compressionOptions: { level: 6 },
    platform: 'DOS',
    mimeType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  });
  fs.writeFileSync(file, out);
  console.log('repacked ' + file + ' (' + names.length + ' parts, ' + out.length + ' bytes)');
})();
