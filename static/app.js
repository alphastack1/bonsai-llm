'use strict';
const $ = id => document.getElementById(id);
const native = typeof window.BonsaiNative !== 'undefined';
const icon = name => `<svg aria-hidden="true"><use href="#i-${name}"/></svg>`;
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const bytes = n => n >= 1e9 ? `${(n / 1e9).toFixed(2)} GB` : `${Math.ceil(n / 1e6)} MB`;
const busy = job => job && ['queued','connecting','downloading','verifying','extracting'].includes(job.status);
const storageKey = 'bonsai2.chats.v1';
// Android's local HTTP asset origin does not expose secure-context randomUUID.
const newID = () => Array.from(crypto.getRandomValues(new Uint32Array(4)), n => n.toString(16).padStart(8,'0')).join('');
const state = {status: null, chats: [], chat: null, streaming: false, controller: null, image: null, thinking: false};
let lastLibrary = '', toastTimer, renderFrame = 0;

function toast(message) {
  $('toast').textContent = message;
  $('toast').hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { $('toast').hidden = true; }, 6500);
}

async function api(path, body) {
  if (native) {
    const result = JSON.parse(window.BonsaiNative.request(path, JSON.stringify(body ?? {})));
    if (result.error && !(path === '/api/status' && result.models)) throw new Error(result.error);
    return result;
  }
  const response = await fetch(path, body === undefined ? {cache:'no-store'} : {
    method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)
  });
  let result;
  try { result = await response.json(); } catch { throw new Error('The local app returned an unexpected response.'); }
  if (!response.ok || (result.error && path !== '/api/status')) throw new Error(result.error || 'The local request failed.');
  return result;
}

function initImageStore() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open('bonsai2-images', 1);
    request.onupgradeneeded = () => request.result.createObjectStore('images');
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}
let imageDB;
async function imageStore(action, key, value) {
  imageDB ??= initImageStore();
  const db = await imageDB;
  return new Promise((resolve, reject) => {
    const tx = db.transaction('images', action === 'get' ? 'readonly' : 'readwrite');
    const store = tx.objectStore('images');
    const request = action === 'get' ? store.get(key) : action === 'delete' ? store.delete(key) : store.put(value, key);
    tx.oncomplete = () => resolve(request.result);
    tx.onerror = () => reject(tx.error);
  });
}

function saveChats() {
  if (state.chat?.messages.length && !state.chats.some(chat => chat.id === state.chat.id)) state.chats.unshift(state.chat);
  try {
    localStorage.setItem(storageKey, JSON.stringify(state.chats.map(chat => ({...chat,
      messages:chat.messages.map(({image, ...message}) => message)}))));
  } catch { toast('Local storage is full. This chat is still here, but could not be saved. Delete older chats to make room.'); }
  renderHistory();
}

function freshChat() {
  if (state.streaming) return toast('Stop the current answer before starting another chat.');
  state.chat = {id:newID(), title:'New conversation', messages:[], created:Date.now()};
  clearImage();
  renderMessages();
  renderHistory();
  $('prompt').value = '';
  resizePrompt();
  closeSidebar();
  $('prompt').focus();
}

async function openChat(id) {
  if (state.streaming) return toast('Stop the current answer before switching chats.');
  const chat = state.chats.find(item => item.id === id);
  if (!chat) return;
  state.chat = chat;
  for (const message of chat.messages) {
    if (message.imageId && !message.image) {
      try { message.image = await imageStore('get', message.imageId); } catch {}
    }
  }
  if (state.chat !== chat) return;
  clearImage();
  renderMessages();
  renderHistory();
  closeSidebar();
}

function renderHistory() {
  $('history').replaceChildren();
  if (!state.chats.length) {
    const p = document.createElement('p');
    p.className = 'empty-history';
    p.textContent = 'Good things start with a conversation. Yours will appear here.';
    $('history').append(p);
  }
  for (const chat of state.chats) {
    const item = document.createElement('div');
    item.className = 'history-item' + (chat.id === state.chat?.id ? ' active' : '');
    const open = document.createElement('button');
    open.textContent = chat.title;
    open.title = chat.title;
    open.addEventListener('click', () => openChat(chat.id));
    const remove = document.createElement('button');
    remove.className = 'icon-button';
    remove.setAttribute('aria-label', 'Delete ' + chat.title);
    remove.innerHTML = icon('trash');
    remove.addEventListener('click', () => {
      if (state.streaming) return toast('Stop the current answer before deleting a chat.');
      if (!confirm('Delete this conversation from this device?')) return;
      for (const message of chat.messages) if (message.imageId) imageStore('delete', message.imageId).catch(() => {});
      state.chats = state.chats.filter(item => item.id !== chat.id);
      if (state.chat?.id === chat.id) freshChat();
      saveChats();
    });
    item.append(open, remove);
    $('history').append(item);
  }
  $('chat-title').textContent = state.chat?.title || 'New conversation';
}

