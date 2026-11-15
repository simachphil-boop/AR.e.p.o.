'use strict';
const $ = (s) => document.querySelector(s);
const N = window.Native || { ready() {}, boot() { return 'ok'; }, cmd() {}, vibrate() {}, installEngine() {}, retry() {}, openSettings() {} };
const cmd = (c, extra) => N.cmd(JSON.stringify(Object.assign({ c }, extra || {})));
const fmt = (v) => Math.round(v).toLocaleString('ru-RU').replace(/,/g, ' ');

let st = { ph: 'MENU' };
let map = { pl: [], me: [0, 0], f: [0, 1] };
let booted = false, paused = false, howOpen = false;

// ===================== звук =====================
const A = {
  c: null,
  ctx() {
    if (!this.c) { try { this.c = new (window.AudioContext || window.webkitAudioContext)(); } catch (e) { return null; } }
    if (this.c.state === 'suspended') this.c.resume();
    return this.c;
  },
  tone(f, dur, type = 'sine', vol = 0.15, to = null, pan = 0, delay = 0) {
    const c = this.ctx(); if (!c) return;
    const t = c.currentTime + delay;
    const o = c.createOscillator(), g = c.createGain(), p = c.createStereoPanner ? c.createStereoPanner() : null;
    o.type = type; o.frequency.setValueAtTime(f, t);
    if (to) o.frequency.exponentialRampToValueAtTime(to, t + dur);
    g.gain.setValueAtTime(0.0001, t); g.gain.linearRampToValueAtTime(vol, t + 0.008); g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
    o.connect(g);
    if (p) { p.pan.value = Math.max(-1, Math.min(1, pan)); g.connect(p); p.connect(c.destination); } else g.connect(c.destination);
    o.start(t); o.stop(t + dur + 0.05);
  },
  noise(dur, vol = 0.2, type = 'bandpass', f = 1500, q = 1, pan = 0, delay = 0) {
    const c = this.ctx(); if (!c) return;
    const t = c.currentTime + delay;
    const n = Math.floor(c.sampleRate * dur), b = c.createBuffer(1, n, c.sampleRate), d = b.getChannelData(0);
    for (let i = 0; i < n; i++) d[i] = Math.random() * 2 - 1;
    const s = c.createBufferSource(); s.buffer = b;
    const fl = c.createBiquadFilter(); fl.type = type; fl.frequency.value = f; fl.Q.value = q;
    const g = c.createGain(); g.gain.setValueAtTime(vol, t); g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
    s.connect(fl); fl.connect(g);
    const p = c.createStereoPanner ? c.createStereoPanner() : null;
    if (p) { p.pan.value = Math.max(-1, Math.min(1, pan)); g.connect(p); p.connect(c.destination); } else g.connect(c.destination);
    s.start(t);
  }
};
const SFX = {
  grab() { A.tone(420, 0.12, 'square', 0.07, 900); },
  release() { A.tone(700, 0.12, 'square', 0.06, 320); },
  hit() { A.noise(0.09, 0.25, 'bandpass', 2600, 2); A.tone(1900, 0.05, 'triangle', 0.06); },
  break() { A.noise(0.6, 0.35, 'highpass', 2800, 0.7); for (let i = 0; i < 6; i++) A.tone(2000 + Math.random() * 4000, 0.08 + Math.random() * 0.2, 'sine', 0.06, null, 0, Math.random() * 0.25); },
  thud() { A.tone(140, 0.18, 'sine', 0.25, 50); },
  coins() { [988, 1319, 1568, 1976, 2637].forEach((f, i) => A.tone(f, 0.25, 'square', 0.06, null, 0, i * 0.08)); },
  start() { A.tone(110, 1.2, 'sawtooth', 0.05, 55); A.tone(880, 0.4, 'sine', 0.08, null, 0, 0.3); },
  hurt() { A.noise(0.35, 0.5, 'lowpass', 500, 1); A.tone(90, 0.35, 'sawtooth', 0.2, 45); },
  spotted() { A.tone(98, 1.1, 'sawtooth', 0.09, 92); A.tone(104, 1.1, 'sawtooth', 0.09, 99); A.noise(0.9, 0.08, 'lowpass', 300); },
  scrape(p) { A.noise(0.4, 0.12, 'bandpass', 700, 3, p); },
  leap(p) { A.noise(0.3, 0.2, 'highpass', 3000, 1, p); A.tone(300, 0.25, 'sawtooth', 0.08, 900, p); },
  shock() { A.tone(1400, 0.35, 'sawtooth', 0.12, 120); A.noise(0.3, 0.2, 'highpass', 4000); },
  heal() { [523, 659, 784].forEach((f, i) => A.tone(f, 0.3, 'sine', 0.1, null, 0, i * 0.09)); },
  buy() { A.tone(784, 0.1, 'square', 0.07); A.tone(1047, 0.18, 'square', 0.07, null, 0, 0.09); },
  blip() { A.tone(1200, 0.06, 'sine', 0.06); },
  beat(vol) { A.tone(62, 0.12, 'sine', vol, 40); A.tone(58, 0.12, 'sine', vol * 0.8, 38, 0, 0.18); },
  growl(p) { A.noise(0.7, 0.18, 'lowpass', 260, 4, p); A.tone(70, 0.7, 'sawtooth', 0.05, 55, p); },
  det(p) { A.tone(1600, 0.05, 'sine', 0.05, null, p); },
  dead() { A.tone(220, 1.5, 'sawtooth', 0.12, 40); A.noise(1.2, 0.2, 'lowpass', 400); }
};
document.addEventListener('touchstart', () => A.ctx(), { once: true });

