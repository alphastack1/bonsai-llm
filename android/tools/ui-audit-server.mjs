import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const toolsDir = path.dirname(fileURLToPath(import.meta.url));
const assetsDir = path.resolve(toolsDir, '../app/src/main/assets');
const indexPath = path.join(assetsDir, 'index.html');
const port = Number(process.argv[2] || 8765);

const mockPrelude = String.raw`<script>
(() => {
  const params = new URLSearchParams(location.search);
  if (params.has('reset')) localStorage.clear();
  const scenario = params.get('scenario') || 'ready';
  const installAll = params.has('all');
  const modelKey = params.get('model')
    || (['vision', 'downloading', 'downloadError', 'downloadFail'].includes(scenario) ? '27B' : '1.7B');
  const models = {
    '1.7B': {
      key: '1.7B', name: 'Bonsai 1.7B (included)', file: 'Bonsai-1.7B-Q1_0.gguf',
      url: 'https://example.invalid/1.7b', size_mb: 249,
      description: 'Included offline text model.', bundled: true, multimodal: false,
      installed: scenario !== 'fresh'
    },
    '4B': {
      key: '4B', name: 'Bonsai 4B (1-bit)', file: 'Bonsai-4B-Q1_0.gguf',
      url: 'https://example.invalid/4b', size_mb: 573,
      description: 'Fast text model for older phones.', bundled: false, multimodal: false,
      installed: installAll || (modelKey === '4B' && scenario === 'ready')
    },
    '8B': {
      key: '8B', name: 'Bonsai 8B (1-bit)', file: 'Bonsai-8B-Q1_0.gguf',
      url: 'https://example.invalid/8b', size_mb: 1159,
      description: 'Higher quality text model.', bundled: false, multimodal: false,
      installed: installAll || (modelKey === '8B' && scenario === 'ready')
    },
    '27B': {
      key: '27B', name: 'Bonsai 27B Vision (1-bit)', file: 'Bonsai-27B-Q1_0.gguf',
      url: 'https://example.invalid/27b', size_mb: 4433,
      language_size_mb: 3804, vision_size_mb: 630,
      description: '27B-class reasoning plus vision.', bundled: false, multimodal: true,
      installed: installAll || (modelKey === '27B' && ['ready', 'vision', 'error'].includes(scenario))
    }
  };
  const audit = {
    binary_installed: true, active_model: modelKey, platform: 'android', free_space_mb: 16000,
    llama_running: scenario === 'ready',
    llama_status: scenario === 'ready' ? 'ready' : scenario === 'error' ? 'error' : 'stopped',
    llama_error: scenario === 'error' ? 'Mock model-load failure' : null,
    models, downloads: {}
  };
  if (scenario === 'vision') {
    audit.llama_running = true;
    audit.llama_status = 'ready';
  }
  if (scenario === 'fresh') {
    audit.downloads['model_1.7B'] = {
      percent: 42, status: 'extracting', detail: 'Installing included Bonsai 1.7B',
      error: null, downloaded_bytes: 104286954, total_bytes: 248302272
    };
  }
  if (scenario === 'downloading') {
    audit.downloads['model_27B'] = {
      percent: 42, status: 'downloading', detail: 'Language model (1/2)', error: null,
      downloaded_bytes: 1861733731, total_bytes: 4432699360
    };
  }
  if (scenario === 'downloadError' || scenario === 'downloadFail') {
    audit.downloads['model_27B'] = {
      percent: 18, status: 'error', detail: 'Language model (1/2)',
      error: 'Mock network interruption', downloaded_bytes: 800000000, total_bytes: 4432699360
    };
  }

  window.__audit = { calls: [], alerts: [], confirms: [], clipboard: '', lastChat: null, xss: false };
  window.alert = message => window.__audit.alerts.push(String(message));
  window.confirm = message => { window.__audit.confirms.push(String(message)); return true; };
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText: async text => { window.__audit.clipboard = String(text); } }
  });

  window.BonsaiNative = {
    getStatus() { return JSON.stringify(audit); },
    getEngineSettings() {
      return JSON.stringify({ threads: 0, ctx_size: 4096, auto_threads: 6, cpu_cores: 8,
        system_prompt: 'You are Bonsai.', temperature: 0.7, top_p: 0.95, top_k: 20, max_tokens: 512 });
    },
    applyAdvancedSettings(threads, ctx, system, temperature, topP, topK, maxTokens) {
      window.__audit.calls.push(['applyAdvancedSettings', threads, ctx, system, temperature, topP, topK, maxTokens]);
      return JSON.stringify({ ok: true, message: 'Settings saved' });
    },
    downloadModel(key) {
      window.__audit.calls.push(['downloadModel', key]);
      if (scenario === 'downloadFail') {
        return JSON.stringify({ ok: false, error: 'Not enough free storage' });
      }
      audit.downloads['model_' + key] = { percent: 7, status: 'downloading', detail: 'Language model', error: null };
      return JSON.stringify({ ok: true, message: 'Download started' });
    },
    switchModel(key) {
      window.__audit.calls.push(['switchModel', key]);
      audit.active_model = key;
      audit.llama_running = false;
      audit.llama_status = 'loading';
      setTimeout(() => { audit.llama_running = true; audit.llama_status = 'ready'; }, 250);
      return JSON.stringify({ ok: true, message: 'Loading' });
    },
    deleteModel(key) {
      window.__audit.calls.push(['deleteModel', key]);
      if (models[key].bundled) return JSON.stringify({ ok: false, error: 'Included model cannot be deleted' });
      models[key].installed = false;
      if (key === audit.active_model) {
        audit.llama_running = false;
        audit.llama_status = 'stopped';
      }
      delete audit.downloads['model_' + key];
      return JSON.stringify({ ok: true, message: 'Deleted' });
    }
  };

  const nativeFetch = window.fetch.bind(window);
  window.fetch = async (resource, options = {}) => {
    const url = String(resource);
    if (!url.includes('/v1/chat/completions')) return nativeFetch(resource, options);
    window.__audit.lastChat = JSON.parse(options.body);
    const encoder = new TextEncoder();
    const slow = params.has('slow');
    const fence = String.fromCharCode(96).repeat(3);
    const responseContent = params.has('code')
      ? fence + 'js\nconst answer = 42;\n' + fence
      : '**Answer** <img src=x onerror="window.__audit.xss=true">';
    const chunks = [
      'data: {"choices":[{"delta":{"reasoning_content":"Reasoning. "}}]}\n\n',
      'data: ' + JSON.stringify({ choices: [{ delta: { content: responseContent } }] }) + '\n\n',
      'data: [DONE]'
    ];
    return new Response(new ReadableStream({
      start(controller) {
        let index = 0;
        let timer;
        const closeForAbort = () => {
          clearTimeout(timer);
          try { controller.error(new DOMException('Aborted', 'AbortError')); } catch (_) {}
        };
        options.signal?.addEventListener('abort', closeForAbort, { once: true });
        const emit = () => {
          if (options.signal?.aborted) return closeForAbort();
          if (index >= chunks.length) { controller.close(); return; }
          const bytes = encoder.encode(chunks[index++]);
          const split = Math.max(1, Math.floor(bytes.length / 2));
          controller.enqueue(bytes.slice(0, split));
          timer = setTimeout(() => {
            controller.enqueue(bytes.slice(split));
            timer = setTimeout(emit, slow ? 900 : 40);
          }, slow ? 900 : 20);
        };
        emit();
      }
    }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
  };
})();
</script>`;