function inline(text) {
  const pieces = [];
  const hold = html => `\u0000${pieces.push(html) - 1}\u0000`;
  text = esc(text).replace(/`([^`]+)`/g, (_, code) => hold(`<code>${code}</code>`));
  text = text.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, (_, label, url) =>
    hold(`<a href="${url}" target="_blank" rel="noopener noreferrer">${label}</a>`));
  text = text.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>').replace(/\*([^*\n]+)\*/g, '<em>$1</em>');
  return text.replace(/\u0000(\d+)\u0000/g, (_, i) => pieces[Number(i)] || '');
}

function markdown(text) {
  const lines = String(text).replace(/\r/g, '').split('\n');
  let html = '', paragraph = [], code = null, list = null;
  const flush = () => { if (paragraph.length) {html += `<p>${paragraph.map(inline).join('<br>')}</p>`; paragraph = [];} };
  const closeList = () => { if (list) {html += `</${list}>`; list = null;} };
  for (let i=0; i<lines.length; i++) {
    const line = lines[i];
    if (/^\s*```/.test(line)) {
      flush(); closeList();
      if (code === null) code = [];
      else {html += `<pre><code>${esc(code.join('\n'))}</code></pre>`; code = null;}
      continue;
    }
    if (code !== null) {code.push(line); continue;}
    if (!line.trim()) {flush(); closeList(); continue;}
    const heading = line.match(/^(#{1,3})\s+(.+)/);
    if (heading) {flush(); closeList(); html += `<h${heading[1].length}>${inline(heading[2])}</h${heading[1].length}>`; continue;}
    const item = line.match(/^\s*(?:([-*])|\d+\.)\s+(.+)/);
    if (item) {
      flush(); const type = item[1] ? 'ul' : 'ol';
      if (list !== type) {closeList(); html += `<${type}>`; list = type;}
      html += `<li>${inline(item[2])}</li>`; continue;
    }
    if (line.startsWith('> ')) {flush(); closeList(); html += `<blockquote>${inline(line.slice(2))}</blockquote>`; continue;}
    if (line.includes('|') && i + 1 < lines.length && /^\s*\|?\s*:?-{3}/.test(lines[i+1])) {
      flush(); closeList();
      const cells = row => row.trim().replace(/^\||\|$/g, '').split('|').map(cell => cell.trim());
      html += `<table><thead><tr>${cells(line).map(cell => `<th>${inline(cell)}</th>`).join('')}</tr></thead><tbody>`;
      i++;
      while (i+1 < lines.length && lines[i+1].includes('|') && lines[i+1].trim()) {
        html += `<tr>${cells(lines[++i]).map(cell => `<td>${inline(cell)}</td>`).join('')}</tr>`;
      }
      html += '</tbody></table>'; continue;
    }
    closeList(); paragraph.push(line);
  }
  flush(); closeList();
  if (code !== null) html += `<pre><code>${esc(code.join('\n'))}</code></pre>`;
  return html;
}

function messageHTML(message, index) {
  const reasoning = message.reasoning ? `<details class="reasoning"><summary>${state.streaming && index === state.chat.messages.length-1 ? 'Thinking…' : 'Thought process'}</summary><div>${esc(message.reasoning)}</div></details>` : '';
  const image = message.image ? `<img class="message-image" alt="Your attached image" src="${esc(message.image)}">` : '';
  if (message.role === 'user') return `${image}<div class="message-content">${esc(message.content)}</div>`;
  return `<div class="message-label"><span class="avatar">✳</span>${esc(message.model || 'Bonsai 2')}</div>${reasoning}<div class="message-content">${markdown(message.content)}</div>${message.error ? `<div class="message-error">${esc(message.error)}</div>` : ''}<div class="message-meta"><button class="icon-button copy-message" data-index="${index}" aria-label="Copy answer">${icon('copy')}</button><span>${esc(message.stats || '')}</span></div>`;
}

