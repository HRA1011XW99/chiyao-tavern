// The app's own pages. Talks to the shell through window.Shell (ShellBridge.kt); slow calls answer
// through window.onShell(type, data). Pages are <section>s; history is a simple stack for the back key.
'use strict';

const $ = (sel) => document.querySelector(sel);
const stack = [];
let status = {};
let pluginsChanged = false;
let shellOfferShown = false;

function show(id) {
  document.querySelectorAll('.page').forEach((p) => { p.hidden = p.id !== id; });
  window.scrollTo(0, 0);
  if (id === 'settings') fillSettings();
  if (id === 'network') fillNetwork();
  if (id === 'plugins') fillPlugins();
  if (id === 'log') $('#l-text').textContent = Shell.logText() || '暂无日志';
}
function current() { return stack[stack.length - 1] || null; }

// page for the current phase when nothing else is open
function home() {
  const s = status;
  if (s.phase === 'ONBOARD') return 'onboard';
  if (!s.installed && !s.onboarded && ['IDLE', 'FAILED'].includes(s.phase) && !s.message) return 'welcome';
  if (s.phase === 'READY') return 'menu';
  return 'start';
}

window.go = (id) => { stack.push(id); show(id); };
window.back = () => {
  if (!$('#dialog').hidden) { closeDialog(); return true; }
  if (stack.length === 0) return false;
  stack.pop();
  show(current() || home());
  return true;
};
window.onShown = () => { if (!current()) show(home()); };

/* —— start-up page —— */
const MB = (n) => (n / 1048576).toFixed(1);

function renderStart(s) {
  $('#s-message').textContent = s.message || '正在准备';
  $('#s-detail').textContent = s.detail || '';
  const downloading = s.phase === 'DOWNLOADING';
  $('#s-bar').hidden = !downloading;
  $('#s-bar i').style.transform = `scaleX(${s.total > 0 ? Math.min(1, s.done / s.total) : 0})`;
  $('#s-bytes').textContent = downloading && s.total > 0 ? `${MB(s.done)} / ${MB(s.total)} MB` : '';
  const failed = s.phase === 'FAILED' || s.phase === 'CRASHED';
  const log = $('#s-log');
  log.hidden = !failed || !(s.log && s.log.length);
  if (!log.hidden) { log.textContent = s.log.join('\n'); log.scrollTop = log.scrollHeight; }

  const acts = [];
  if (s.canSkip) acts.push(['skip', '跳过本次更新', '']);
  if (s.phase === 'CRASHED') acts.push(['restart', '重启', 'primary']);
  if (s.phase === 'FAILED') acts.push(['boot', '重试', 'primary']);
  if (failed) {
    acts.push(['copyLog', '复制日志', '']);
    if (s.previous) acts.push(['rollback', `回退到 ${s.previous.replace(/-\d+$/, '')}`, '']);
    acts.push(['settings', '设置', '']);
  }
  const box = $('#s-actions');
  box.replaceChildren(...acts.map(([act, label, cls]) => {
    const b = document.createElement('button');
    b.textContent = label;
    if (cls) b.className = cls;
    if (act === 'settings') b.dataset.go = 'settings'; else b.dataset.act = act;
    return b;
  }));
}

function render(s) {
  status = s;
  renderStart(s);
  if (!current()) show(home());
  if (s.notice) { toast(s.notice); Shell.clearNotice(); }
  if (s.phase === 'READY' && s.shellOffer && !shellOfferShown) {
    shellOfferShown = true;
    offerShell(s.shellOffer, true);
  }
}

/* —— settings —— */
function fillSettings() {
  $('#v-shell').textContent = status.shellVersion || '';
  $('#v-st').textContent = (status.installed || '未安装').replace(/-\d+$/, '');
}

function fillNetwork() {
  const s = JSON.parse(Shell.settings());
  $('#n-prefix').value = s.githubPrefix;
  $('#n-npm').value = s.npmRegistry;
  $('#n-git').checked = s.gitMirror;
  $('#n-port').value = s.port;
  $('#n-error').textContent = '';
}

