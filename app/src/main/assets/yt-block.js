/* Zerium Browser — YouTube ad suppression (v1.4.0)
 *
 * Injected at document start on youtube.com / youtube-nocookie.com /
 * music.youtube.com via WebViewCompat.addDocumentStartJavaScript, before
 * the YouTube player initializes.
 *
 * Technique mirrors the actively maintained uBlock Origin YouTube
 * scriptlets (uAssets quick-fixes): ad structures are pruned from player
 * JSON before the player parses them, so ads are never scheduled — the
 * "block" behavior, where the ad does not show up at all. Whatever still
 * renders is handled by instant skip clicks, overlay closing, CSS hiding
 * and a strictly-scoped in-stream fallback.
 *
 * v1.4.0 changes (mid-roll ads):
 *   - Player/XHR endpoint matching no longer requires a query string; a bare
 *     /youtubei/v1/player request used to slip through unpruned. Added
 *     get_watch and ssap endpoints.
 *   - XHR interception is now order-independent: instance getters for
 *     response/responseText installed at open() time prune on ACCESS. The
 *     v1.2.0 listener ran after the page's own listener, which could read
 *     the raw response first.
 *   - Renderer-level pruning (adSlotRenderer / adBreakAdRenderer /
 *     adPlacementRenderer / inVideoAdCta) removes ad schedules nested under
 *     containers we do not know by name.
 *   - The web "network machine" experiment flags are switched off when
 *     present (client-side experiment toggle only — no client identity is
 *     changed or spoofed).
 *
 * Honest scope: a share of mid-roll ads is now delivered via SSAP
 * (server-side ad stitching — the ad segments are part of the video stream
 * itself). No client-side blocker can remove those from the stream; the
 * confirmed-ad watchdog below fast-forwards through them instead. This is
 * the same limitation every WebView-class blocker has. Client-side
 * scheduled ads (pre-rolls and most classic mid-rolls) are pruned and never
 * appear.
 *
 * Evaluated and deliberately rejected from the reference implementations:
 *   - uBO's premium-client masquerade (user-agent rewriting plus global
 *     Promise/Map/Array prototype hooking): fragile across releases and
 *     misrepresents the client to Google's servers.
 *   - Network-blocking of ad video segments: in-stream ads share delivery
 *     endpoints with the video itself; failing those requests can stall
 *     playback instead of skipping cleanly.
 */
