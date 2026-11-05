/* EasonYin's Minecraft – 3D 浮空方块岛 (纯 CSS 3D + 程序生成像素贴图，无依赖) */
(function () {
  if (window.__mc3d) return; window.__mc3d = 1;
  var reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  function rnd(s) { return function () { s = (s * 16807) % 2147483647; return s / 2147483647; }; }
  function tex(kind, seed) {
    var c = document.createElement('canvas'); c.width = c.height = 16;
    var g = c.getContext('2d'), r = rnd(seed || 7);
    function px(x, y, col) { g.fillStyle = col; g.fillRect(x, y, 1, 1); }
    function noise(base, v) { for (var y = 0; y < 16; y++) for (var x = 0; x < 16; x++) { var k = (r() - .5) * v; px(x, y, 'rgb(' + (base[0] + k | 0) + ',' + (base[1] + k | 0) + ',' + (base[2] + k | 0) + ')'); } }
    if (kind === 'grassTop') noise([92, 150, 58], 40);
    else if (kind === 'dirt') noise([134, 96, 67], 36);
    else if (kind === 'stone') noise([125, 125, 125], 34);
    else if (kind === 'grassSide') { noise([134, 96, 67], 36); for (var x = 0; x < 16; x++) { var d = 3 + (r() * 3 | 0); for (var y = 0; y < d; y++) { var k = (r() - .5) * 40; px(x, y, 'rgb(' + (92 + k | 0) + ',' + (150 + k | 0) + ',' + (58 + k | 0) + ')'); } } }
    else if (kind === 'logSide') { for (var x2 = 0; x2 < 16; x2++) { var b = 90 + (x2 % 3 ? 0 : -18) + (r() - .5) * 14; for (var y2 = 0; y2 < 16; y2++) px(x2, y2, 'rgb(' + (b | 0) + ',' + (b * .72 | 0) + ',' + (b * .42 | 0) + ')'); } }
    else if (kind === 'logTop') { noise([170, 136, 82], 20); g.strokeStyle = '#8a6a3c'; g.strokeRect(3.5, 3.5, 9, 9); g.strokeRect(6.5, 6.5, 3, 3); g.strokeStyle = '#6b5030'; g.strokeRect(.5, .5, 15, 15); }
    else if (kind === 'leaves') { for (var y3 = 0; y3 < 16; y3++) for (var x3 = 0; x3 < 16; x3++) { var k3 = r(); px(x3, y3, k3 < .18 ? 'rgba(20,60,20,.9)' : 'rgb(' + (40 + k3 * 40 | 0) + ',' + (110 + k3 * 50 | 0) + ',' + (35 + k3 * 20 | 0) + ')'); } }
    else if (kind === 'diamond' || kind === 'gold') { noise([125, 125, 125], 34); var oc = kind === 'diamond' ? ['#5decf5', '#2bb5c0', '#c8fbff'] : ['#f5d14a', '#c99a22', '#fff3a8']; for (var i = 0; i < 6; i++) { var ox = 1 + r() * 12 | 0, oy = 1 + r() * 12 | 0; px(ox, oy, oc[0]); px(ox + 1, oy, oc[1]); px(ox, oy + 1, oc[1]); px(ox + 1, oy + 1, oc[2]); } }
    return c.toDataURL();
  }
  var T = {}; ['grassTop', 'grassSide', 'dirt', 'stone', 'logSide', 'logTop', 'leaves', 'diamond', 'gold'].forEach(function (k, i) { T[k] = tex(k, 11 + i * 97); });
  var BLOCK = {
    grass: [T.grassTop, T.dirt, T.grassSide], dirt: [T.dirt, T.dirt, T.dirt], stone: [T.stone, T.stone, T.stone],
    log: [T.logTop, T.logTop, T.logSide], leaves: [T.leaves, T.leaves, T.leaves], diamond: [T.diamond, T.diamond, T.diamond], gold: [T.gold, T.gold, T.gold]
  };
  var S = 34; // block size px
  var css = '' +
    '.mc3d{position:absolute;inset:0;pointer-events:none;perspective:1100px;z-index:1}' +
    '.mc3d-world{position:absolute;left:50%;top:50%;transform-style:preserve-3d;transform:rotateX(-24deg) rotateY(var(--ry,35deg));transition:transform .9s cubic-bezier(.2,.7,.2,1)}' +
    '.mc3d-float{position:absolute;transform-style:preserve-3d;animation:mc3dBob 6s ease-in-out infinite}' +
    '.mc3d-spin{transform-style:preserve-3d;animation:mc3dSpin 40s linear infinite}' +
    '.mc3d-b{position:absolute;width:' + S + 'px;height:' + S + 'px;transform-style:preserve-3d}' +
    '.mc3d-b i{position:absolute;inset:0;background-size:100% 100%;image-rendering:pixelated;backface-visibility:hidden}' +
    '.mc3d-b.lv i{backface-visibility:visible}' +
    '.mc3d-drop{position:absolute;transform-style:preserve-3d;animation:mc3dDrop 7s ease-in-out infinite}' +
    '.mc3d-shadow{position:absolute;left:50%;top:78%;width:260px;height:60px;margin-left:-130px;border-radius:50%;background:radial-gradient(#0008,#0000 70%);filter:blur(6px);animation:mc3dSh 6s ease-in-out infinite}' +
    '@keyframes mc3dBob{0%,100%{transform:translateY(0)}50%{transform:translateY(-14px)}}' +
    '@keyframes mc3dSpin{to{transform:rotateY(360deg)}}' +
    '@keyframes mc3dDrop{0%,100%{transform:translateY(0) rotateY(0)}50%{transform:translateY(-22px) rotateY(180deg)}}' +
    '@keyframes mc3dSh{0%,100%{transform:scale(1);opacity:.9}50%{transform:scale(.88);opacity:.6}}' +
    '@media (max-width:900px){.mc3d-world{left:50%;top:50%;scale:.72}.mc3d-shadow{left:50%;top:80%;scale:.72}}' +
    '@media (prefers-reduced-motion:reduce){.mc3d-float,.mc3d-spin,.mc3d-drop,.mc3d-shadow{animation:none}}';
  function cube(type, x, y, z, light) {
    var t = BLOCK[type], b = document.createElement('div'); b.className = 'mc3d-b' + (type === 'leaves' ? ' lv' : '');
    b.style.transform = 'translate3d(' + (x * S - S / 2) + 'px,' + (-y * S - S / 2) + 'px,' + (z * S) + 'px)';
    var h = S / 2, faces = [
      [t[0], 'rotateX(90deg) translateZ(' + h + 'px)', 1.08], [t[1], 'rotateX(-90deg) translateZ(' + h + 'px)', .45],
      [t[2], 'translateZ(' + h + 'px)', .92], [t[2], 'rotateY(180deg) translateZ(' + h + 'px)', .7],
      [t[2], 'rotateY(90deg) translateZ(' + h + 'px)', .8], [t[2], 'rotateY(-90deg) translateZ(' + h + 'px)', .62]];
    faces.forEach(function (f) { var i = document.createElement('i'); i.style.backgroundImage = 'url(' + f[0] + ')'; i.style.transform = f[1]; i.style.filter = 'brightness(' + (f[2] * (light || 1)) + ')'; b.appendChild(i); });
    return b;
  }
  function build(host) {
    if (!document.getElementById('mc3d-style')) { var st = document.createElement('style'); st.id = 'mc3d-style'; st.textContent = css; document.head.appendChild(st); }
    var root = document.createElement('div'); root.className = 'mc3d'; root.setAttribute('aria-hidden', 'true');
    var sh = document.createElement('div'); sh.className = 'mc3d-shadow'; root.appendChild(sh);
    var world = document.createElement('div'); world.className = 'mc3d-world';
    var fl = document.createElement('div'); fl.className = 'mc3d-float';
    var sp = document.createElement('div'); sp.className = 'mc3d-spin';
    var blocks = [];
    // 3 层浮空岛：顶层草方块 5x5（去角），中层泥土，底层石头/矿石，尖底
    for (var x = -2; x <= 2; x++) for (var z = -2; z <= 2; z++) {
      if (Math.abs(x) === 2 && Math.abs(z) === 2) continue;
      blocks.push(['grass', x, 0, z]);
      if (Math.abs(x) + Math.abs(z) <= 2) blocks.push(['dirt', x, -1, z]);
      if (Math.abs(x) + Math.abs(z) <= 1) blocks.push([(x === 1 && z === 0) ? 'diamond' : (x === 0 && z === -1 ? 'gold' : 'stone'), x, -2, z]);
    }
    blocks.push(['stone', 0, -3, 0]);
    // 橡树
    for (var h = 1; h <= 4; h++) blocks.push(['log', -1, h, -1]);
    for (var lx = -2; lx <= 0; lx++) for (var lz = -2; lz <= 0; lz++) for (var ly = 3; ly <= 4; ly++) if (!(lx === -1 && lz === -1) && !(ly === 4 && Math.abs(lx + 1) + Math.abs(lz + 1) === 2)) blocks.push(['leaves', lx, ly, lz]);
    blocks.push(['leaves', -1, 5, -1]);
    // 画家排序（rotateY 动画下近似即可，preserve-3d 负责实际遮挡）
    blocks.forEach(function (b) { sp.appendChild(cube(b[0], b[1], b[2], b[3])); });
    fl.appendChild(sp); world.appendChild(fl);
    // 漂浮掉落物
    [['diamond', 3.4, 2.2, 1.2, '0s'], ['gold', -3.2, 1.2, 2.4, '-2.3s'], ['grass', 2.6, 4.4, -2.6, '-4.6s']].forEach(function (d) {
      var w = document.createElement('div'); w.className = 'mc3d-drop'; w.style.animationDelay = d[4];
      var c = cube(d[0], d[1], d[2], d[3]); c.style.scale = '.45'; w.appendChild(c); world.appendChild(w);
    });
    root.appendChild(world);
    var shade = null;
    host.insertBefore(root, shade ? shade.nextSibling : null);
    if (!reduce && matchMedia('(pointer:fine)').matches) {
      host.addEventListener('pointermove', function (e) { var r = host.getBoundingClientRect(); var dx = (e.clientX - r.left) / r.width - .5, dy = (e.clientY - r.top) / r.height - .5; world.style.transform = 'rotateX(' + (-24 + dy * 10) + 'deg) rotateY(' + (35 + dx * 24) + 'deg)'; });
      host.addEventListener('pointerleave', function () { world.style.transform = ''; });
    }
  }
  function init() { var h = document.querySelector('[data-mc3d]'); if (h && !h.querySelector('.mc3d')) build(h); }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();
  // Next 客户端导航回首页时重新挂载
  var t; new MutationObserver(function () { if (document.querySelector('.hero-scene .mc3d')) return; clearTimeout(t); t = setTimeout(init, 150); }).observe(document.body || document.documentElement, { childList: true });
  addEventListener('popstate', function () { setTimeout(init, 150); });
})();