/* —— plugins —— */
function fillPlugins() {
  const list = JSON.parse(Shell.plugins());
  $('#p-restart').hidden = !pluginsChanged || status.phase !== 'READY';
  const box = $('#p-list');
  if (!list.length) {
    box.innerHTML = '<div class="empty">尚未安装服务端插件</div>';
    return;
  }
  box.replaceChildren(...list.map((p) => {
    const el = document.createElement('div');
    el.className = 'plugin' + (p.enabled ? '' : ' off');
    el.innerHTML = `<div class="top"><span class="name"></span><input type="checkbox"></div>
      <div class="desc"></div><div class="ops"></div>`;
    el.querySelector('.name').textContent = p.name + (p.version ? `  ${p.version}` : '');
    el.querySelector('.desc').textContent = p.description || p.remote || '';
    const cb = el.querySelector('input');
    cb.checked = p.enabled;
    cb.onchange = () => pluginOp(`${cb.checked ? '启用' : '停用'} ${p.name}`, () => Shell.pluginToggle(p.name, cb.checked));
    const ops = el.querySelector('.ops');
    if (p.remote) ops.append(button('更新', () => pluginOp(`正在更新 ${p.name}`, () => Shell.pluginUpdate(p.name))));
    ops.append(button('删除', () => confirmDialog(`删除插件 ${p.name}？`, '插件文件夹会被删除。', '删除', () =>
      pluginOp(`正在删除 ${p.name}`, () => Shell.pluginRemove(p.name))), 'danger'));
    return el;
  }));
}

function button(label, onclick, cls) {
  const b = document.createElement('button');
  b.textContent = label;
  if (cls) b.className = cls;
  b.onclick = onclick;
  return b;
}

let pluginBusy = false;
let pluginInstalling = false; // the native-module notice is shown once, on install, not on every update
function pluginOp(label, call, installing = false) {
  if (pluginBusy) return;
  pluginBusy = true;
  pluginInstalling = installing;
  $('#p-busy').textContent = label;
  $('#p-error').textContent = '';
  $('#p-log').hidden = true;
  document.querySelectorAll('#plugins button, #plugins input').forEach((b) => { b.disabled = true; });
  call();
}

function pluginDone(r) {
  pluginBusy = false;
  document.querySelectorAll('#plugins button, #plugins input').forEach((b) => { b.disabled = false; });
  $('#p-busy').textContent = '';
  if (r.cancelled) return;
  if (r.ok) {
    pluginsChanged = true;
    $('#p-url').value = '';
    if (pluginInstalling && r.native && r.native.length) {
      dialog(`${r.name} 已安装`, `以下依赖为原生模块，无法在本设备加载：${r.native.join('、')}。插件的部分或全部功能可能无法使用。`,
        [['确定', closeDialog, 'primary']]);
    }
  } else {
    $('#p-error').textContent = r.error || '操作失败';
    if (r.log) { $('#p-log').hidden = false; $('#p-log').textContent = r.log; }
  }
  fillPlugins();
}

/* —— dialogs —— */
function dialog(title, body, actions) {
  $('#d-title').textContent = title;
  $('#d-body').textContent = body || '';
  $('#d-bar').hidden = true;
  $('#d-actions').replaceChildren(...actions.map(([label, fn, cls]) => button(label, fn, cls)));
  $('#dialog').hidden = false;
}
function closeDialog() { $('#dialog').hidden = true; }
function confirmDialog(title, body, ok, fn) {
  dialog(title, body, [[ok, () => { closeDialog(); fn(); }, 'primary'], ['取消', closeDialog]]);
}

// auto: shown by itself once SillyTavern is up; declining then goes straight back to SillyTavern
function offerShell(o, auto = false) {
  dialog(`吃药酒馆 ${o.version} 可以更新`, o.notes || '', [
    ['更新', () => {
      $('#d-actions').replaceChildren();
      $('#d-body').textContent = '正在下载';
      $('#d-bar').hidden = false;
      Shell.shellUpdate();
    }, 'primary'],
    ['以后再说', () => { Shell.shellDecline(); closeDialog(); if (auto && !current()) actions.tavern(); }],
  ]);
}

let toastTimer = 0;
function toast(msg) {
  const t = $('#toast');
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 3500);
}