const mockPostlude = String.raw`<script>
(() => {
  const params = new URLSearchParams(location.search);
  const probe = document.createElement('div');
  probe.id = 'audit-probe';
  probe.hidden = true;
  document.body.appendChild(probe);
  const syncProbe = () => { probe.textContent = JSON.stringify(window.__audit); };
  setInterval(syncProbe, 25);
  syncProbe();
  const png = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zl1sAAAAASUVORK5CYII=';
  const bytes = Uint8Array.from(atob(png.split(',')[1]), char => char.charCodeAt(0));
  resizeImageForPhone(new File([bytes], 'audit.png', { type: 'image/png' }), 1280, 0.84)
    .then(value => { window.__audit.resizeOk = value.startsWith('data:image/jpeg'); })
    .catch(error => { window.__audit.resizeError = String(error); });
  if (params.has('image')) {
    pendingImage = { dataUrl: png, name: 'audit.png' };
    document.getElementById('image-preview-img').src = png;
    document.getElementById('image-preview').classList.remove('hidden');
    updateSendButton();
  }
})();
</script>`;

function contentType(filePath) {
  const extension = path.extname(filePath).toLowerCase();
  return ({
    '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8', '.ttf': 'font/ttf', '.png': 'image/png',
    '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.svg': 'image/svg+xml'
  })[extension] || 'application/octet-stream';
}

const server = http.createServer((request, response) => {
  const url = new URL(request.url, `http://${request.headers.host}`);
  if (url.pathname === '/' || url.pathname === '/ui') {
    let html = fs.readFileSync(indexPath, 'utf8');
    html = html.replace('<script>', `${mockPrelude}\n<script>`)
      .replace('</body>', `${mockPostlude}\n</body>`);
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    response.end(html);
    return;
  }

  const relative = decodeURIComponent(url.pathname).replace(/^\/+/, '');
  const filePath = path.resolve(assetsDir, relative);
  if (!filePath.startsWith(assetsDir + path.sep) || !fs.existsSync(filePath) || !fs.statSync(filePath).isFile()) {
    response.writeHead(404);
    response.end('Not found');
    return;
  }
  response.writeHead(200, { 'Content-Type': contentType(filePath), 'Cache-Control': 'no-store' });
  fs.createReadStream(filePath).pipe(response);
});

server.listen(port, '127.0.0.1');
