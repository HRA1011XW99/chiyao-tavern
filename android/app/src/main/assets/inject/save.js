// Injected into the SillyTavern page by the app shell (MainActivity). WebView does not save blob:/data:
// downloads by itself, so clicks on download links are caught here and the bytes handed to the shell's
// TavernSave bridge in chunks. SillyTavern revokes the blob URL right after a.click(), so blobs are
// remembered when their URL is created rather than fetched afterwards.
(function () {
  if (window.__tavernSave) return;
  const KEY = '__KEY__';
  const blobs = new Map();
  const create = URL.createObjectURL.bind(URL);
  URL.createObjectURL = function (obj) {
    const url = create(obj);
    if (obj instanceof Blob) {
      blobs.set(url, obj);
      setTimeout(() => blobs.delete(url), 120000);
    }
    return url;
  };

  function nameFrom(href) {
    try { return decodeURIComponent(new URL(href).pathname.split('/').pop()) || 'download'; } catch { return 'download'; }
  }

  async function blobOf(href) {
    if (blobs.has(href)) return blobs.get(href);
    const r = await fetch(href, { credentials: 'same-origin' });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    return await r.blob();
  }

  async function save(href, name) {
    try {
      const b = await blobOf(href);
      const id = TavernSave.begin(KEY, name || nameFrom(href), b.type || 'application/octet-stream');
      if (id.startsWith('!')) { alert(id.slice(1)); return; }
      const CHUNK = 512 * 1024;
      for (let o = 0; o < b.size; o += CHUNK) {
        const buf = new Uint8Array(await b.slice(o, o + CHUNK).arrayBuffer());
        let s = '';
        for (let i = 0; i < buf.length; i += 0x8000) s += String.fromCharCode.apply(null, buf.subarray(i, i + 0x8000));
        const err = TavernSave.write(id, btoa(s));
        if (err) { TavernSave.end(id, false); alert(err.slice(1)); return; }
      }
      const err = TavernSave.end(id, true);
      if (err) alert(err.slice(1));
    } catch (e) {
      alert('保存失败：' + (e && e.message || e));
    }
  }

  function wanted(a) {
    const h = a.href || '';
    if (h.startsWith('blob:') || h.startsWith('data:')) return true;
    return a.hasAttribute('download') && h.startsWith(location.origin);
  }

  const click = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (wanted(this)) { save(this.href, this.getAttribute('download') || ''); return; }
    return click.call(this);
  };
  document.addEventListener('click', (e) => {
    const a = e.target && e.target.closest && e.target.closest('a[href]');
    if (!a || !wanted(a)) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    save(a.href, a.getAttribute('download') || '');
  }, true);

  // Downloads the WebView reports itself (attachment responses from the local server)
  window.__tavernSaveUrl = (href, name) => save(href, name);
  window.__tavernSave = true;
})();