function renderMessages() {
  $('welcome').hidden = state.chat.messages.length > 0;
  $('messages').replaceChildren();
  state.chat.messages.forEach((message, index) => {
    const el = document.createElement('article');
    el.className = 'message ' + message.role;
    el.id = 'message-' + index;
    el.innerHTML = messageHTML(message, index);
    $('messages').append(el);
  });
  $('chat-title').textContent = state.chat.title;
  $('conversation').scrollTop = $('conversation').scrollHeight;
}

function updateReply() {
  renderFrame = 0;
  const i = state.chat.messages.length-1;
  const el = $('message-' + i);
  if (!el) return;
  const container = $('conversation');
  const nearBottom = container.scrollHeight - container.scrollTop - container.clientHeight < 130;
  const open = el.querySelector('details')?.open;
  el.innerHTML = messageHTML(state.chat.messages[i], i);
  el.classList.toggle('pending', state.streaming);
  if (open && el.querySelector('details')) el.querySelector('details').open = true;
  if (nearBottom) container.scrollTop = container.scrollHeight;
}

function updateControls() {
  const ready = state.status?.ready;
  $('send').disabled = !state.streaming && (!ready || (!$('prompt').value.trim() && !state.image));
  $('send').classList.toggle('stop', state.streaming);
  $('send').setAttribute('aria-label', state.streaming ? 'Stop generating' : 'Send message');
  $('send').innerHTML = state.streaming ? '<span class="stop-mark"></span>' : icon('arrow');
  $('attach-image').disabled = state.streaming || !ready || !state.status?.vision;
  $('thinking').disabled = state.streaming;
}

async function poll() {
  try {
    state.status = await api('/api/status');
    const s = state.status;
    if (state.image && !s.vision) clearImage();
    $('model-name').textContent = s.models?.[s.active_model]?.name || 'Bonsai 2';
    $('status-dot').className = 'status-dot ' + (s.ready ? 'ready' : ['loading','installing'].includes(s.state) ? 'loading' : '');
    $('model-picker').title = s.ready ? `Running locally · ${s.backend || 'CPU'}${s.vision ? ' · images enabled' : ''}` : 'Open model library';
    let message = '';
    if (s.error) message = s.error;
    else if (s.state === 'installing') message = 'Preparing the included 2B model for offline use. This only happens once.';
    else if (s.state === 'loading') message = 'Loading your model locally…';
    else if (!s.ready) message = 'Choose a downloaded model in your library to get started.';
    const setup = s.downloads?.setup;
    if (busy(setup)) message = `Preparing offline chat · ${setup.status} ${setup.percent || 0}%`;
    else if (setup?.error) message = setup.error;
    $('status-bar').textContent = message;
    if (!s.engine_ready && !busy(setup)) {
      const button = document.createElement('button');
      button.textContent = 'Set up local engine & 2B';
      button.addEventListener('click', () => act('/api/setup/start', {}));
      $('status-bar').append(' ', button);
    }
    $('status-bar').hidden = !message;
    const signature = JSON.stringify([s.models,s.active_model,s.vision,s.state,s.downloads]);
    if (signature !== lastLibrary) {lastLibrary = signature; renderModels();}
    $('backend-setting').hidden = native;
    $('download-cuda').hidden = native || s.available_backends?.includes('cuda');
    const engineJob = s.downloads?.engine;
    $('download-cuda').disabled = busy(engineJob);
    $('download-cuda').textContent = busy(engineJob) ? `NVIDIA engine · ${engineJob.status} ${engineJob.percent || 0}%` : 'Download NVIDIA engine · about 705 MB';
  } catch {
    if (state.status) state.status.ready = false;
    $('status-bar').textContent = 'The local app is unavailable. Reopen Bonsai to reconnect.';
    $('status-bar').hidden = false;
  }
  updateControls();
  setTimeout(poll, document.hidden ? 5000 : 1800);
}

async function act(path, body) {
  try {await api(path, body); lastLibrary = ''; await refresh();}
  catch(error) {toast(error.message);}
}
async function refresh() { state.status = await api('/api/status'); renderModels(); updateControls(); }

function progressHTML(job) {
  if (!job) return '';
  if (job.error) return `<div class="download-error">${esc(job.error)}</div>`;
  if (!busy(job)) return '';
  return `<div class="download-state">${esc(job.status)} · ${job.percent || 0}%<progress value="${Math.max(0,Math.min(100,job.percent || 0))}" max="100"></progress></div>`;
}

