/**
 * Comet-X Transformers.js runtime (v2.1.0) — the in-browser LLM engine.
 *
 * Wire contract (mirrored by ai/web/WebLlmProtocol.kt — keep in sync):
 *   JS → native (CometXWebAI.post(JSON string)):
 *     { t:"boot" }                                   page module is up
 *     { t:"log",  msg }                              diagnostic line
 *     { t:"status", phase, model, pct?, loaded?, total?, file? }   load progress
 *     { t:"ready", model }                           pipeline resident
 *     { t:"stream", sid, text }                      incremental decode chunk
 *     { t:"done",  sid, text }                       final full completion
 *     { t:"error", sid?, msg }                       failure (load or generate)
 *   native → JS (window.__cometxAI.handle(JSON object)):
 *     { op:"load", model, dtype }
 *     { op:"generate", sid, messages:[{role,content}], maxTokens, temperature }
 *     { op:"interrupt", sid }
 *
 * Design notes:
 *  - Models load from the Hugging Face hub into Cache Storage on this secure
 *    origin; the second run never re-downloads (env.useBrowserCache = true).
 *  - ORT Web binaries are bundled next to this page and referenced through the
 *    {wasm, mjs} object form of env.backends.onnx.wasm.wasmPaths — no CDN.
 *  - device is pinned to 'wasm' (CPU): reliable on every evergreen WebView,
 *    no WebGPU requirement. Multi-threading engages automatically when the
 *    native side's COOP/COEP headers produce SharedArrayBuffer; otherwise
 *    ORT falls back to a single thread on its own.
 */

import {
  pipeline, env, TextStreamer, InterruptableStoppingCriteria,
} from './transformers.min.js';

env.allowLocalModels = false;          // models come from the HF hub
env.useBrowserCache = true;            // persist weights in Cache Storage
env.backends.onnx.wasm.wasmPaths = {
  wasm: new URL('./ort-wasm-simd-threaded.wasm', import.meta.url).href,
  mjs: new URL('./ort-wasm-simd-threaded.mjs', import.meta.url).href,
};

/** Native bridge — injected by Kotlin as "CometXWebAI". */
const native = typeof CometXWebAI !== 'undefined'
  ? CometXWebAI
  : { post: (s) => console.log('[bridge-missing]', s) };

const stateEl = document.getElementById('state');
const barEl = document.getElementById('bar');
const fillEl = document.getElementById('fill');

function post(obj) {
  try { native.post(JSON.stringify(obj)); } catch (e) { /* bridge gone — page is dying */ }
}

function paintStatus(s) {
  if (!stateEl) return;
  if (s.phase === 'loading') {
    stateEl.textContent = s.total
      ? `downloading model · ${(s.loaded / 1048576).toFixed(0)} / ${(s.total / 1048576).toFixed(0)} MB`
      : 'downloading model…';
    barEl.style.visibility = 'visible';
    fillEl.style.width = Math.max(3, Math.min(100, s.pct || 0)) + '%';
  } else if (s.phase === 'warming') {
    stateEl.textContent = 'building session…';
    barEl.style.visibility = 'visible';
    fillEl.style.width = '100%';
  } else if (s.phase === 'loaded') {
    stateEl.textContent = 'model ready';
    barEl.style.visibility = 'hidden';
  }
}

let pipe = null;            // resident text-generation pipeline
let pipeModel = null;       // which model the pipeline holds
let busy = false;           // one generate at a time inside the page
let stopper = null;         // InterruptableStoppingCriteria for the live run

window.__cometxAI = {
  async handle(msg) {
    try {
      if (!msg || typeof msg.op !== 'string') return;

      if (msg.op === 'load') {
        if (pipe && pipeModel === msg.model) { post({ t: 'ready', model: msg.model }); return; }
        post({ t: 'status', phase: 'loading', model: msg.model });
        pipe = null;
        pipeModel = null;
        pipe = await pipeline('text-generation', msg.model, {
          dtype: msg.dtype || 'q4',
          device: 'wasm',
          progress_callback: (p) => {
            if (p && p.status === 'progress') {
              post({
                t: 'status', phase: 'loading', model: msg.model, file: p.file,
                pct: p.progress || 0, loaded: p.loaded || 0, total: p.total || 0,
              });
              paintStatus({ phase: 'loading', pct: p.progress, loaded: p.loaded, total: p.total });
            }
          },
        });
        pipeModel = msg.model;
        post({ t: 'ready', model: msg.model });
        paintStatus({ phase: 'loaded' });

      } else if (msg.op === 'generate') {
        if (!pipe || pipeModel === null) {
          post({ t: 'error', sid: msg.sid, msg: 'no model loaded' });
          return;
        }
        if (busy) {
          post({ t: 'error', sid: msg.sid, msg: 'runtime busy with another generation' });
          return;
        }
        busy = true;
        stopper = new InterruptableStoppingCriteria();
        const sid = msg.sid;
        try {
          const streamer = new TextStreamer(pipe.tokenizer, {
            skip_prompt: true,
            callback_function: (txt) => { if (txt) post({ t: 'stream', sid, text: txt }); },
          });
          const temp = typeof msg.temperature === 'number' ? msg.temperature : 0.2;
          const out = await pipe(msg.messages, {
            max_new_tokens: msg.maxTokens || 512,
            do_sample: temp > 0,
            temperature: temp,
            streamer,
            stopping_criteria: stopper,
          });
          let text = '';
          const seq = Array.isArray(out) ? out[0] && out[0].generated_text : out && out.generated_text;
          if (Array.isArray(seq)) {
            const last = seq[seq.length - 1];
            if (last && typeof last.content === 'string') text = last.content;
          } else if (typeof seq === 'string') {
            text = seq;
          }
          post({ t: 'done', sid, text });
        } finally {
          busy = false;
          stopper = null;
        }

      } else if (msg.op === 'interrupt') {
        if (stopper) stopper.interrupt();

      } else if (msg.op === 'reset') {
        pipe = null;
        pipeModel = null;
        post({ t: 'status', phase: 'reset' });
      }
    } catch (e) {
      post({ t: 'error', sid: msg && msg.sid, msg: String((e && e.message) || e) });
    }
  },
};

post({ t: 'boot' });
if (stateEl) stateEl.textContent = 'runtime idle';