/* —— actions —— */
const actions = {
  boot: () => Shell.boot(),
  skip: () => Shell.skip(),
  restart: () => { pluginsChanged = false; stack.length = 0; Shell.restart(); show('start'); },
  rollback: () => Shell.rollback(),
  copyLog: () => Shell.copyLog(),
  tavern: () => { stack.length = 0; Shell.openTavern(); },
  background: () => { stack.length = 0; Shell.background(); },
  quit: () => confirmDialog('退出吃药酒馆？', 'SillyTavern 将停止运行。', '退出', () => Shell.quit()),
  fresh: () => Shell.onboardFresh(),
  import: () => confirmDialog('从备份导入？', '导入会替换压缩包中包含的内容，替换前当前数据会自动导出到「下载」。', '选择文件', () => Shell.importBackup()),
  export: () => { toast('正在导出'); Shell.exportBackup(); },
  check: () => { $('#check-btn').disabled = true; $('#check-result').textContent = '正在检查'; Shell.checkUpdate(); },
  updateNow: () => { stack.length = 0; Shell.updateNow(); show('start'); },
  saveNetwork: () => {
    const err = Shell.saveSettings(JSON.stringify({
      githubPrefix: $('#n-prefix').value,
      npmRegistry: $('#n-npm').value,
      gitMirror: $('#n-git').checked,
      port: Number($('#n-port').value),
    }));
    $('#n-error').textContent = err;
    if (!err) { toast(status.phase === 'READY' ? '已保存，重启 SillyTavern 后生效' : '已保存'); back(); }
  },
  pluginAdd: () => {
    const url = $('#p-url').value.trim();
    if (!url) { $('#p-error').textContent = '请填写插件仓库地址'; return; }
    pluginOp('正在安装', () => Shell.pluginAdd(url), true);
  },
  pluginZip: () => pluginOp('正在导入', () => Shell.pluginAddZip(), true),
};

document.addEventListener('click', (e) => {
  const t = e.target.closest('[data-act],[data-go],[data-back],[data-fill]');
  if (!t || t.disabled) return;
  if (t.dataset.go) go(t.dataset.go);
  else if ('back' in t.dataset) back();
  else if (t.dataset.fill) $('#n-npm').value = t.dataset.fill;
  else actions[t.dataset.act]?.();
});
document.addEventListener('click', (e) => {
  const a = e.target.closest('a[href]');
  if (!a) return;
  e.preventDefault();
  Shell.openUrl(a.href);
});

/* —— events from the shell —— */
window.onShell = (type, d) => {
  switch (type) {
    case 'status': render(d); break;
    case 'peek':
      if (d.ok && d.size) $('#w-size').textContent = `需要下载 SillyTavern ${d.version}，约 ${MB(d.size)} MB。`;
      break;
    case 'plugin': pluginDone(d); break;
    case 'export': toast(d.ok ? `已导出到「下载」：${d.file}` : `导出失败：${d.error}`); break;
    case 'importProgress': stack.length = 0; show('start'); $('#s-message').textContent = d.message; break;
    case 'import':
      if (d.cancelled) break;
      if (d.ok) toast(d.backup ? `导入完成，原数据已导出为 ${d.backup}` : '导入完成');
      else dialog('导入失败', d.error, [['确定', closeDialog, 'primary']]);
      break;
    case 'checkUpdate': {
      $('#check-btn').disabled = false;
      const r = $('#check-result');
      if (!d.ok) { r.textContent = `检查失败：${d.error}`; break; }
      const lines = [];
      if (d.st) lines.push(`SillyTavern ${d.st} 可以更新。`);
      if (d.needsShell) lines.push(`SillyTavern ${d.needsShell} 需要先更新吃药酒馆。`);
      if (d.shell) lines.push(`吃药酒馆 ${d.shell} 可以更新。`);
      r.textContent = lines.length ? lines.join('\n') : '已是最新版本';
      if (d.st) dialog(`更新 SillyTavern ${d.st}？`, 'SillyTavern 将重新启动。', [['立即更新', () => { closeDialog(); actions.updateNow(); }, 'primary'], ['以后再说', closeDialog]]);
      else if (d.shell) offerShell({ version: d.shell, notes: d.notes });
      break;
    }
    case 'shellProgress':
      $('#d-bar i').style.transform = `scaleX(${d.total > 0 ? Math.min(1, d.done / d.total) : 0})`;
      break;
    case 'shellUpdate':
      if (d.ok) closeDialog();
      else dialog('更新失败', d.error, [['确定', closeDialog, 'primary']]);
      break;
  }
};

/* —— boot —— */
render(JSON.parse(Shell.status()));
if (location.hash === '#settings') go('settings');
if (home() === 'welcome') Shell.peek();