function renderModels() {
  const s = state.status;
  if (!s) return;
  $('model-list').innerHTML = Object.entries(s.models || {}).map(([key,m]) => {
    const active = s.active_model === key && s.ready;
    const loading = s.state === 'loading' || s.state === 'installing' || state.streaming;
    const job = s.downloads?.['model_'+key], vjob = s.downloads?.['vision_'+key];
    return `<article class="model-card ${active ? 'active' : ''}" data-key="${esc(key)}"><div class="model-card-top"><div class="model-number">${esc(key)}</div><div><h3>${esc(m.name)}</h3><span class="model-tag">${esc(m.label)}${m.bundled ? ' · included' : ''}</span></div><span class="model-size">${bytes(m.bytes)}</span></div><p>${esc(m.description)}</p><div class="model-actions">${progressHTML(job)}${m.installed ? `${active ? `<span class="active-label">${icon('check')} In use · offline</span>` : `<button class="primary" data-action="load" ${loading || !s.engine_ready ? 'disabled' : ''}>Use model</button>`}<button class="danger" data-action="delete" ${active || loading || busy(job) ? 'disabled' : ''} aria-label="Delete ${esc(m.name)}">Remove</button>` : `<button class="primary" data-action="download" ${busy(job) ? 'disabled' : ''}>${busy(job) ? 'Downloading…' : job?.error ? 'Retry download' : 'Download for offline use'}</button>`}</div><div class="vision-option"><span>Image understanding<br><small>${m.vision_installed ? 'Downloaded · runs locally' : `Optional · ${bytes(m.vision_bytes)}`}</small></span>${m.vision_installed ? `<button class="secondary" data-action="vision-load" ${loading || !m.installed || !s.engine_ready ? 'disabled' : ''}>${active && s.vision ? 'Turn images off' : 'Use with images'}</button>` : `<button class="secondary" data-action="vision-download" ${busy(vjob) ? 'disabled' : ''}>${busy(vjob) ? 'Downloading…' : 'Download'}</button>`}</div>${progressHTML(vjob)}</article>`;
  }).join('');
}