// ===================== уведомления =====================
function toast(text, cls = '') {
  const t = document.createElement('div'); t.className = 'toast ' + cls; t.textContent = text;
  $('#toasts').appendChild(t);
  setTimeout(() => t.remove(), 2700);
  while ($('#toasts').children.length > 3) $('#toasts').firstChild.remove();
}
function flash(color = '#ff0000', o = 0.45) {
  const f = $('#flash'); f.style.background = color; f.style.transition = 'none'; f.style.opacity = o;
  requestAnimationFrame(() => { f.style.transition = 'opacity .5s'; f.style.opacity = 0; });
}

// ===================== экраны =====================
const SCREENS = ['boot', 'menu', 'how', 'scan', 'intro', 'hud', 'pause', 'done', 'shop', 'dead'];
function show(...ids) { SCREENS.forEach((s) => { $('#' + s).hidden = !ids.includes(s); }); }

function screenFor() {
  if (!booted) return ['boot'];
  if (howOpen) return ['how'];
  switch (st.ph) {
    case 'MENU': return ['menu'];
    case 'SCAN': return ['scan'];
    case 'INTRO': return ['intro'];
    case 'PLAY': return paused ? ['hud', 'pause'] : ['hud'];
    case 'DONE': return ['done'];
    case 'SHOP': return ['shop'];
    case 'DEAD': return ['dead'];
  }
  return ['menu'];
}

function bootLoop() {
  const b = N.boot();
  const msg = $('#bootMsg'), btns = $('#bootBtns');
  if (b === 'ok') { booted = true; render(); return; }
  if (b === 'checking') { msg.textContent = 'Запуск камеры и AR Engine…'; btns.innerHTML = ''; }
  else if (b === 'nocamera') {
    msg.innerHTML = '📷 Игре нужен доступ к камере — через неё ты видишь ценности в квартире.';
    btns.innerHTML = '<button class="btn big gold" id="bRetry">Разрешить камеру</button><button class="btn" id="bSet">Открыть настройки</button>';
    $('#bRetry').onclick = () => N.retry(); $('#bSet').onclick = () => N.openSettings();
  } else if (b === 'noengine') {
    msg.innerHTML = '🧩 Нужен сервис <b>HUAWEI AR Engine</b>. Он бесплатный и ставится из AppGallery. После установки вернись в игру.';
    btns.innerHTML = '<button class="btn big gold" id="bInst">Установить AR Engine</button><button class="btn" id="bRetry">Я установил — проверить</button>';
    $('#bInst').onclick = () => N.installEngine(); $('#bRetry').onclick = () => N.retry();
  } else if (b.startsWith('error:')) {
    msg.innerHTML = '⚠️ Не получилось запустить AR:<br><small>' + b.slice(6) + '</small>';
    btns.innerHTML = '<button class="btn big gold" id="bRetry">Попробовать снова</button>';
    $('#bRetry').onclick = () => N.retry();
  }
  show('boot');
  setTimeout(bootLoop, 500);
}

// ===================== состояние =====================
let lastShop = '';
window.S = function (s) {
  st = s;
  if (st.ph !== 'PLAY') paused = false;
  render();
};