(function () {
  'use strict';
  if (window.__zeriumYT) return;
  window.__zeriumYT = true;

  /* ------------------------------------------------------------------ *
   * 1. Response pruning — the "block" layer
   * ------------------------------------------------------------------ */
  var AD_KEYS = {
    adPlacements: 1,
    adSlots: 1,
    playerAds: 1,
    adBreaks: 1,
    adBreakHeartbeatParams: 1,
    adPlacementsForThirdParty: 1,
    /* Renderer-level keys: containers that exist ONLY to hold ad renderers.
       Pruning by renderer name catches ad schedules nested under response
       wrappers whose container names we do not know. */
    adSlotRenderer: 1,
    adBreakAdRenderer: 1,
    adPlacementRenderer: 1,
    inVideoAdCta: 1
  };
  var PLAYER_API = /\/youtubei\/v1\/(player|next|video_details|get_midroll_info|viewer|playlist|get_watch|ssap)(?:[?#]|$)/;

  function deepPrune(node, depth) {
    if (!node || typeof node !== 'object' || depth > 60) return node;
    if (Array.isArray(node)) {
      for (var i = 0; i < node.length; i++) deepPrune(node[i], depth + 1);
      return node;
    }
    for (var key in node) {
      if (!Object.prototype.hasOwnProperty.call(node, key)) continue;
      /* own-key lookup guard: a page JSON key named "constructor" or
         "hasOwnProperty" must not resolve through Object.prototype */
      if (Object.prototype.hasOwnProperty.call(AD_KEYS, key)) {
        try { delete node[key]; } catch (e) { node[key] = undefined; }
        continue;
      }
      deepPrune(node[key], depth + 1);
    }
    return node;
  }

  function prunedResponse(res) {
    return res.clone().json().then(function (data) {
      deepPrune(data, 0);
      var headers = new Headers();
      res.headers.forEach(function (value, name) {
        if (name === 'content-encoding' || name === 'content-length') return;
        try { headers.set(name, value); } catch (e) {}
      });
      return new Response(JSON.stringify(data), {
        status: res.status,
        statusText: res.statusText,
        headers: headers
      });
    }).catch(function () { return res; });
  }

  /* fetch hook */
  try {
    var nativeFetch = window.fetch;
    if (typeof nativeFetch === 'function') {
      window.fetch = function (input) {
        var url = '';
        try {
          url = input && input.url ? String(input.url) : (typeof input === 'string' ? input : '');
        } catch (e) {}
        var promise = nativeFetch.apply(this, arguments);
        return PLAYER_API.test(url) ? promise.then(prunedResponse) : promise;
      };
    }
  } catch (e) {}

  /* XHR hook — order-independent.
   *
   * Instance-level getters for response / responseText are installed at
   * open() time and prune on ACCESS, so every read — by the page's own
   * readystatechange listeners (registered before send), timers, or
   * promises — returns pruned data regardless of listener order. The
   * v1.2.0 approach registered a listener inside send(), which ran after
   * the page's own listener and could leave it holding raw ad data.
   */
  try {
    var XHR = XMLHttpRequest.prototype;
    var nativeOpen = XHR.open;
    var respDesc = Object.getOwnPropertyDescriptor(XHR, 'response');
    var respTextDesc = Object.getOwnPropertyDescriptor(XHR, 'responseText');

    function prunedText(raw) {
      if (typeof raw !== 'string' || raw.length < 2 || raw.charCodeAt(0) !== 123) return raw;
      try {
        var data = JSON.parse(raw);
        deepPrune(data, 0);
        return JSON.stringify(data);
      } catch (e) { return raw; }
    }

    XHR.open = function (method, url) {
      var xhr = this;
      try {
        if (!xhr.__zeriumHooked && respDesc && respTextDesc
            && PLAYER_API.test(String(url || ''))) {
          xhr.__zeriumHooked = true;
          var textCache = { state: -1, len: -1, out: null };

          function cachedPrunedText() {
            var raw = respTextDesc.get.call(xhr);
            if (xhr.readyState === textCache.state && textCache.out !== null
                && raw !== null && raw.length === textCache.len) {
              return textCache.out;
            }
            var out = prunedText(raw);
            textCache.state = xhr.readyState;
            textCache.len = raw === null ? -1 : raw.length;
            textCache.out = out;
            return out;
          }

          Object.defineProperty(xhr, 'response', {
            configurable: true,
            get: function () {
              var t = xhr.responseType;
              if (t === 'json') {
                /* The engine caches the parsed object per XHR, so pruning
                   it in place fixes every future read of this response. */
                var v = respDesc.get.call(xhr);
                try { deepPrune(v, 0); } catch (e) {}
                return v;
              }
              if (t === 'text' || t === '') return cachedPrunedText();
              return respDesc.get.call(xhr);
            }
          });
          Object.defineProperty(xhr, 'responseText', {
            configurable: true,
            get: function () { return cachedPrunedText(); }
          });
        }
      } catch (e) {}
      return nativeOpen.apply(this, arguments);
    };
  } catch (e) {}

  /* Initial player response: prune before the page ever reads it */
  try {
    var playerResponse;
    Object.defineProperty(window, 'ytInitialPlayerResponse', {
      configurable: true,
      get: function () { return playerResponse; },
      set: function (value) { playerResponse = deepPrune(value, 0); }
    });
  } catch (e) {}

  /* ------------------------------------------------------------------ *
   * 1b. Experiment flags — web "network machine" off (defensive)
   *
   * Mirrors uBO's current quick-fixes. Flips client-side experiment flags
   * off when they exist; no client identity, user agent or header is
   * changed. Best effort with a short retry, because ytcfg is populated
   * by page scripts after document start.
   * ------------------------------------------------------------------ */
  try {
    var flagTries = 0;
    var flagTimer = setInterval(function () {
      try {
        var f = window.ytcfg && window.ytcfg.data_ && window.ytcfg.data_.EXPERIMENT_FLAGS;
        if (f) {
          if ('all_web_enable_network_machine' in f) f.all_web_enable_network_machine = false;
          if ('all_web_network_machine_raw_request' in f) f.all_web_network_machine_raw_request = false;
          clearInterval(flagTimer);
        }
      } catch (e) { clearInterval(flagTimer); }
      if (++flagTries > 40) clearInterval(flagTimer);
    }, 250);
  } catch (e) {}

  /* ------------------------------------------------------------------ *
   * 2. UI layer — instant skip, overlay close, enforcement dismiss
   * ------------------------------------------------------------------ */
  var SKIP = '.ytp-ad-skip-button-modern,.ytp-ad-skip-button,.ytp-skip-ad-button,'
           + '.ytp-ad-skip-button-slot button,'
           + '.ytp-ad-player-overlay-layout .ytp-ad-skip-button-container button,'
           + 'button[class*="ytp-ad-skip"]';

  function visible(el) { return !!el && el.offsetParent !== null; }
  function click(el) { try { el.click(); return true; } catch (e) { return false; } }

  function sweep() {
    var skip = document.querySelector(SKIP);
    if (visible(skip) && !skip.disabled) click(skip);

    var overlayClose = document.querySelector('.ytp-ad-overlay-close-button');
    if (visible(overlayClose)) click(overlayClose);

    var enforcement = document.querySelector('ytd-enforcement-message-view-model');
    if (enforcement && !enforcement.__zeriumHandled) {
      enforcement.__zeriumHandled = true;
      var dialog = enforcement.closest('tp-yt-paper-dialog');
      if (dialog) dialog.style.display = 'none';
      document.querySelectorAll('tp-yt-iron-overlay-backdrop').forEach(function (b) {
        b.style.display = 'none';
      });
      var confirm = enforcement.querySelector(
        'button.yt-spec-button-shape-next--filled,button.yt-spec-button-shape-next--tonal,button');
      if (confirm) click(confirm);
    }
  }

  /* ------------------------------------------------------------------ *
   * 3. In-stream fallback state machine
   *
   * Only a CONFIRMED in-stream ad (.ad-showing / .ad-interrupting on the
   * player root) may touch the shared <video> element. Overlay ads never
   * trigger this. Rate and mute are captured BEFORE any change and
   * restored exactly when the ad state ends. (v1.1.0 matched the always
   * -present .ytp-ad-module and reset the rate to a hardcoded 1, which
   * fast-forwarded the main video — fixed here.)
   *
   * This layer is also what carries SSAP server-stitched mid-rolls: the
   * ad segments are inside the media stream, so the only client-side
   * option is to mute and fast-forward through the confirmed ad state.
   * ------------------------------------------------------------------ */
  var saved = null;

  function adStateMachine() {
    var player = document.querySelector('.html5-video-player');
    if (!player) return;
    var video = player.querySelector('video.html5-main-video') || player.querySelector('video');
    if (!video) return;

    var inStreamAd = player.classList.contains('ad-showing')
                  || player.classList.contains('ad-interrupting');

    if (inStreamAd && !saved) {
      saved = { rate: video.playbackRate || 1, muted: !!video.muted };
      try { video.muted = true; video.playbackRate = 16; } catch (e) {}
    } else if (!inStreamAd && saved) {
      try { video.playbackRate = saved.rate; video.muted = saved.muted; } catch (e) {}
      saved = null;
    }
  }

  /* ------------------------------------------------------------------ *
   * 4. CSS hiding — ad renderers in feeds/search/watch + leftovers
   * ------------------------------------------------------------------ */
  var CSS = [
    '#masthead-ad',
    'ytd-ad-slot-renderer',
    'ytd-in-feed-ad-layout-renderer',
    'ytd-display-ad-renderer',
    'ytd-compact-promoted-video-renderer',
    'ytd-promoted-sparkles-web-renderer',
    'ytd-promoted-sparkles-text-search-renderer',
    'ytd-video-masthead-ad-v3-renderer',
    'ytd-video-masthead-ad-advertiser-info-renderer',
    'ytd-search-pyv-renderer',
    'ytd-promoted-video-renderer',
    'ytd-companion-slot-renderer',
    'ytd-player-legacy-desktop-watch-ads-renderer',
    '#player-ads',
    'ytd-mealbar-promo-renderer',
    'ytm-promoted-video-renderer',
    'ytd-enforcement-message-view-model',
    '.ytp-ad-player-overlay',
    '.ytp-ad-message-container',
    '.ytp-ad-overlay-container',
    '.ytp-ad-text-overlay',
    '.ytp-ad-image-overlay',
    '.ytp-ad-action-interstitial',
    '.ytp-paid-content-overlay'
  ].join(',') + '{display:none!important}';

  function injectCss() {
    try {
      if (!document.getElementById('zerium-yt-css')) {
        var style = document.createElement('style');
        style.id = 'zerium-yt-css';
        style.textContent = CSS;
        (document.head || document.documentElement).appendChild(style);
      }
    } catch (e) {}
  }

  /* ------------------------------------------------------------------ *
   * 5. Loop — MutationObserver for instant reaction + 500 ms safety net
   * ------------------------------------------------------------------ */
  function tick() { injectCss(); sweep(); adStateMachine(); }

  try {
    var queued = false;
    var observer = new MutationObserver(function () {
      if (queued) return;
      queued = true;
      requestAnimationFrame(function () { queued = false; tick(); });
    });
    var attach = function () {
      if (document.body) observer.observe(document.body, { childList: true, subtree: true });
      else setTimeout(attach, 400);
    };
    attach();
  } catch (e) {}

  setInterval(tick, 500);
  tick();
})();