async function send(event) {
  event.preventDefault();
  if (state.streaming) {state.controller?.abort(); return;}
  const text = $('prompt').value.trim();
  if ((!text && !state.image) || !state.status?.ready) return;
  if (state.chat.messages.length >= 190) return toast('This conversation is full. Start a new one to keep chatting.');
  const message = {role:'user', content:text || 'Describe this image.'};
  if (state.image) {
    message.image = state.image;
    message.imageId = newID();
    try {await imageStore('put', message.imageId, message.image);} catch {toast('The photo could not be saved. It will be available for this session.');}
  }
  state.chat.messages.push(message);
  if (state.chat.messages.filter(m => m.role === 'user').length === 1) state.chat.title = message.content.slice(0,45);
  $('prompt').value = ''; resizePrompt(); clearImage(); saveChats();
  const reply = {role:'assistant',content:'',reasoning:'',model:state.status.models[state.status.active_model].name};
  const messages = [{role:'system',content:localStorage.getItem('bonsai2.system') || 'You are a helpful assistant.'}, ...state.chat.messages.filter(m => m.content).map(m => ({role:m.role,content:m.image && state.status.vision ? [{type:'text',text:m.content},{type:'image_url',image_url:{url:m.image}}] : m.content}))];
  state.chat.messages.push(reply);
  state.streaming = true;
  state.controller = new AbortController();
  renderMessages(); updateControls(); renderModels(); updateReply();
  const started = performance.now();
  let finishReason, usage, timings;
  try {
    const body = {messages, thinking:state.thinking};
    let url = '/api/chat', headers = {'Content-Type':'application/json'};
    if (native) {
      url = state.status.inference.url + '/v1/chat/completions';
      headers.Authorization = 'Bearer ' + state.status.inference.token;
      Object.assign(body, {model:'bonsai',stream:true,stream_options:{include_usage:true},
        max_tokens:state.thinking ? 8192 : 2048,temperature:state.thinking ? 1 : .7,
        top_p:state.thinking ? .95 : .8,top_k:20,min_p:0,presence_penalty:state.thinking ? 1 : 1.5,
        repeat_penalty:1,repeat_last_n:64,chat_template_kwargs:{enable_thinking:state.thinking}});
    }
    const response = await fetch(url,{method:'POST',headers,body:JSON.stringify(body),signal:state.controller.signal});
    if (!response.ok) {
      let error;
      try {const data = await response.json(); error = typeof data.error === 'object' ? data.error.message : data.error;} catch {}
      throw new Error(error || 'The local model could not answer. Try a shorter chat or reload the model.');
    }
    const reader = response.body.getReader(), decoder = new TextDecoder();
    let buffer = '', doneEvent = false;
    const consume = line => {
      if (!line.startsWith('data:')) return;
      const raw = line.slice(5).trim();
      if (raw === '[DONE]') {doneEvent = true; return;}
      if (!raw) return;
      const data = JSON.parse(raw);
      if (data.error) throw new Error(data.error.message || String(data.error));
      const choice = data.choices?.[0], delta = choice?.delta || {};
      if (delta.reasoning_content) reply.reasoning += delta.reasoning_content;
      if (delta.content) reply.content += delta.content;
      if (choice?.finish_reason) finishReason = choice.finish_reason;
      if (data.usage) usage = data.usage;
      if (data.timings) timings = data.timings;
      if (!renderFrame) renderFrame = requestAnimationFrame(updateReply);
    };
    while (true) {
      const {value,done} = await reader.read();
      buffer += decoder.decode(value || new Uint8Array(),{stream:!done});
      const lines = buffer.split('\n'); buffer = lines.pop();
      lines.forEach(consume);
      if (done) {if(buffer.trim()) consume(buffer); break;}
    }
    if (!doneEvent && !finishReason) throw new Error('The local connection ended before the answer was complete.');
    if (finishReason === 'length') reply.error = 'Response limit reached. Turn off Think deeper for a shorter answer, or ask to continue.';
    if (!reply.content && !reply.reasoning) throw new Error('The model returned an empty answer. Please try again.');
  } catch (error) {
    if (error.name === 'AbortError') reply.stats = 'Stopped';
    else reply.error = error.message;
  } finally {
    state.streaming = false; state.controller = null;
    const elapsed = ((performance.now()-started)/1000).toFixed(1);
    reply.stats ||= `${elapsed}s${usage?.completion_tokens ? ' · '+usage.completion_tokens+' tokens' : ''}${timings?.predicted_per_second ? ' · '+Math.round(timings.predicted_per_second)+' tokens/s' : ''}`;
    $('generation-info').textContent = reply.stats;
    if (renderFrame) cancelAnimationFrame(renderFrame);
    updateReply(); updateControls(); saveChats(); renderModels();
  }
}

function clearImage() {state.image = null; $('attachment').hidden = true; $('image-input').value = ''; updateControls();}
function resizePrompt() {$('prompt').style.height = 'auto'; $('prompt').style.height = Math.min($('prompt').scrollHeight,180)+'px'; updateControls();}
function closeSidebar() {$('sidebar').classList.remove('open'); $('sidebar-scrim').hidden = true;}
function openDialog(id) {closeSidebar(); $(id).showModal();}
window.handleAppBack = () => {
  for (const id of ['library','settings']) if ($(id).open) {$(id).close(); return true;}
  if ($('sidebar').classList.contains('open')) {closeSidebar(); return true;}
  return false;
};