function render() {
  if (!booted) return;
  show(...screenFor());
  const s = st;
  switch (s.ph) {
    case 'MENU':
      $('#mBest').textContent = s.best ? 'уровень ' + s.best : '—';
      $('#mLvl').textContent = s.lvl; $('#mMoney').textContent = fmt(s.money) + ' $';
      break;
    case 'SCAN': renderScan(s); break;
    case 'INTRO':
      $('#iLvl').textContent = s.lvl; $('#iQuota').textContent = fmt(s.quota) + ' $';
      $('#iHp').textContent = s.hp + ' / ' + s.hpMax; $('#iMoney').textContent = fmt(s.money) + ' $';
      $('#iDanger').textContent = ['нет', 'нет', '👤 Тень', '🗿 Статуя', '👤🗿', '👤🕷'][s.lvl] || '👤🗿🕷' + (s.lvl >= 8 ? '👤' : '');
      break;
    case 'PLAY': renderHud(s); break;
    case 'DONE': renderDone(s.sum || {}); break;
    case 'SHOP': renderShop(s); break;
    case 'DEAD': renderDead(s.sum || {}); break;
  }
}

function renderScan(s) {
  const fl = s.floor || 0;
  $('#sFloor').textContent = fl.toFixed(1);
  $('#sSurf').textContent = s.surf || 0;
  $('#sDoors').textContent = s.doors || 0;
  $('#sWalk').textContent = Math.round(s.walked || 0);
  $('#sBar').style.width = Math.min(100, fl / 25 * 100) + '%';
  let hint;
  if (!s.trk) hint = '📷 Медленно поводи камерой — ищу пространство…';
  else if (fl < 1.5) hint = 'Направь камеру на пол и медленно води ей. Пол подсветится голубым.';
  else if (fl < 8) hint = 'Отлично! Теперь пройди в другие комнаты. Столы и диваны тоже сканируй — там будут ценности.';
  else if (fl < 18) hint = 'Хорошо идёт! Загляни во все комнаты, на кухню и в коридор.';
  else hint = 'Квартира отлично отсканирована. Поставь точку сдачи и жми «Готово».';
  $('#scanHint').textContent = hint;
  $('#sZone').textContent = s.hasZone ? '📍 Перенести точку сдачи сюда' : '📍 Точка сдачи здесь';
  $('#sDone').disabled = fl < 1.5;
}

const fragTxt = (f) => f >= 1.7 ? 'очень хрупкое' : f >= 1.05 ? 'хрупкое' : f <= 0.5 ? 'прочное' : 'обычное';

let beatT = 0, growlT = 0, detT = 0;
function renderHud(s) {
  $('#hLvl').textContent = s.lvl;
  $('#hZone').textContent = fmt(s.zoneVal || 0); $('#hQuota').textContent = fmt(s.quota || 0);
  const q = s.quota ? Math.min(1, (s.zoneVal || 0) / s.quota) : 0;
  $('#hBar').style.width = q * 100 + '%';
  $('.quota').classList.toggle('ok', q >= 1);
  $('#hHp').style.width = (s.hp / s.hpMax * 100) + '%'; $('#hHpT').textContent = s.hp;
  $('#lost').hidden = !!s.trk;
  // прицел
  $('#cross').classList.toggle('on', !!s.aim);
  const al = $('#aimL');
  if (s.aim && !s.held) { al.hidden = false; al.innerHTML = `${s.aim.e} ${s.aim.n} · <span style="color:var(--gold)">${fmt(s.aim.v)} $</span><small>${fragTxt(s.aim.fr)}</small>`; }
  else al.hidden = true;
  // в руках
  const hc = $('#heldC'), g = $('#hGrab');
  if (s.held) {
    hc.hidden = false;
    $('#heldN').textContent = s.held.e + ' ' + s.held.n;
    $('#heldV').textContent = fmt(s.held.v) + ' $';
    const r = s.held.v / s.held.max;
    $('#heldBar').style.width = r * 100 + '%';
    $('#heldBar').style.background = r > 0.6 ? 'linear-gradient(90deg,#5ef08a,#22b552)' : r > 0.3 ? 'linear-gradient(90deg,#ffc23d,#ff9a1f)' : 'linear-gradient(90deg,#ff4646,#ff7b5a)';
    g.textContent = '👐 Отпустить'; g.classList.add('drop');
  } else {
    hc.hidden = true; g.textContent = '✋ Взять'; g.classList.remove('drop');
  }
  $('#hExtract').hidden = !s.canExtract;
  // стрелки
  if (s.zd != null) {
    $('#zArrow .a').style.transform = `rotate(${s.za}deg)`;
    $('#zDist').textContent = '🏁 ' + s.zd.toFixed(1) + ' м';
  }
  const da = $('#dArrow');
  if (s.det) { da.hidden = false; da.querySelector('.a').style.transform = `rotate(${s.det.a}deg)`; da.querySelector('span').textContent = '📡 ' + s.det.d.toFixed(1) + ' м'; }
  else da.hidden = true;
  // расходники
  [['med', '#cMed'], ['shock', '#cShock'], ['magnet', '#cMagnet']].forEach(([k, id]) => {
    const n = (s.cons || {})[k] || 0; const b = $(id); b.querySelector('i').textContent = n; b.classList.toggle('empty', n <= 0);
  });
  // монстры: виньетка, сердце, рычание
  const m = s.mon, now = performance.now();
  let danger = 0;
  if (m && !m.stun) danger = Math.max(0, Math.min(1, (5 - m.d) / 4.2));
  $('#vig').style.boxShadow = `inset 0 0 ${60 + danger * 90}px ${danger * 40}px rgba(200,0,0,${danger * 0.65})`;
  const mw = $('#monW');
  if (m && m.d < 4 && !m.stun) {
    mw.hidden = false;
    const side = Math.abs(m.a) < 40 ? 'впереди' : Math.abs(m.a) > 130 ? 'сзади' : m.a > 0 ? 'справа' : 'слева';
    const who = ['👤 Тень', '🗿 Статуя', '🕷 Прыгун'][m.k];
    mw.textContent = `${who} ${side} · ${m.d.toFixed(1)} м`;
  } else mw.hidden = true;
  if (m && m.d < 6 && !m.stun && now > beatT) { SFX.beat(0.12 + danger * 0.35); beatT = now + 350 + (m.d / 6) * 1100; if (danger > 0.6) N.vibrate(30); }
  if (m && m.d < 3.2 && m.hunt && !m.stun && now > growlT) { SFX.growl(Math.sin(m.a * Math.PI / 180)); growlT = now + 2500 + Math.random() * 2000; }
  if (s.det && now > detT) { SFX.det(Math.sin(s.det.a * Math.PI / 180) * 0.8); detT = now + 250 + Math.min(2000, s.det.d * 350); }
}

function renderDone(sum) {
  $('#doneCard').innerHTML = `
    <div class="row between"><span>Уровень</span><b>${sum.lvl || ''}</b></div>
    <div class="row between"><span>Сдано вещей</span><b>${sum.n || 0}</b></div>
    <div class="row between"><span>💰 Заработано</span><b style="color:var(--gold)">+${fmt(sum.got || 0)} $</b></div>
    <div class="row between"><span>🎯 Квота была</span><b>${fmt(sum.quota || 0)} $</b></div>
    <div class="row between"><span>💥 Разбито</span><b>${sum.broken || 0} (−${fmt(sum.lost || 0)} $)</b></div>
    <div class="row between"><span>Осталось в квартире</span><b>${sum.left || 0}</b></div>`;
}
function renderDead(sum) {
  $('#deadCard').innerHTML = `
    <div class="row between"><span>Дошёл до уровня</span><b>${sum.lvl || 1}</b></div>
    <div class="row between"><span>Заработано за забег</span><b>${fmt(sum.earned || 0)} $</b></div>
    <div class="row between"><span>🏆 Рекорд</span><b>уровень ${sum.best || 1}</b></div>
    <div class="hint">Улучшения и деньги сгорели. Квартира остаётся отсканированной.</div>`;
}
function renderShop(s) {
  $('#shMoney').textContent = fmt(s.money) + ' $';
  $('#shLvl').textContent = s.lvl; $('#shQuota').textContent = fmt(s.nextQuota || 0);
  const key = JSON.stringify(s.shop) + s.money;
  if (key === lastShop) return;
  lastShop = key;
  const g = $('#shGrid'); g.innerHTML = '';
  (s.shop || []).forEach((it) => {
    const max = it.l >= it.max;
    const d = document.createElement('div');
    d.className = 'sitem' + (max ? ' max' : '');
    const lv = it.cons ? `В запасе: ${it.l}` : (it.max > 1 ? `Уровень ${it.l} / ${it.max}` : (it.l ? 'Куплено ✓' : ''));
    d.innerHTML = `<div class="e">${it.e}</div><div class="n">${it.n}</div><div class="d">${it.d}</div><div class="lv">${lv}</div>
      <button class="btn ${!max && s.money >= it.p ? 'gold' : ''}" ${max || s.money < it.p ? 'disabled' : ''}>${max ? 'Максимум' : fmt(it.p) + ' $'}</button>`;
    d.querySelector('button').onclick = () => cmd('buy', { id: it.id });
    g.appendChild(d);
  });
}