$('composer').addEventListener('submit',send);
$('prompt').addEventListener('input',resizePrompt);
$('prompt').addEventListener('keydown',event => {if(event.key === 'Enter' && !event.shiftKey && !event.isComposing){event.preventDefault(); if(!state.streaming) send(event);}});
$('new-chat').addEventListener('click',freshChat);
document.querySelector('.brand').addEventListener('click',event => {event.preventDefault(); freshChat();});
document.querySelectorAll('[data-prompt]').forEach(button => button.addEventListener('click',() => {$('prompt').value=button.dataset.prompt; resizePrompt(); $('prompt').focus();}));
$('menu').addEventListener('click',() => {$('sidebar').classList.add('open'); $('sidebar-scrim').hidden=false;});
$('sidebar-scrim').addEventListener('click',closeSidebar);
$('open-models').addEventListener('click',() => openDialog('library'));
$('model-picker').addEventListener('click',() => openDialog('library'));
document.querySelectorAll('.close-dialog').forEach(button => button.addEventListener('click',() => button.closest('dialog').close()));
document.querySelectorAll('dialog').forEach(dialog => dialog.addEventListener('click',event => {if(event.target === dialog){const r=dialog.getBoundingClientRect();if(event.clientX<r.left||event.clientX>r.right||event.clientY<r.top||event.clientY>r.bottom)dialog.close();}}));
$('thinking').addEventListener('click',() => {state.thinking=!state.thinking; $('thinking').setAttribute('aria-pressed',String(state.thinking)); if(state.thinking) toast('Bonsai will think longer before answering. Use 16K context or more for room to reason.');});
$('remove-image').addEventListener('click',clearImage);
$('attach-image').addEventListener('click',() => $('image-input').click());
$('image-input').addEventListener('change',async () => {
  const file = $('image-input').files[0];
  if (!file) return;
  if (!/^image\/(png|jpeg|webp)$/.test(file.type) || file.size>25*1024*1024) {toast('Choose a PNG, JPEG, or WebP image smaller than 25 MB.'); return;}
  const url = URL.createObjectURL(file);
  try {
    const image = new Image(); image.src=url; await image.decode();
    const scale = Math.min(1,1280/Math.max(image.width,image.height));
    const canvas = document.createElement('canvas'); canvas.width=Math.max(1,Math.round(image.width*scale)); canvas.height=Math.max(1,Math.round(image.height*scale));
    const ctx=canvas.getContext('2d'); ctx.fillStyle='#fff'; ctx.fillRect(0,0,canvas.width,canvas.height); ctx.drawImage(image,0,0,canvas.width,canvas.height);
    state.image=canvas.toDataURL('image/jpeg',.85); $('attachment-image').src=state.image; $('attachment').hidden=false; updateControls();
  } catch {toast('That image could not be opened. Try another file.');}
  finally {URL.revokeObjectURL(url);}
});
$('model-list').addEventListener('click',event => {
  const button=event.target.closest('button[data-action]'); if(!button || button.disabled)return;
  if(state.streaming)return toast('Stop the current answer before changing models.');
  const key=button.closest('[data-key]').dataset.key, action=button.dataset.action;
  button.disabled=true;
  if(action==='delete'&&!confirm(`Remove Bonsai 2 ${key} from this device? You can download it again.`)){button.disabled=false;return;}
  const paths={load:'load',delete:'delete',download:'download','vision-download':'download','vision-load':'load'};
  let vision=action==='vision-download'||action==='vision-load';
  if(action==='vision-load'&&state.status.active_model===key&&state.status.vision)vision=false;
  act('/api/model/'+paths[action],{key,vision});
});
$('open-settings').addEventListener('click',() => {
  const s=state.status?.settings||{threads:0,ctx_size:16384,backend:'auto'};
  $('context').value=String(s.ctx_size); $('threads').value=String(s.threads); $('backend').value=s.backend||'auto';
  $('system-prompt').value=localStorage.getItem('bonsai2.system')||'';
  $('backend-note').textContent=`Currently using ${state.status?.backend||'the local engine'}. Auto tries an installed GPU engine, then CPU.`;
  openDialog('settings');
});
$('download-cuda').addEventListener('click',() => act('/api/engine/download',{backend:'cuda'}));
$('settings-form').addEventListener('submit',async event => {
  event.preventDefault();
  if(state.streaming)return toast('Stop the current answer before changing settings.');
  const settings={threads:Number($('threads').value),ctx_size:Number($('context').value),backend:native?'cpu':$('backend').value};
  try {
    const current=state.status?.settings||{};
    if(['threads','ctx_size','backend'].some(key=>current[key]!==settings[key]))await api('/api/settings',settings);
    localStorage.setItem('bonsai2.system',$('system-prompt').value.trim());
    $('settings').close(); toast('Preferences saved on this device.');
  } catch(error){toast(error.message);}
});
$('messages').addEventListener('click',async event => {
  const button=event.target.closest('.copy-message'); if(!button)return;
  const text=state.chat.messages[Number(button.dataset.index)]?.content||'';
  try {if(native) await api('/api/clipboard',{text}); else await navigator.clipboard.writeText(text); toast('Answer copied.');}
  catch {toast('Copy is unavailable here. Select the answer text to copy it.');}
});
document.addEventListener('keydown',event => {if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==='n'){event.preventDefault();freshChat();}});
try {const chats=JSON.parse(localStorage.getItem(storageKey)||'[]');state.chats=Array.isArray(chats)?chats.filter(c=>typeof c.id==='string'&&typeof c.title==='string'&&Array.isArray(c.messages)):[];} catch {}
document.querySelector('.shortcut').textContent = /Mac/.test(navigator.platform) ? '⌘ N' : 'Ctrl N';
freshChat(); poll();