// ===================== карта =====================
window.M = function (m) {
  if (m.pl) map.pl = m.pl;
  map.me = m.me; map.f = m.f; map.zone = m.zone; map.items = m.items; map.mons = m.mons;
  if (st.ph === 'SCAN') drawMap($('#scanMap'), false);
  else if (st.ph === 'PLAY') drawMap($('#miniMap'), true);
};

function drawMap(cv, heading) {
  const dpr = window.devicePixelRatio || 1, w = cv.clientWidth, h = cv.clientHeight;
  if (!w || !h) return;
  if (cv.width !== w * dpr) { cv.width = w * dpr; cv.height = h * dpr; }
  const g = cv.getContext('2d'); g.setTransform(dpr, 0, 0, dpr, 0, 0); g.clearRect(0, 0, w, h);
  const me = map.me || [0, 0], f = map.f || [0, 1];
  let tf, scale;
  if (heading) {
    // карта повёрнута по взгляду, игрок в центре
    const r = [-f[1], f[0]]; // вправо от взгляда в плоскости xz (z вниз)
    scale = Math.min(w, h) / 2 / 3.5;
    tf = (x, z) => {
      const dx = x - me[0], dz = z - me[1];
      const sx = dx * r[0] + dz * r[1], sy = -(dx * f[0] + dz * f[1]);
      return [w / 2 + sx * scale, h / 2 + sy * scale];
    };
    g.save(); g.beginPath(); g.arc(w / 2, h / 2, Math.min(w, h) / 2 - 1, 0, Math.PI * 2); g.clip();
  } else {
    let minX = me[0] - 2, maxX = me[0] + 2, minZ = me[1] - 2, maxZ = me[1] + 2;
    map.pl.forEach((p) => { for (let i = 0; i < p.p.length; i += 2) { minX = Math.min(minX, p.p[i]); maxX = Math.max(maxX, p.p[i]); minZ = Math.min(minZ, p.p[i + 1]); maxZ = Math.max(maxZ, p.p[i + 1]); } });
    scale = Math.min((w - 20) / (maxX - minX), (h - 20) / (maxZ - minZ));
    const ox = (w - (maxX - minX) * scale) / 2, oz = (h - (maxZ - minZ) * scale) / 2;
    tf = (x, z) => [ox + (x - minX) * scale, oz + (z - minZ) * scale];
  }
  const col = { floor: 'rgba(56,225,255,.35)', surf: 'rgba(255,194,61,.6)', other: 'rgba(255,255,255,.15)' };
  // поверхности
  map.pl.forEach((p) => {
    if (p.k === 'wall' || p.k === 'door') return;
    g.beginPath();
    for (let i = 0; i < p.p.length; i += 2) { const [x, y] = tf(p.p[i], p.p[i + 1]); i ? g.lineTo(x, y) : g.moveTo(x, y); }
    g.closePath(); g.fillStyle = col[p.k] || col.other; g.fill();
  });
  // стены и двери — отрезком
  map.pl.forEach((p) => {
    if (p.k !== 'wall' && p.k !== 'door') return;
    let best = [0, 0], bd = -1;
    for (let i = 0; i < p.p.length; i += 2) for (let j = i + 2; j < p.p.length; j += 2) {
      const d = (p.p[i] - p.p[j]) ** 2 + (p.p[i + 1] - p.p[j + 1]) ** 2; if (d > bd) { bd = d; best = [i, j]; }
    }
    const a = tf(p.p[best[0]], p.p[best[0] + 1]), b = tf(p.p[best[1]], p.p[best[1] + 1]);
    g.strokeStyle = p.k === 'door' ? '#3bdc6e' : 'rgba(170,140,255,.85)'; g.lineWidth = p.k === 'door' ? 4 : 2.5;
    g.beginPath(); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); g.stroke();
  });
  // ценности и монстры
  (map.items || []).forEach((it) => { const [x, y] = tf(it[0], it[1]); g.fillStyle = it[2] ? '#3bdc6e' : '#ffc23d'; g.beginPath(); g.arc(x, y, 3.5, 0, 7); g.fill(); });
  (map.mons || []).forEach((m) => { const [x, y] = tf(m[0], m[1]); g.fillStyle = '#ff4646'; g.beginPath(); g.arc(x, y, 5, 0, 7); g.fill(); });
  // точка сдачи
  if (map.zone) {
    const [x, y] = tf(map.zone[0], map.zone[1]);
    g.strokeStyle = '#3bdc6e'; g.lineWidth = 3; g.beginPath(); g.arc(x, y, Math.max(6, 0.55 * scale), 0, 7); g.stroke();
    g.fillStyle = '#3bdc6e'; g.font = 'bold 12px system-ui'; g.fillText('🏁', x - 7, y + 4);
  }
  // игрок
  const [px, py] = tf(me[0], me[1]);
  let ang = heading ? -Math.PI / 2 : Math.atan2(f[1], f[0]);
  g.save(); g.translate(px, py); g.rotate(ang);
  g.fillStyle = '#fff'; g.beginPath(); g.moveTo(9, 0); g.lineTo(-6, 6); g.lineTo(-3, 0); g.lineTo(-6, -6); g.closePath(); g.fill();
  g.restore();
  if (heading) g.restore();
}

// ===================== события =====================
window.E = function (e) {
  switch (e.t) {
    case 'msg': toast(e.m); SFX.blip(); break;
    case 'zone': SFX.blip(); N.vibrate(20); break;
    case 'scanDone': SFX.coins(); break;
    case 'levelStart': SFX.start(); toast(`Уровень ${e.lvl}: собери ${fmt(e.quota)} $`, 'money'); if (e.mon) setTimeout(() => toast('Ты здесь не один…', 'bad'), 1800); break;
    case 'grab': SFX.grab(); N.vibrate(15); break;
    case 'release': SFX.release(); break;
    case 'hit': SFX.hit(); N.vibrate(25); toast(`💥 ${e.n} −${fmt(e.v)} $`, 'bad'); break;
    case 'thud': SFX.thud(); break;
    case 'break': SFX.break(); N.vibrate(120); flash('#ffffff', 0.25); toast(`💔 ${e.n} разбилось! −${fmt(e.v)} $`, 'bad'); break;
    case 'extract': SFX.coins(); N.vibrate(80); flash('#3bdc6e', 0.3); break;
    case 'hurt': SFX.hurt(); N.vibrate(250); flash('#ff0000', 0.5); toast(`−${e.d} ❤️`, 'bad'); break;
    case 'spotted': SFX.spotted(); toast('👁 Тебя заметили!', 'bad'); N.vibrate(60); break;
    case 'statue': SFX.scrape(0); break;
    case 'leap': SFX.leap(0); break;
    case 'shock': SFX.shock(); flash('#7fdcff', 0.35); toast(e.n ? `⚡ Оглушено: ${e.n}` : '⚡ Мимо — рядом никого'); break;
    case 'heal': SFX.heal(); toast('🩹 +40 ❤️', 'good'); break;
    case 'buy': SFX.buy(); toast('Куплено: ' + e.n, 'good'); break;
    case 'dead': SFX.dead(); N.vibrate(500); break;
  }
};

window.onBack = function () {
  if (howOpen) { howOpen = false; render(); return; }
  if (st.ph === 'PLAY') { paused = !paused; render(); }
};

// ===================== кнопки =====================
$('#mStart').onclick = () => { A.ctx(); cmd('start'); };
$('#mHow').onclick = () => { howOpen = true; render(); };
$('#howBack').onclick = () => { howOpen = false; render(); };
$('#sZone').onclick = () => cmd('setZone');
$('#sDone').onclick = () => cmd('finishScan');
$('#iStart').onclick = () => { A.ctx(); cmd('startLevel'); };
$('#iRescan').onclick = () => cmd('scan');
$('#hGrab').onclick = () => cmd(st.held ? 'release' : 'grab');
$('#hExtract').onclick = () => cmd('extract');
$('#hPause').onclick = () => { paused = true; render(); };
$('#pResume').onclick = () => { paused = false; render(); };
$('#pAbandon').onclick = () => { paused = false; cmd('abandon'); };
$('#pRescan').onclick = () => { paused = false; cmd('scan'); };
$('#pNew').onclick = () => { paused = false; cmd('newRun'); };
$('#dShop').onclick = () => cmd('shop');
$('#shNext').onclick = () => cmd('next');
$('#deadNew').onclick = () => cmd('newRun');
$('#cMed').onclick = () => cmd('use', { id: 'med' });
$('#cShock').onclick = () => cmd('use', { id: 'shock' });
$('#cMagnet').onclick = () => cmd('use', { id: 'magnet' });

N.ready();
bootLoop();
