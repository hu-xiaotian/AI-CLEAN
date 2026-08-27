/**
 * AI Clean 数据清洗系统 - 前端应用
 */
const API = '/api';
let currentTitleId = null;

// ==================== 页面权限控制 ====================
let myPermCodes = [];                     // 当前用户拥有的全部权限编码
let myPagePermsLoaded = false;            // 是否已加载当前用户权限

// 页面 -> 页面级权限编码映射
const PAGE_PERM_MAP = {
    'dashboard': 'page:dashboard',
    'import': 'page:import',
    'oneclick': 'page:oneclick',
    'search': 'page:search',
    'externalclean': 'page:externalclean',
    'clean': 'page:clean',
    'extract': 'page:extract',
    'mapping': 'page:mapping',
    'result': 'page:result',
    'unmapped': 'page:unmapped',
    'rule': 'page:rule',
    'standard': 'page:standard',
    'users': 'page:users',
    'role': 'page:role',
    'permission': 'page:permission',
    'oplog': 'page:oplog',
};

// 当前用户是否有指定权限编码
function hasPerm(permCode) {
    if (!permCode) return true;
    // 管理员默认拥有全部权限
    const user = getCurrentUser();
    if (user && user.role === 'admin') return true;
    return myPermCodes.indexOf(permCode) !== -1;
}

let _myPermPromise = null;   // 防并发：共享同一个加载 Promise
// 加载当前用户权限编码（登录后调用一次）
async function loadMyPermissions() {
    if (myPagePermsLoaded) return;
    if (!_myPermPromise) {
        _myPermPromise = (async () => {
            try {
                const codes = await api('/permissions/mine');
                myPermCodes = codes || [];
            } catch (e) {
                // 权限接口异常时不阻塞，保留空权限（管理员不受影响）
                myPermCodes = [];
            } finally {
                myPagePermsLoaded = true;
            }
        })();
    }
    await _myPermPromise;
}

// 根据权限控制菜单显隐：无权限的页面菜单隐藏
function applyMenuPermissionVisibility() {
    const adminRole = getCurrentUser() && getCurrentUser().role === 'admin';
    document.querySelectorAll('.nav-item[data-page]').forEach(function (el) {
        const page = el.getAttribute('data-page');
        const perm = el.getAttribute('data-perm');
        let visible = true;
        if (perm) {
            visible = adminRole || myPermCodes.indexOf(perm) !== -1;
        }
        el.style.display = visible ? '' : 'none';
    });
}

// 返回当前用户可访问的第一个页面名（无权限页面被跳过）
function firstAccessiblePage() {
    const order = ['dashboard', 'import', 'oneclick', 'search', 'externalclean',
        'clean', 'extract', 'mapping', 'result', 'unmapped',
        'rule', 'standard', 'users', 'role', 'permission', 'oplog'];
    for (let i = 0; i < order.length; i++) {
        const page = order[i];
        const perm = PAGE_PERM_MAP[page];
        const el = document.querySelector('.nav-item[data-page="' + page + '"]');
        if (el && el.style.display !== 'none' && hasPerm(perm)) {
            return page;
        }
    }
    // 兜底：全无权限时退回第一个页面
    return 'import';
}

// ==================== 认证相关 ====================

const TOKEN_KEY = 'dc_token';
const USER_KEY = 'dc_user';

function getToken() { return localStorage.getItem(TOKEN_KEY); }

function getCurrentUser() {
    try { return JSON.parse(localStorage.getItem(USER_KEY) || '{}'); }
    catch (e) { return {}; }
}

function clearAuth() {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
}

function redirectToLogin() {
    clearAuth();
    location.replace('/login.html');
}

async function logout() {
    try { await api('/auth/logout', { method: 'POST' }); } catch (e) { /* 忽略 */ }
    redirectToLogin();
}

// 全局包装原生 fetch：为所有 /api 请求自动附加 Token，并统一处理 401
(function () {
    const rawFetch = window.fetch.bind(window);
    window.fetch = function (input, init) {
        init = init || {};
        const url = typeof input === 'string' ? input : (input && input.url) || '';
        const isApi = url.indexOf('/api') !== -1;
        const token = getToken();
        if (isApi && token) {
            const headers = new Headers(init.headers || (typeof input !== 'string' && input.headers) || {});
            if (!headers.has('Authorization')) {
                headers.set('Authorization', 'Bearer ' + token);
            }
            init.headers = headers;
        }
        return rawFetch(input, init).then(function (res) {
            if (isApi && res.status === 401) {
                redirectToLogin();
            }
            return res;
        });
    };
})();

// ==================== 工具函数 ====================

function $(sel) { return document.querySelector(sel); }
function $$(sel) { return Array.from(document.querySelectorAll(sel)); }

async function api(url, options = {}) {
    const config = {
        headers: { 'Content-Type': 'application/json' },
        ...options,
    };
    // 附加认证 Token
    const token = getToken();
    if (token) {
        config.headers['Authorization'] = 'Bearer ' + token;
    }
    if (config.body && typeof config.body === 'object' && !(config.body instanceof FormData)) {
        config.body = JSON.stringify(config.body);
    }
    if (config.body instanceof FormData) {
        delete config.headers['Content-Type'];
    }
    const res = await fetch(API + url, config);
    // 未授权：登录失效，跳转登录页
    if (res.status === 401) {
        redirectToLogin();
        throw new Error('登录已过期，请重新登录');
    }
    const text = await res.text();
    if (!text || !text.trim()) {
        throw new Error('服务器未返回数据（可能请求超时或连接中断），请稍后重试');
    }
    let data;
    try {
        data = JSON.parse(text);
    } catch (e) {
        throw new Error('响应解析失败：' + e.message);
    }
    if (data.code === 401) {
        redirectToLogin();
        throw new Error(data.msg || '登录已过期，请重新登录');
    }
    if (data.code !== 200) {
        throw new Error(data.msg || '请求失败');
    }
    return data.data;
}

// 安全解析响应 JSON：避免空响应体导致 "Unexpected end of JSON input" 这类无法解读的未捕获异常
async function safeJson(res, label) {
    const prefix = label ? (label + '：') : '';
    if (!res.ok) {
        let body = '';
        try { body = (await res.text()) || ''; } catch (e) {}
        throw new Error(prefix + 'HTTP ' + res.status + (body ? ' ' + body : ''));
    }
    const text = await res.text();
    if (!text || !text.trim()) {
        throw new Error(prefix + '服务器未返回数据（可能请求超时或连接中断），请稍后到对应页面查看结果或重试');
    }
    try {
        return JSON.parse(text);
    } catch (e) {
        throw new Error(prefix + '响应解析失败：' + e.message);
    }
}

function showToast(msg, type = 'success') {
    const toast = $('#toast');
    toast.textContent = msg;
    toast.className = `toast ${type} show`;
    setTimeout(() => toast.classList.remove('show'), 3000);
}

function showModal(title, bodyHtml, size) {
    $('#modalTitle').textContent = title;
    $('#modalBody').innerHTML = bodyHtml;
    const modal = $('#modal');
    // 重置拖动位置，恢复默认居中
    modal.classList.remove('dragging');
    modal.style.top = '';
    modal.style.left = '';
    // 应用尺寸
    modal.classList.remove('modal-xl', 'modal-lg', 'modal-extract');
    if (size === 'xl') modal.classList.add('modal-xl');
    else if (size === 'extract') modal.classList.add('modal-extract');
    modal.classList.add('show');
    $('#modalOverlay').classList.add('show');
}

function closeModal() {
    $('#modal').classList.remove('show');
    $('#modalOverlay').classList.remove('show');
    // 清理尺寸 class，恢复默认
    const modal = $('#modal');
    if (modal) modal.classList.remove('modal-xl', 'modal-lg', 'modal-extract');
    // 清理残留子弹窗
    document.querySelectorAll('.sub-modal').forEach(el => el.remove());
}

// 子弹窗：嵌入在主弹窗内，用于二级操作（源数据/修改），不销毁主弹窗
function openSubModal(title, bodyHtml) {
    // 先移除已存在的子弹窗，避免叠加
    closeSubModal();
    const overlay = document.createElement('div');
    overlay.className = 'sub-modal';
    overlay.innerHTML = `<div class="sub-modal-card">
        <div class="sub-modal-header">
            <h4>${escapeHtml(title)}</h4>
            <button class="btn-close" onclick="closeSubModal()">&#x2715;</button>
        </div>
        <div class="sub-modal-body">${bodyHtml}</div>
    </div>`;
    // 嵌入到当前显示的主弹窗内（如果有），否则挂到 body
    const modal = $('#modal');
    (modal && modal.classList.contains('show') ? modal : document.body).appendChild(overlay);
}

function closeSubModal() {
    document.querySelectorAll('.sub-modal').forEach(el => el.remove());
}

// 让通用弹窗可拖动（通过标题栏拖拽）
(function enableModalDrag() {
    const modal = document.getElementById('modal');
    if (!modal) return;
    const header = modal.querySelector('.modal-header');
    if (!header) return;
    let startX = 0, startY = 0, startLeft = 0, startTop = 0, dragging = false;

    function onPointerDown(e) {
        // 点击关闭按钮等不触发拖动
        if (e.target.closest('.btn-close')) return;
        // 先读取当前（居中 translate 状态）的实际屏幕位置，再切换为绝对定位
        const rect = modal.getBoundingClientRect();
        dragging = true;
        modal.classList.add('dragging');
        modal.style.left = rect.left + 'px';
        modal.style.top = rect.top + 'px';
        startLeft = rect.left;
        startTop = rect.top;
        startX = e.clientX;
        startY = e.clientY;
        e.preventDefault();
    }

    function onPointerMove(e) {
        if (!dragging) return;
        let newLeft = startLeft + (e.clientX - startX);
        let newTop = startTop + (e.clientY - startY);
        // 限制在视口范围内
        const maxLeft = window.innerWidth - modal.offsetWidth;
        const maxTop = window.innerHeight - modal.offsetHeight;
        newLeft = Math.max(0, Math.min(newLeft, maxLeft));
        newTop = Math.max(0, Math.min(newTop, maxTop));
        modal.style.left = newLeft + 'px';
        modal.style.top = newTop + 'px';
    }

    function onPointerUp() {
        // 拖动结束后保持绝对定位（dragging），否则 transform: translate(-50%,-50%) 会重新生效使弹窗跳回居中
        dragging = false;
    }

    header.addEventListener('mousedown', onPointerDown);
    document.addEventListener('mousemove', onPointerMove);
    document.addEventListener('mouseup', onPointerUp);
})();

function showLoading(msg) {
    const overlay = $('#loadingOverlay');
    if (overlay && msg) {
        $('#loadingText').textContent = msg;
    }
    if (overlay) overlay.classList.add('show');
}

function hideLoading() {
    const overlay = $('#loadingOverlay');
    if (overlay) overlay.classList.remove('show');
}

function formatDate(str) {
    if (!str) return '-';
    const d = new Date(str);
    return d.toLocaleString('zh-CN');
}

function statusBadge(status) {
    const map = {
        'draft':'badge-default','queued':'badge-warning','processing':'badge-info','needs_review':'badge-warning','reviewing':'badge-info',
        'approved':'badge-success','rejected':'badge-danger','modified':'badge-info',
        'export_ready':'badge-success','processed':'badge-info','completed':'badge-success',
    };
    const label = {
        'draft':'未清洗','queued':'排队等待中','processing':'清洗中','needs_review':'待审核','reviewing':'审核中',
        'approved':'审核通过','rejected':'审核驳回','modified':'已修改',
        'export_ready':'可导出','processed':'已处理','completed':'已完成',
    };
    return `<span class="badge ${map[status]||'badge-default'}">${label[status]||status||'-'}</span>`;
}

function confidenceHtml(val) {
    if (!val && val !== 0) return '-';
    const pct = Math.round(val * 100);
    const cls = pct >= 80 ? 'confidence-high' : pct >= 50 ? 'confidence-mid' : 'confidence-low';
    return `<div style="display:flex;align-items:center;gap:6px"><span>${pct}%</span><div class="confidence-bar" style="flex:1"><div class="confidence-fill ${cls}" style="width:${pct}%"></div></div></div>`;
}

// ==================== 页面切换 ====================

function switchPage(name) {
    // 页面级权限控制：无权限时跳转到第一个可访问页面
    const needPerm = PAGE_PERM_MAP[name];
    if (needPerm && !hasPerm(needPerm)) {
        const fallback = firstAccessiblePage();
        if (fallback && fallback !== name) {
            showToast('您没有访问该页面的权限', 'error');
            return switchPage(fallback);
        }
    }
    $$('.page').forEach(p => p.classList.remove('active'));
    $$('.nav-item').forEach(n => n.classList.remove('active'));
    $(`#page-${name}`).classList.add('active');
    $(`.nav-item[data-page="${name}"]`).classList.add('active');

    // 同步导航分组状态：展开当前项所在分组并标记高亮
    $$('.nav-group').forEach(g => g.classList.remove('has-active'));
    const activeItem = $(`.nav-item[data-page="${name}"]`);
    if (activeItem) {
        const group = activeItem.closest('.nav-group');
        if (group) {
            group.classList.remove('collapsed');
            group.classList.add('has-active');
        }
    }

    const loaders = {
        'import': () => { loadTitles(); },                          // 刷新导入文件列表
        'rule': () => { loadRules(true); },                            // 刷新解析规则列表
        'extract': () => { loadTitles(); loadTitlesForSelect('aiExtractTitleId'); loadAiExtractTasks(); },  // 刷新提取相关数据
        'clean': () => { refreshCleanPage(); },     // 刷新清洗相关数据 + 加载已清洗记录
        'mapping': () => { 
            loadTitlesForSelect('mapTitleId').then(() => {
                // 数据文件加载完成后触发一次联动，按需加载补充数据表头与标准字段表头
                // （onMapTitleChange 内部已按当前数据文件过滤标准字段表头，无需再单独全量加载，
                //  避免与下方重复请求同一接口导致映射列表加载变慢）
                onMapTitleChange();
            });
        },
        'result': async () => { 
            // 仅首次进入时填充下拉框并联动过滤标准/补充表头，
            // 之后切换页面不再重新加载下拉框，保留用户已选条件、结果与翻页位置
            if (!_resultSelectsReady) {
                // 结果数据模块：数据文件下拉框只显示"导入数据状态为完成"的数据
                await loadTitlesForSelect('resultTitleId', 'completed');
                await onResultTitleChange();
                _resultSelectsReady = true;
            }
        },
        'search': () => { loadCategories(); },
        'standard': () => { loadStandardTitleList(); },              // 刷新标准字段表头列表
        'users': () => { loadUsers(1); },                            // 刷新用户列表
        'unmapped': () => { loadTitlesForUnmapped(); },
        'oneclick': () => { loadOneClickPage(); },
        'dashboard': () => { loadDashboardPage(); },
        'externalclean': () => { loadTitlesForSelect('ecTitleId'); ecLoadTasks(1); ecStartAutoRefresh(); },
        'role': () => { loadRoles(1); },                                // 刷新角色列表
        'permission': () => {                                           // 填充角色下拉并刷新权限配置
            const target = _pendingPermRole;
            _pendingPermRole = null;
            initPermissionPage(target);
        },
        'oplog': () => { loadOpLogs(1); },                              // 刷新操作日志
    };
    if (loaders[name]) loaders[name]();
}

// 折叠/展开导航分组
function toggleNavGroup(titleEl) {
    const group = titleEl.closest('.nav-group');
    if (group) group.classList.toggle('collapsed');
}

// 折叠/展开卡片内容（如清洗结果记录）
function toggleCardCollapse(titleEl) {
    const card = titleEl.closest('.card');
    if (card) card.classList.toggle('collapsed');
}

// ==================== 一键数据清洗 ====================
let ocRunning = false;
let ocStompClient = null;
let ocPollTimer = null;

// 初始化一键清洗页下拉框
async function loadOneClickPage() {
    await loadTitlesForSelect('ocTitleId');
    await loadRulesForSelect('ocRuleId');
}

function setOcStep(name, state) {
    const step = $('#ocStep-' + name);
    if (!step) return;
    step.setAttribute('data-state', state);
    const statusEl = step.querySelector('.oc-step-status');
    const textMap = {
        waiting: '等待中',
        running: '执行中…',
        done: '已完成',
        error: '失败',
    };
    if (statusEl) statusEl.textContent = textMap[state] || state;
    // 同步到 AI 清洗特效弹窗
    if (AiCleanOverlay.visible) AiCleanOverlay.setStep(name, state);
}

function setOcOverall(percent, text) {
    $('#ocOverallFill').style.width = percent + '%';
    $('#ocOverallFill').textContent = percent + '%';
    if (text) $('#ocOverallText').textContent = text;
    // 同步到 AI 清洗特效弹窗
    if (AiCleanOverlay.visible) AiCleanOverlay.setProgress(percent, text);
}

function resetOcUI() {
    ['clean', 'extract', 'map'].forEach(n => setOcStep(n, 'waiting'));
    setOcOverall(0, '等待开始');
    $('#ocCleanStatsCard').style.display = 'none';
    const ocTitleEl = $('#ocCleanStatsTitle');
    if (ocTitleEl) ocTitleEl.textContent = '清洗实时进度';
    $('#ocCleanFill').style.width = '0%';
    $('#ocCleanFill').textContent = '0%';
    $('#ocCleanCurrent').textContent = '0';
    $('#ocCleanTotal').textContent = '0';
    $('#ocCleanSuccess').textContent = '0';
    $('#ocCleanError').textContent = '0';
}

// 步骤一：智能分类（数据清洗，固定 AI 分类）
function ocDoCleaning(titleId, ruleId) {
    return new Promise((resolve, reject) => {
        // 读取页面配置的每批条数（可空，后端用默认 batch-classify-size）
        const batchSizeRaw = $('#batchSizeInput') && $('#batchSizeInput').value;
        const batchSize = (batchSizeRaw && Number(batchSizeRaw) > 0) ? Number(batchSizeRaw) : '';
        let settled = false;
        let started = false; // 是否已观测到任务开始（避免复用历史 completed 状态误判）
        let timeout = null;
        const finish = (ok, msg) => {
            if (settled) return;
            settled = true;
            if (ocPollTimer) { clearInterval(ocPollTimer); ocPollTimer = null; }
            if (ocStompClient) { try { ocStompClient.disconnect(); } catch (e) {} ocStompClient = null; }
            if (timeout) { clearTimeout(timeout); timeout = null; }
            if (ok) resolve(); else reject(new Error(msg || '清洗失败'));
        };

        // 启动清洗任务（固定 AI 分类，不再传 useAi 开关）
        fetch(API + `/cleaning/start?titleId=${titleId}&batchSize=${batchSize}`, { method: 'POST' })
            .then(res => safeJson(res, '启动清洗'))
            .then(data => { if (data.code !== 200) throw new Error(data.msg); })
            .catch(e => finish(false, '清洗启动失败: ' + e.message));

        // 实时进度（WebSocket，主要完成信号）
        try {
            const socket = new SockJS('/ws-cleaning');
            ocStompClient = Stomp.over(socket);
            ocStompClient.debug = null;
            let ocWsConnected = false;
            // 已连接后若与后台 WebSocket 断开：提示切换至后台，不视为失败（轮询兜底继续）
            socket.onclose = () => {
                if (ocWsConnected && !settled) {
                    showToast('清洗时间过长，已切换至后台进行', 'warning');
                    const pct = parseInt($('#ocOverallFill').style.width) || 0;
                    setOcOverall(pct, '清洗时间过长，已切换至后台进行');
                }
            };
            ocStompClient.connect({}, () => {
                ocWsConnected = true;
                ocStompClient.subscribe('/topic/cleaning/' + titleId, message => {
                    const msg = JSON.parse(message.body);
                    if (msg.type === 'start' || msg.type === 'progress') started = true;
                    ocUpdateCleanProgress(msg);
                    if (msg.type === 'complete') finish(true);
                    else if (msg.type === 'stopped') finish(false, '已手动停止清洗');
                    else if (msg.type === 'error') finish(false, '清洗异常终止');
                });
            }, () => { /* WebSocket 失败，依赖轮询兜底 */ });
        } catch (e) { /* 忽略，走轮询 */ }

        // 轮询兜底：仅在已观测到任务开始后，才信任 completed 状态
        ocPollTimer = setInterval(async () => {
            try {
                const p = await api(`/cleaning/progress/${titleId}`);
                if (p.status === 'rejected') finish(false, '清洗异常终止');
                else if (p.status === 'completed' && started) finish(true);
            } catch (e) { /* 忽略轮询错误 */ }
        }, 1500);

        // 超时兜底：WebSocket 完全不可用时，按最终表头状态判定
        timeout = setTimeout(async () => {
            try {
                const p = await api(`/cleaning/progress/${titleId}`);
                if (p.status === 'completed') finish(true);
                else finish(false, '清洗超时，请到“智能分类”页查看状态');
            } catch (e) { finish(false, '清洗超时'); }
        }, 60 * 60 * 1000);
    });
}

function ocRenderCleanStatsCard(msg) {
    const percent = msg.progressPercent || 0;
    const current = msg.current || 0;
    const total = msg.total || 0;
    $('#ocCleanFill').style.width = percent + '%';
    $('#ocCleanFill').textContent = percent + '%';
    $('#ocCleanCurrent').textContent = current;
    $('#ocCleanTotal').textContent = total;
    $('#ocCleanSuccess').textContent = msg.successCount || 0;
    $('#ocCleanError').textContent = msg.errorCount || 0;
}

function ocUpdateCleanProgress(msg) {
    $('#ocCleanStatsCard').style.display = 'block';
    ocRenderCleanStatsCard(msg);
    // 整体进度映射到 5% ~ 35%（智能分类完成态为 35%）
    setOcOverall(5 + Math.round((msg.progressPercent || 0) * 0.30), `正在执行：智能分类（${msg.progressPercent || 0}%）`);
}

// 步骤二：属性补全（自动映射 + 填充全部标准表头，补充数据表头默认第一项）
async function ocDoMapFill(titleId) {
    // 补充数据表头默认取第一项（需在自动映射前确定，否则补充字段无法被映射，填充结果为空）
    let extraTitleId = '';
    try {
        const extraTitles = await api('/cleaning/extra-titles');
        const filtered = (extraTitles || []).filter(et => et.tempDataTitleId == titleId);
        if (filtered.length > 0) extraTitleId = filtered[0].id;
    } catch (e) { /* 无补充表头也可继续 */ }

    // 自动映射字段（带上补充数据表头，确保补充字段也能被映射，否则填充结果为空）
    const mapParams = new URLSearchParams({ tempDataTitleId: titleId });
    if (extraTitleId) mapParams.append('extraDataTitleId', extraTitleId);
    const mapRes = await fetch(API + `/cleaning/auto-map-fields?${mapParams}`, { method: 'POST' });
    const mapData = await safeJson(mapRes, '自动映射');
    if (mapData.code !== 200) throw new Error(mapData.msg);

    // 复用“清洗实时进展”卡片展示属性补全进度
    $('#ocCleanStatsCard').style.display = 'block';
    $('#ocCleanStatsTitle').textContent = '属性补全实时进度';
    $('#ocCleanFill').style.width = '0%';
    $('#ocCleanFill').textContent = '0%';
    $('#ocCleanCurrent').textContent = '0';
    $('#ocCleanTotal').textContent = '0';
    $('#ocCleanSuccess').textContent = '0';
    $('#ocCleanError').textContent = '0';

    // 通过 WebSocket 实时展示属性补全进度（消息格式与清洗一致）
    let stompClient = null;
    let mapWsConnected = false;
    try {
        const socket = new SockJS('/ws-cleaning');
        stompClient = Stomp.over(socket);
        stompClient.debug = null;
        // 已连接后若与后台 WebSocket 断开：提示切换至后台（服务端同步执行不受影响）
        socket.onclose = () => {
            if (mapWsConnected) {
                showToast('清洗时间过长，已切换至后台进行', 'warning');
                const pct = parseInt($('#ocOverallFill').style.width) || 0;
                setOcOverall(pct, '清洗时间过长，已切换至后台进行');
            }
        };
        stompClient.connect({}, () => {
            mapWsConnected = true;
            stompClient.subscribe('/topic/fill/*', message => {
                try { ocRenderCleanStatsCard(JSON.parse(message.body)); } catch (e) {}
            });
        }, () => { /* WebSocket 失败，无实时进度，依赖最终完成态兜底 */ });
    } catch (e) { /* 忽略，走完成态兜底 */ }

    // 填充全部标准表头（服务端同步执行，请求返回即代表全部完成）
    const fillParams = new URLSearchParams({ tempDataTitleId: titleId });
    if (extraTitleId) fillParams.append('extraDataTitleId', extraTitleId);
    const fillRes = await fetch(API + `/cleaning/fill-result/fill-all?${fillParams}`, { method: 'POST' });
    const fillData = await safeJson(fillRes, '填充结果');
    if (stompClient) { try { stompClient.disconnect(); } catch (e) {} }
    if (fillData.code !== 200) throw new Error(fillData.msg);

    // 兜底：强制刷新为完成态（避免 WebSocket 消息延迟导致卡片未到 100%）
    $('#ocCleanFill').style.width = '100%';
    $('#ocCleanFill').textContent = '100%';
}

// 步骤二：属性提取（AI 智能提取）
async function ocDoExtract(titleId) {
    await ocDoExtractAi(titleId);
    return true;
}

// 步骤二（AI 模式）：启动 AI 智能提取并等待完成，复用清洗实时进展卡片展示进度
function ocDoExtractAi(titleId) {
    return new Promise((resolve, reject) => {
        let settled = false;
        let started = false;
        let pollTimer = null;
        let stompClient = null;
        let timeout = null;
        const finish = (ok, msg) => {
            if (settled) return;
            settled = true;
            if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
            if (stompClient) { try { stompClient.disconnect(); } catch (e) {} }
            if (timeout) { clearTimeout(timeout); timeout = null; }
            if (ok) resolve(); else reject(new Error(msg || 'AI 提取失败'));
        };

        // 复用清洗实时进展卡片展示 AI 提取进度
        $('#ocCleanStatsCard').style.display = 'block';
        $('#ocCleanStatsTitle').textContent = 'AI 属性提取实时进度';
        // 先以初始值（全 0）渲染，确保「成功/失败」计数从本步骤开始正确显示（避免沿用上一步智能分类的计数）
        ocRenderCleanStatsCard({ progressPercent: 0, current: 0, total: 0, successCount: 0, errorCount: 0 });

        // 启动 AI 提取任务
        fetch(API + `/cleaning/extract-extra-ai?titleId=${titleId}` + (customName ? `&customName=${encodeURIComponent(customName)}` : ''), { method: 'POST' })
            .then(res => safeJson(res, '启动AI提取'))
            .then(data => { if (data.code !== 200) throw new Error(data.msg); })
            .catch(e => finish(false, 'AI 提取启动失败: ' + e.message));

        const onMsg = (msg) => {
            if (!msg) return;
            if (msg.type === 'start' || msg.type === 'progress') started = true;
            ocRenderCleanStatsCard(msg);
            if (msg.type === 'complete') finish(true);
            else if (msg.type === 'error') finish(false, 'AI 提取异常终止：' + (msg.message || ''));
        };

        // 实时进度（WebSocket）
        try {
            const socket = new SockJS('/ws-cleaning');
            stompClient = Stomp.over(socket);
            stompClient.debug = null;
            let aiWsConnected = false;
            // 已连接后若与后台 WebSocket 断开：提示切换至后台，不视为失败（轮询兜底继续）
            socket.onclose = () => {
                if (aiWsConnected && !settled) {
                    showToast('清洗时间过长，已切换至后台进行', 'warning');
                    const pct = parseInt($('#ocOverallFill').style.width) || 0;
                    setOcOverall(pct, '清洗时间过长，已切换至后台进行');
                }
            };
            stompClient.connect({}, () => {
                aiWsConnected = true;
                stompClient.subscribe('/topic/ai-extract/' + titleId, message => {
                    try { onMsg(JSON.parse(message.body)); } catch (e) {}
                });
            }, () => { /* WebSocket 失败，依赖轮询兜底 */ });
        } catch (e) { /* 忽略，走轮询 */ }

        // 轮询兜底
        pollTimer = setInterval(async () => {
            try {
                const p = await api(`/cleaning/ai-extract-progress/${titleId}`);
                onMsg(p);
                if (p.type === 'complete') finish(true);
                else if (p.type === 'error') finish(false, 'AI 提取异常终止：' + (p.message || ''));
            } catch (e) { /* 忽略轮询错误 */ }
        }, 2000);

        // 超时兜底
        timeout = setTimeout(() => {
            if (!settled) finish(false, 'AI 提取超时（30分钟），请到“属性提取”页查看状态');
        }, 60 * 60 * 1000);
    });
}

// 一键清洗主流程
async function runOneClickClean() {
    if (ocRunning) { showToast('正在执行中，请稍候', 'warning'); return; }
    const titleId = $('#ocTitleId').value;
    const ruleId = $('#ocRuleId').value;
    if (!titleId || !ruleId) { showToast('请选择数据文件和解析规则', 'warning'); return; }

    ocRunning = true;
    $('#ocStartBtn').disabled = true;
    $('#ocStopBtn').disabled = false;
    resetOcUI();

    // 固定 AI 分类：默认启用 AI 特效与进度卡片 AI 动效
    const useAi = true;
    if (useAi) AiCleanOverlay.show();

    // AI 介入（固定 AI 评分 或 AI 智能提取）时，整体进度卡片启动动态 AI 特效
    const ocAiInvolved = true;
    const ocProgressCard = $('#ocOverallFill') ? $('#ocOverallFill').closest('.card') : null;
    if (ocAiInvolved && ocProgressCard) AiFx.activate(ocProgressCard);

    let ocSuccess = false;
    try {
        // 步骤一：智能分类
        setOcStep('clean', 'running');
        setOcOverall(5, '正在执行：智能分类');
        await ocDoCleaning(titleId, ruleId);
        setOcStep('clean', 'done');
        setOcOverall(35, '已完成：智能分类');

        // 步骤二：属性提取（AI 智能提取）
        setOcStep('extract', 'running');
        setOcOverall(40, '正在执行：属性提取（AI）');
        await ocDoExtract(titleId);
        setOcStep('extract', 'done');
        setOcOverall(60, '已完成：属性提取');

        // 步骤三：属性补全
        setOcStep('map', 'running');
        setOcOverall(65, '正在执行：属性补全');
        await ocDoMapFill(titleId);
        setOcStep('map', 'done');
        setOcOverall(100, '全部完成');
        showToast('一键数据清洗全部完成！');
        ocSuccess = true;

        // 清洗完成：自动跳转到「智能分类」页，展示清洗记录（清洗固定启用 AI 智能分类）
        ocPendingTitleId = titleId;
        switchPage('clean');
    } catch (e) {
        // 标记当前进行中的步骤为失败
        ['clean', 'extract', 'map'].forEach(n => {
            const step = $('#ocStep-' + n);
            if (step && step.getAttribute('data-state') === 'running') setOcStep(n, 'error');
        });
        showToast('执行失败: ' + e.message, 'error');
        setOcOverall($('#ocOverallFill').style.width.replace('%', '') || 0, '执行中断：' + e.message);
    } finally {
        ocRunning = false;
        $('#ocStartBtn').disabled = false;
        $('#ocStopBtn').disabled = true;
        if (ocProgressCard && ocProgressCard.classList.contains('ai-active')) AiFx.deactivate(ocProgressCard);
        // 关闭 AI 清洗特效弹窗（成功后稍作停留展示 100% 完成态）
        if (ocSuccess) setTimeout(() => AiCleanOverlay.hide(), 1200);
        else AiCleanOverlay.hide();
        if (ocPollTimer) { clearInterval(ocPollTimer); ocPollTimer = null; }
        if (ocStompClient) { try { ocStompClient.disconnect(); } catch (e) {} ocStompClient = null; }
    }
}

// 停止一键清洗（调用后端停止清洗接口）
async function stopOneClickClean() {
    if (!ocRunning) { showToast('当前没有正在执行的清洗任务', 'warning'); return; }
    const titleId = $('#ocTitleId').value;
    if (!titleId) { showToast('未选择数据文件', 'warning'); return; }
    if (!confirm('确定要停止当前的清洗任务吗？已清洗的数据将保留。')) return;
    try {
        const res = await fetch(API + `/cleaning/stop/${titleId}`, { method: 'POST' });
        const data = await safeJson(res, '停止清洗');
        if (data.code !== 200) throw new Error(data.msg || '停止失败');
        showToast('已发送停止信号，任务将在处理完当前分片后停止', 'success');
    } catch (e) {
        showToast('停止失败: ' + e.message, 'error');
    }
}

// 数据文件变更时的联动过滤
async function onResultTitleChange() {
    const titleId = $('#resultTitleId').value;
    if (!titleId) {
        // 未选择数据文件：标准字段表头不显示全部列表，提示先选择文件
        const sel = $('#resultStandardTitleId');
        if (sel) sel.innerHTML = '<option value="">-- 请先选择数据文件 --</option>';
        loadExtraTitlesForSelect('resultExtraTitleId');
        $('#failedCard').style.display = 'none';
        return;
    }
    // 根据选中的数据文件过滤标准字段表头
    await loadStandardTitles('resultStandardTitleId', titleId);
    // 根据选中的数据文件过滤补充数据表头
    await loadExtraTitlesForSelect('resultExtraTitleId', titleId);
    // 联动刷新填充失败记录
    loadFailedResults();
}

// 属性补全模块：数据文件变更时联动过滤补充数据表头，并加载标准字段表头列表
async function onMapTitleChange() {
    const titleId = $('#mapTitleId').value;
    // 根据选中的数据文件过滤补充数据表头，实现数据文件与补充数据表头的绑定
    await loadExtraTitlesForSelect('mapExtraTitleId', titleId);
    // 加载该数据文件对应的标准字段表头列表（列表形式展示，行内可编辑映射）
    await loadMapStandardList();
}

// ==================== 属性补全模块：标准字段表头列表（替代原下拉框） ====================

// 加载"标准字段表头列表"：选数据文件后展示该文件可填充的全部标准字段表头，行内可编辑映射
async function loadMapStandardList() {
    const titleId = $('#mapTitleId').value;
    const card = $('#mapStandardListCard');
    const tbody = $('#mapStandardTbody');
    const countEl = $('#mapStandardListCount');
    if (!titleId) {
        card.style.display = 'none';
        return;
    }
    card.style.display = 'block';
    tbody.innerHTML = '<tr><td colspan="6" class="empty-hint">加载中…</td></tr>';
    countEl.textContent = '';
    try {
        // 返回该数据文件已关联（或懒回填）的标准字段表头
        const list = await api(`/cleaning/standard-titles/by-title?tempDataTitleId=${encodeURIComponent(titleId)}`);
        if (!list || list.length === 0) {
            tbody.innerHTML = '<tr><td colspan="6" class="empty-hint">该数据文件清洗后得到的分类暂无可对应的标准字段表头，请先在「标准字段表头管理」中创建对应分类的标准表头</td></tr>';
            return;
        }
        const hasUnrelated = list.some(st => st.related === false);
        let html = '';
        list.forEach((st, idx) => {
            let fieldCount = 0;
            for (let i = 1; i <= 20; i++) {
                if (st['colTitle' + i]) fieldCount++;
            }
            const titleName = st.categoryName || st.categoryCode || ('标准表头#' + st.id);
            const relBadge = st.related === false
                ? '<span class="badge badge-warning">未关联</span>'
                : '<span class="badge badge-info">已关联</span>';
            html += '<tr>'
                + '<td>' + (idx + 1) + '</td>'
                + '<td>' + escapeHtml(titleName) + '</td>'
                + '<td>' + escapeHtml(st.categoryCode || '-') + '</td>'
                + '<td>' + fieldCount + '</td>'
                + '<td>' + relBadge + '</td>'
                + '<td>'
                + '<button class="btn btn-xs btn-default" onclick="editMapStandard(' + st.id + ')">编辑</button> '
                + '<button class="btn btn-xs btn-primary" onclick="fillMapStandard(' + st.id + ')">填充</button>'
                + '</td>'
                + '</tr>';
        });
        tbody.innerHTML = html;
        countEl.textContent = '共 ' + list.length + ' 个标准字段表头'
            + (hasUnrelated ? '（含未关联，可直接编辑，保存后自动建立关联）' : '');
    } catch (e) {
        tbody.innerHTML = '<tr><td colspan="6" class="empty-hint">加载失败: ' + escapeHtml(e.message) + '</td></tr>';
    }
}

// 编辑某个标准字段表头的映射关系（复用结果数据模块的手动映射弹窗）
function editMapStandard(standardTitleId) {
    const titleId = $('#mapTitleId').value;
    if (!titleId) { showToast('请先选择数据文件', 'warning'); return; }
    openManualFillModal({
        standardTitleId: String(standardTitleId),
        titleIdSel: 'mapTitleId',
        extraTitleIdSel: 'mapExtraTitleId',
    });
}

// 对单个标准字段表头执行填充（采用其已保存的映射）
function fillMapStandard(standardTitleId) {
    const titleId = $('#mapTitleId').value;
    const extraTitleId = $('#mapExtraTitleId').value;
    if (!titleId) { showToast('请先选择数据文件', 'warning'); return; }
    switchPage('mapping');
    startFillWithSocket(standardTitleId, titleId, extraTitleId || 0);
}

// 刷新结果数据列表（如果已选择标准表头）
async function reloadResultData(standardTitleId) {
    const titleId = $('#resultTitleId').value;
    if (!standardTitleId) return;
    try {
        resultPageState.page = 1;
        const condition = { page: 1, pageSize: resultPageState.pageSize, standardTitleId: parseInt(standardTitleId) };
        const [results, total] = await Promise.all([
            api('/cleaning/result-data/search', { method: 'POST', body: condition }),
            api('/cleaning/result-data/count', { method: 'POST', body: condition }),
        ]);
        resultPageState.total = total || 0;
        resultPageState.totalPages = Math.ceil(resultPageState.total / resultPageState.pageSize) || 1;
        renderResultData(results || []);
        updateResultPagination();
    } catch (e) {
        console.error('刷新结果数据失败:', e);
    }
}

// ==================== 数据导入 ====================

function handleFileSelect(e) {
    const file = e.target.files[0];
    if (!file) return;
    uploadFile(file);
}

// 拖拽支持
document.addEventListener('DOMContentLoaded', () => {
    // 登录校验：未登录直接跳转登录页
    if (!getToken()) {
        redirectToLogin();
        return;
    }
    // 渲染当前登录用户信息
    renderCurrentUser();

    const area = $('#uploadArea');
    area.addEventListener('dragover', e => { e.preventDefault(); area.classList.add('drag-over'); });
    area.addEventListener('dragleave', () => area.classList.remove('drag-over'));
    area.addEventListener('drop', e => {
        e.preventDefault();
        area.classList.remove('drag-over');
        const file = e.dataTransfer.files[0];
        if (file) uploadFile(file);
    });
    // 初始进入：先应用菜单权限（内部会拉取权限并跳转到首个可访问页面）
    applyRoleVisibility();
});

// 渲染侧边栏底部当前用户信息，并从后端刷新最新资料
function renderCurrentUser() {
    const box = $('#currentUserBox');
    if (!box) return;
    const user = getCurrentUser();
    const nameEl = $('#currentUserName');
    if (nameEl) nameEl.textContent = user.realName || user.username || '未知用户';
    // 后台静默刷新用户信息
    api('/auth/current').then(u => {
        if (u) {
            localStorage.setItem(USER_KEY, JSON.stringify(u));
            if (nameEl) nameEl.textContent = u.realName || u.username || '未知用户';
        }
        applyRoleVisibility();
    }).catch(() => { /* 忽略：401 已由全局处理 */ });
}

// 根据当前用户角色控制管理员专属元素的显示，并按页面权限控制菜单显隐
async function applyRoleVisibility() {
    const user = getCurrentUser();
    const isAdmin = user && user.role === 'admin';
    // 1. 管理员专属元素（data-role="admin"）
    document.querySelectorAll('[data-role="admin"]').forEach(el => {
        el.style.display = isAdmin ? '' : 'none';
    });
    // 2. 按页面权限控制菜单显隐（拉取当前用户权限编码）
    if (!myPagePermsLoaded) {
        await loadMyPermissions();
    }
    applyMenuPermissionVisibility();
    // 3. 若当前激活页面无权限，跳转到第一个可访问页面
    const activePage = document.querySelector('.page.active');
    if (activePage) {
        const activeName = activePage.id.replace('page-', '');
        if (PAGE_PERM_MAP[activeName] && !hasPerm(PAGE_PERM_MAP[activeName])) {
            switchPage(firstAccessiblePage());
        }
    }
}

// 修改密码弹窗
function openChangePasswordModal() {
    showModal('修改密码', `
        <div class="form-group" style="margin-bottom:14px">
            <label>旧密码</label>
            <input type="password" id="cpOldPwd" class="form-input" placeholder="请输入旧密码">
        </div>
        <div class="form-group" style="margin-bottom:14px">
            <label>新密码</label>
            <input type="password" id="cpNewPwd" class="form-input" placeholder="请输入新密码">
        </div>
        <div class="form-group" style="margin-bottom:20px">
            <label>确认新密码</label>
            <input type="password" id="cpNewPwd2" class="form-input" placeholder="请再次输入新密码">
        </div>
        <div style="display:flex;justify-content:flex-end;gap:8px">
            <button class="btn btn-default" onclick="closeModal()">取消</button>
            <button class="btn btn-primary" onclick="submitChangePassword()">确定</button>
        </div>
    `);
}

async function submitChangePassword() {
    const oldPassword = $('#cpOldPwd').value;
    const newPassword = $('#cpNewPwd').value;
    const newPassword2 = $('#cpNewPwd2').value;
    if (!oldPassword || !newPassword) { showToast('请填写完整', 'error'); return; }
    if (newPassword !== newPassword2) { showToast('两次输入的新密码不一致', 'error'); return; }
    try {
        await api('/auth/change-password', { method: 'POST', body: { oldPassword, newPassword } });
        showToast('密码修改成功，请重新登录');
        closeModal();
        setTimeout(logout, 1000);
    } catch (e) {
        showToast(e.message || '修改失败', 'error');
    }
}

async function uploadFile(file) {
    const formData = new FormData();
    formData.append('file', file);

    $('#uploadProgress').style.display = 'block';
    $('#uploadStatus').textContent = '上传中...';
    $('#progressFill').style.width = '30%';

    try {
        const res = await fetch(API + '/import/upload', { method: 'POST', body: formData });
        const data = await res.json();
        if (data.code !== 200) throw new Error(data.msg);
        $('#progressFill').style.width = '100%';
        $('#uploadStatus').textContent = `导入成功！共 ${data.data.totalRows} 行数据`;
        showToast('文件导入成功');
        loadTitles();
        if (data.data) {
            currentTitleId = data.data.id;
            showImportResult(data.data);
        }
    } catch (err) {
        showToast('导入失败: ' + err.message, 'error');
        $('#uploadStatus').textContent = '导入失败: ' + err.message;
    }
}

function showImportResult(title) {
    $('#importResult').style.display = 'block';
    $('#importResultContent').innerHTML = `
        <p><strong>文件名</strong> ${title.fileName || '-'}</p>
        <p><strong>数据行数</strong> ${title.totalRows || 0}</p>
        <p><strong>状态</strong> ${statusBadge(title.status)}</p>
        <p><strong>上传时间</strong> ${formatDate(title.uploadTime)}</p>
    `;
}

async function loadTitles() {
    try {
        const titles = await api('/import/titles');
        const tbody = $('#titleTbody');
        if (!titles || titles.length === 0) {
            tbody.innerHTML = '<tr><td colspan="7" class="empty-hint">暂无数据</td></tr>';
            return;
        }
        tbody.innerHTML = titles.map(t => `
            <tr>
                <td>${t.id}</td>
                <td>${t.fileName || '-'}</td>
                <td>${t.totalRows || 0}</td>
                <td>${t.categoryCol || '<span style="color:var(--text-tertiary)">默认(第2列)</span>'}</td>
                <td>${statusBadge(t.status)}</td>
                <td>${formatDate(t.uploadTime || t.createdAt)}</td>
                <td>
                    <button class="btn btn-sm btn-primary" onclick="viewTitleDetail(${t.id})">查看</button>
                    <button class="btn btn-sm btn-info" onclick="openCatColModal(${t.id})">编辑</button>
                    <button class="btn btn-sm btn-danger" onclick="deleteTitle(${t.id})">删除</button>
                </td>
            </tr>
        `).join('');
    } catch (e) {
        console.error('加载文件列表失败:', e);
    }
}

async function deleteTitle(id) {
    if (!confirm('确定要删除该导入数据及其所有关联内容（全描述提取、清洗结果、字段映射、结果数据）吗？此操作不可恢复。')) return;
    showLoading('正在删除数据…');
    try {
        await api(`/import/title/${id}`, { method: 'DELETE' });
        showToast('已删除该导入数据及所有关联内容');
        loadTitles();
    } catch (e) {
        showToast('删除失败: ' + e.message, 'error');
    } finally {
        hideLoading();
    }
}

// ===== 指定分类列 & 全描述列 =====

let catColState = { titleId: null, currentCategoryCol: null, currentFullDescCol: null };

async function openCatColModal(titleId) {
    try {
        const titles = await api('/import/titles');
        const title = (titles || []).find(t => t.id == titleId);
        if (!title) {
            showToast('文件不存在', 'error');
            return;
        }
        catColState.titleId = titleId;
        catColState.currentCategoryCol = title.categoryCol || null;
        catColState.currentFullDescCol = title.fullDescCol || null;

        // 构建列名列表（col1-col10中有值的列）
        const cols = [];
        for (let i = 1; i <= 10; i++) {
            const colTitle = title['col' + i + 'Title'];
            if (colTitle && colTitle.trim()) {
                cols.push({ index: i, name: colTitle.trim() });
            }
        }

        // 渲染分类列选择
        const catListDiv = $('#catColList');
        if (cols.length === 0) {
            catListDiv.innerHTML = '<p style="text-align:center;padding:20px;color:var(--text-tertiary)">该文件没有有效的列标题</p>';
        } else {
            catListDiv.innerHTML = cols.map(c => {
                const checked = (title.categoryCol && title.categoryCol === c.name) ? 'checked' : '';
                return `
                    <label class="cat-col-radio-label" style="display:flex;align-items:center;gap:8px;padding:8px 12px;border:1px solid var(--border);border-radius:6px;cursor:pointer;transition:all 0.2s">
                        <input type="radio" name="catColRadio" value="${c.name}" data-idx="${c.index}" ${checked}
                               style="accent-color:var(--accent)">
                        <span>第${c.index}列：<strong>${c.name}</strong></span>
                    </label>
                `;
            }).join('');
        }

        // 渲染全描述列选择
        const descListDiv = $('#fullDescColList');
        if (cols.length === 0) {
            descListDiv.innerHTML = '<p style="text-align:center;padding:20px;color:var(--text-tertiary)">该文件没有有效的列标题</p>';
        } else {
            descListDiv.innerHTML = cols.map(c => {
                const checked = (title.fullDescCol && title.fullDescCol === c.name) ? 'checked' : '';
                return `
                    <label class="cat-col-radio-label" style="display:flex;align-items:center;gap:8px;padding:8px 12px;border:1px solid var(--border);border-radius:6px;cursor:pointer;transition:all 0.2s">
                        <input type="radio" name="fullDescColRadio" value="${c.name}" data-idx="${c.index}" ${checked}
                               style="accent-color:var(--accent)">
                        <span>第${c.index}列：<strong>${c.name}</strong></span>
                    </label>
                `;
            }).join('');
        }

        $('#catColOverlay').classList.add('show');
        $('#catColModal').classList.add('show');
    } catch (e) {
        showToast('加载文件信息失败: ' + e.message, 'error');
    }
}

function onCatColRadioChange(radio) {
    catColState.selectedCategoryCol = radio.value;
}

function closeCatColModal() {
    $('#catColOverlay').classList.remove('show');
    $('#catColModal').classList.remove('show');
}

async function saveCategoryCol() {
    const selectedCatRadio = document.querySelector('input[name="catColRadio"]:checked');
    const categoryCol = selectedCatRadio ? selectedCatRadio.value : null;
    const selectedDescRadio = document.querySelector('input[name="fullDescColRadio"]:checked');
    const fullDescCol = selectedDescRadio ? selectedDescRadio.value : null;

    const changedCols = [];
    if (categoryCol !== catColState.currentCategoryCol || (!categoryCol && catColState.currentCategoryCol)) {
        changedCols.push('分类列');
    }
    if (fullDescCol !== catColState.currentFullDescCol || (!fullDescCol && catColState.currentFullDescCol)) {
        changedCols.push('全描述列');
    }

    if (changedCols.length === 0) {
        showToast('未做任何修改', 'info');
        closeCatColModal();
        return;
    }

    showLoading('正在保存…');
    try {
        const promises = [];
        if (categoryCol !== catColState.currentCategoryCol || (!categoryCol && catColState.currentCategoryCol)) {
            promises.push(api(`/import/title/${catColState.titleId}/category-col`, {
                method: 'PUT',
                body: { categoryCol: categoryCol || '' }
            }));
        }
        if (fullDescCol !== catColState.currentFullDescCol || (!fullDescCol && catColState.currentFullDescCol)) {
            promises.push(api(`/import/title/${catColState.titleId}/full-desc-col`, {
                method: 'PUT',
                body: { fullDescCol: fullDescCol || '' }
            }));
        }
        await Promise.all(promises);
        showToast('文件设置已更新: ' + changedCols.join('、'));
        catColState.currentCategoryCol = categoryCol;
        catColState.currentFullDescCol = fullDescCol;
        closeCatColModal();
        loadTitles();
    } catch (e) {
        showToast('保存失败: ' + e.message, 'error');
    } finally {
        hideLoading();
    }
}

let viewDataState = {};

async function viewTitleDetail(id) {
    currentTitleId = id;
    showModal('查看原始数据', '<p style="text-align:center;padding:40px;color:var(--text-secondary)">加载中...</p>');
    loadViewData(id, 1);
}

async function loadViewData(titleId, page) {
    const pageSize = 15;
    try {
        const res = await fetch(API + `/cleaning/temp-data/${titleId}/page?page=${page}&pageSize=${pageSize}`);
        const data = await res.json();
        if (data.code !== 200) throw new Error(data.msg);

        const pageData = data.data;
        viewDataState = { titleId, page };

        // 表头
        const title = pageData.title || {};
        const headers = [];
        for (let i = 1; i <= 10; i++) {
            const ct = title['col' + i + 'Title'];
            if (ct) headers.push(ct);
        }

        const totalPages = Math.ceil(pageData.total / pageSize);
        const colSpan = headers.length + 1;

        let html = `<div class="view-data-info"><span><strong>文件名:</strong> ${title.fileName || '-'}</span><span><strong>总行数:</strong> ${pageData.total || 0}</span></div>`;

        html += '<div class="table-scroll"><table class="data-table"><thead><tr><th>行号</th>';
        headers.forEach(h => { html += `<th>${h || '-'}</th>`; });
        html += '</tr></thead><tbody>';

        if (!pageData.list || pageData.list.length === 0) {
            html += `<tr><td colspan="${colSpan}" class="empty-hint">暂无数据</td></tr>`;
        } else {
            pageData.list.forEach(row => {
                html += `<tr><td>${row.rowIndex || '-'}</td>`;
                for (let i = 1; i <= headers.length; i++) {
                    html += `<td>${(row['col' + i] || '').replace(/</g, '&lt;').replace(/>/g, '&gt;')}</td>`;
                }
                html += '</tr>';
            });
        }
        html += '</tbody></table></div>';

        // 分页控件
        html += '<div class="pagination"><div class="pagination-info">';
        html += `第 <strong>${page}/${totalPages}</strong> 页，共 <strong>${pageData.total}</strong> 条`;
        html += '</div><div class="pagination-btns">';
        html += `<button class="btn btn-sm" ${page <= 1 ? 'disabled' : ''} onclick="loadViewData(${titleId}, ${page - 1})">上一页</button>`;
        html += `<button class="btn btn-sm" ${page >= totalPages ? 'disabled' : ''} onclick="loadViewData(${titleId}, ${page + 1})">下一页</button>`;
        html += '</div></div>';

        $('#modalBody').innerHTML = html;
    } catch (e) {
        $('#modalBody').innerHTML = `<p style="text-align:center;padding:40px;color:var(--danger)">加载失败: ${e.message}</p>`;
    }
}

// 查看结果数据对应的单条原始数据
async function viewSourceData(tempDataId) {
    if (tempDataId === null || tempDataId === undefined || tempDataId === 'null') {
        showToast('该记录没有关联的原始数据', 'warning');
        return;
    }
    showModal('原始数据', '<p style="text-align:center;padding:40px;color:var(--text-secondary)">加载中...</p>');
    try {
        const res = await api(`/cleaning/temp-data/by-id/${tempDataId}`);
        if (!res || !res.data) {
            $('#modalBody').innerHTML = '<p style="text-align:center;padding:40px;color:var(--text-secondary)">未找到对应的原始数据</p>';
            return;
        }
        const title = res.title || {};
        const row = res.data;

        // 表头（列标题）
        const headers = [];
        for (let i = 1; i <= 10; i++) {
            const ct = title['col' + i + 'Title'];
            if (ct) headers.push(ct);
        }

        let html = `<div class="view-data-info"><span><strong>原始数据ID:</strong> ${row.id}</span><span><strong>行号:</strong> ${row.rowIndex || '-'}</span></div>`;
        html += '<div class="table-scroll"><table class="data-table"><thead><tr><th>列</th><th>值</th></tr></thead><tbody>';
        for (let i = 1; i <= 10; i++) {
            const ct = title['col' + i + 'Title'] || ('列' + i);
            const val = (row['col' + i] || '').replace(/</g, '&lt;').replace(/>/g, '&gt;');
            html += `<tr><td>${ct}</td><td style="white-space:pre-wrap;word-break:break-all">${val}</td></tr>`;
        }
        html += '</tbody></table></div>';

        $('#modalBody').innerHTML = html;
    } catch (e) {
        $('#modalBody').innerHTML = `<p style="text-align:center;padding:40px;color:var(--danger)">加载失败: ${e.message}</p>`;
    }
}

// ==================== 解析规则 ====================

let _rulesCache = null;
async function loadRules(force = false) {
    if (!_rulesCache || force) {
        try {
            _rulesCache = await api('/cleaning/parse-rules/active') || [];
        } catch (e) {
            showToast('加载规则失败: ' + e.message, 'error');
            return;
        }
    }
    const kw = (document.getElementById('ruleSearchInput')?.value || '').trim().toLowerCase();
    let rules = _rulesCache;
    if (kw) {
        rules = rules.filter(r =>
            (r.ruleName || '').toLowerCase().includes(kw) ||
            (r.description || '').toLowerCase().includes(kw)
        );
    }
    renderRuleTable(rules);
}

function renderRuleTable(rules) {
    const tbody = $('#ruleTbody');
    const count = $('#ruleRecordCount');
    if (!rules || rules.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6" class="empty-hint">暂无规则，请创建</td></tr>';
        if (count) count.textContent = '共 0 条';
        return;
    }
    if (count) count.textContent = `共 ${rules.length} 条`;
    tbody.innerHTML = rules.map(r => `
        <tr>
            <td>${r.id}</td>
            <td>${r.ruleName || '-'}</td>
            <td>${r.description || '-'}</td>
            <td><code>${r.keyValueSeparator || ' '}</code></td>
            <td><code>${r.itemSeparator || ';'}</code></td>
            <td>
                <button class="btn btn-sm btn-primary" onclick="openRuleEditModal(${r.id})">编辑</button>
                <button class="btn btn-sm btn-danger" onclick="deleteRule(${r.id})">删除</button>
            </td>
        </tr>
    `).join('');
}

function queryRules() {
    loadRules();
}

function resetRuleSearch() {
    const inp = document.getElementById('ruleSearchInput');
    if (inp) inp.value = '';
    loadRules();
}

async function saveRule() {
    const id = $('#ruleId').value;
    const body = {
        ruleName: $('#ruleName').value,
        keyValueSeparator: $('#keySep').value || ' ',
        itemSeparator: $('#itemSep').value || ';',
        escapeChar: $('#escapeChar').value || '',
        description: $('#ruleDesc').value || '',
        trimSpaces: $('#trimSpaces').checked,
        ignoreEmptyItems: $('#ignoreEmpty').checked,
        isActive: $('#isActive').checked,
    };
    showLoading('正在保存规则…');
    try {
        if (id) {
            await fetch(API + `/cleaning/parse-rule/${id}`, {
                method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
            }).then(r => r.json());
        } else {
            await api('/cleaning/parse-rule', { method: 'POST', body });
        }
        showToast('规则保存成功');
        clearRuleForm();
        closeModal();
        loadRules(true);
    } catch (e) {
        showToast('保存失败: ' + e.message, 'error');
    } finally {
        hideLoading();
    }
}

async function openRuleEditModal(id) {
    const formHtml = `
        <div class="form-grid">
            <input type="hidden" id="ruleId">
            <div class="form-group"><label>规则名称</label><input id="ruleName" class="form-input" placeholder="默认全描述规则" required></div>
            <div class="form-group"><label>键值分隔符</label><input id="keySep" class="form-input" placeholder="空格" value=" "></div>
            <div class="form-group"><label>项目分隔符</label><input id="itemSep" class="form-input" placeholder="分号" value=";"></div>
            <div class="form-group"><label>转义字符</label><input id="escapeChar" class="form-input" placeholder="选填"></div>
            <div class="form-group"><label>描述</label><input id="ruleDesc" class="form-input" placeholder="选填"></div>
            <div class="form-group checkbox-group">
                <label><input type="checkbox" id="trimSpaces" checked> 去除空格</label>
                <label><input type="checkbox" id="ignoreEmpty" checked> 忽略空项</label>
                <label><input type="checkbox" id="isActive" checked> 启用</label>
            </div>
            <div class="form-group"><button type="button" class="btn btn-primary" onclick="saveRule()">保存</button></div>
        </div>
    `;
    if (id) {
        showModal('编辑规则', formHtml);
        try {
            const rule = await api(`/cleaning/parse-rule/${id}`);
            $('#ruleId').value = rule.id;
            $('#ruleName').value = rule.ruleName || '';
            $('#keySep').value = rule.keyValueSeparator || ' ';
            $('#itemSep').value = rule.itemSeparator || ';';
            $('#escapeChar').value = rule.escapeChar || '';
            $('#ruleDesc').value = rule.description || '';
            $('#trimSpaces').checked = rule.trimSpaces !== false;
            $('#ignoreEmpty').checked = rule.ignoreEmptyItems !== false;
            $('#isActive').checked = rule.isActive !== false;
        } catch (e) {
            showToast('获取规则失败: ' + e.message, 'error');
            closeModal();
        }
    } else {
        showModal('新建规则', formHtml);
    }
}

async function deleteRule(id) {
    if (!confirm('确定删除该规则？')) return;
    showLoading('正在删除规则…');
    try {
        await api(`/cleaning/parse-rule/${id}`, { method: 'DELETE' });
        showToast('规则已删除');
        loadRules(true);
    } catch (e) {
        showToast('删除失败: ' + e.message, 'error');
    } finally {
        hideLoading();
    }
}

function clearRuleForm() {
    $('#ruleId').value = '';
    $('#ruleName').value = '';
    $('#keySep').value = ' ';
    $('#itemSep').value = ';';
    $('#escapeChar').value = '';
    $('#ruleDesc').value = '';
    $('#trimSpaces').checked = true;
    $('#ignoreEmpty').checked = true;
    $('#isActive').checked = true;
}

// ==================== 全描述提取 ====================

async function loadTitlesForSelect(selId, status) {
    try {
        // 指定 status 时（如结果数据模块只显示"导入数据完成"的数据文件）按状态过滤
        const url = status ? `/import/titles/by-status?status=${encodeURIComponent(status)}` : '/import/titles';
        const titles = await api(url);
        const sel = $(`#${selId}`);
        sel.innerHTML = '<option value="">-- 请选择 --</option>' +
            (titles || []).map(t => `<option value="${t.id}">${t.fileName || '数据#' + t.id} (${t.totalRows || 0}行)</option>`).join('');
    } catch (e) {
        console.error('加载文件选择列表失败:', e);
    }
}

// 标准字段表头列表缓存：避免每次查询/切换页面都全量拉取整张表头
let _standardTitlesCache = null;
async function getStandardTitles(force = false) {
    if (!force && _standardTitlesCache) return _standardTitlesCache;
    _standardTitlesCache = await api('/cleaning/standard-titles') || [];
    return _standardTitlesCache;
}
function invalidateStandardTitlesCache() {
    _standardTitlesCache = null;
}

async function loadRulesForSelect(selId) {
    try {
        const rules = await api('/cleaning/parse-rules/active');
        const sel = $(`#${selId}`);
        sel.innerHTML = '<option value="">-- 请选择 --</option>' +
            (rules || []).map(r => `<option value="${r.id}">${r.ruleName || '规则#' + r.id}</option>`).join('');
    } catch (e) {
        console.error('加载规则列表失败:', e);
    }
}

async function loadExtraTitlesForSelect(selId, titleId) {
    const sel = $(`#${selId}`);
    sel.innerHTML = '<option value="">加载中…</option>';
    try {
        const extraTitles = await api('/cleaning/extra-titles');
        // 如果指定了titleId，则过滤该数据文件关联的补充数据表头
        let filtered = extraTitles || [];
        if (titleId) {
            filtered = filtered.filter(et => et.tempDataTitleId == titleId);
        }
        if (!filtered || filtered.length === 0) {
            sel.innerHTML = '<option value="">-- 暂无补充数据表头 --</option>';
            return;
        }
        sel.innerHTML = '<option value="">-- 请选择 --</option>' +
            filtered.map(et => `<option value="${et.id}">${et.customName ? escapeHtml(et.customName) : '补充表头#' + et.id} (关联数据ID:${et.tempDataTitleId})</option>`).join('');
    } catch (e) {
        console.error('加载补充数据表头失败:', e);
        sel.innerHTML = '<option value="">-- 请选择 --</option>';
    }
}

async function loadStandardTitles(selId, titleId) {
    const sel = $(`#${selId}`);
    const prev = sel.value;   // 保留当前选中，避免重建下拉框时丢失用户已选条件
    sel.innerHTML = '<option value="">加载中…</option>';
    try {
        let standardTitles;
        if (titleId) {
            // 按数据文件查询其关联的标准字段表头（后端已建关联表，快速且准确）
            standardTitles = await api(`/cleaning/standard-titles/by-title?tempDataTitleId=${titleId}`) || [];
        } else {
            // 未指定数据文件时加载全部标准表头（如字段映射页）
            standardTitles = await getStandardTitles();
        }
        const filtered = standardTitles || [];

        if (!filtered || filtered.length === 0) {
            sel.innerHTML = '<option value="">-- 暂无标准字段表头 --</option>';
            return;
        }
        sel.innerHTML = '<option value="">-- 请选择 --</option>' +
            filtered.map(st => `<option value="${st.id}">${st.categoryName || st.categoryCode || '标准表头#' + st.id}</option>`).join('');
        if (prev) sel.value = prev;   // 恢复选中（若该项仍在新列表中）
    } catch (e) {
        console.error('加载标准字段表头失败:', e);
        sel.innerHTML = '<option value="">-- 请选择 --</option>';
    }
}

// 数据文件列表缓存：用于把关联数据ID映射为中文文件名
let _titlesCache = null;
async function getTitles(force = false) {
    if (!_titlesCache || force) {
        _titlesCache = await api('/import/titles') || [];
    }
    return _titlesCache;
}


// ==================== AI 智能提取 ====================

let aiExtractStomp = null;
let aiExtractSub = null;
let aiExtractPollTimer = null;

async function startAiExtract() {
    const titleId = $('#aiExtractTitleId').value;
    if (!titleId) { showToast('请先选择数据文件', 'warning'); return; }

    // 重置进度 UI
    $('#aiExtractProgressCard').style.display = 'block';
    $('#aiExtractFill').style.width = '0%';
    $('#aiExtractFill').textContent = '0%';
    $('#aiExtractCurrent').textContent = '0';
    $('#aiExtractTotal').textContent = '0';
    $('#aiExtractSuccess').textContent = '0';
    $('#aiExtractError').textContent = '0';
    $('#aiExtractStatus').textContent = '正在连接 AI 提取服务…';

    // 启动动态 AI 特效
    const aiExCard = document.getElementById('aiExtractProgressCard');
    if (aiExCard) AiFx.activate(aiExCard);

    disconnectAiExtractWs();

    connectAiExtractWs(titleId, function connected() {
        $('#aiExtractStatus').textContent = 'AI 提取任务已启动，正在处理…';
        fetch(API + `/cleaning/extract-extra-ai?titleId=${titleId}`, { method: 'POST' })
            .then(res => safeJson(res, 'AI 提取'))
            .then(data => { if (data.code !== 200) throw new Error(data.msg); })
            .catch(e => {
                $('#aiExtractStatus').textContent = 'AI 提取启动失败: ' + e.message;
                showToast('AI 提取启动失败: ' + e.message, 'error');
            });
    });
}

function connectAiExtractWs(titleId, onConnected) {
    const socket = new SockJS('/ws-cleaning');
    aiExtractStomp = Stomp.over(socket);
    aiExtractStomp.debug = null;
    aiExtractStomp.connect({}, function (frame) {
        console.log('AI 提取 WebSocket 已连接:', frame);
        aiExtractSub = aiExtractStomp.subscribe('/topic/ai-extract/' + titleId, function (message) {
            handleAiExtractMessage(JSON.parse(message.body));
        });
        startAiExtractPoll(titleId);
        if (onConnected) onConnected();
    }, function (error) {
        console.error('AI 提取 WebSocket 连接失败:', error);
        $('#aiExtractStatus').textContent = '实时连接失败，使用轮询模式';
        startAiExtractPoll(titleId);
        if (onConnected) onConnected();
    });
}

function startAiExtractPoll(titleId) {
    if (aiExtractPollTimer) clearInterval(aiExtractPollTimer);
    aiExtractPollTimer = setInterval(async () => {
        try {
            const p = await api(`/cleaning/ai-extract-progress/${titleId}`);
            handleAiExtractMessage(p);
            if (p.type === 'complete' || p.type === 'error') {
                clearInterval(aiExtractPollTimer);
                aiExtractPollTimer = null;
            }
        } catch (e) { /* 忽略轮询错误 */ }
    }, 2000);
}

function disconnectAiExtractWs() {
    if (aiExtractSub) { try { aiExtractSub.unsubscribe(); } catch (e) {} aiExtractSub = null; }
    if (aiExtractStomp) { try { aiExtractStomp.disconnect(); } catch (e) {} aiExtractStomp = null; }
    if (aiExtractPollTimer) { clearInterval(aiExtractPollTimer); aiExtractPollTimer = null; }
}

function handleAiExtractMessage(msg) {
    if (!msg) return;
    const type = msg.type;
    const aiExCard = document.getElementById('aiExtractProgressCard');
    const current = msg.current || 0;
    const total = msg.total || 0;
    const percent = msg.progressPercent || 0;
    const success = msg.successCount || 0;
    const error = msg.errorCount || 0;

    $('#aiExtractFill').style.width = percent + '%';
    $('#aiExtractFill').textContent = percent + '%';
    $('#aiExtractCurrent').textContent = current;
    $('#aiExtractTotal').textContent = total;
    $('#aiExtractSuccess').textContent = success;
    $('#aiExtractError').textContent = error;

    if (type === 'start') {
        $('#aiExtractStatus').textContent = 'AI 提取开始，共 ' + total + ' 条数据';
    } else if (type === 'progress') {
        $('#aiExtractStatus').textContent = 'AI 提取中… ' + current + '/' + total + ' (成功 ' + success + ', 失败 ' + error + ')';
    } else if (type === 'complete') {
        $('#aiExtractStatus').textContent = (msg.message || 'AI 提取完成') + '，共处理 ' + total + ' 条 (成功 ' + success + ', 失败 ' + error + ')';
        showToast('AI 属性提取完成');
        if (aiExCard && aiExCard.classList.contains('ai-active')) AiFx.deactivate(aiExCard);
        loadExtraTitlesForSelect('mapExtraTitleId');
        // 自动弹窗展示本次提取结果
        const aiSel = $('#aiExtractTitleId');
        if (aiSel && aiSel.value) {
            loadExtractTree(aiSel.value);
        }
        loadAiExtractTasks();
        setTimeout(disconnectAiExtractWs, 2000);
    } else if (type === 'error') {
        $('#aiExtractStatus').textContent = 'AI 提取异常终止：' + (msg.message || '');
        showToast('AI 提取异常终止', 'error');
        if (aiExCard && aiExCard.classList.contains('ai-active')) AiFx.deactivate(aiExCard);
        setTimeout(disconnectAiExtractWs, 2000);
    }
}

// ==================== AI 智能提取记录列表 ====================

async function loadAiExtractTasks() {
    const tbody = $('#aiExtractTaskTbody');
    if (!tbody) return;
    tbody.innerHTML = '<tr><td colspan="7" class="empty-hint">加载中…</td></tr>';
    try {
        const list = await api('/cleaning/ai-extract-tasks');
        if (!list || list.length === 0) {
            tbody.innerHTML = '<tr><td colspan="7" class="empty-hint">暂无提取记录</td></tr>';
            return;
        }
        const statusMap = {
            RUNNING: '<span class="badge badge-warning">提取中</span>',
            SUCCESS: '<span class="badge badge-success">成功</span>',
            PARTIAL: '<span class="badge badge-warning">部分成功</span>',
            FAILED: '<span class="badge badge-danger">失败</span>'
        };
        tbody.innerHTML = list.map(t => {
            const cost = t.extractCostMs != null ? (t.extractCostMs / 1000).toFixed(1) + ' 秒' : '-';
            const status = t.extractStatus ? (statusMap[t.extractStatus] || escapeHtml(t.extractStatus)) : '-';
            return `<tr>
                <td>${escapeHtml(t.fileName || '-')}</td>
                <td>${t.rowCount != null ? t.rowCount : '-'}</td>
                <td>${status}</td>
                <td>${fmtLocalTime(t.extractStartTime)}</td>
                <td>${fmtLocalTime(t.extractEndTime)}</td>
                <td>${cost}</td>
                <td><button class="btn btn-sm btn-primary" onclick="viewAiExtractTask(${t.tempDataTitleId})">查看</button></td>
            </tr>`;
        }).join('');
    } catch (e) {
        tbody.innerHTML = `<tr><td colspan="7" class="empty-hint" style="color:var(--danger)">加载失败: ${escapeHtml(e.message)}</td></tr>`;
    }
}

// 点击查看：弹窗展示该文件的层级提取结果
async function viewAiExtractTask(tempDataTitleId) {
    await loadExtractTree(tempDataTitleId);
}

// 当前弹窗中查看的 tempDataTitleId（用于明细修改后刷新弹窗）
let _extractModalTitleId = null;

// ==================== 属性提取结果：弹窗层级查看（文件 → 分类 → 属性） ====================

async function loadExtractTree(tempDataTitleId) {
    if (!tempDataTitleId) return;
    _extractModalTitleId = tempDataTitleId;
    showModal('提取结果（层级查看）', '<p style="text-align:center;padding:40px;color:var(--text-secondary)">加载中…</p>', 'extract');
    try {
        // 不传 extraDataTitleId，后端自动取该文件最近一次提取结果
        const data = await api(`/cleaning/extract-result-tree?tempDataTitleId=${encodeURIComponent(tempDataTitleId)}`);
        renderExtractTreeInto('modalBody', data);
    } catch (e) {
        $('#modalBody').innerHTML = `<p style="text-align:center;padding:40px;color:var(--danger)">加载失败: ${escapeHtml(e.message)}</p>`;
    }
}

function renderExtractTreeInto(containerId, data) {
    const box = $(`#${containerId}`);
    if (!box) return;

    const file = data.file || {};
    const s = data.summary || {};
    const summaryHtml = data.extraTitle
        ? `<div style="font-size:13px;color:var(--text-secondary);margin-bottom:12px">
             文件：<b>${escapeHtml(file.fileName || '-')}</b>　|　提取行数：<b>${s.totalRows || 0}</b>　|　分类数：<b>${s.categoryCount || 0}</b>　|　属性列数：<b>${s.columnCount || 0}</b>
           </div>`
        : `<div style="font-size:13px;color:var(--text-secondary);margin-bottom:12px">
             文件：<b>${escapeHtml(file.fileName || '-')}</b>　该文件暂无提取结果
           </div>`;

    const cats = data.categories || [];
    if (!data.extraTitle || cats.length === 0) {
        box.innerHTML = summaryHtml + '<p class="empty-hint">暂无提取结果，请先执行属性提取</p>';
        return;
    }

    // 第一层：文件；第二层：分类（可折叠）；第三层：属性列表 + 明细行
    let html = summaryHtml + `<div class="tree-file" style="border:1px solid var(--border,#e5e7eb);border-radius:8px;overflow:hidden">
        <div style="padding:10px 14px;background:var(--bg-secondary,#f6f7f9);font-weight:600">
            📄 ${escapeHtml(file.fileName || '未知文件')}
            <span style="font-weight:400;color:var(--text-secondary);font-size:12px">（${cats.length} 个分类 / ${s.totalRows || 0} 行）</span>
        </div>`;

    cats.forEach((c, ci) => {
        const catLabel = c.categoryCode
            ? `${escapeHtml(c.categoryCode)} ${escapeHtml(c.categoryName || '')}`
            : '未分类';
        html += `<div style="border-top:1px solid var(--border,#e5e7eb)">
            <div onclick="toggleExtractCat(${ci})" style="padding:9px 14px 9px 30px;cursor:pointer;display:flex;align-items:center;gap:8px">
                <span id="treeCatIcon-${ci}">▸</span>
                <b>📁 ${catLabel}</b>
                <span style="color:var(--text-secondary);font-size:12px">${c.rowCount} 行 · ${(c.attributes || []).length} 个属性</span>
            </div>
            <div id="treeCatBody-${ci}" style="display:none;padding:0 14px 14px 44px">`;

        // 属性列表（汇总视图）
        const attrs = c.attributes || [];
        if (attrs.length === 0) {
            html += '<p class="empty-hint">该分类下未提取到属性</p>';
        } else {
            html += `<table class="data-table" style="margin-bottom:10px">
                <thead><tr><th>属性名</th><th>是否标准字段</th><th>已填充</th><th>填充率</th></tr></thead><tbody>`;
            attrs.forEach(a => {
                html += `<tr>
                    <td>${escapeHtml(a.name)}</td>
                    <td>${a.isStandardField ? '<span class="badge badge-success">标准</span>' : '<span class="badge">额外</span>'}</td>
                    <td>${a.filledCount}</td>
                    <td>${a.fillRate}%</td>
                </tr>`;
            });
            html += '</tbody></table>';
            html += `<button class="btn btn-secondary btn-sm" onclick="toggleExtractRows(${ci})">查看/收起明细（${c.rowCount} 行）</button>`;

            // 明细行（属性值矩阵）
            const cols = attrs.map(a => a.name);
            html += `<div id="treeCatRows-${ci}" style="display:none;overflow:auto;margin-top:10px">
                <table class="data-table"><thead><tr><th>物料编码</th><th>物料名称</th>${cols.map(x => `<th>${escapeHtml(x)}</th>`).join('')}<th>操作</th></tr></thead><tbody>`;
            (c.rows || []).slice(0, 500).forEach(r => {
                const extraId = r.extraDataId != null ? r.extraDataId : '';
                html += `<tr><td>${escapeHtml(r.materialCode || '-')}</td><td>${escapeHtml(r.materialName || '-')}</td>`
                    + cols.map(x => `<td>${escapeHtml((r.attrs || {})[x] || '')}</td>`).join('')
                    + `<td><button class="btn btn-sm btn-secondary" onclick="viewSourceData(${extraId})">源数据</button> `
                    + `<button class="btn btn-sm btn-primary" onclick="editExtraRow(${extraId})">修改</button></td></tr>`;
            });
            html += '</tbody></table>';
            if ((c.rows || []).length > 500) {
                html += `<p class="empty-hint">仅展示前 500 行，共 ${c.rows.length} 行</p>`;
            }
            html += '</div>';
        }
        html += '</div></div>';
    });
    html += '</div>';
    box.innerHTML = html;
}

function toggleExtractCat(ci) {
    const body = $(`#treeCatBody-${ci}`);
    const icon = $(`#treeCatIcon-${ci}`);
    if (!body) return;
    const open = body.style.display !== 'none';
    body.style.display = open ? 'none' : 'block';
    if (icon) icon.textContent = open ? '▸' : '▾';
}

function toggleExtractRows(ci) {
    const el = $(`#treeCatRows-${ci}`);
    if (!el) return;
    el.style.display = el.style.display === 'none' ? 'block' : 'none';
}

// ==================== 属性提取明细：源数据查看 / 修改 ====================

// 源数据查看：子弹窗展示原始各列 + 当前提取属性（不销毁主弹窗）
async function viewSourceData(extraDataId) {
    openSubModal('源数据查看', '<p style="text-align:center;padding:40px;color:var(--text-secondary)">加载中...</p>');
    try {
        const d = await api(`/cleaning/extra-row/${extraDataId}`);
        const srcHeaders = d.sourceHeaders || [];
        const srcData = d.sourceData || [];
        let html = `<div class="view-data-info"><span><strong>物料:</strong> ${escapeHtml(d.materialName || '-')}</span>`
            + `<span><strong>分类:</strong> ${escapeHtml(d.categoryName || '未分类')}</span></div>`;

        // 源数据表
        html += `<h4 style="margin:14px 0 6px">原始数据</h4>`;
        if (srcHeaders.length === 0) {
            html += '<p class="empty-hint">无源数据</p>';
        } else {
            html += '<div class="table-scroll"><table class="data-table"><thead><tr>'
                + srcHeaders.map(h => `<th>${escapeHtml(h)}</th>`).join('') + '</tr></thead><tbody><tr>'
                + srcData.map(v => `<td>${escapeHtml(v || '')}</td>`).join('') + '</tr></tbody></table></div>';
        }

        // 提取属性表
        html += `<h4 style="margin:14px 0 6px">提取属性</h4>`;
        const cols = d.columns || [];
        const attrs = d.attrs || {};
        if (cols.length === 0) {
            html += '<p class="empty-hint">无提取属性</p>';
        } else {
            html += '<div class="table-scroll"><table class="data-table"><thead><tr><th>属性名</th><th>属性值</th></tr></thead><tbody>'
                + cols.map(c => `<tr><td>${escapeHtml(c)}</td><td>${escapeHtml(attrs[c] || '')}</td></tr>`).join('')
                + '</tbody></table></div>';
        }
        const body = document.querySelector('.sub-modal .sub-modal-body');
        if (body) body.innerHTML = html;
    } catch (e) {
        const body = document.querySelector('.sub-modal .sub-modal-body');
        if (body) body.innerHTML = `<p style="text-align:center;padding:40px;color:var(--danger)">加载失败: ${escapeHtml(e.message)}</p>`;
    }
}

// 明细修改：子弹窗展示可编辑的提取属性表单，保存后刷新层级树（不销毁主弹窗）
let _editExtraDataId = null;
async function editExtraRow(extraDataId) {
    openSubModal('修改提取明细', '<p style="text-align:center;padding:40px;color:var(--text-secondary)">加载中...</p>');
    try {
        const d = await api(`/cleaning/extra-row/${extraDataId}`);
        const cols = d.columns || [];
        const attrs = d.attrs || {};
        _editExtraDataId = extraDataId;

        let html = `<div class="view-data-info"><span><strong>物料:</strong> ${escapeHtml(d.materialName || '-')}</span>`
            + `<span><strong>分类:</strong> ${escapeHtml(d.categoryName || '未分类')}</span></div>`;
        html += `<p style="color:var(--text-secondary);font-size:12px;margin:10px 0">仅修改下方提取属性，保存后将直接覆盖该行的提取结果。</p>`;
        html += '<div id="editExtraForm" class="form-grid">';
        if (cols.length === 0) {
            html += '<p class="empty-hint">该明细无提取属性列</p>';
        } else {
            cols.forEach(c => {
                html += `<div class="form-group"><label>${escapeHtml(c)}</label>`
                    + `<input class="form-input edit-col" data-col="${escapeHtml(c)}" type="text" value="${escapeHtml(attrs[c] || '')}"></div>`;
            });
        }
        html += '</div>';
        html += `<div style="margin-top:14px;text-align:right">
            <button class="btn btn-secondary" onclick="closeSubModal()">取消</button>
            <button class="btn btn-primary" onclick="saveExtraRow()">保存</button></div>`;
        const body = document.querySelector('.sub-modal .sub-modal-body');
        if (body) body.innerHTML = html;
    } catch (e) {
        const body = document.querySelector('.sub-modal .sub-modal-body');
        if (body) body.innerHTML = `<p style="text-align:center;padding:40px;color:var(--danger)">加载失败: ${escapeHtml(e.message)}</p>`;
    }
}

async function saveExtraRow() {
    if (!_editExtraDataId) return;
    const inputs = document.querySelectorAll('#editExtraForm .edit-col');
    const cols = {};
    inputs.forEach(inp => { cols[inp.getAttribute('data-col')] = inp.value; });
    showLoading('正在保存…');
    try {
        await api(`/cleaning/extra-row/${_editExtraDataId}`, { method: 'POST', body: JSON.stringify(cols), headers: { 'Content-Type': 'application/json' } });
        showToast('保存成功');
        // 关闭子弹窗
        closeSubModal();
        // 在原层级查看主弹窗中重新渲染
        if (_extractModalTitleId) {
            const modal = $('#modalBody');
            if (modal) {
                modal.innerHTML = '<p style="text-align:center;padding:40px;color:var(--text-secondary)">加载中…</p>';
                try {
                    const data = await api(`/cleaning/extract-result-tree?tempDataTitleId=${encodeURIComponent(_extractModalTitleId)}`);
                    renderExtractTreeInto('modalBody', data);
                } catch (e2) {
                    modal.innerHTML = `<p style="text-align:center;padding:40px;color:var(--danger)">刷新失败: ${escapeHtml(e2.message)}</p>`;
                }
            }
        }
        _editExtraDataId = null;
        // 同时刷新记录列表
        loadAiExtractTasks();
    } catch (e) {
        showToast('保存失败: ' + e.message, 'error');
    } finally {
        hideLoading();
    }
}

// ==================== 数据清洗 ====================

let stompClient = null;
let cleaningSubscription = null;

async function loadCleanStats() {
    try {
        const stats = await api('/cleaning/statistics');
        $('#statTotal').textContent = stats.totalFiles || 0;
        $('#statCleaned').textContent = stats.totalCleaned || 0;

        const report = await api('/cleaning/quality-report');
        $('#statAvgScore').textContent = report.averageScore ? report.averageScore.toFixed(1) : '-';
    } catch (e) {
        console.error('加载清洗统计失败:', e);
        $('#statTotal').textContent = '-';
        $('#statCleaned').textContent = '-';
        $('#statAvgScore').textContent = '-';
    }
}

// 一键清洗完成后，切换到智能分类页时携带的上下文（待加载的文件）
let ocPendingTitleId = null;

// 刷新智能分类页：加载下拉、统计、文件列表（清洗状态一览）
async function refreshCleanPage() {
    loadTitles();
    loadRules();
    loadCleanStats();
    loadRulesForSelect('cleanRuleId');
    await loadTitlesForSelect('cleanTitleId');
    // 回显批次分类每批条数默认值（与后端 app.ai.batch-classify-size 保持一致；用户可改）
    const bsEl = document.getElementById('batchSizeInput');
    if (bsEl && !bsEl.value) bsEl.value = '5';
    // 一键清洗完成后跳转到本页时，自动选中对应的数据文件
    if (ocPendingTitleId != null) {
        const ct = document.getElementById('cleanTitleId');
        if (ct) ct.value = String(ocPendingTitleId);
        ocPendingTitleId = null;
    }
    // 以文件列表为单位展示清洗状态
    loadCleanFileList();
    // 清洗固定启用 AI 智能分类，无需再触发 AI 辅助分类检测
}

/** 格式化耗时（毫秒 -> 时:分:秒） */
function formatDuration(ms) {
    if (ms == null) return '-';
    const total = Math.max(0, Math.round(ms / 1000));
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    const s = total % 60;
    if (h > 0) return h + '时' + m + '分' + s + '秒';
    if (m > 0) return m + '分' + s + '秒';
    return s + '秒';
}

// 加载清洗文件列表（以数据文件为单位展示清洗状态）
async function loadCleanFileList() {
    const tbody = $('#cleanFileTbody');
    const summary = $('#cleanFileSummary');
    if (!tbody) return;
    tbody.innerHTML = '<tr><td colspan="7" class="empty-hint">加载中…</td></tr>';
    try {
        const titles = await api('/import/titles');
        if (!titles || titles.length === 0) {
            tbody.innerHTML = '<tr><td colspan="7" class="empty-hint">暂无数据文件</td></tr>';
            if (summary) summary.textContent = '共 0 个文件';
            return;
        }
        // 当前正在查看结果的文件 ID（用于高亮）
        const currentTitleId = window.__cleanViewTitleId;
        tbody.innerHTML = titles.map(t => {
            const st = t.status ? t.status.toLowerCase() : 'draft';
            const startT = t.cleanStartTime ? fmtLocalTime(t.cleanStartTime) : '-';
            const endT = t.cleanEndTime ? fmtLocalTime(t.cleanEndTime) : '-';
            let duration = '-';
            if (t.cleanStartTime) {
                const startMs = new Date(t.cleanStartTime).getTime();
                const endMs = t.cleanEndTime ? new Date(t.cleanEndTime).getTime() : Date.now();
                duration = formatDuration(endMs - startMs);
            }
            const highlight = currentTitleId != null && String(currentTitleId) === String(t.id) ? ' style="background:rgba(26,115,232,0.06)"' : '';
            return '<tr' + highlight + '>' +
                '<td title="' + escapeHtml(t.fileName || '') + '">' + escapeHtml(t.fileName || ('数据#' + t.id)) + '</td>' +
                '<td>' + (t.totalRows || 0) + '</td>' +
                '<td>' + statusBadge(st) + '</td>' +
                '<td>' + startT + '</td>' +
                '<td>' + endT + '</td>' +
                '<td>' + duration + '</td>' +
                '<td><button class="btn btn-xs btn-primary" onclick="viewCleanResult(' + t.id + ')">查看</button></td>' +
                '</tr>';
        }).join('');
        if (summary) summary.textContent = '共 ' + titles.length + ' 个文件';
    } catch (e) {
        console.error('加载清洗文件列表失败:', e);
        tbody.innerHTML = '<tr><td colspan="7" class="empty-hint">加载失败：' + (e && e.message ? e.message : e) + '</td></tr>';
    }
}

// 点击「查看」进入指定文件的清洗结果记录
function viewCleanResult(titleId) {
    if (!titleId) { showToast('文件不存在', 'warning'); return; }
    window.__cleanViewTitleId = titleId;
    const fileListCard = document.getElementById('cleanFileCard');
    const recordsCard = document.getElementById('cleanedRecordsCard');
    const titleEl = document.getElementById('cleanedRecordsTitle');
    if (fileListCard) fileListCard.style.display = 'none';
    if (recordsCard) recordsCard.style.display = 'block';
    // 回显文件名到结果卡片标题
    const sel = document.getElementById('cleanTitleId');
    const opt = sel ? sel.querySelector('option[value="' + titleId + '"]') : null;
    if (titleEl) titleEl.textContent = '清洗结果：' + (opt ? opt.textContent.split(' (')[0] : ('文件#' + titleId));
    loadCleanedRecords(titleId);
}

// 返回文件列表
function closeCleanResult() {
    window.__cleanViewTitleId = null;
    const fileListCard = document.getElementById('cleanFileCard');
    const recordsCard = document.getElementById('cleanedRecordsCard');
    if (fileListCard) fileListCard.style.display = 'block';
    if (recordsCard) recordsCard.style.display = 'none';
    loadCleanFileList();
}

// 加载指定数据文件的清洗结果记录
async function loadCleanedRecords(titleId) {
    const card = document.getElementById('cleanedRecordsCard');
    const tbody = $('#cleanedRecordsTbody');
    if (!titleId) {
        if (card) card.style.display = 'none';
        return;
    }
    if (card) card.style.display = 'block';
    tbody.innerHTML = '<tr><td colspan="11" class="empty-hint">加载中…</td></tr>';
    try {
        const list = await api('/cleaning/cleaned-data/search', {
            method: 'POST',
            body: { tempDataTitleId: parseInt(titleId, 10), queryAll: true, sortBy: 'createdAt', sortOrder: 'desc' }
        });
        renderCleanedRecords(list || []);
    } catch (e) {
        console.error('加载清洗结果记录失败:', e);
        tbody.innerHTML = '<tr><td colspan="11" class="empty-hint">加载失败：' + (e && e.message ? e.message : e) + '</td></tr>';
    }
}

// 渲染清洗结果记录表格（含 AI 辅助评分、AI 分类理由与状态，不含清洗开始/结束时间）
function renderCleanedRecords(list) {
    const tbody = $('#cleanedRecordsTbody');
    const summary = $('#cleanedRecordsSummary');
    if (!list || list.length === 0) {
        tbody.innerHTML = '<tr><td colspan="11" class="empty-hint">暂无清洗结果记录</td></tr>';
        if (summary) summary.textContent = '共 0 条';
        return;
    }
    let sum = 0, scored = 0;
    let html = '';
    list.forEach((d, i) => {
        const idx = i + 1;
        const score = d.qualityScore != null ? Number(d.qualityScore).toFixed(1) : '-';
        const scoreClass = d.qualityScore != null ? (d.qualityScore >= 80 ? 'badge-success' : d.qualityScore >= 60 ? 'badge-warning' : 'badge-danger') : '';
        const name = d.materialName ? (d.materialName.length > 24 ? d.materialName.substring(0, 24) + '…' : d.materialName) : '-';
        const reasonCell = d.aiReason
            ? '<div class="reason-cell" title="' + escapeHtml(d.aiReason) + '">' + escapeHtml(d.aiReason) + '</div>'
            : '<span class="empty-hint">-</span>';
        // AI候选分类（top-k）：解析 JSON 展示编码+名称，悬停显示完整路径
        const candidateCell = renderAiCandidates(d.aiCandidateCodes);
        // 原始数据查看按钮
        const viewBtn = '<button class="btn btn-xs btn-outline" onclick="viewCleanedSource(' + d.tempDataId + ',' + d.id + ')">查看</button>';
        // 分类名称（单击可编辑）+ 分类编码
        const catNameCell = '<span class="cat-edit" title="单击修改分类（搜索选择标准分类）" onclick="openCategoryEditor(' + d.id + ', this)">' +
            escapeHtml(d.categoryName || '-') + '</span>';
        // 备注（可编辑）
        const remark = d.reviewComment || '';
        const remarkCell = '<span class="cat-remark" title="点击修改备注" onclick="editCleanedRemark(' + d.id + ', this)">' +
            (remark ? escapeHtml(remark) : '<span class="empty-hint">-</span>') + '</span>';
        html += '<tr>' +
            '<td>' + idx + '</td>' +
            '<td>' + viewBtn + '</td>' +
            '<td>' + (d.materialCode || '-') + '</td>' +
            '<td title="' + (d.materialName || '') + '">' + name + '</td>' +
            '<td>' + catNameCell + '</td>' +
            '<td>' + (d.categoryCode || '-') + '</td>' +
            '<td><span class="badge ' + scoreClass + '">' + score + '</span></td>' +
            '<td>' + remarkCell + '</td>' +
            '<td>' + reasonCell + '</td>' +
            '<td>' + candidateCell + '</td>' +
            '<td>' + statusBadge(statusCleanText(d.status)) + '</td>' +
        '</tr>';
        if (d.qualityScore != null) { sum += d.qualityScore; scored++; }
    });
    tbody.innerHTML = html;
    const avg = scored ? (sum / scored).toFixed(1) : '-';
    if (summary) summary.textContent = '共 ' + list.length + ' 条 ｜ 平均 AI 辅助评分 ' + avg;
}

// ==================== 智能分类人工修正 ====================

/** 查看清洗数据的原始数据 */
function viewCleanedSource(tempDataId, cleanedDataId) {
    if (!tempDataId) { showToast('该记录没有关联的原始数据', 'warning'); return; }
    viewSourceData(tempDataId);
}

/** 编辑备注（修改原因） */
async function editCleanedRemark(id, el) {
    const cur = el.innerText === '-' ? '' : el.innerText;
    const remark = prompt('请输入备注（修改原因）：', cur);
    if (remark === null) return;
    await saveCleanedCategory(id, null, null, remark, el);
}

/** 保存单条分类修正（可只改分类、只改备注、或都改）；状态自动置为已修改 */
async function saveCleanedCategory(id, categoryCode, categoryName, remark, el) {
    try {
        const params = new URLSearchParams();
        if (categoryCode != null && categoryCode !== '') params.append('categoryCode', categoryCode);
        if (categoryName != null && categoryName !== '') params.append('categoryName', categoryName);
        if (remark != null && remark !== '') params.append('remark', remark);
        const url = '/cleaning/cleaned-data/' + id + (params.toString() ? '?' + params.toString() : '');
        // 使用统一 api()：自动携带登录 Token，并统一判断 data.code，避免裸 fetch 缺鉴权导致保存失败
        const res = await api(url, { method: 'PUT' });
        if (!res) throw new Error('保存失败：无返回');
        showToast('已保存，状态改为已修改', 'success');
        // 刷新当前正在查看的清洗结果（若在文件列表视图则同时刷新文件列表）
        const viewTitleId = window.__cleanViewTitleId || $('#cleanTitleId').value;
        if (viewTitleId) loadCleanedRecords(viewTitleId);
        if (!window.__cleanViewTitleId) loadCleanFileList();
    } catch (e) {
        showToast('保存失败：' + e.message, 'error');
    }
}

/** 打开分类名称编辑框：搜索下拉模糊匹配名称/编码，选择后保存（可同时填写修改原因备注） */
function openCategoryEditor(id, el) {
    const curName = el.innerText === '-' ? '' : el.innerText;
    const row = el.closest('tr');
    // 从当前行取：分类编码、备注、物料名（供搜索参考）
    const cells = row ? row.querySelectorAll('td') : [];
    const curCode = cells.length > 5 ? cells[5].innerText.trim() : '';
    const curRemark = cells.length > 7 ? (cells[7].innerText === '-' ? '' : cells[7].innerText.trim()) : '';

    let html = '<p style="font-size:12px;color:var(--text-secondary);margin-bottom:8px">在下方搜索并选择标准分类，或填写备注后保存；当前分类：' +
        escapeHtml(curName) + '（' + escapeHtml(curCode) + '）</p>';
    html += '<input id="catSearchInput" class="form-input" placeholder="输入分类名称或分类代码进行模糊搜索…" value="' + escapeHtml(curName) + '" oninput="onCatSearchInput(this.value)"/>';
    html += '<div id="catSearchResult" style="max-height:240px;overflow-y:auto;border:1px solid var(--border,#ddd);border-radius:6px;margin-top:8px">' +
        '<p style="padding:12px;color:var(--text-secondary);font-size:13px">输入关键词后列出候选分类</p></div>';
    html += '<div style="margin-top:10px"><label style="font-size:12px;color:var(--text-secondary)">备注（修改原因）：</label>' +
        '<textarea id="catRemarkInput" class="form-input" style="min-height:48px;margin-top:4px" placeholder="填写本次修改的原因，便于追溯">' + escapeHtml(curRemark) + '</textarea></div>';
    html += '<div style="margin-top:10px;display:flex;gap:8px;justify-content:flex-end">' +
        '<button class="btn btn-outline" onclick="closeModal()">取消</button>' +
        '<button class="btn btn-primary" id="catSaveBtn" onclick="saveSelectedCategory(' + id + ')">保存修改</button></div>';

    showModal('修改分类', html);
    // 重置已选分类
    window.__catSelCode = null;
    window.__catSelName = null;
    // 初始加载候选（默认按当前分类名搜索）
    onCatSearchInput(curName);
}

/** 分类搜索输入实时触发 */
let _catSearchTimer = null;
async function onCatSearchInput(kw) {
    clearTimeout(_catSearchTimer);
    _catSearchTimer = setTimeout(async () => {
        const box = $('#catSearchResult');
        if (!box) return;
        if (!kw || !kw.trim()) {
            box.innerHTML = '<p style="padding:12px;color:var(--text-secondary);font-size:13px">输入关键词后列出候选分类</p>';
            return;
        }
        try {
            const res = await api('/cleaning/category-search?keyword=' + encodeURIComponent(kw.trim()) + '&limit=20');
            // 注意：api() 已解包返回 data 本体（此处即为分类数组），不能用 res.data（会是 undefined，导致搜索结果永远为空）
            const list = res || [];
            if (list.length === 0) {
                box.innerHTML = '<p style="padding:12px;color:var(--text-secondary);font-size:13px">未找到匹配的标准分类</p>';
                return;
            }
            box.innerHTML = list.map(c => {
                const selected = (c.categoryCode === window.__catSelCode ? ' style="background:var(--accent);color:#fff"' : '');
                return '<div class="cat-search-item" data-code="' + escapeHtml(c.categoryCode) + '" data-name="' + escapeHtml(c.categoryName) + '" ' +
                    'onclick="selectCatItem(this)" ' + selected + '>' +
                    '<span class="cat-code">' + escapeHtml(c.categoryCode) + '</span>' +
                    '<span class="cat-name">' + escapeHtml(c.categoryName) + '</span>' +
                    '<span class="cat-path">' + escapeHtml(c.categoryFullPath || '') + '</span></div>';
            }).join('');
        } catch (e) {
            box.innerHTML = '<p style="padding:12px;color:var(--danger);font-size:13px">搜索失败：' + e.message + '</p>';
        }
    }, 300);
}

/** 选中一条候选分类 */
function selectCatItem(el) {
    window.__catSelCode = el.getAttribute('data-code');
    window.__catSelName = el.getAttribute('data-name');
    $$('#catSearchResult .cat-search-item').forEach(x => x.style.background = '');
    el.style.background = 'var(--accent)';
    el.style.color = '#fff';
    // 把输入框内容同步为已选分类，避免后续输入重渲染冲掉选中态
    const input = $('#catSearchInput');
    if (input) input.value = (window.__catSelCode || '') + ' ' + (window.__catSelName || '');
    const box = $('#catSearchResult');
    if (box) box.innerHTML = '<p style="padding:10px;color:var(--accent);font-size:13px">已选择：' +
        escapeHtml(window.__catSelCode || '') + ' ' + escapeHtml(window.__catSelName || '') + '（可再次输入关键词重新搜索）</p>';
}

/** 保存选中的分类（若无选择分类则仅保存备注/不改变分类） */
async function saveSelectedCategory(id) {
    const code = window.__catSelCode || null;
    const name = window.__catSelName || null;
    const remarkInput = $('#catRemarkInput');
    const remark = remarkInput ? remarkInput.value : null;
    await saveCleanedCategory(id, code, name, remark);
    closeModal();
}

// 智能分类页切换数据文件下拉时仅刷新文件列表（清洗状态一览），不自动打开结果
function onCleanTitleChange() {
    loadCleanFileList();
}

async function startCleaning() {
    const titleId = $('#cleanTitleId').value;
    if (!titleId) { showToast('请选择数据文件', 'warning'); return; }
    // 清洗逻辑固定启用 AI 智能分类（待分类数据 + top-K 相关标准分类交由大模型识别并给出理由）
    const useAi = true;
    // 读取页面配置的每批条数（可空，后端用默认 batch-classify-size）
    const batchSizeRaw = $('#batchSizeInput').value;
    const batchSize = (batchSizeRaw && Number(batchSizeRaw) > 0) ? Number(batchSizeRaw) : '';

    // AI 介入时启动动态 AI 特效
    const clCard = document.getElementById('cleanLiveCard');
    if (clCard) AiFx.activate(clCard);

    // 断开之前的连接
    disconnectWebSocket();

    // 显示实时清洗面板
    $('#cleanLiveCard').style.display = 'block';
    $('#cleanProgressFill').style.width = '0%';
    $('#cleanProgressFill').textContent = '0%';
    $('#liveCurrent').textContent = '0';
    $('#liveTotal').textContent = '0';
    $('#liveSuccess').textContent = '0';
    $('#liveError').textContent = '0';
    $('#cleanStatus').style.display = 'block';
    $('#cleanStatus').innerHTML = '<p style="font-size:13px;color:var(--text-secondary)">正在连接清洗服务…</p>';

    // 先连接 WebSocket
    connectWebSocket(titleId, function connected() {
        // WebSocket 连接成功后，调用清洗 API
        $('#cleanStatus').innerHTML = '<p style="font-size:13px;color:var(--accent)">清洗任务已启动，正在处理…</p>';
        fetch(API + `/cleaning/start?titleId=${titleId}&batchSize=${batchSize}`, { method: 'POST' })
            .then(res => safeJson(res, '启动清洗'))
            .then(data => {
                if (data.code !== 200) throw new Error(data.msg);
            })
            .catch(e => {
                $('#cleanStatus').innerHTML = `<p style="color:var(--danger)">清洗启动失败: ${e.message}</p>`;
                showToast('清洗启动失败: ' + e.message, 'error');
            });
    });
}

function connectWebSocket(titleId, onConnected) {
    const socket = new SockJS('/ws-cleaning');
    stompClient = Stomp.over(socket);
    stompClient.debug = null; // 关闭调试日志

    stompClient.connect({}, function(frame) {
        console.log('WebSocket 已连接:', frame);
        // 订阅清洗进度主题
        cleaningSubscription = stompClient.subscribe('/topic/cleaning/' + titleId, function(message) {
            handleCleaningMessage(JSON.parse(message.body));
        });
        if (onConnected) onConnected();
    }, function(error) {
        console.error('WebSocket 连接失败:', error);
        // WebSocket 连接失败降级为轮询模式
        $('#cleanStatus').innerHTML = '<p style="color:var(--warning);font-size:13px">实时连接失败，使用轮询模式</p>';
        // 仍然触发回调以启动清洗
        if (onConnected) onConnected();
    });
}

function disconnectWebSocket() {
    if (cleaningSubscription) {
        try { cleaningSubscription.unsubscribe(); } catch(e) {}
        cleaningSubscription = null;
    }
    if (stompClient) {
        try { stompClient.disconnect(); } catch(e) {}
        stompClient = null;
    }
}

function handleCleaningMessage(msg) {
    const type = msg.type;
    const current = msg.current || 0;
    const total = msg.total || 0;
    const percent = msg.progressPercent || 0;
    const success = msg.successCount || 0;
    const error = msg.errorCount || 0;

    // 更新进度条
    $('#cleanProgressFill').style.width = percent + '%';
    $('#cleanProgressFill').textContent = percent + '%';

    // 更新统计数字
    $('#liveCurrent').textContent = current;
    $('#liveTotal').textContent = total;
    $('#liveSuccess').textContent = success;
    $('#liveError').textContent = error;

    if (type === 'start') {
        $('#cleanStatus').innerHTML = '<p style="color:var(--accent);font-size:13px">清洗开始，共 ' + total + ' 条数据</p>';
        loadCleanFileList();
    } else if (type === 'progress') {
        $('#cleanStatus').innerHTML = '<p style="color:var(--accent);font-size:13px">清洗中… ' + current + '/' + total + ' (成功 ' + success + ', 失败 ' + error + ')</p>';
        // 节流刷新文件列表，实时更新"清洗中"状态与耗时
        if (!window.__cleanListRefreshTimer) {
            window.__cleanListRefreshTimer = setTimeout(() => { window.__cleanListRefreshTimer = null; loadCleanFileList(); }, 1000);
        }
    } else if (type === 'complete') {
        $('#cleanStatus').innerHTML = '<p style="color:var(--success);font-size:13px">清洗完成，共处理 ' + total + ' 条 (成功 ' + success + ', 失败 ' + error + ')</p>';
        const clCard = document.getElementById('cleanLiveCard');
        if (clCard && clCard.classList.contains('ai-active')) AiFx.deactivate(clCard);
        showToast('数据清洗完成');
        loadCleanStats();
        loadCleanFileList();
        // 清洗完成后，若当前正查看该文件的清洗结果则一并刷新
        if (window.__cleanViewTitleId && String(msg.titleId) === String(window.__cleanViewTitleId)) loadCleanedRecords(msg.titleId);
        // 延迟断开
        setTimeout(disconnectWebSocket, 2000);
    } else if (type === 'error') {
        $('#cleanStatus').innerHTML = '<p style="color:var(--danger);font-size:13px">清洗异常终止，已处理 ' + current + '/' + total + ' 条</p>';
        const clCard = document.getElementById('cleanLiveCard');
        if (clCard && clCard.classList.contains('ai-active')) AiFx.deactivate(clCard);
        showToast('清洗异常终止', 'error');
        loadCleanStats();
        loadCleanFileList();
        // 异常终止后也刷新一次结果视图，避免残留旧数据
        if (window.__cleanViewTitleId && String(msg.titleId) === String(window.__cleanViewTitleId)) loadCleanedRecords(msg.titleId);
        setTimeout(disconnectWebSocket, 2000);
    }
}

function statusCleanText(status) {
    const map = {
        'EXPORT_READY': '已审核', 'APPROVED': '已审核',
        'NEEDS_REVIEW': '待审核', 'REVIEWING': '审核中',
        'MODIFIED': '已修改', 'PROCESSED': '已处理',
        'REJECTED': '已驳回', 'DRAFT': '未清洗',
        'QUEUED': '排队等待中', 'PROCESSING': '清洗中', 'COMPLETED': '已完成'
    };
    return map[status] || status || '-';
}

// ==================== 字段映射 ====================

async function autoMapFields() {
    const titleId = $('#mapTitleId').value;
    const extraTitleId = $('#mapExtraTitleId').value;
    if (!titleId) { showToast('请选择数据文件', 'warning'); return; }

    showLoading('正在自动映射字段…');
    try {
        // Step 1: 自动映射字段（快速，无需进度）
        const params = new URLSearchParams({ tempDataTitleId: titleId });
        if (extraTitleId) params.append('extraDataTitleId', extraTitleId);
        const res = await fetch(API + `/cleaning/auto-map-fields?${params}`, { method: 'POST' });
        const data = await res.json();
        if (data.code !== 200) throw new Error(data.msg);

        hideLoading();
        showToast('字段映射完成，开始填充所有标准表头的结果数据…');

        // Step 2: 通过 WebSocket 显示填充进度（对所有标准表头执行填充）
        startFillAllWithSocket(titleId, extraTitleId);
    } catch (e) {
        showToast('映射或填充失败: ' + e.message, 'error');
        hideLoading();
    }
}

// 采用【已保存的映射】对所有标准表头执行填充（不重新自动映射，保留手动编辑的配置）
async function manualMapAndFill() {
    const titleId = $('#mapTitleId').value;
    const extraTitleId = $('#mapExtraTitleId').value;
    if (!titleId) { showToast('请选择数据文件', 'warning'); return; }
    // 直接调用 fill-all（后端读取各标准表头已保存的 field_mappings 进行填充），
    // 不会像 autoMapFields 那样先覆盖式自动映射，从而保留用户自定义的映射关系。
    startFillAllWithSocket(titleId, extraTitleId);
}

// 通过 WebSocket 实时展示"填充所有标准表头"的进度，并在连接失败时降级为后台填充
function startFillAllWithSocket(titleId, extraTitleId) {
    disconnectFillWebSocket();
    fillGlobalMode = true;
    fillGlobalTotal = 0;
    fillGlobalProgress = 0;
    fillGlobalSuccess = 0;
    fillGlobalError = 0;

    $('#fillLiveCard').style.display = 'block';
    $('#fillProgressFill').style.width = '0%';
    $('#fillProgressFill').textContent = '0%';
    $('#fillLiveCurrent').textContent = '0';
    $('#fillLiveTotal').textContent = '0';
    $('#fillLiveSuccess').textContent = '0';
    $('#fillLiveError').textContent = '0';
    $('#fillLiveTbody').innerHTML = '<tr><td colspan="6" class="empty-hint">连接中…</td></tr>';
    $('#fillLiveStatus').innerHTML = '<p style="font-size:13px;color:var(--text-secondary)">正在连接填充服务…</p>';

    const socket = new SockJS('/ws-cleaning');
    fillStompClient = Stomp.over(socket);
    fillStompClient.debug = null;

    fillStompClient.connect({}, function(frame) {
        console.log('Fill WebSocket 已连接 (fill-all)');
        // 订阅所有标准表头的填充进度（通配符）
        fillSubscription = fillStompClient.subscribe('/topic/fill/*', function(message) {
            handleFillMessage(JSON.parse(message.body));
        });

        $('#fillLiveStatus').innerHTML = '<p style="font-size:13px;color:var(--accent)">填充任务已启动，正在处理…</p>';

        // 调用 fill-all API（服务端同步执行，WebSocket 实时推送进度）
        const fillParams = new URLSearchParams({ tempDataTitleId: titleId });
        if (extraTitleId) fillParams.append('extraDataTitleId', extraTitleId);
        fetch(API + `/cleaning/fill-result/fill-all?${fillParams}`, { method: 'POST' })
            .then(fillRes => safeJson(fillRes, '填充结果'))
            .then(fillData => {
                if (fillData.code !== 200) throw new Error(fillData.msg);
                fillGlobalMode = false;
                $('#fillLiveStatus').innerHTML = '<p style="color:var(--success);font-size:13px">全部填充完成！共处理 ' + fillGlobalTotal + ' 条 (成功 ' + fillGlobalSuccess + ', 失败 ' + fillGlobalError + ')</p>';
                $('#fillProgressFill').style.width = '100%';
                $('#fillProgressFill').textContent = '100%';
                showToast('所有标准表头结果数据填充完成！');
                loadFieldMappings();
                setTimeout(disconnectFillWebSocket, 2000);
            })
            .catch(e => {
                fillGlobalMode = false;
                $('#fillLiveStatus').innerHTML = '<p style="color:var(--danger)">填充失败: ' + e.message + '</p>';
                showToast('填充失败: ' + e.message, 'error');
                setTimeout(disconnectFillWebSocket, 2000);
            });
    }, function(error) {
        console.error('Fill WebSocket 连接失败:', error);
        fillGlobalMode = false;
        $('#fillLiveStatus').innerHTML = '<p style="color:var(--warning);font-size:13px">实时连接失败，后台填充中…</p>';
        $('#fillLiveTbody').innerHTML = '<tr><td colspan="6" class="empty-hint">实时连接失败，填充在后台进行中…</td></tr>';

        // WebSocket 连接失败降级：直接调用 fill-all
        const fillParams = new URLSearchParams({ tempDataTitleId: titleId });
        if (extraTitleId) fillParams.append('extraDataTitleId', extraTitleId);
        fetch(API + `/cleaning/fill-result/fill-all?${fillParams}`, { method: 'POST' })
            .then(fillRes => safeJson(fillRes, '填充结果'))
            .then(fillData => {
                if (fillData.code !== 200) throw new Error(fillData.msg);
                showToast('所有标准表头结果数据填充完成！');
                loadFieldMappings();
            })
            .catch(e2 => {
                showToast('填充失败: ' + e2.message, 'error');
            });
    });
}

async function fillResultData() {
    const titleId = $('#mapTitleId').value;
    const extraTitleId = $('#mapExtraTitleId').value;
    if (!titleId) { showToast('请选择数据文件', 'warning'); return; }

    // 自动获取标准表头ID
    const standardTitleId = await getStandardTitleIdByTempDataTitleId(titleId);
    if (!standardTitleId) { showToast('无法获取标准字段表头，请确保已执行自动映射', 'warning'); return; }

    startFillWithSocket(standardTitleId, titleId, extraTitleId || 0);
}

// 根据 tempDataTitleId 获取对应的标准表头ID
async function getStandardTitleIdByTempDataTitleId(tempDataTitleId) {
    try {
        const standardTitleId = await api(`/cleaning/standard-title-id/by-title/${tempDataTitleId}`);
        return standardTitleId;
    } catch (e) {
        console.error('获取标准表头ID失败:', e);
    }
    return null;
}

// ==================== 结果数据填充 ====================

let fillStompClient = null;
let fillSubscription = null;
let fillGlobalMode = false;
let fillGlobalTotal = 0;
let fillGlobalProgress = 0;
let fillGlobalSuccess = 0;
let fillGlobalError = 0;

function startFillWithSocket(standardTitleId, titleId, extraTitleId) {
    // 断开之前的填充连接
    disconnectFillWebSocket();

    // 显示实时填充面板
    $('#fillLiveCard').style.display = 'block';
    $('#fillProgressFill').style.width = '0%';
    $('#fillProgressFill').textContent = '0%';
    $('#fillLiveCurrent').textContent = '0';
    $('#fillLiveTotal').textContent = '0';
    $('#fillLiveSuccess').textContent = '0';
    $('#fillLiveError').textContent = '0';
    $('#fillLiveTbody').innerHTML = '<tr><td colspan="6" class="empty-hint">连接中…</td></tr>';
    $('#fillLiveStatus').innerHTML = '<p style="font-size:13px;color:var(--text-secondary)">正在连接填充服务…</p>';

    connectFillWebSocket(standardTitleId, function connected() {
        $('#fillLiveStatus').innerHTML = '<p style="font-size:13px;color:var(--accent)">填充任务已启动，正在处理…</p>';
        const params = new URLSearchParams({ standardTitleId, tempDataTitleId: titleId, extraDataTitleId: extraTitleId });
        fetch(API + `/cleaning/fill-result/start?${params}`, { method: 'POST' })
            .then(res => safeJson(res, '启动填充'))
            .then(data => {
                if (data.code !== 200) throw new Error(data.msg);
            })
            .catch(e => {
                $('#fillLiveStatus').innerHTML = `<p style="color:var(--danger)">填充启动失败: ${e.message}</p>`;
                showToast('填充启动失败: ' + e.message, 'error');
            });
    });
}

function connectFillWebSocket(standardTitleId, onConnected) {
    const socket = new SockJS('/ws-cleaning');
    fillStompClient = Stomp.over(socket);
    fillStompClient.debug = null;

    fillStompClient.connect({}, function(frame) {
        console.log('Fill WebSocket 已连接:', frame);
        fillSubscription = fillStompClient.subscribe('/topic/fill/' + standardTitleId, function(message) {
            handleFillMessage(JSON.parse(message.body));
        });
        if (onConnected) onConnected();
    }, function(error) {
        console.error('Fill WebSocket 连接失败:', error);
        $('#fillLiveStatus').innerHTML = '<p style="color:var(--warning);font-size:13px">实时连接失败，使用轮询模式</p>';
        $('#fillLiveTbody').innerHTML = '<tr><td colspan="6" class="empty-hint">实时连接失败，填充在后台进行中…</td></tr>';
        if (onConnected) onConnected();
    });
}

function disconnectFillWebSocket() {
    if (fillSubscription) {
        try { fillSubscription.unsubscribe(); } catch(e) {}
        fillSubscription = null;
    }
    if (fillStompClient) {
        try { fillStompClient.disconnect(); } catch(e) {}
        fillStompClient = null;
    }
}

function handleFillMessage(msg) {
    if (fillGlobalMode) {
        handleFillGlobalMessage(msg);
        return;
    }

    const type = msg.type;
    const current = msg.current || 0;
    const total = msg.total || 0;
    const percent = msg.progressPercent || 0;
    const success = msg.successCount || 0;
    const error = msg.errorCount || 0;

    // 更新进度条
    $('#fillProgressFill').style.width = percent + '%';
    $('#fillProgressFill').textContent = percent + '%';

    // 更新统计数字
    $('#fillLiveCurrent').textContent = current;
    $('#fillLiveTotal').textContent = total;
    $('#fillLiveSuccess').textContent = success;
    $('#fillLiveError').textContent = error;

    if (type === 'start') {
        $('#fillLiveStatus').innerHTML = '<p style="color:var(--accent);font-size:13px">填充开始，共 ' + total + ' 条数据</p>';
        $('#fillLiveTbody').innerHTML = '';
    } else if (type === 'progress') {
        const rowData = msg.data;
        if (rowData) {
            appendFillRow(current, rowData);
        }
        $('#fillLiveStatus').innerHTML = '<p style="color:var(--accent);font-size:13px">填充中… ' + current + '/' + total + ' (成功 ' + success + ', 失败 ' + error + ')</p>';
    } else if (type === 'complete') {
        const skipped = msg.skippedCount || 0;
        const noCleaned = msg.skippedNoCleaned || 0;
        const notMatch = msg.skippedNotMatch || 0;
        const existing = msg.skippedExisting || 0;
        let skipDetails = [];
        if (noCleaned > 0) skipDetails.push(`${noCleaned} 条无清洗数据（请先执行数据清洗）`);
        if (notMatch > 0) skipDetails.push(`${notMatch} 条不属于该标准表头`);
        if (existing > 0) skipDetails.push(`${existing} 条已有填充结果`);
        const skipInfo = skipDetails.length > 0 ? ` (跳过: ${skipDetails.join('; ')})` : '';
        // 如果全部跳过且无成功数据，显示警告
        if (success === 0 && error === 0 && skipped > 0 && skipped === total) {
            let advice = '';
            if (noCleaned === total) {
                advice = '<br><span style="color:var(--danger)">全部数据尚未清洗，请先在"数据清洗"页面对该数据文件执行清洗操作</span>';
            } else if (notMatch === total) {
                advice = '<br><span style="color:var(--danger)">全部数据与当前标准表头不匹配，请确认分类编码是否一致</span>';
            } else if (existing === total) {
                advice = '<br><span style="color:var(--info)">全部数据已有填充结果，无需重复填充</span>';
            }
            $('#fillLiveStatus').innerHTML = `<p style="color:var(--danger);font-size:13px">填充完成，全部跳过！${skipInfo}${advice}</p>`;
        } else {
            $('#fillLiveStatus').innerHTML = `<p style="color:var(--success);font-size:13px">填充完成，共处理 ${total} 条 (成功 ${success}, 失败 ${error})${skipInfo}</p>`;
        }
        showToast('数据填充完成');
        setTimeout(disconnectFillWebSocket, 2000);
    } else if (type === 'error') {
        $('#fillLiveStatus').innerHTML = '<p style="color:var(--danger);font-size:13px">填充异常终止，已处理 ' + current + '/' + total + ' 条</p>';
        showToast('填充异常终止', 'error');
        setTimeout(disconnectFillWebSocket, 2000);
    }
}

function handleFillGlobalMessage(msg) {
    const type = msg.type;

    if (type === 'start') {
        // 累加 total：每个标准表头只上报自己“可填充”的行数，多个表头之和即为全局数据总数
        fillGlobalTotal += (msg.total || 0);
        $('#fillLiveTotal').textContent = fillGlobalTotal;
        $('#fillLiveStatus').innerHTML = '<p style="color:var(--accent);font-size:13px">开始处理新的标准表头，共 ' + (msg.total || 0) + ' 条</p>';
    } else if (type === 'progress') {
        fillGlobalProgress++;
        const rowData = msg.data;
        if (rowData) {
            const rowStatus = (rowData.status || '').toLowerCase();
            const isError = rowStatus === 'error' || rowStatus === 'rejected';
            if (isError) fillGlobalError++; else fillGlobalSuccess++;
            appendFillRow(fillGlobalProgress, rowData);
        } else {
            fillGlobalError++;
        }
        const pct = fillGlobalTotal > 0 ? Math.round(fillGlobalProgress * 100 / fillGlobalTotal) : 0;
        $('#fillProgressFill').style.width = pct + '%';
        $('#fillProgressFill').textContent = pct + '%';
        $('#fillLiveCurrent').textContent = fillGlobalProgress;
        $('#fillLiveSuccess').textContent = fillGlobalSuccess;
        $('#fillLiveError').textContent = fillGlobalError;
        $('#fillLiveStatus').innerHTML = '<p style="color:var(--accent);font-size:13px">填充中… ' + fillGlobalProgress + '/' + fillGlobalTotal + '</p>';
    } else if (type === 'complete') {
        const skipped = msg.skippedCount || 0;
        const noCleaned = msg.skippedNoCleaned || 0;
        const notMatch = msg.skippedNotMatch || 0;
        const existing = msg.skippedExisting || 0;
        let skipDetails = [];
        if (noCleaned > 0) skipDetails.push(`${noCleaned}条无清洗`);
        if (notMatch > 0) skipDetails.push(`${notMatch}条不匹配`);
        if (existing > 0) skipDetails.push(`${existing}条已有结果`);
        const skipInfo = skipDetails.length > 0 ? ` (跳过: ${skipDetails.join(',')})` : '';
        $('#fillLiveStatus').innerHTML = '<p style="color:var(--accent);font-size:13px">一个标准表头填充完成，继续处理…' + skipInfo + '</p>';
    } else if (type === 'error') {
        $('#fillLiveStatus').innerHTML = '<p style="color:var(--danger);font-size:13px">一个标准表头填充异常，继续处理…</p>';
    }
}

// 刷新字段映射列表（页面暂无映射列表组件，保留以兼容后续扩展）
function loadFieldMappings() {
    // 可在后续版本中实现映射列表的刷新
}

function appendFillRow(index, data) {
    const tbody = $('#fillLiveTbody');
    if (tbody.firstElementChild && tbody.firstElementChild.querySelector('.empty-hint')) {
        tbody.innerHTML = '';
    }

    const tr = document.createElement('tr');
    const filledBadge = data.filledCount > 5 ? 'badge-success' : data.filledCount > 0 ? 'badge-warning' : 'badge-danger';

    tr.innerHTML = 
        '<td>' + index + '</td>' +
        '<td>' + (data.resultId || '-') + '</td>' +
        '<td>' + (data.tempDataId || '-') + '</td>' +
        '<td>' + (data.cleanedDataId || '-') + '</td>' +
        '<td><span class="badge ' + filledBadge + '">' + (data.filledCount || 0) + ' 个字段</span></td>' +
        '<td>' + statusBadge(data.status || 'draft') + '</td>';

    tbody.insertBefore(tr, tbody.firstChild);
}

// ==================== 结果数据 ====================

// 分页状态
let resultPageState = {
    page: 1,
    pageSize: 20,
    total: 0,
    totalPages: 0,
};

// 结果数据页下拉框是否已初始化（仅首次进入时填充，避免切换页面时重置已选条件与结果）
let _resultSelectsReady = false;

// 结果数据当前显示顺序：desc（倒序/默认）| asc（顺序）
let resultSortOrder = 'desc';

// 点击 ID 表头倒三角：切换显示顺序并触发查询
function toggleResultSort() {
    resultSortOrder = (resultSortOrder === 'desc') ? 'asc' : 'desc';
    resultPageState.page = 1;
    loadResultData();
}

async function loadResultData(page) {
    const standardTitleId = $('#resultStandardTitleId').value;
    const titleId = $('#resultTitleId').value;
    if (!standardTitleId && !titleId) { showToast('请至少选择一个查询条件', 'warning'); return; }

    if (page) resultPageState.page = page;
    const { page: curPage, pageSize } = resultPageState;

    showLoading('正在查询结果数据…');
    try {
        const condition = { page: curPage, pageSize: pageSize };
        if (standardTitleId) condition.standardTitleId = parseInt(standardTitleId);
        // 按当前数据文件过滤，避免结果数据跨文件显示（当前文件匹配）
        if (titleId) condition.tempDataTitleId = parseInt(titleId);
        // 显示顺序：倒序(desc)/顺序(asc)，点击 ID 表头倒三角切换
        condition.sortBy = 'createdAt';
        condition.sortOrder = resultSortOrder || 'desc';

        // 并行查询数据和总数
        const [results, total] = await Promise.all([
            api('/cleaning/result-data/search', { method: 'POST', body: condition }),
            api('/cleaning/result-data/count', { method: 'POST', body: condition }),
        ]);

        resultPageState.total = total || 0;
        resultPageState.totalPages = Math.ceil(resultPageState.total / pageSize) || 1;

        renderResultData(results || []);
        updateResultPagination();
    } catch (e) {
        showToast('查询失败: ' + e.message, 'error');
    } finally {
        hideLoading();
    }
}

async function downloadResultData() {
    const standardTitleId = $('#resultStandardTitleId').value;
    const titleId = $('#resultTitleId').value;

    // 未选择标准字段表头 -> 多 Sheet 导出（下拉框每一条生成一个 sheet）
    if (!standardTitleId) {
        if (!titleId) { showToast('请先选择数据文件', 'warning'); return; }
        await downloadMultiSheetResult(titleId);
        return;
    }

    showLoading('正在下载数据…');
    try {
        // 后端导出：表头为 行号 + 原始数据列（前置）+ 结果属性列，便于与原始数据对比
        const token = getToken();
        const headers = {};
        if (token) headers['Authorization'] = 'Bearer ' + token;
        const res = await fetch(API + '/cleaning/result-data/export?standardTitleId=' + encodeURIComponent(standardTitleId) +
            (titleId ? '&tempDataTitleId=' + encodeURIComponent(titleId) : ''), {
            method: 'GET',
            headers
        });
        if (res.status === 401) {
            showToast('登录已过期，请重新登录', 'error');
            hideLoading();
            return;
        }
        if (!res.ok) {
            let msg = '下载失败';
            try { const t = await res.text(); if (t) msg += ': ' + t; } catch (e) {}
            showToast(msg, 'error');
            hideLoading();
            return;
        }
        const blob = await res.blob();
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        const now = new Date();
        const timestamp = now.getFullYear() + ('0' + (now.getMonth() + 1)).slice(-2) + ('0' + now.getDate()).slice(-2) + '_' +
            ('0' + now.getHours()).slice(-2) + ('0' + now.getMinutes()).slice(-2) + ('0' + now.getSeconds()).slice(-2);
        link.download = 'result_data_' + timestamp + '.xlsx';
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        URL.revokeObjectURL(url);

        showToast('下载完成');
    } catch (e) {
        showToast('下载失败: ' + e.message, 'error');
    } finally {
        hideLoading();
    }
}

// 多 Sheet 导出：未选标准字段表头时，后端为下拉框每条标准表头生成一张 sheet，合并为一个 .xlsx 下载
async function downloadMultiSheetResult(titleId) {
    showLoading('正在生成多表头下载文件…');
    try {
        const token = getToken();
        const headers = {};
        if (token) headers['Authorization'] = 'Bearer ' + token;
        const res = await fetch(API + '/cleaning/result-data/export-multi-sheet?tempDataTitleId=' + encodeURIComponent(titleId), {
            method: 'GET',
            headers
        });
        if (res.status === 401) {
            hideLoading();
            redirectToLogin();
            showToast('登录已过期，请重新登录', 'error');
            return;
        }
        if (!res.ok) {
            let errMsg = 'HTTP ' + res.status;
            try { errMsg = (await res.text()) || errMsg; } catch (e) {}
            throw new Error(errMsg);
        }
        const blob = await res.blob();
        if (!blob || blob.size === 0) { throw new Error('导出内容为空，请确认该数据文件已关联标准字段表头并存在结果数据'); }
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        const now = new Date();
        const ts = now.getFullYear() + ('0' + (now.getMonth() + 1)).slice(-2) + ('0' + now.getDate()).slice(-2) + '_' +
            ('0' + now.getHours()).slice(-2) + ('0' + now.getMinutes()).slice(-2) + ('0' + now.getSeconds()).slice(-2);
        link.download = 'result_data_multi_' + titleId + '_' + ts + '.xlsx';
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        URL.revokeObjectURL(url);
        showToast('多表头下载完成');
    } catch (e) {
        showToast('下载失败: ' + e.message, 'error');
    } finally {
        hideLoading();
    }
}

async function fillResultDataManual() {
    const standardTitleId = $('#resultStandardTitleId').value;
    const titleId = $('#resultTitleId').value;
    const extraTitleId = $('#resultExtraTitleId').value;
    if (!standardTitleId || !titleId) { showToast('请选择标准字段表头和数据文件', 'warning'); return; }

    // 切换到映射页面显示填充进度
    switchPage('mapping');
    $('#mapTitleId').value = titleId;
    if (extraTitleId) $('#mapExtraTitleId').value = extraTitleId;
    startFillWithSocket(standardTitleId, titleId, extraTitleId || 0);
}

// 填充失败列表是否处于显示状态（供切换数据文件时判断是否自动刷新）
let _failedCardVisible = false;

async function loadFailedResults() {
    const titleId = $('#resultTitleId').value;
    if (!titleId) {
        _failedCardVisible = false;
        $('#failedCard').style.display = 'none';
        return;
    }
    try {
        const data = await api(`/cleaning/failed-results?titleId=${titleId}`);
        renderFailedResults(data || []);
        // 仅在用户主动展开时才显示，避免切换数据文件时列表自动弹出
        $('#failedCard').style.display = _failedCardVisible ? 'block' : 'none';
    } catch (e) {
        // 查询失败不影响主流程，仅隐藏失败列表
        $('#failedCard').style.display = 'none';
        console.warn('查询填充失败记录失败:', e.message);
    }
}

// 查看填充失败：参考"显示映射状态"，再次点击可隐藏
function showFailedResults() {
    const card = $('#failedCard');
    // 再次点击则隐藏填充失败列表
    if (card.style.display === 'block') {
        _failedCardVisible = false;
        card.style.display = 'none';
        return;
    }
    if (!$('#resultTitleId').value) {
        showToast('请先选择数据文件', 'warning');
        return;
    }
    _failedCardVisible = true;
    loadFailedResults();
}

function escapeHtml(str) {
    if (str == null) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

// 渲染 AI top-k 候选分类（aiCandidateCodes 为 JSON 文本），悬停显示完整路径
function renderAiCandidates(json) {
    if (!json) return '<span class="empty-hint">-</span>';
    var arr;
    try {
        arr = typeof json === 'string' ? JSON.parse(json) : json;
    } catch (e) {
        return '<span class="empty-hint" title="' + escapeHtml(json) + '">' + escapeHtml(String(json)) + '</span>';
    }
    if (!Array.isArray(arr) || arr.length === 0) return '<span class="empty-hint">-</span>';
    var cells = [];
    for (var i = 0; i < arr.length; i++) {
        var it = arr[i] || {};
        var code = it.code || '';
        var nm = it.name || '';
        var desc = it.desc || '';
        // 展示：编码 名称（说明）拼接，不再显示完整路径
        var shown = (code ? code + ' ' : '') + nm;
        if (desc) shown += '（' + desc + '）';
        cells.push('<span class="cand-item" title="' + escapeHtml(shown) + '">' +
            escapeHtml(shown) + '</span>');
    }
    return '<div class="cand-cell">' + cells.join('') + '</div>';
}

// 格式化后端返回的 LocalDateTime（形如 2026-08-25T10:00:00 / 带毫秒 / 带时区）为 2026-08-25 10:00:00
function fmtLocalTime(s) {
    if (!s) return '-';
    s = String(s).replace('T', ' ');
    // 去掉可能的毫秒与时区后缀
    s = s.replace(/\.\d+/, '').replace(/[Zz]$/, '').replace(/[+\-]\d{2}:?\d{2}$/, '');
    return s;
}

function renderFailedResults(list) {
    $('#failedCount').textContent = list.length ? `（共 ${list.length} 条）` : '';
    const tbody = $('#failedTbody');
    if (!list.length) {
        tbody.innerHTML = '<tr><td colspan="6" class="empty-hint">无填充失败记录</td></tr>';
        return;
    }
    tbody.innerHTML = list.map(f => `<tr>
        <td>${f.id ?? ''}</td>
        <td>${f.tempDataId ?? ''}</td>
        <td>${f.categoryCode ?? ''}</td>
        <td>${escapeHtml(f.reason ?? '')}</td>
        <td style="max-width:340px;white-space:pre-wrap;word-break:break-all">${escapeHtml(f.rawData ?? '')}</td>
        <td>${f.createdAt ?? ''}</td>
    </tr>`).join('');
}

function showMappingStatus() {
    const card = $('#mfStatusCard');
    // 再次点击则隐藏映射状态列表
    if (card.style.display === 'block') {
        card.style.display = 'none';
        return;
    }
    const titleId = $('#resultTitleId').value;
    if (!titleId) {
        showToast('请先选择数据文件', 'warning');
        return;
    }
    loadStandardTitleMappingStatus();
}

function updateResultPagination() {
    const { page, pageSize, total, totalPages } = resultPageState;

    $('#resultPageInfo').textContent = `共 ${total} 条`;
    $('#resultCurPage').textContent = page;
    $('#resultTotalPages').textContent = totalPages;

    const renderBtns = (containerId) => {
        const container = $(containerId);
        let html = '';
        html += `<button class="btn btn-sm" ${page <= 1 ? 'disabled' : ''} onclick="loadResultData(1)">首页</button>`;
        html += `<button class="btn btn-sm" ${page <= 1 ? 'disabled' : ''} onclick="loadResultData(${page - 1})">上一页</button>`;

        // 显示页码按钮（最多5个）
        const maxBtns = 5;
        let startPage = Math.max(1, page - Math.floor(maxBtns / 2));
        let endPage = Math.min(totalPages, startPage + maxBtns - 1);
        if (endPage - startPage < maxBtns - 1) {
            startPage = Math.max(1, endPage - maxBtns + 1);
        }
        for (let i = startPage; i <= endPage; i++) {
            html += `<button class="btn btn-sm ${i === page ? 'btn-primary' : ''}" onclick="loadResultData(${i})">${i}</button>`;
        }

        html += `<button class="btn btn-sm" ${page >= totalPages ? 'disabled' : ''} onclick="loadResultData(${page + 1})">下一页</button>`;
        html += `<button class="btn btn-sm" ${page >= totalPages ? 'disabled' : ''} onclick="loadResultData(${totalPages})">末页</button>`;
        html += ` <span style="font-size:12px;margin-left:8px">每页 ${pageSize} 条</span>`;
        container.innerHTML = html;
    };

    renderBtns('#resultPageBtnsBottom');
}

// ==================== 手动填充弹窗 ====================

let manualFillState = {
    standardTitleId: null,
    titleId: null,
    extraTitleId: null,
    standardFields: [],      // [{key:'colTitle1', title:'物料代码', isMust:true}, ...]
    dataFileCols: [],        // [{key:'col1Title', title:'物料代码'}, ...]
    extraDataCols: [],       // [{key:'col1Title', title:'物资名称'}, ...]
    existingMappings: [],    // [FieldMappingAuditEntity, ...]
};

async function openManualFillModal(opts) {
    // opts: { standardTitleId, standardTitleIdSel, titleIdSel, extraTitleIdSel }
    //  - standardTitleId: 直接传入的标准字段表头 id（属性补全模块列表行编辑时使用）
    //  - standardTitleIdSel: 下拉框 id（结果数据模块使用），未传则默认 resultStandardTitleId
    const standardTitleId = (opts && opts.standardTitleId)
        || (opts && opts.standardTitleIdSel ? $('#' + opts.standardTitleIdSel).value : $('#resultStandardTitleId').value);
    const titleIdSel = (opts && opts.titleIdSel) || 'resultTitleId';
    const extraTitleIdSel = (opts && opts.extraTitleIdSel) || 'resultExtraTitleId';

    const titleId = $('#' + titleIdSel).value;

    if (!standardTitleId || !titleId) {
        showToast('请选择标准字段表头和数据文件', 'warning');
        return;
    }

    // 加载补充数据表头下拉
    await loadExtraTitlesForSelect('mfExtraTitleSelect', titleId);
    const mfExtraSelect = $('#mfExtraTitleSelect');
    // 默认选择第一项（跳过"-- 请选择 --"占位项）
    if (mfExtraSelect && mfExtraSelect.options && mfExtraSelect.options.length > 1) {
        mfExtraSelect.selectedIndex = 1;
    }
    const extraTitleId = mfExtraSelect ? mfExtraSelect.value : null;

    // 显示/隐藏补充表头信息区
    if (extraTitleId) {
        $('#mfExtraInfoItem').style.display = '';
    } else {
        $('#mfExtraInfoItem').style.display = 'none';
    }

    // 重置状态
    manualFillState = {
        standardTitleId: parseInt(standardTitleId),
        titleId: parseInt(titleId),
        extraTitleId: extraTitleId ? parseInt(extraTitleId) : null,
        standardFields: [],
        dataFileCols: [],
        extraDataCols: [],
        existingMappings: [],
    };

    // 显示弹窗
    $('#manualFillOverlay').classList.add('show');
    $('#manualFillModal').classList.add('show');
    $('#mfTableContainer').innerHTML = '<p style="text-align:center;padding:40px;color:var(--text-secondary)">加载中...</p>';

    // 并行加载数据
    await loadManualFillData();
}

function closeManualFillModal() {
    $('#manualFillOverlay').classList.remove('show');
    $('#manualFillModal').classList.remove('show');
}

async function onMfExtraTitleChange() {
    const extraTitleId = $('#mfExtraTitleSelect').value;
    manualFillState.extraTitleId = extraTitleId ? parseInt(extraTitleId) : null;
    // 重新加载映射数据
    await loadManualFillData();
}

async function loadManualFillData() {
    const { standardTitleId, titleId, extraTitleId } = manualFillState;

    try {
        // 并行加载：标准表头、数据文件列表、补充表头列表、已有映射
        const [standardTitles, importTitles, extraTitles, mappings] = await Promise.all([
            api('/cleaning/standard-titles'),
            api('/import/titles'),
            api('/cleaning/extra-titles'),
            loadExistingMappings(standardTitleId, titleId, extraTitleId),
        ]);

        // 解析标准字段
        const st = standardTitles.find(s => s.id == standardTitleId);
        if (!st) { showToast('未找到选中的标准字段表头', 'error'); return; }
        manualFillState.standardFields = [];
        for (let i = 1; i <= 20; i++) {
            const title = st['colTitle' + i];
            if (title) {
                manualFillState.standardFields.push({
                    key: 'colTitle' + i,
                    title: title,
                    isMust: !!st['colTitle' + i + 'IsMust'],
                });
            }
        }

        // 解析数据文件列
        const dt = importTitles.find(t => t.id == titleId);
        if (!dt) { showToast('未找到选中的数据文件', 'error'); return; }
        manualFillState.dataFileCols = [];
        for (let i = 1; i <= 10; i++) {
            const colTitle = dt['col' + i + 'Title'];
            if (colTitle) {
                manualFillState.dataFileCols.push({ key: 'col' + i + 'Title', title: colTitle, index: i });
            }
        }

        // 解析补充数据列
        manualFillState.extraDataCols = [];
        if (extraTitleId) {
            const et = extraTitles.find(e => e.id == extraTitleId);
            if (et) {
                for (let i = 1; i <= 20; i++) {
                    const colTitle = et['col' + i + 'Title'];
                    if (colTitle) {
                        manualFillState.extraDataCols.push({ key: 'col' + i + 'Title', title: colTitle, index: i });
                    }
                }
            }
        }

        manualFillState.existingMappings = mappings || [];

        // 更新概要信息
        $('#mfStandardTitle').textContent = st.categoryCode || ('标准表头#' + st.id);
        $('#mfDataFile').textContent = dt.fileName || ('数据文件#' + dt.id);
        if (extraTitleId) {
            $('#mfExtraInfoItem').style.display = '';
        } else {
            $('#mfExtraInfoItem').style.display = 'none';
        }

        // 渲染映射表格
        renderMappingTable();
    } catch (e) {
        $('#mfTableContainer').innerHTML = `<p style="text-align:center;padding:40px;color:var(--danger)">加载失败: ${e.message}</p>`;
        showToast('加载映射数据失败: ' + e.message, 'error');
    }
}

async function loadExistingMappings(standardTitleId, titleId, extraTitleId) {
    try {
        const params = new URLSearchParams();
        if (standardTitleId) params.append('standardTitleId', standardTitleId);
        params.append('tempDataTitleId', titleId);
        if (extraTitleId) params.append('extraDataTitleId', extraTitleId);
        return await api(`/cleaning/field-mappings?${params}`);
    } catch (e) {
        return [];
    }
}

function renderMappingTable() {
    const { standardFields, dataFileCols, extraDataCols, existingMappings } = manualFillState;

    if (standardFields.length === 0) {
        $('#mfTableContainer').innerHTML = '<p class="mf-source-empty">标准字段表头中没有定义任何字段</p>';
        return;
    }

    // 构建映射查找表：targetField -> 已选中的来源字段集合（支持多选，按字段名匹配，跨类型回显）
    const existingFieldSet = {};
    const existingTypeSet = {};
    existingMappings.forEach(m => {
        if (m.targetField && m.sourceField) {
            m.sourceField.split("[,，]").forEach(f => {
                const t = f.trim();
                if (t) {
                    if (!existingFieldSet[m.targetField]) existingFieldSet[m.targetField] = new Set();
                    existingFieldSet[m.targetField].add(t);
                }
            });
            if (m.sourceType) {
                if (!existingTypeSet[m.targetField]) existingTypeSet[m.targetField] = new Set();
                existingTypeSet[m.targetField].add(m.sourceType);
            }
        }
    });

    // 收集所有来源字段（带类型），供复选框渲染
    const buildItems = (cols, type) => cols.map(c => ({
        title: c.title,
        type: type,
        value: type + '|' + c.title
    }));
    const dataFileItems = buildItems(dataFileCols, 'temp_data');
    const extraDataItems = buildItems(extraDataCols, 'extra_data');

    let html = '<table class="mf-table"><thead><tr>';
    html += '<th style="width:180px">标准字段</th>';
    html += '<th>映射来源（可多选）</th>';
    html += '</tr></thead><tbody>';

    standardFields.forEach(sf => {
        const eset = existingFieldSet[sf.title] || new Set();
        const etypes = existingTypeSet[sf.title] || new Set();
        // 推断该标准字段的 sourceType（含 temp 与 extra 时以是否有 extra 决定；用于保存时判断多类型）
        let dominantType = 'temp_data';
        if (etypes.has('extra_data') && !etypes.has('temp_data')) dominantType = 'extra_data';

        html += '<tr>';
        html += `<td><strong>${sf.title}</strong>${sf.isMust ? '<span class="mf-required-badge">*必填</span>' : ''}</td>`;
        html += '<td>';
        html += `<div class="mf-multiselect" data-target="${sf.title.replace(/"/g, '&quot;')}" data-dominant-type="${dominantType}">`;
        html += '<div class="mf-ms-control"><span class="mf-ms-text">-- 不映射 --</span><span class="mf-ms-arrow">▾</span></div>';
        html += '<div class="mf-ms-panel" style="display:none">';
        // "不映射" 互斥项
        html += `<label class="mf-cb-item mf-cb-none"><input type="checkbox" class="mf-cb" value="" /> 不映射</label>`;
        if (dataFileItems.length > 0) {
            html += '<div class="mf-ms-group">── 数据文件列 ──</div>';
            dataFileItems.forEach(it => {
                const checked = eset.has(it.title) ? ' checked' : '';
                html += `<label class="mf-cb-item"><input type="checkbox" class="mf-cb" value="${it.value.replace(/"/g, '&quot;')}"${checked} /> ${it.title}</label>`;
            });
        }
        if (extraDataItems.length > 0) {
            html += '<div class="mf-ms-group">── 补充数据列 ──</div>';
            extraDataItems.forEach(it => {
                const checked = eset.has(it.title) ? ' checked' : '';
                html += `<label class="mf-cb-item"><input type="checkbox" class="mf-cb" value="${it.value.replace(/"/g, '&quot;')}"${checked} /> ${it.title}</label>`;
            });
        }
        html += '</div>'; // panel
        html += '</div>'; // multiselect
        html += '</td>';
        html += '</tr>';
    });

    html += '</tbody></table>';
    $('#mfTableContainer').innerHTML = html;

    // 初始化下拉多选交互
    initMultiSelect();
    $$('.mf-multiselect').forEach(updateMultiSelectText);
}

/**
 * 初始化下拉多选组件的交互逻辑（事件委托，只绑定一次）
 * - 点击控制条展开/收起面板
 * - 复选框互斥：勾选具体字段自动取消"不映射"；勾选"不映射"取消全部具体字段
 * - 点击页面其他区域收起面板
 */
function initMultiSelect() {
    const container = $('#mfTableContainer');
    if (!container || container._mfMsBound) return;
    container._mfMsBound = true;

    container.addEventListener('click', (e) => {
        const ms = e.target.closest('.mf-multiselect');
        if (!ms) return;
        const control = e.target.closest('.mf-ms-control');
        const panel = ms.querySelector('.mf-ms-panel');
        const cb = e.target.closest('.mf-cb');

        if (control && !cb) {
            // 点击控制条：切换面板显隐
            const isOpen = panel.style.display !== 'none';
            // 收起其他已展开的面板
            $$('.mf-multiselect .mf-ms-panel').forEach(p => { if (p !== panel) p.style.display = 'none'; });
            panel.style.display = isOpen ? 'none' : 'block';
            return;
        }

        if (cb) {
            const noneCb = ms.querySelector('.mf-cb-none input');
            if (cb.classList.contains('mf-cb-none') || cb.parentElement.classList.contains('mf-cb-none')) {
                // 勾选"不映射"时取消其它具体字段
                ms.querySelectorAll('.mf-cb:not(.mf-cb-none)').forEach(c => { c.checked = false; });
            } else {
                // 勾选具体字段时取消"不映射"
                if (noneCb) noneCb.checked = false;
            }
            updateMultiSelectText(ms);
        }
    });

    // 点击页面其他区域收起所有面板
    document.addEventListener('click', (e) => {
        if (!e.target.closest('.mf-multiselect')) {
            $$('.mf-multiselect .mf-ms-panel').forEach(p => { p.style.display = 'none'; });
        }
    });
}

/**
 * 根据勾选状态刷新多选控制条文本
 */
function updateMultiSelectText(ms) {
    const sel = ms.querySelector('.mf-ms-text');
    if (!sel) return;
    const checked = Array.from(ms.querySelectorAll('.mf-cb:not(.mf-cb-none)')).filter(c => c.checked);
    if (checked.length === 0) {
        sel.textContent = '-- 不映射 --';
        sel.classList.remove('mf-ms-active');
    } else {
        sel.textContent = checked.map(c => c.parentElement.textContent.trim()).join('、');
        sel.classList.add('mf-ms-active');
    }
}

/**
 * 计算两个字符串的相似度（0~1），基于编辑距离归一化
 */
function stringSimilarity(a, b) {
    if (!a || !b) return 0;
    a = a.toLowerCase().trim();
    b = b.toLowerCase().trim();
    if (a === b) return 1;

    // 计算 Levenshtein 编辑距离
    const la = a.length, lb = b.length;
    const matrix = [];
    for (let i = 0; i <= la; i++) { matrix[i] = [i]; }
    for (let j = 0; j <= lb; j++) { matrix[0][j] = j; }
    for (let i = 1; i <= la; i++) {
        for (let j = 1; j <= lb; j++) {
            const cost = a[i - 1] === b[j - 1] ? 0 : 1;
            matrix[i][j] = Math.min(
                matrix[i - 1][j] + 1,
                matrix[i][j - 1] + 1,
                matrix[i - 1][j - 1] + cost
            );
        }
    }
    const dist = matrix[la][lb];
    const maxLen = Math.max(la, lb);
    return maxLen > 0 ? 1 - dist / maxLen : 1;
}

/**
 * 弹窗内自动映射：完全匹配的自动选中，否则推荐最相似的来源字段
 */
/**
 * 标准化字符串用于匹配：去空格、转小写（与后端 findBestFieldMatch 逻辑一致）
 */
function normalizeForMatch(str) {
    if (!str) return '';
    return str.toLowerCase().replace(/\s+/g, '');
}

async function autoMapInModal() {
    const { standardFields, dataFileCols, extraDataCols } = manualFillState;

    // 合并所有来源字段（数据文件列 + 补充数据列），标注来源类型
    const allSourceCols = [
        ...dataFileCols.map(c => ({ ...c, sourceType: 'temp_data', normalized: normalizeForMatch(c.title) })),
        ...extraDataCols.map(c => ({ ...c, sourceType: 'extra_data', normalized: normalizeForMatch(c.title) })),
    ];

    if (allSourceCols.length === 0) {
        showToast('没有可用的来源字段，请确认数据文件和补充数据表头', 'warning');
        return;
    }

    const multiselects = $$('.mf-multiselect');
    let exactCount = 0;
    let containsCount = 0;
    let fuzzyCount = 0;
    let skippedCount = 0;

    multiselects.forEach(ms => {
        const targetField = ms.getAttribute('data-target');
        if (!targetField) return;

        const normalizedTarget = normalizeForMatch(targetField);

        // 先清空已有勾选（自动映射为覆盖式）
        ms.querySelectorAll('.mf-cb').forEach(c => { c.checked = false; });
        let matched = null;

        // 1. 精确匹配
        const exactMatch = allSourceCols.find(c => c.normalized === normalizedTarget);
        if (exactMatch) { matched = exactMatch; exactCount++; }
        else {
            // 2. 包含匹配
            const containsMatch = allSourceCols.find(c =>
                c.normalized.length > 0 && (
                    c.normalized.includes(normalizedTarget) || normalizedTarget.includes(c.normalized)
                )
            );
            if (containsMatch) { matched = containsMatch; containsCount++; }
            else {
                // 3. 模糊推荐
                let bestMatch = null;
                let bestScore = 0;
                for (const col of allSourceCols) {
                    const score = stringSimilarity(targetField, col.title);
                    if (score > bestScore) { bestScore = score; bestMatch = col; }
                }
                if (bestMatch && bestScore >= 0.4) { matched = bestMatch; fuzzyCount++; }
                else { skippedCount++; }
            }
        }

        if (matched) {
            const val = `${matched.sourceType}|${matched.title}`;
            const cb = ms.querySelector(`.mf-cb[value="${val.replace(/"/g, '&quot;')}"]`);
            if (cb) {
                cb.checked = true;
                updateMultiSelectText(ms);
            }
        }
    });

    let msg = `自动映射完成：完全匹配 ${exactCount} 个`;
    if (containsCount > 0) msg += `，包含匹配 ${containsCount} 个`;
    if (fuzzyCount > 0) msg += `，相似推荐 ${fuzzyCount} 个`;
    if (skippedCount > 0) msg += `，未匹配 ${skippedCount} 个（保留原映射）`;
    showToast(msg);
}

function collectMappingsFromSelects() {
    const multiselects = $$('.mf-multiselect');
    const mappings = [];
    multiselects.forEach(ms => {
        const targetField = ms.getAttribute('data-target');
        if (!targetField) return;

        const checked = Array.from(ms.querySelectorAll('.mf-cb:not(.mf-cb-none)')).filter(c => c.checked);
        if (checked.length === 0) {
            // 显式"不映射"
            mappings.push({ sourceType: '', sourceField: '', targetField: targetField });
            return;
        }

        // 收集所有勾选的来源字段，逗号拼接
        const sourceFields = [];
        const types = new Set();
        checked.forEach(c => {
            const parts = c.value.split('|');
            const type = parts[0];
            const field = parts.slice(1).join('|');
            sourceFields.push(field);
            types.add(type);
        });
        // 若存在 extra_data 类型来源，则 sourceType 记为 extra_data（后端有跨类型兜底，仅用于标识主类型）
        const sourceType = types.has('extra_data') ? 'extra_data' : 'temp_data';
        mappings.push({
            sourceType: sourceType,
            sourceField: sourceFields.join(','),
            targetField: targetField,
        });
    });
    return mappings;
}

async function saveMappingsOnly() {
    const { standardTitleId, titleId, extraTitleId } = manualFillState;
    const mappings = collectMappingsFromSelects();

    showLoading('正在保存映射…');
    try {
        const params = new URLSearchParams({ standardTitleId, tempDataTitleId: titleId });
        if (extraTitleId) params.append('extraDataTitleId', extraTitleId);
        const saveRes = await fetch(API + `/cleaning/field-mappings/batch?${params}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(mappings),
        });
        const saveData = await saveRes.json();
        if (saveData.code !== 200) throw new Error(saveData.msg);
        showToast('映射已保存（共 ' + (saveData.data ? saveData.data.length : 0) + ' 条）');
        // 刷新映射状态表
        loadStandardTitleMappingStatus();
    } catch (e) {
        showToast('保存失败: ' + e.message, 'error');
    } finally {
        hideLoading();
    }
}

async function executeManualFill() {
    const { standardTitleId, titleId, extraTitleId } = manualFillState;
    const mappings = collectMappingsFromSelects();

    // 统计真正配置了来源的字段（"不映射"的 sourceField 为空，不计入）
    const mappedList = mappings.filter(m => m.sourceField);
    if (mappedList.length === 0) {
        showToast('请至少配置一个字段映射', 'warning');
        return;
    }

    closeManualFillModal();
    showLoading('正在保存映射并填充…');
    try {
        // 1. 保存映射（仅针对当前选中的标准字段表头 + 数据文件组合）
        const params = new URLSearchParams({ standardTitleId, tempDataTitleId: titleId });
        if (extraTitleId) params.append('extraDataTitleId', extraTitleId);

        const saveRes = await fetch(API + `/cleaning/field-mappings/batch?${params}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(mappings),
        });
        const saveData = await saveRes.json();
        if (saveData.code !== 200) throw new Error(saveData.msg);

        hideLoading();

        // 2. 切换到映射页面，通过 WebSocket 显示填充进度
        switchPage('mapping');
        $('#mapTitleId').value = titleId;
        if (extraTitleId) $('#mapExtraTitleId').value = extraTitleId;

        startFillWithSocket(standardTitleId, titleId, extraTitleId || 0);
    } catch (e) {
        showToast('执行填充失败: ' + e.message, 'error');
        hideLoading();
    }
}

// ==================== 全部手动填充 / 映射状态表 ====================

async function fillAllStandardTitles() {
    const titleId = $('#resultTitleId').value;
    const extraTitleId = $('#resultExtraTitleId').value;
    if (!titleId) { showToast('请选择数据文件', 'warning'); return; }

    if (!confirm('将使用各标准字段表头已保存的映射配置进行批量填充。确定继续？')) return;

    showLoading('正在批量填充所有标准字段表头…');
    try {
        const params = new URLSearchParams({ tempDataTitleId: titleId });
        if (extraTitleId) params.append('extraDataTitleId', extraTitleId);
        const res = await fetch(API + `/cleaning/fill-result/fill-all?${params}`, { method: 'POST' });
        const data = await res.json();
        if (data.code !== 200) throw new Error(data.msg);
        hideLoading();
        showToast('全部填充任务已启动，请到字段映射页面查看进度');
        switchPage('mapping');
    } catch (e) {
        showToast('全部填充启动失败: ' + e.message, 'error');
        hideLoading();
    }
}

async function fillSingleStandardTitle(standardTitleId) {
    const titleId = $('#resultTitleId').value;
    const extraTitleId = $('#resultExtraTitleId').value;
    if (!titleId) { showToast('请选择数据文件', 'warning'); return; }

    switchPage('mapping');
    $('#mapTitleId').value = titleId;
    if (extraTitleId) $('#mapExtraTitleId').value = extraTitleId;
    startFillWithSocket(standardTitleId, titleId, extraTitleId || 0);
}

async function loadStandardTitleMappingStatus() {
    const titleId = $('#resultTitleId').value;
    if (!titleId) {
        $('#mfStatusCard').style.display = 'none';
        return;
    }

    try {
        // 先加载当前数据文件的映射（单表查询，较快）
        const allMappings = await loadExistingMappings(null, titleId, null);

        // 收集有关联映射的标准表头 ID
        const stdIdSet = new Set();
        (allMappings || []).forEach(m => { if (m.standardTitleId) stdIdSet.add(m.standardTitleId); });

        // 仅按需获取这些标准表头，避免 /cleaning/standard-titles 全量请求（后端 N+1 查询极慢）
        let standardTitles = [];
        if (stdIdSet.size > 0) {
            const ids = Array.from(stdIdSet);
            standardTitles = await Promise.all(ids.map(id => {
                if (_standardTitlesCache) {
                    const c = _standardTitlesCache.find(s => s.id == id);
                    if (c) return c;
                }
                return api(`/cleaning/standard-title/${id}`).catch(() => null);
            }));
            standardTitles = standardTitles.filter(Boolean);
        }

        // 按 standardTitleId 分组映射
        const mappingMap = {};
        (allMappings || []).forEach(m => {
            if (m.standardTitleId) {
                if (!mappingMap[m.standardTitleId]) mappingMap[m.standardTitleId] = [];
                mappingMap[m.standardTitleId].push(m);
            }
        });

        $('#mfStatusCard').style.display = 'block';
        const tbody = $('#mfStatusTbody');

        if (standardTitles.length === 0) {
            tbody.innerHTML = `<tr><td colspan="5" class="empty-hint">
                暂无关联映射，请先在<a href="javascript:void(0)" onclick="switchPage('mapping')" style="color:var(--accent);text-decoration:underline">字段映射页面</a>执行"自动映射字段"
            </td></tr>`;
            return;
        }

        tbody.innerHTML = standardTitles.map(st => {
            const mappings = mappingMap[st.id] || [];
            const hasMapping = mappings.length > 0;
            const badge = hasMapping
                ? '<span class="badge badge-success">已配置</span>'
                : '<span class="badge badge-default">未配置</span>';

            return `<tr>
                <td>${st.id}</td>
                <td>${st.categoryName || st.categoryCode || '标准表头#' + st.id}</td>
                <td>${badge}</td>
                <td>${mappings.length}</td>
                <td>
                    <div class="action-btn-group">
                        <button class="btn btn-sm btn-primary" onclick="$('#resultStandardTitleId').value='${st.id}';openManualFillModal()">配置</button>
                        <button class="btn btn-sm btn-success" onclick="$('#resultStandardTitleId').value='${st.id}';fillSingleStandardTitle(${st.id})" ${hasMapping ? '' : 'disabled'}>填充</button>
                    </div>
                </td>
            </tr>`;
        }).join('');
    } catch (e) {
        console.error('加载映射状态失败:', e);
        $('#mfStatusTbody').innerHTML = '<tr><td colspan="5" class="empty-hint">加载失败</td></tr>';
    }
}

async function renderResultData(results) {
    $('#resultCard').style.display = 'block';
    if (!results || results.length === 0) {
        $('#resultTbody').innerHTML = '<tr><td colspan="30" class="empty-hint">暂无数据，请先执行字段映射和结果填充</td></tr>';
        return;
    }

    // 只获取"当前选中的标准字段表头"，避免拉取全量列表。
    // 原逻辑 await getStandardTitles() 会触发 /cleaning/standard-titles 全量请求，
    // 该后端接口对每个标准表头单独查一次分类（N+1 查询），数据量大时极慢，
    // 且 await 在渲染之前会阻塞"状态"列，导致结果数据迟迟显示不出来、后台一直在查询。
    const standardTitleId = $('#resultStandardTitleId').value;
    let selectedStandard = null;
    if (standardTitleId) {
        if (_standardTitlesCache) {
            selectedStandard = _standardTitlesCache.find(s => s.id == standardTitleId) || null;
        }
        if (!selectedStandard) {
            selectedStandard = await api(`/cleaning/standard-title/${standardTitleId}`).catch(() => null);
        }
    }

    // 标准表头名称映射：优先复用缓存，避免全量请求（查询结果通常已按标准表头过滤）
    const standardTitleMap = {};
    if (_standardTitlesCache) {
        _standardTitlesCache.forEach(st => {
            standardTitleMap[st.id] = st.categoryName || st.categoryCode || ('标准表头#' + st.id);
        });
    }
    const selectedName = selectedStandard
        ? (selectedStandard.categoryName || selectedStandard.categoryCode || ('标准表头#' + selectedStandard.id))
        : '-';
    if (selectedStandard) standardTitleMap[selectedStandard.id] = selectedName;

    // 动态获取标准字段表头列
    let standardCols = [];
    if (selectedStandard) {
        for (let i = 1; i <= 20; i++) {
            const title = selectedStandard['colTitle' + i];
            if (title) standardCols.push({ key: 'col' + i, title });
        }
    }

    if (standardCols.length === 0) {
        const cols = ['col1','col2','col3','col4','col5','col6','col7','col8','col9','col10',
                      'col11','col12','col13','col14','col15','col16','col17','col18','col19','col20'];
        standardCols = cols.map((c, i) => ({ key: c, title: '列' + (i + 1) }));
    }

    const sortArrow = resultSortOrder === 'desc'
        ? '▼'   // 倒序：倒三角
        : '▲';  // 顺序：正三角
    $('#resultThead').innerHTML = `<tr><th class="sortable-id" onclick="toggleResultSort()" title="点击切换显示顺序">ID <span class="sort-arrow">${sortArrow}</span></th><th>标准表头</th><th>状态</th>` +
        standardCols.map(c => `<th>${c.title}</th>`).join('') + '<th>操作</th></tr>';

    $('#resultTbody').innerHTML = results.map(r => `
        <tr>
            <td>${r.id}</td>
            <td><span class="badge badge-info" title="standardTitleId: ${r.standardTitleId || '-'}">${standardTitleMap[r.standardTitleId] || '-'}</span></td>
            <td>
                <select class="status-select" onchange="updateResultStatus(${r.id}, this.value)">
                    <option value="draft" ${r.status==='draft'?'selected':''}>草稿</option>
                    <option value="approved" ${r.status==='approved'?'selected':''}>通过</option>
                    <option value="rejected" ${r.status==='rejected'?'selected':''}>驳回</option>
                    <option value="modified" ${r.status==='modified'?'selected':''}>已修改</option>
                </select>
            </td>
            ${standardCols.map((c, i) => {
                const val = cellArg(r[c.key] || '');
                const title = cellArg(c.title || '');
                return `<td class="editable-cell" ondblclick="editResultCell(${r.id}, ${i+1}, '${title}', '${val}')">${r[c.key] || ''}</td>`;
            }).join('')}
            <td>
                <div class="action-btn-group">
                    <button class="btn btn-sm btn-primary" onclick="reviewResult(${r.id})">审核</button>
                    <button class="btn btn-sm btn-info" onclick="viewSourceData(${r.tempDataId ?? 'null'})">源数据</button>
                </div>
            </td>
        </tr>
    `).join('');
}

let _cellEditId = null;
let _cellEditCol = null;

// 将值安全转义后嵌入 ondblclick 的 JS 字符串字面量与 HTML 属性中
function cellArg(v) {
    let s = String(v == null ? '' : v)
        .replace(/\\/g, '\\\\').replace(/'/g, "\\'")
        .replace(/\n/g, '\\n').replace(/\r/g, '').replace(/\t/g, '\\t');
    s = s.replace(/&/g, '&amp;').replace(/"/g, '&quot;')
         .replace(/</g, '&lt;').replace(/>/g, '&gt;');
    return s;
}

function editResultCell(id, colIndex, colTitle, currentValue) {
    _cellEditId = id;
    _cellEditCol = colIndex;
    $('#cellEditColName').textContent = colTitle || ('列' + colIndex);
    const input = $('#cellEditInput');
    input.value = currentValue || '';
    $('#cellEditModal').classList.add('show');
    $('#cellEditOverlay').classList.add('show');
    setTimeout(() => { input.focus(); input.select(); }, 50);
}

function closeCellEditModal() {
    $('#cellEditModal').classList.remove('show');
    $('#cellEditOverlay').classList.remove('show');
}

async function saveCellEdit() {
    const value = $('#cellEditInput').value;
    closeCellEditModal();
    updateResultData(_cellEditId, _cellEditCol, value);
}

document.addEventListener('keydown', (e) => {
    const modal = $('#cellEditModal');
    if (!modal || !modal.classList.contains('show')) return;
    const input = $('#cellEditInput');
    if (e.key === 'Escape') {
        e.preventDefault();
        closeCellEditModal();
    } else if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        saveCellEdit();
    }
});

async function updateResultData(id, colIndex, value) {
    showLoading('正在更新数据…');
    try {
        const res = await fetch(API + `/cleaning/result-data/${id}?colIndex=${colIndex}&value=${encodeURIComponent(value)}`, { method: 'PUT' });
        const data = await res.json();
        if (data.code !== 200) throw new Error(data.msg);
        showToast('数据已更新');
        loadResultData();
    } catch (e) {
        showToast('更新失败: ' + e.message, 'error');
    } finally {
        hideLoading();
    }
}

function reviewResult(id) {
    showModal('审核结果数据', `
        <p>数据ID: ${id}</p>
        <div class="form-group"><label>审核意见</label><textarea id="reviewComment" class="form-input" rows="3" placeholder="请输入审核意见"></textarea></div>
        <div class="mt-2" style="display:flex;gap:10px">
            <button class="btn btn-success" onclick="doReview(${id},'approved')">通过</button>
            <button class="btn btn-danger" onclick="doReview(${id},'rejected')">驳回</button>
        </div>
    `);
}

async function doReview(id, status) {
    const comment = $('#reviewComment')?.value || '';
    showLoading('正在提交审核…');
    try {
        const res = await fetch(API + `/cleaning/result-data/${id}/status?status=${status}&comment=${encodeURIComponent(comment)}`, { method: 'PUT' });
        const data = await res.json();
        if (data.code !== 200) throw new Error(data.msg);
        showToast('审核完成');
        closeModal();
        loadResultData();
    } catch (e) {
        showToast('审核失败: ' + e.message, 'error');
    } finally {
        hideLoading();
    }
}

async function updateResultStatus(id, status) {
    showLoading('正在更新状态…');
    try {
        const res = await fetch(API + `/cleaning/result-data/${id}/status?status=${status}`, { method: 'PUT' });
        const data = await res.json();
        if (data.code !== 200) throw new Error(data.msg);
        showToast('状态已更新');
    } catch (e) {
        showToast('更新失败: ' + e.message, 'error');
    } finally {
        hideLoading();
    }
}

// ==================== 数据检索 ====================

let searchPageState = { page: 1, size: 20, total: 0, pages: 1 };

async function searchData(page) {
    if (page) searchPageState.page = page;
    const { page: curPage, size } = searchPageState;

    const condition = {
        materialCode: $('#searchCode').value.trim() || null,
        materialName: $('#searchName').value.trim() || null,
        specification: $('#searchSpec').value.trim() || null,
        page: curPage,
        pageSize: size,
    };
    if ($('#searchCatCode').value.trim()) {
        condition.categoryPathPrefix = $('#searchCatCode').value.trim();
    }

    showLoading('正在搜索数据…');
    try {
        const [results, count] = await Promise.all([
            api('/cleaning/cleaned-data/search', { method: 'POST', body: condition }),
            api('/cleaning/cleaned-data/count', { method: 'POST', body: condition }),
        ]);

        searchPageState.total = count || 0;
        searchPageState.pages = Math.max(1, Math.ceil((count || 0) / size));

        $('#searchResultCard').style.display = 'block';
        $('#searchCount').textContent = count || 0;
        updateSearchPagination();

        if (!results || results.length === 0) {
            $('#searchTbody').innerHTML = '<tr><td colspan="11" class="empty-hint">未找到匹配数据</td></tr>';
            return;
        }
        $('#searchTbody').innerHTML = results.map(r => `
            <tr>
                <td>${r.id}</td>
                <td>${r.materialCode || '-'}</td>
                <td>${r.materialName || '-'}</td>
                <td>${r.specification || '-'}</td>
                <td>${r.categoryCode || '-'}</td>
                <td>${r.categoryName || '-'}</td>
                <td>${r.matchSource || '-'}</td>
                <td>${r.matchConfidence != null ? (r.matchConfidence * 100).toFixed(0) + '%' : '-'}</td>
                <td>${r.qualityScore != null ? r.qualityScore.toFixed(1) : '-'}</td>
                <td>${statusBadge(r.status)}</td>
                <td><button class="btn btn-sm btn-info" onclick="viewSourceData(${r.tempDataId ?? 'null'})">源数据</button></td>
            </tr>
        `).join('');
    } catch (e) {
        showToast('搜索失败: ' + e.message, 'error');
    } finally {
        hideLoading();
    }
}

function updateSearchPagination() {
    const { page, size, total, pages } = searchPageState;
    $('#searchPageInfo').textContent = `共 ${total} 条`;
    $('#searchCurPage').textContent = page;
    $('#searchTotalPages').textContent = pages;

    let html = '';
    html += `<button class="btn btn-sm" ${page <= 1 ? 'disabled' : ''} onclick="searchData(1)">首页</button>`;
    html += `<button class="btn btn-sm" ${page <= 1 ? 'disabled' : ''} onclick="searchData(${page - 1})">上一页</button>`;
    const maxBtns = 5;
    let startPage = Math.max(1, page - Math.floor(maxBtns / 2));
    let endPage = Math.min(pages, startPage + maxBtns - 1);
    if (endPage - startPage < maxBtns - 1) {
        startPage = Math.max(1, endPage - maxBtns + 1);
    }
    for (let i = startPage; i <= endPage; i++) {
        html += `<button class="btn btn-sm ${i === page ? 'btn-primary' : ''}" onclick="searchData(${i})">${i}</button>`;
    }
    html += `<button class="btn btn-sm" ${page >= pages ? 'disabled' : ''} onclick="searchData(${page + 1})">下一页</button>`;
    html += `<button class="btn btn-sm" ${page >= pages ? 'disabled' : ''} onclick="searchData(${pages})">末页</button>`;
    html += ` <span style="font-size:12px;margin-left:8px">每页 ${size} 条</span>`;
    $('#searchPageBtns').innerHTML = html;
}

// ==================== 标准字段表头管理 ====================

const MAX_STD_COLS = 20;
let standardTitlesCache = []; // 历史遗留缓存，保留以避免他处引用报错

let standardPageState = {
    page: 1,
    size: 10,
    total: 0,
    pages: 1,
    keyword: '',
};

function buildStandardColFields(values, mustFlags) {
    let html = '';
    for (let i = 1; i <= MAX_STD_COLS; i++) {
        html += `<div class="modal-form-group">
            <label>列 ${i} 标题</label>
            <input id="stdColTitle${i}" class="modal-form-input" placeholder="列${i}标题" value="${(values && values[i-1]) || ''}">
        </div>`;
        html += `<div class="modal-form-group" style="justify-content:flex-end">
            <label style="display:flex;align-items:center;gap:6px;cursor:pointer">
                <input type="checkbox" id="stdColMust${i}" ${(mustFlags && mustFlags[i-1]) ? 'checked' : ''}> 
                <span style="font-size:12px">必填</span>
            </label>
        </div>`;
    }
    return html;
}

function clearStandardForm() {
    const body = document.getElementById('standardEditBody');
    if (body) {
        body.innerHTML = `
            <div class="modal-form-grid">
                <div class="modal-form-group" style="grid-column:1/-1">
                    <label>分类编码 <span class="required">*</span></label>
                    <input type="hidden" id="stdEditId">
                    <input type="text" id="stdEditCategoryCode" class="modal-form-input" placeholder="如：10，对应物料分类" required>
                </div>
            </div>
            <div class="modal-divider"></div>
            <div class="modal-section-title">字段列定义（最多20列，设置列标题并勾选是否必填）</div>
            <div id="stdEditColFields" style="display:grid;grid-template-columns:1fr 1fr;gap:12px 20px">
                ${buildStandardColFields([], [])}
            </div>
            <div class="modal-divider"></div>
            <div style="display:flex;justify-content:flex-end;gap:12px">
                <button type="button" class="btn btn-default" onclick="closeStandardEditModal()">取消</button>
                <button type="button" class="btn btn-primary" onclick="saveStandardTitleFromModal()">保存</button>
            </div>
        `;
    }
}

function loadStandardTitleList() {
    queryStandardTitles(1);
}

// 分页查询标准字段表头
// 标准列表当前显示顺序：desc（倒序/默认）| asc（顺序）
let standardSortOrder = 'desc';

// 点击 ID 表头倒三角：切换显示顺序并触发查询
function toggleStandardSort() {
    standardSortOrder = (standardSortOrder === 'desc') ? 'asc' : 'desc';
    const th = document.getElementById('standardSortTh');
    if (th) th.querySelector('.sort-arrow').textContent = (standardSortOrder === 'desc') ? '▼' : '▲';
    queryStandardTitles(1);
}

async function queryStandardTitles(page) {
    if (page) standardPageState.page = page;
    standardPageState.keyword = document.getElementById('standardSearchInput').value.trim();
    const { page: curPage, size, keyword } = standardPageState;
    try {
        const qs = `page=${curPage}&size=${size}` + (keyword ? '&keyword=' + encodeURIComponent(keyword) : '') +
            '&sortOrder=' + (standardSortOrder || 'desc');
        const data = await api('/cleaning/standard-titles/page?' + qs);
        standardPageState.total = data.total || 0;
        standardPageState.pages = data.pages || 1;
        renderStandardTable(data.records || []);
        updateStandardPagination();
    } catch (e) {
        showToast('加载标准字段表头失败: ' + e.message, 'error');
    }
}

// 重置搜索条件
function resetStandardSearch() {
    document.getElementById('standardSearchInput').value = '';
    standardPageState.keyword = '';
    queryStandardTitles(1);
}

function renderStandardTable(titles) {
    const tbody = document.getElementById('standardTbody');
    const recordCount = document.getElementById('standardRecordCount');
    
    if (!titles || titles.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6" class="empty-hint">暂无数据，请点击"新建表头"创建</td></tr>';
        if (recordCount) recordCount.textContent = '共 0 条记录';
        return;
    }
    
    if (recordCount) recordCount.textContent = `共 ${standardPageState.total} 条记录`;
    
    const start = (standardPageState.page - 1) * standardPageState.size;
    tbody.innerHTML = titles.map((st, index) => {
        const cols = [];
        let colCount = 0;
        for (let i = 1; i <= MAX_STD_COLS; i++) {
            const title = st['colTitle' + i];
            const isMust = st['colTitle' + i + 'IsMust'];
            if (title) {
                cols.push({
                    name: title,
                    isMust: isMust,
                    index: i
                });
                colCount++;
            }
        }
        
        const colsPreview = cols.slice(0, 3).map(c => 
            `${c.name}${c.isMust ? '<span style="color:var(--danger)">*</span>' : ''}`
        ).join(', ');
        const colsMore = cols.length > 3 ? ` +${cols.length - 3}列` : '';
        
        return `<tr data-id="${st.id}">
            <td style="text-align:center;color:var(--text-secondary)">${start + index + 1}</td>
            <td>${st.id}</td>
            <td><span class="badge badge-info">${st.categoryCode || '-'}</span></td>
            <td>
                <div style="display:flex;align-items:center;gap:8px">
                    <span style="max-width:300px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title="${cols.map(c => c.name + (c.isMust ? '*' : '')).join(', ')}">
                        ${colsPreview}${colsMore}
                    </span>
                    <span class="badge badge-default">${colCount}列</span>
                </div>
            </td>
            <td>${formatDate(st.createdAt)}</td>
            <td>
                <div class="action-btn-group">
                    <button class="btn btn-sm btn-primary" onclick="viewStandardTitle(${st.id})">查看</button>
                    <button class="btn btn-sm btn-warning" onclick="editStandardTitle(${st.id})">编辑</button>
                    <button class="btn btn-sm btn-danger" onclick="deleteStandardTitleById(${st.id})">删除</button>
                </div>
            </td>
        </tr>`;
    }).join('');
}

function updateStandardPagination() {
    const { page, size, total, pages } = standardPageState;
    $('#standardPageInfo').textContent = `共 ${total} 条`;
    $('#standardCurPage').textContent = page;
    $('#standardTotalPages').textContent = pages;

    let html = '';
    html += `<button class="btn btn-sm" ${page <= 1 ? 'disabled' : ''} onclick="queryStandardTitles(1)">首页</button>`;
    html += `<button class="btn btn-sm" ${page <= 1 ? 'disabled' : ''} onclick="queryStandardTitles(${page - 1})">上一页</button>`;
    const maxBtns = 5;
    let startPage = Math.max(1, page - Math.floor(maxBtns / 2));
    let endPage = Math.min(pages, startPage + maxBtns - 1);
    if (endPage - startPage < maxBtns - 1) {
        startPage = Math.max(1, endPage - maxBtns + 1);
    }
    for (let i = startPage; i <= endPage; i++) {
        html += `<button class="btn btn-sm ${i === page ? 'btn-primary' : ''}" onclick="queryStandardTitles(${i})">${i}</button>`;
    }
    html += `<button class="btn btn-sm" ${page >= pages ? 'disabled' : ''} onclick="queryStandardTitles(${page + 1})">下一页</button>`;
    html += `<button class="btn btn-sm" ${page >= pages ? 'disabled' : ''} onclick="queryStandardTitles(${pages})">末页</button>`;
    html += ` <span style="font-size:12px;margin-left:8px">每页 ${size} 条</span>`;
    $('#standardPageBtns').innerHTML = html;
}

// ========== 查看弹窗 ==========

async function viewStandardTitle(id) {
    try {
        const st = await api(`/cleaning/standard-title/${id}`);
        
        const cols = [];
        for (let i = 1; i <= MAX_STD_COLS; i++) {
            const title = st['colTitle' + i];
            const isMust = st['colTitle' + i + 'IsMust'];
            if (title) {
                cols.push({
                    index: i,
                    name: title,
                    isMust: isMust
                });
            }
        }
        
        const fieldsHtml = cols.length > 0 ? 
            `<div class="field-col-grid">${cols.map(c => `
                <div class="field-col-item">
                    <span class="col-num">列${c.index}</span>
                    <span class="col-name">${c.name}</span>
                    ${c.isMust ? '<span class="col-must">必填</span>' : ''}
                </div>
            `).join('')}</div>` :
            '<div class="field-col-empty">暂无字段定义</div>';
        
        const body = document.getElementById('standardViewBody');
        body.innerHTML = `
            <div class="view-info-grid">
                <div class="view-info-item">
                    <span class="label">ID</span>
                    <span class="value">${st.id}</span>
                </div>
                <div class="view-info-item">
                    <span class="label">分类编码</span>
                    <span class="value"><code>${st.categoryCode || '-'}</code></span>
                </div>
                <div class="view-info-item">
                    <span class="label">字段数量</span>
                    <span class="value">${cols.length} 列</span>
                </div>
                <div class="view-info-item">
                    <span class="label">创建时间</span>
                    <span class="value">${formatDate(st.createdAt)}</span>
                </div>
            </div>
            <div class="modal-divider"></div>
            <div class="modal-section-title">字段列定义</div>
            ${fieldsHtml}
            <div class="modal-divider"></div>
            <div style="display:flex;justify-content:flex-end;gap:12px">
                <button class="btn btn-default" onclick="closeStandardViewModal()">关闭</button>
                <button class="btn btn-primary" onclick="closeStandardViewModal();editStandardTitle(${id})">编辑</button>
            </div>
        `;
        
        document.getElementById('standardViewOverlay').classList.add('show');
        document.getElementById('standardViewModal').classList.add('show');
    } catch (e) {
        showToast('加载标准字段表头详情失败: ' + e.message, 'error');
    }
}

function closeStandardViewModal() {
    document.getElementById('standardViewOverlay').classList.remove('show');
    document.getElementById('standardViewModal').classList.remove('show');
}

// ========== 编辑弹窗 ==========

function openAddStandardModal() {
    clearStandardForm();
    document.getElementById('standardEditTitle').textContent = '新建标准字段表头';
    document.getElementById('standardEditOverlay').classList.add('show');
    document.getElementById('standardEditModal').classList.add('show');
}

async function editStandardTitle(id) {
    try {
        const st = await api(`/cleaning/standard-title/${id}`);
        
        clearStandardForm();
        document.getElementById('stdEditId').value = st.id;
        document.getElementById('stdEditCategoryCode').value = st.categoryCode || '';
        document.getElementById('standardEditTitle').textContent = `编辑标准字段表头 (ID: ${st.id})`;
        
        const values = [];
        const mustFlags = [];
        for (let i = 1; i <= MAX_STD_COLS; i++) {
            values.push(st['colTitle' + i] || '');
            mustFlags.push(!!st['colTitle' + i + 'IsMust']);
        }
        document.getElementById('stdEditColFields').innerHTML = buildStandardColFields(values, mustFlags);
        
        document.getElementById('standardEditOverlay').classList.add('show');
        document.getElementById('standardEditModal').classList.add('show');
    } catch (e) {
        showToast('加载标准字段表头失败: ' + e.message, 'error');
    }
}

function closeStandardEditModal() {
    document.getElementById('standardEditOverlay').classList.remove('show');
    document.getElementById('standardEditModal').classList.remove('show');
}

async function saveStandardTitleFromModal() {
    const id = document.getElementById('stdEditId').value;
    const categoryCode = document.getElementById('stdEditCategoryCode').value.trim();
    if (!categoryCode) { showToast('请输入分类编码', 'warning'); return; }

    const body = { categoryCode };

    let hasTitle = false;
    for (let i = 1; i <= MAX_STD_COLS; i++) {
        const title = document.getElementById('stdColTitle' + i).value.trim();
        const isMust = document.getElementById('stdColMust' + i).checked;
        body['colTitle' + i] = title || null;
        body['colTitle' + i + 'IsMust'] = title ? isMust : false;
        if (title) hasTitle = true;
    }

    if (!hasTitle) { showToast('请至少定义一个字段列', 'warning'); return; }

    showLoading('正在保存标准字段表头…');
    try {
        if (id) {
            await fetch(API + `/cleaning/standard-title/${id}`, {
                method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
            }).then(r => r.json());
            showToast('标准字段表头更新成功');
        } else {
            await api('/cleaning/standard-title', { method: 'POST', body });
            showToast('标准字段表头创建成功');
        }
        closeStandardEditModal();
        queryStandardTitles(1);
        // 刷新其他页面的标准字段表头列表 / 下拉框
        invalidateStandardTitlesCache();
        loadMapStandardList();
        loadStandardTitles('resultStandardTitleId', $('#resultTitleId').value);
    } catch (e) {
        showToast('保存失败: ' + e.message, 'error');
    } finally {
        hideLoading();
    }
}

async function deleteStandardTitleById(id) {
    if (!confirm('确定删除该标准字段表头？此操作不可恢复。')) return;
    showLoading('正在删除标准字段表头…');
    try {
        await api(`/cleaning/standard-title/${id}`, { method: 'DELETE' });
        showToast('标准字段表头已删除');
        queryStandardTitles(1);
        // 刷新列表 / 下拉框（保持结果页当前数据文件过滤与已选标准表头）
        invalidateStandardTitlesCache();
        loadMapStandardList();
        loadStandardTitles('resultStandardTitleId', $('#resultTitleId').value);
    } catch (e) {
        showToast('删除失败: ' + e.message, 'error');
    } finally {
        hideLoading();
    }
}

// ==================== 导出 ====================

async function loadCategories() {
    try {
        const res = await fetch(API + '/categories/tree');
        const data = await res.json();
        const cats = data.data || [];
        const sel = $('#exportCategoryId');
        sel.innerHTML = '<option value="">-- 全部 --</option>';
        function addCat(catList, prefix) {
            (catList || []).forEach(c => {
                sel.innerHTML += `<option value="${c.id}">${prefix}${c.categoryName || c.categoryCode}</option>`;
                if (c.children) addCat(c.children, prefix + '  ');
            });
        }
        addCat(cats, '');
    } catch (e) {
        // 降级：尝试 search 接口
        try {
            const res = await fetch(API + '/categories/search?keyword=');
            const data = await res.json();
            const cats = data.data || [];
            const sel = $('#exportCategoryId');
            sel.innerHTML = '<option value="">-- 全部 --</option>' +
                cats.map(c => `<option value="${c.id}">${c.categoryName || c.categoryCode}</option>`).join('');
        } catch (e2) {}
    }
}

async function loadExportHistory() {
    try {
        const res = await fetch(API + '/export/my-history?userId=system&page=1&size=20');
        const data = await res.json();
        const batches = data.data || [];
        const tbody = $('#exportTbody');
        if (!batches || batches.length === 0) {
            tbody.innerHTML = '<tr><td colspan="6" class="empty-hint">暂无导出记录</td></tr>';
            return;
        }
        tbody.innerHTML = batches.map(b => `
            <tr>
                <td>${b.id}</td>
                <td>${b.fileName || b.batchName || '-'}</td>
                <td>${b.totalRecords || 0}</td>
                <td>${b.format || 'excel'}</td>
                <td>${b.status || '-'}</td>
                <td>${formatDate(b.exportedAt)}</td>
            </tr>
        `).join('');
    } catch (e) {
        console.error('加载导出历史失败:', e);
        const tbody = $('#exportTbody');
        tbody.innerHTML = '<tr><td colspan="6" class="empty-hint">暂无导出记录</td></tr>';
    }
}

async function exportByCategory() {
    const categoryId = $('#exportCategoryId').value;
    showLoading('正在创建导出任务…');
    try {
        const catIds = categoryId ? [parseInt(categoryId)] : [];
        const body = { categoryIds: catIds, format: 'excel', userId: 'system' };
        const res = await fetch(API + '/export/by-categories', {
            method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
        });
        const data = await res.json();
        if (data.code === 200) {
            showToast('导出任务已创建');
            loadExportHistory();
        } else {
            showToast('导出失败: ' + data.msg, 'error');
        }
    } catch (e) {
        showToast('导出失败: ' + e.message, 'error');
    } finally {
        hideLoading();
    }
}

// ==================== 未映射结果查询 ====================

let unmappedCache = [];
let unmappedTitleCache = null;  // 当前选择的 TempDataTitleEntity（含列头）
let unmappedAllTitles = [];     // 全部 titles 列表

async function loadTitlesForUnmapped() {
    try {
        const titles = await api('/import/titles');
        unmappedAllTitles = titles || [];
        const sel = $('#unmappedTitleId');
        sel.innerHTML = '<option value="">-- 请选择数据文件 --</option>' +
            (titles || []).map(t => `<option value="${t.id}">${t.fileName || '数据#' + t.id} (${t.totalRows || 0}行)</option>`).join('');
    } catch (e) {
        console.error('加载文件列表失败:', e);
    }
}

function getUnmappedColumns() {
    // 从选中的 title 中提取非空列头
    if (!unmappedTitleCache) return ['ID'];
    const cols = [];
    for (let i = 1; i <= 10; i++) {
        const title = unmappedTitleCache['col' + i + 'Title'];
        if (title && title.trim() !== '') {
            cols.push(title.trim());
        } else {
            // 如果列头为空但数据存在也保留（使用列序号）
            cols.push('列' + i);
        }
    }
    return cols.length > 0 ? cols : ['ID'];
}

// 过滤掉完全为空的列，返回保留的列索引列表（0-based col index: 1-10）
function getNonEmptyColumnIndices() {
    if (!unmappedTitleCache) return [1,2,3,4,5,6,7,8,9,10];
    const indices = [];
    for (let i = 1; i <= 10; i++) {
        const title = unmappedTitleCache['col' + i + 'Title'];
        // 有列头 或者 数据中有值的列都保留
        if (title && title.trim() !== '') {
            indices.push(i);
        } else {
            // 检查缓存中是否有数据行该列非空
            const hasData = unmappedCache.some(d => {
                const td = d.tempData;
                if (!td) return false;
                const colKey = 'col' + i;
                const val = td[colKey];
                return val != null && String(val).trim() !== '';
            });
            if (hasData) indices.push(i);
        }
    }
    return indices.length > 0 ? indices : [1,2,3,4,5,6,7,8,9,10];
}

function getColumnHeaders(indices) {
    if (!unmappedTitleCache) return indices.map(i => '列' + i);
    return indices.map(i => {
        const title = unmappedTitleCache['col' + i + 'Title'];
        return (title && title.trim() !== '') ? title.trim() : '列' + i;
    });
}

async function loadUnmappedResults() {
    const titleId = $('#unmappedTitleId').value;
    if (!titleId) { showToast('请先选择数据文件', 'warning'); return; }

    // 缓存选中的 title 信息
    unmappedTitleCache = unmappedAllTitles.find(t => t.id == titleId) || null;

    showLoading('正在查询未映射数据…');
    try {
        const [results, count] = await Promise.all([
            api(`/cleaning/unmapped-results?titleId=${titleId}`),
            api(`/cleaning/unmapped-results/count?titleId=${titleId}`),
        ]);
        unmappedCache = results || [];
        $('#unmappedCount').textContent = `共 ${count || 0} 条`;
        renderUnmappedResults(unmappedCache);
        $('#unmappedCard').style.display = 'block';
    } catch (e) {
        showToast('查询失败: ' + e.message, 'error');
    } finally {
        hideLoading();
    }
}

function renderUnmappedResults(data) {
    const thead = $('#unmappedThead');
    const tbody = $('#unmappedTbody');

    if (!data || data.length === 0) {
        thead.innerHTML = '<tr><th colspan="100%">未映射记录</th></tr>';
        tbody.innerHTML = '<tr><td colspan="100%" class="empty-hint">暂无未映射数据</td></tr>';
        return;
    }

    const colIndices = getNonEmptyColumnIndices();
    const headers = getColumnHeaders(colIndices);
    const colCount = colIndices.length;

    // 动态列头
    thead.innerHTML = '<tr>' + headers.map(h => `<th>${h}</th>`).join('') + '</tr>';

    // 数据行
    tbody.innerHTML = data.map(d => {
        const td = d.tempData || {};
        return '<tr>' + colIndices.map(i => {
            const val = td['col' + i];
            return '<td>' + (val != null ? String(val) : '-') + '</td>';
        }).join('') + '</tr>';
    }).join('');
}

async function downloadUnmappedResults() {
    const titleId = $('#unmappedTitleId').value;
    if (!titleId) { showToast('请先选择数据文件', 'warning'); return; }
    if (unmappedCache.length === 0) {
        showToast('没有可导出的数据', 'warning');
        return;
    }

    showLoading('正在导出数据…');
    try {
        const colIndices = getNonEmptyColumnIndices();
        const headers = getColumnHeaders(colIndices);
        const rows = [headers];

        unmappedCache.forEach(d => {
            const td = d.tempData || {};
            rows.push(colIndices.map(i => {
                const val = td['col' + i];
                return val != null ? String(val) : '';
            }));
        });

        const csvContent = rows.map(row =>
            row.map(cell => {
                const val = String(cell);
                if (val.includes(',') || val.includes('"') || val.includes('\n')) {
                    return '"' + val.replace(/"/g, '""') + '"';
                }
                return val;
            }).join(',')
        ).join('\n');

        const blob = new Blob(['\uFEFF' + csvContent], { type: 'text/csv;charset=utf-8;' });
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        const now = new Date();
        const timestamp = now.getFullYear() + ('0' + (now.getMonth() + 1)).slice(-2) + ('0' + now.getDate()).slice(-2) + '_' +
            ('0' + now.getHours()).slice(-2) + ('0' + now.getMinutes()).slice(-2) + ('0' + now.getSeconds()).slice(-2);
        link.download = 'unmapped_results_' + timestamp + '.csv';
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        URL.revokeObjectURL(url);

        showToast('导出完成，共 ' + unmappedCache.length + ' 条数据');
    } catch (e) {
        showToast('导出失败: ' + e.message, 'error');
    } finally {
        hideLoading();
    }
}

// ==================== 用户管理 ====================

let userPageState = {
    page: 1,
    size: 10,
    total: 0,
    pages: 1,
    keyword: '',
};

let userCache = {};
let editingUserId = null;

function esc(str) {
    if (str == null) return '';
    return String(str).replace(/[&<>"']/g, c => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[c]));
}

function queryUsers(page) {
    userPageState.keyword = document.getElementById('userSearchInput').value.trim();
    loadUsers(page || 1);
}

function resetUserSearch() {
    document.getElementById('userSearchInput').value = '';
    userPageState.keyword = '';
    loadUsers(1);
}

async function loadUsers(page) {
    if (page) userPageState.page = page;
    const { page: curPage, size, keyword } = userPageState;
    try {
        const qs = `page=${curPage}&size=${size}` + (keyword ? '&keyword=' + encodeURIComponent(keyword) : '');
        const data = await api('/users?' + qs);
        userPageState.total = data.total || 0;
        userPageState.pages = data.pages || 1;
        renderUsersTable(data.records || []);
        updateUsersPagination();
    } catch (e) {
        showToast('加载用户列表失败: ' + e.message, 'error');
    }
}

function renderUsersTable(users) {
    const tbody = $('#userTbody');
    const count = $('#userRecordCount');
    if (!users || users.length === 0) {
        tbody.innerHTML = '<tr><td colspan="8" class="empty-hint">暂无用户</td></tr>';
        if (count) count.textContent = '共 0 条';
        return;
    }
    if (count) count.textContent = `共 ${userPageState.total} 条`;
    userCache = {};
    const start = (userPageState.page - 1) * userPageState.size;
    tbody.innerHTML = users.map((u, i) => {
        userCache[u.id] = u;
        const statusBadge = u.status === 1
            ? '<span class="badge badge-success">启用</span>'
            : '<span class="badge badge-danger">禁用</span>';
        // 角色列：优先展示 sys_user_role 关联的多角色，回退到 role 主角色字段
        let roleBadge;
        if (Array.isArray(u.roleNames) && u.roleNames.length) {
            roleBadge = u.roleNames.map((n, idx) => {
                const code = (u.roleCodes || [])[idx];
                const cls = code === 'admin' ? 'badge-info' : 'badge-default';
                return `<span class="badge ${cls}" style="margin-right:4px">${esc(n)}</span>`;
            }).join('');
        } else {
            roleBadge = '<span class="badge badge-default">未分配</span>';
        }
        const toggleBtn = u.status === 1
            ? `<button class="btn btn-sm btn-warning" onclick="toggleUserStatus(${u.id}, 0)">禁用</button>`
            : `<button class="btn btn-sm btn-success" onclick="toggleUserStatus(${u.id}, 1)">启用</button>`;
        const isSelf = String(u.id) === String(getCurrentUser().id);
        const delBtn = isSelf
            ? `<button class="btn btn-sm btn-danger" disabled title="不能删除当前账号">删除</button>`
            : `<button class="btn btn-sm btn-danger" onclick="deleteUser(${u.id})">删除</button>`;
        return `<tr data-id="${u.id}">
            <td style="text-align:center;color:var(--text-secondary)">${start + i + 1}</td>
            <td>${u.id}</td>
            <td>${esc(u.username)}</td>
            <td>${esc(u.realName)}</td>
            <td>${roleBadge}</td>
            <td>${statusBadge}</td>
            <td>${formatDate(u.lastLoginTime)}</td>
            <td>
                <div class="action-btn-group">
                    <button class="btn btn-sm btn-primary" onclick="openUserModal(${u.id})">编辑</button>
                    <button class="btn btn-sm btn-default" onclick="openAssignRoleModal(${u.id})">分配角色</button>
                    ${toggleBtn}
                    <button class="btn btn-sm btn-default" onclick="resetUserPassword(${u.id})">重置密码</button>
                    ${delBtn}
                </div>
            </td>
        </tr>`;
    }).join('');
}

function updateUsersPagination() {
    const { page, size, total, pages } = userPageState;
    $('#userPageInfo').textContent = `共 ${total} 条`;
    $('#userCurPage').textContent = page;
    $('#userTotalPages').textContent = pages;

    let html = '';
    html += `<button class="btn btn-sm" ${page <= 1 ? 'disabled' : ''} onclick="loadUsers(1)">首页</button>`;
    html += `<button class="btn btn-sm" ${page <= 1 ? 'disabled' : ''} onclick="loadUsers(${page - 1})">上一页</button>`;
    const maxBtns = 5;
    let startPage = Math.max(1, page - Math.floor(maxBtns / 2));
    let endPage = Math.min(pages, startPage + maxBtns - 1);
    if (endPage - startPage < maxBtns - 1) {
        startPage = Math.max(1, endPage - maxBtns + 1);
    }
    for (let i = startPage; i <= endPage; i++) {
        html += `<button class="btn btn-sm ${i === page ? 'btn-primary' : ''}" onclick="loadUsers(${i})">${i}</button>`;
    }
    html += `<button class="btn btn-sm" ${page >= pages ? 'disabled' : ''} onclick="loadUsers(${page + 1})">下一页</button>`;
    html += `<button class="btn btn-sm" ${page >= pages ? 'disabled' : ''} onclick="loadUsers(${pages})">末页</button>`;
    html += ` <span style="font-size:12px;margin-left:8px">每页 ${size} 条</span>`;
    $('#userPageBtns').innerHTML = html;
}

async function openUserModal(id) {
    editingUserId = id || null;
    const u = id ? userCache[id] : null;
    const isEdit = !!u;

    // 加载可选角色列表，供多选分配
    let roles = [];
    try {
        roles = await api('/roles/enabled') || [];
    } catch (e) {
        showToast('加载角色列表失败: ' + e.message, 'error');
    }
    const ownedCodes = isEdit && Array.isArray(u.roleCodes) ? u.roleCodes : ['user'];
    const roleCheckboxes = roles.length
        ? roles.map(r => `<label class="perm-item" style="margin-right:12px">
                <input type="checkbox" class="u-role-cb" value="${esc(r.roleCode)}" ${ownedCodes.includes(r.roleCode) ? 'checked' : ''}>
                <span>${esc(r.roleName)}</span>
                <span class="perm-code">${esc(r.roleCode)}</span>
            </label>`).join('')
        : '<span style="color:var(--text-tertiary);font-size:12px">暂无可用角色，请先到「角色管理」创建</span>';

    const formHtml = `
        <div class="form-group" style="margin-bottom:14px">
            <label>用户名</label>
            <input type="text" id="uUsername" class="form-input" value="${isEdit ? esc(u.username) : ''}" placeholder="登录账号" ${isEdit ? 'readonly' : ''}>
        </div>
        <div class="form-group" style="margin-bottom:14px">
            <label>${isEdit ? '密码（留空则不修改）' : '密码（留空则默认 admin123）'}</label>
            <input type="password" id="uPassword" class="form-input" placeholder="请输入密码">
        </div>
        <div class="form-group" style="margin-bottom:14px">
            <label>姓名</label>
            <input type="text" id="uRealName" class="form-input" value="${isEdit ? esc(u.realName) : ''}" placeholder="真实姓名">
        </div>
        <div style="display:flex;gap:12px;margin-bottom:14px">
            <div class="form-group" style="flex:1;margin:0">
                <label>邮箱</label>
                <input type="text" id="uEmail" class="form-input" value="${isEdit ? esc(u.email) : ''}" placeholder="邮箱">
            </div>
            <div class="form-group" style="flex:1;margin:0">
                <label>手机号</label>
                <input type="text" id="uPhone" class="form-input" value="${isEdit ? esc(u.phone) : ''}" placeholder="手机号">
            </div>
        </div>
        <div class="form-group" style="margin-bottom:14px">
            <label>角色（可多选）</label>
            <div style="display:flex;flex-wrap:wrap;gap:6px;padding:8px;border:1px solid var(--border-color);border-radius:6px">
                ${roleCheckboxes}
            </div>
        </div>
        <div style="display:flex;gap:12px;margin-bottom:20px">
            <div class="form-group" style="flex:1;margin:0">
                <label>状态</label>
                <select id="uStatus" class="form-input">
                    <option value="1" ${!isEdit || u.status === 1 ? 'selected' : ''}>启用</option>
                    <option value="0" ${isEdit && u.status === 0 ? 'selected' : ''}>禁用</option>
                </select>
            </div>
        </div>
        <div style="display:flex;justify-content:flex-end;gap:8px">
            <button class="btn btn-default" onclick="closeModal()">取消</button>
            <button class="btn btn-primary" onclick="saveUser()">确定</button>
        </div>
    `;
    showModal(isEdit ? '编辑用户' : '新建用户', formHtml);
}

async function saveUser() {
    const username = $('#uUsername').value.trim();
    const password = $('#uPassword').value;
    const realName = $('#uRealName').value.trim();
    const email = $('#uEmail').value.trim();
    const phone = $('#uPhone').value.trim();
    const status = parseInt($('#uStatus').value);
    const roleCodes = Array.from(document.querySelectorAll('.u-role-cb:checked')).map(cb => cb.value);
    if (!username) { showToast('请输入用户名', 'error'); return; }
    if (roleCodes.length === 0) { showToast('请至少选择一个角色', 'error'); return; }
    // role 为主角色，含 admin 时优先 admin，兼容后端历史字段
    const role = roleCodes.includes('admin') ? 'admin' : roleCodes[0];
    const body = { username, realName, email, phone, role, status, roleCodes };
    if (password) body.password = password;
    try {
        if (editingUserId) {
            await api(`/users/${editingUserId}`, { method: 'PUT', body });
            showToast('更新成功');
        } else {
            await api('/users', { method: 'POST', body });
            showToast('创建成功');
        }
        closeModal();
        loadUsers(editingUserId ? userPageState.page : 1);
    } catch (e) {
        showToast(e.message || '保存失败', 'error');
    }
}

async function deleteUser(id) {
    if (!confirm('确定删除该用户？此操作不可恢复。')) return;
    try {
        await api(`/users/${id}`, { method: 'DELETE' });
        showToast('删除成功');
        loadUsers(userPageState.page);
    } catch (e) {
        showToast(e.message || '删除失败', 'error');
    }
}

async function toggleUserStatus(id, status) {
    try {
        await api(`/users/${id}/status?status=${status}`, { method: 'POST' });
        showToast(status === 1 ? '已启用' : '已禁用');
        loadUsers(userPageState.page);
    } catch (e) {
        showToast(e.message || '操作失败', 'error');
    }
}

async function resetUserPassword(id) {
    if (!confirm('确定将该用户密码重置为 admin123？')) return;
    try {
        await api(`/users/${id}/reset-password`, { method: 'POST' });
        showToast('密码已重置为 admin123');
    } catch (e) {
        showToast(e.message || '重置失败', 'error');
    }
}

// ==================== 初始化 ====================

// ==================== 数据统计看板 ====================

let _dashStats = null;
let _failList = [];
let _unmatchList = [];
let _duplicateList = [];
let _lowConfList = [];

const STATUS_LABELS = {
    'draft': '草稿', 'needs_review': '待审核', 'reviewing': '审核中',
    'approved': '审核通过', 'rejected': '审核驳回', 'modified': '已修改',
    'export_ready': '可导出', 'processed': '已处理', 'completed': '已完成'
};

const CHART_COLORS = ['#2563eb', '#059669', '#d97706', '#dc2626', '#7c3aed', '#0891b2', '#db2777', '#65a30d', '#ea580c', '#4f46e5'];

// 看板页面加载
function loadDashboardPage() {
    loadDashboardTitles();
    loadDashboard();
}

async function loadDashboardTitles() {
    try {
        const titles = await api('/import/titles');
        const sel = $('#dashTitleId');
        const cur = sel.value;
        sel.innerHTML = '<option value="">全部文件</option>' +
            (titles || []).map(t => `<option value="${t.id}">${t.fileName || '数据#' + t.id} (${t.totalRows || 0}行)</option>`).join('');
        if (cur) sel.value = cur;
    } catch (e) {
        console.error('加载文件列表失败:', e);
    }
}

async function loadDashboard() {
    try {
        const titleId = $('#dashTitleId').value;
        const url = '/cleaning/dashboard-statistics' + (titleId ? '?titleId=' + titleId : '');
        const stats = await api(url);
        _dashStats = stats;
        renderDashboard(stats);
    } catch (e) {
        console.error('加载看板失败:', e);
        showToast('看板数据加载失败: ' + e.message, 'error');
    }
}

function formatNum(v) {
    if (v === null || v === undefined) return '-';
    return Number(v).toLocaleString('zh-CN');
}

function renderDashboard(s) {
    const grid = $('#dashKpiGrid');
    const cards = [
        { label: '文件数', value: s.fileCount, color: 'var(--text)' },
        { label: '清洗总条数', value: s.totalCleaned, color: 'var(--accent)' },
        { label: '分类匹配', value: s.matchCount, color: 'var(--success)', click: "switchFailureTab('unmatch');openFailureModal()", sub: '点击查看不匹配' },
        { label: '分类不匹配', value: s.unmatchCount, color: 'var(--warning)', click: "switchFailureTab('unmatch');openFailureModal()", sub: '点击查看' },
        { label: '重复数据', value: s.duplicateCount || 0, color: 'var(--warning)', click: "switchFailureTab('dup');openFailureModal()", sub: '点击查看' },
        { label: '低置信样本', value: s.lowConfidenceCount || 0, color: 'var(--danger)', click: "switchFailureTab('lowconf');openFailureModal()", sub: '点击查看' },
        { label: '填充成功', value: s.successCount, color: 'var(--success)' },
        { label: '填充失败', value: s.failureCount, color: 'var(--danger)', click: "switchFailureTab('fill');openFailureModal()", sub: '点击查看失败明细' }
    ];
    grid.innerHTML = cards.map(c => `
        <div class="stat-card kpi-card ${c.click ? 'kpi-clickable' : ''}" ${c.click ? `onclick="${c.click}"` : ''}>
            <div class="stat-value" style="color:${c.color}">${formatNum(c.value)}</div>
            <div class="stat-label">${c.label}</div>
            ${c.sub ? `<div class="kpi-sub">${c.sub}</div>` : ''}
        </div>
    `).join('');

    renderDonut('chartFill', 'legendFill', s.fillDistribution, ['#059669', '#dc2626']);
    renderDonut('chartMatch', 'legendMatch', s.matchDistribution, ['#2563eb', '#d97706']);
    const statusData = (s.statusDistribution || []).map(d => ({ name: STATUS_LABELS[d.status] || d.status, value: d.count }));
    renderDonut('chartStatus', 'legendStatus', statusData);
    const catData = (s.categoryDistribution || []).map(d => ({ name: d.categoryName || d.categoryCode || '未知', value: d.count }));
    renderDonut('chartCategory', 'legendCategory', catData);
}

// SVG 环形饼图
function renderDonut(canvasId, legendId, data, colorOverride) {
    const canvas = document.getElementById(canvasId);
    const legend = document.getElementById(legendId);
    if (!canvas) return;
    const total = (data || []).reduce((sum, d) => sum + (Number(d.value) || 0), 0);
    const size = 180, cx = size / 2, cy = size / 2, r = 70, inner = 42;
    let svg = `<svg viewBox="0 0 ${size} ${size}" width="100%" height="180" style="max-width:200px;display:block;margin:0 auto">`;
    if (total <= 0) {
        svg += `<circle cx="${cx}" cy="${cy}" r="${r}" fill="none" stroke="var(--border)" stroke-width="22"/>`;
        svg += `<text x="${cx}" y="${cy + 4}" text-anchor="middle" font-size="12" fill="var(--text-secondary)">暂无数据</text>`;
        svg += `</svg>`;
    } else {
        let start = -Math.PI / 2;
        (data || []).forEach((d, i) => {
            const val = Number(d.value) || 0;
            if (val <= 0) return;
            const angle = val / total * Math.PI * 2;
            const end = start + angle;
            const large = angle > Math.PI ? 1 : 0;
            const x1 = cx + r * Math.cos(start), y1 = cy + r * Math.sin(start);
            const x2 = cx + r * Math.cos(end), y2 = cy + r * Math.sin(end);
            const xi1 = cx + inner * Math.cos(end), yi1 = cy + inner * Math.sin(end);
            const xi2 = cx + inner * Math.cos(start), yi2 = cy + inner * Math.sin(start);
            const color = (colorOverride && colorOverride[i]) || CHART_COLORS[i % CHART_COLORS.length];
            svg += `<path d="M ${x1.toFixed(2)} ${y1.toFixed(2)} A ${r} ${r} 0 ${large} 1 ${x2.toFixed(2)} ${y2.toFixed(2)} L ${xi1.toFixed(2)} ${yi1.toFixed(2)} A ${inner} ${inner} 0 ${large} 0 ${xi2.toFixed(2)} ${yi2.toFixed(2)} Z" fill="${color}"/>`;
            start = end;
        });
        svg += `<text x="${cx}" y="${cy - 2}" text-anchor="middle" font-size="20" font-weight="700" fill="var(--text)">${formatNum(total)}</text>`;
        svg += `<text x="${cx}" y="${cy + 16}" text-anchor="middle" font-size="11" fill="var(--text-secondary)">总计</text>`;
        svg += `</svg>`;
    }
    canvas.innerHTML = svg;
    if (legend) {
        legend.innerHTML = (data || []).map((d, i) => {
            const color = (colorOverride && colorOverride[i]) || CHART_COLORS[i % CHART_COLORS.length];
            const pct = total > 0 ? ((Number(d.value) || 0) / total * 100).toFixed(1) : 0;
            return `<div class="legend-item"><span class="legend-dot" style="background:${color}"></span><span class="legend-name">${escapeHtml(d.name)}</span><span class="legend-val">${formatNum(d.value)} (${pct}%)</span></div>`;
        }).join('');
    }
}

// 失败明细
    async function openFailureModal() {
        $('#failureOverlay').classList.add('show');
        $('#failureModal').classList.add('show');
        const titleId = $('#dashTitleId').value;
        const qs = titleId ? '?titleId=' + titleId : '';
        try {
            const [fills, unmatches, dups, lowconfs] = await Promise.all([
                api('/cleaning/failed-results' + qs),
                api('/cleaning/unmatched-classify' + qs),
                api('/cleaning/duplicate-data' + qs),
                api('/cleaning/low-confidence-samples' + qs)
            ]);
            renderFailTable(fills || []);
            renderUnmatchTable(unmatches || []);
            renderDuplicateTable(dups || []);
            renderLowConfTable(lowconfs || []);
        } catch (e) {
            console.error('加载失败明细失败:', e);
            showToast('加载失败明细失败: ' + e.message, 'error');
        }
    }

function closeFailureModal() {
    $('#failureOverlay').classList.remove('show');
    $('#failureModal').classList.remove('show');
}

    function switchFailureTab(tab) {
        document.querySelectorAll('#failureModal .tab').forEach(t => t.classList.toggle('active', t.dataset.tab === tab));
        $('#failureTabFill').style.display = tab === 'fill' ? 'block' : 'none';
        $('#failureTabUnmatch').style.display = tab === 'unmatch' ? 'block' : 'none';
        $('#failureTabDup').style.display = tab === 'dup' ? 'block' : 'none';
        $('#failureTabLowConf').style.display = tab === 'lowconf' ? 'block' : 'none';
    }

function renderFailTable(list) {
    _failList = list || [];
    $('#failCountFill').textContent = _failList.length;
    const tb = $('#failTbody');
    if (!_failList.length) {
        tb.innerHTML = '<tr><td colspan="6" class="empty-hint">暂无填充失败记录</td></tr>';
        return;
    }
    tb.innerHTML = _failList.map((f, idx) => `
        <tr>
            <td>${f.id}</td>
            <td>${f.tempDataId || '-'}</td>
            <td>${f.categoryCode || '-'}</td>
            <td style="color:var(--danger);max-width:240px">${escapeHtml(f.reason)}</td>
            <td style="max-width:300px;white-space:pre-wrap;font-size:12px">${escapeHtml(f.rawData) || '-'}</td>
            <td><button class="btn btn-sm btn-info" onclick="askAiAboutFail(${idx})">问AI</button></td>
        </tr>
    `).join('');
}

    function renderUnmatchTable(list) {
        _unmatchList = list || [];
        $('#failCountUnmatch').textContent = _unmatchList.length;
        const tb = $('#unmatchTbody');
        if (!_unmatchList.length) {
            tb.innerHTML = '<tr><td colspan="6" class="empty-hint">暂无分类不匹配记录</td></tr>';
            return;
        }
        tb.innerHTML = _unmatchList.map((c, idx) => `
            <tr>
                <td>${c.id}</td>
                <td>${escapeHtml(c.materialCode) || '-'}</td>
                <td>${escapeHtml(c.materialName) || '-'}</td>
                <td>${c.categoryCode || '-'}</td>
                <td>${c.matchSource || '-'}</td>
                <td><button class="btn btn-sm btn-info" onclick="askAiAboutUnmatch(${idx})">问AI</button></td>
            </tr>
        `).join('');
    }

    function renderDuplicateTable(list) {
        _duplicateList = list || [];
        $('#failCountDup').textContent = _duplicateList.length;
        const tb = $('#dupTbody');
        if (!_duplicateList.length) {
            tb.innerHTML = '<tr><td colspan="6" class="empty-hint">暂无重复数据</td></tr>';
            return;
        }
        tb.innerHTML = _duplicateList.map((c, idx) => `
            <tr>
                <td>${c.id}</td>
                <td>${escapeHtml(c.materialCode) || '-'}</td>
                <td>${escapeHtml(c.materialName) || '-'}</td>
                <td>${c.categoryCode || '-'}</td>
                <td style="max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title="${escapeHtml(c.sourceRowHash)}">${escapeHtml(c.sourceRowHash)}</td>
                <td><button class="btn btn-sm btn-info" onclick="askAiAboutDuplicate(${idx})">问AI</button></td>
            </tr>
        `).join('');
    }

    function renderLowConfTable(list) {
        _lowConfList = list || [];
        $('#failCountLowConf').textContent = _lowConfList.length;
        const tb = $('#lowConfTbody');
        if (!_lowConfList.length) {
            tb.innerHTML = '<tr><td colspan="7" class="empty-hint">暂无低置信样本</td></tr>';
            return;
        }
        tb.innerHTML = _lowConfList.map((c, idx) => `
            <tr>
                <td>${c.id}</td>
                <td style="max-width:260px;white-space:pre-wrap;font-size:12px">${escapeHtml(c.sourceText) || '-'}</td>
                <td>${escapeHtml(c.sourceCategoryName) || '-'}</td>
                <td>${escapeHtml(c.targetCategoryName) || '-'}</td>
                <td>${c.confidence != null ? c.confidence : '-'}</td>
                <td>${c.score != null ? c.score : '-'}</td>
                <td><button class="btn btn-sm btn-info" onclick="askAiAboutLowConf(${idx})">问AI</button></td>
            </tr>
        `).join('');
    }

function askAiAboutFail(idx) {
    const f = _failList[idx];
    if (!f) return;
    const text = `【填充失败记录】\nID: ${f.id}\n原始数据ID: ${f.tempDataId || '-'}\n分类编码: ${f.categoryCode || '-'}\n失败原因: ${f.reason || '-'}\n原始数据: ${f.rawData || '-'}\n\n请分析该失败原因，并给出处理建议。`;
    copyToChat(text);
    showToast('已带入 AI 对话框', 'success');
}

    function askAiAboutUnmatch(idx) {
        const c = _unmatchList[idx];
        if (!c) return;
        const text = `【分类不匹配记录】\nID: ${c.id}\n物料代码: ${c.materialCode || '-'}\n物料名称: ${c.materialName || '-'}\n分类编码: ${c.categoryCode || '-'}\n匹配来源: ${c.matchSource || '-'}\n\n该物料未被匹配到标准分类，请分析可能原因并给出处理建议。`;
        copyToChat(text);
        showToast('已带入 AI 对话框', 'success');
    }

    function askAiAboutDuplicate(idx) {
        const c = _duplicateList[idx];
        if (!c) return;
        const text = `【重复数据记录】\nID: ${c.id}\n物料代码: ${c.materialCode || '-'}\n物料名称: ${c.materialName || '-'}\n分类编码: ${c.categoryCode || '-'}\n行指纹(数据血缘): ${c.sourceRowHash || '-'}\n\n该记录与同文件内其他记录指纹相同（数据血缘重复），请分析是否应去重或合并，并给出处理建议。`;
        copyToChat(text);
        showToast('已带入 AI 对话框', 'success');
    }

    function askAiAboutLowConf(idx) {
        const c = _lowConfList[idx];
        if (!c) return;
        const text = `【低置信样本】\nID: ${c.id}\n来源文本(属性拆分列): ${c.sourceText || '-'}\n原分类: ${c.sourceCategoryName || '-'}\n推荐分类: ${c.targetCategoryName || '-'}\n置信度: ${c.confidence != null ? c.confidence : '-'}\n质量分: ${c.score != null ? c.score : '-'}\n说明: ${c.reason || '-'}\n\n该样本为 AI 分类检测低置信/未匹配，已沉淀为主动学习样本，请分析可能原因并给出处理建议。`;
        copyToChat(text);
        showToast('已带入 AI 对话框', 'success');
    }

function copyDashboardSummary() {
    const s = _dashStats;
    if (!s) { showToast('请先加载看板数据', 'warning'); return; }
    const lines = [];
    lines.push('【数据清洗看板摘要】');
    if (s.scope === 'file') lines.push('数据文件：' + (s.fileName || '-'));
    lines.push('文件数：' + s.fileCount);
    lines.push('导入总行数：' + s.totalRows);
    lines.push('清洗总条数：' + s.totalCleaned);
    lines.push('分类匹配：' + s.matchCount + '，分类不匹配：' + s.unmatchCount);
    lines.push('重复数据：' + (s.duplicateCount || 0) + '，低置信样本：' + (s.lowConfidenceCount || 0));
    lines.push('填充成功：' + s.successCount + '，填充失败：' + s.failureCount);
    lines.push('平均质量分：' + s.avgScore);
    copyToChat(lines.join('\n') + '\n\n请帮我分析以上数据，指出可能的问题与改进建议。');
    showToast('已复制到 AI 对话框', 'success');
}

// ==================== AI 对话助手 ====================

let chatHistory = [];
let aiChatReady = false;
// AI 对话模式：general（通用问答）/ category（标准分类代码查询，接入 main_data_category）
let aiChatMode = 'general';

async function initAiChat() {
    try {
        const r = await api('/ai/chat-enabled');
        aiChatReady = !!(r && r.enabled);
        const d = $('#aiChatDisabled');
        if (!aiChatReady && d) {
            d.style.display = 'block';
            d.textContent = 'AI 对话未启用（请在 application.yml 配置 app.ai 的 base-url / api-key / model）';
        }
    } catch (e) {
        console.warn('查询 AI 对话状态失败:', e.message);
    }
    if (aiChatReady) renderAiChatTips();
}

// 渲染 AI 对话框的触发示例提示（初始/清空对话时展示，默认折叠，点击展开，引导用户如何触发各类能力）
function renderAiChatTips() {
    const box = $('#aiChatMessages');
    if (!box) return;
    box.innerHTML = '';
    const tips = [
        { tag: '标准分类问答', desc: '切到「标准分类」模式，或问', ex: '分类编码100101是什么 / 有哪些一级分类 / 铸钢件的标准编码' },
        { tag: '相似物料推荐', desc: '切到「相似物料」模式，或问', ex: '推荐相似物料：碳素铸钢件 规格Q235 / 和螺纹钢类似的物料有哪些' },
        { tag: '单条文字分类', desc: '输入', ex: '请分类：碳素铸钢件 规格Q235 / 碳素铸钢件属于哪类' },
        { tag: '数据文件分类检测', desc: '在「智能分类」页选中文件后，问含“分类”的问题，如', ex: '这批数据分类情况如何' }
    ];
    const wrap = document.createElement('div');
    wrap.className = 'ai-tips';

    const head = document.createElement('div');
    head.className = 'ai-tips-head';
    const chevron = document.createElement('span');
    chevron.className = 'ai-tips-chevron';
    chevron.textContent = '▸';
    const headText = document.createElement('span');
    headText.textContent = '使用提示 / 触发示例（点击展开）';
    head.appendChild(chevron);
    head.appendChild(headText);

    const list = document.createElement('div');
    list.className = 'ai-tips-list';
    list.style.display = 'none';
    tips.forEach(t => {
        const item = document.createElement('div');
        item.className = 'ai-tips-item';
        const tag = document.createElement('span');
        tag.className = 'ai-tips-tag';
        tag.textContent = t.tag;
        const body = document.createElement('div');
        body.className = 'ai-tips-body';
        body.appendChild(document.createTextNode(t.desc + ' '));
        const ex = document.createElement('span');
        ex.className = 'ai-tips-ex';
        ex.textContent = t.ex;
        body.appendChild(ex);
        item.appendChild(tag);
        item.appendChild(body);
        list.appendChild(item);
    });

    head.addEventListener('click', () => {
        const open = list.style.display !== 'none';
        list.style.display = open ? 'none' : 'block';
        chevron.textContent = open ? '▸' : '▾';
        headText.textContent = open ? '使用提示 / 触发示例（点击展开）' : '使用提示 / 触发示例（点击收起）';
    });

    wrap.appendChild(head);
    wrap.appendChild(list);
    box.appendChild(wrap);
    box.scrollTop = box.scrollHeight;
}

function toggleAiChat() {
    const p = $('#aiChatPanel');
    if (p.classList.contains('open')) p.classList.remove('open');
    else p.classList.add('open');
}

function appendChatMessage(role, content) {
    const box = $('#aiChatMessages');
    const div = document.createElement('div');
    div.className = 'ai-msg ' + (role === 'user' ? 'ai-msg-user' : 'ai-msg-assistant');
    div.textContent = content;
    box.appendChild(div);
    box.scrollTop = box.scrollHeight;
}

async function sendAiMessage() {
    if (!aiChatReady) { showToast('AI 对话未启用', 'warning'); return; }
    const input = $('#aiChatInput');
    const text = input.value.trim();
    if (!text) return;
    // 发送首条消息时移除提示卡片
    const tipsEl = $('#aiChatMessages .ai-tips');
    if (tipsEl) tipsEl.remove();
    appendChatMessage('user', text);
    chatHistory.push({ role: 'user', content: text });
    input.value = '';
    const box = $('#aiChatMessages');
    const thinking = document.createElement('div');
    thinking.className = 'ai-msg ai-msg-assistant ai-thinking';
    thinking.textContent = '正在思考…';
    box.appendChild(thinking);
    box.scrollTop = box.scrollHeight;
    const aiPanel = document.getElementById('aiChatPanel');
    if (aiPanel) aiPanel.classList.add('ai-thinking-active');
    try {

    // 标准分类问答模式（或问题被识别为标准分类查询）：基于 main_data_category 检索并回答
    if (aiChatMode === 'category' || isCategoryQuestion(text)) {
        await runCategoryChat(box, thinking, text);
        return;
    }

    // 相似物料推荐模式（或问题被识别为相似物料查询）：基于 cleaned_data 召回相似物料并综述
    if (aiChatMode === 'similar' || isSimilarMaterialQuestion(text)) {
        await runSimilarMaterialChat(box, thinking, text);
        return;
    }

    // 触发方式二：用户提问"某段文字属于哪一类" -> 复用 AI 辅助分类检测逻辑识别该文字，返回推荐分类/编码/理由
    const classifyInput = extractClassifyText(text);
    if (classifyInput && classifyInput.length >= 2) {
        thinking.textContent = '正在对输入文字进行 AI 分类识别…';
        try {
            const data = await classifyTextWithTimeout(classifyInput);
            if (box.contains(thinking)) box.removeChild(thinking);
            const out = formatClassifyResult(data);
            appendChatMessage('assistant', out);
            chatHistory.push({ role: 'assistant', content: out });
        } catch (e) {
            if (box.contains(thinking)) box.removeChild(thinking);
            appendChatMessage('assistant', '分类识别失败：' + e.message);
        }
        return;
    }

    // 触发方式一：分类相关问题 -> 触发「AI 辅助分类检测」逻辑分析数据文件，作为上下文
    let systemPrompt = null;
    if (/分类/.test(text)) {
        thinking.textContent = '正在调用 AI 辅助分类检测分析数据…';
        systemPrompt = await buildClassifyContextPrompt();
    }

    try {
        const body = { messages: chatHistory };
        if (systemPrompt) body.systemPrompt = systemPrompt;
        const res = await api('/ai/chat', {
            method: 'POST',
            body: JSON.stringify(body)
        });
        if (box.contains(thinking)) box.removeChild(thinking);
        const reply = res.reply || '(无回复)';
        appendChatMessage('assistant', reply);
        chatHistory.push({ role: 'assistant', content: reply });
    } catch (e) {
        if (box.contains(thinking)) box.removeChild(thinking);
        appendChatMessage('assistant', '调用失败：' + e.message);
    }

    } catch (e) {
        if (box.contains(thinking)) box.removeChild(thinking);
        appendChatMessage('assistant', '调用失败：' + e.message);
    } finally {
        if (aiPanel) aiPanel.classList.remove('ai-thinking-active');
    }
}

// 当用户提问包含「分类」关键词时，调用「AI 辅助分类检测」接口（useAi=true 即触发 AI 识别），
// 将检测结果汇总为系统提示词上下文，供 AI 参考回答。
async function buildClassifyContextPrompt() {
    // 未提供独立的"分析文件"下拉，复用清洗模块当前所选文件
    const ct = $('#cleanTitleId');
    const titleId = ct ? ct.value : '';
    if (!titleId) {
        return AI_CHAT_SYSTEM_PROMPT + '\n\n用户询问了分类相关问题，但未选择数据文件，无法调用 AI 辅助分类检测，请基于通用数据清洗与物料分类知识回答。';
    }
    try {
        const data = await classifyByColumnWithTimeout(titleId);
        if (!data || data.total === 0) {
            const msg = data && data.message ? '（' + data.message + '）' : '';
            return AI_CHAT_SYSTEM_PROMPT + '\n\n所选数据文件（ID=' + titleId + '）' + (msg || '暂无可用于分类识别的数据') + '，无法进行分类检测，请基于通用知识回答用户的分类问题。';
        }
        const details = data.details || [];
        const mismatches = details.filter(d => !d.matched).slice(0, 20);
        let ctx = '以下是数据文件（ID=' + titleId + '）经「AI 辅助分类检测」得到的参考信息，请据此回答用户关于分类的问题：\n';
        ctx += '总条数：' + data.total + '，分类匹配：' + data.matchedCount + '，不匹配/存疑：' + data.mismatchCount +
            '，平均评分：' + data.avgScore + '，是否启用 AI 识别：' + data.useAi + '。\n';
        if (mismatches.length) {
            ctx += '分类不匹配/存疑的记录（物料代码 / 系统分类 / 建议标准编码 / 评分 / 说明）：\n';
            mismatches.forEach(d => {
                ctx += '- [' + (d.materialCode || '-') + '] ' + (d.materialName || '-') +
                    '：系统=' + (d.categoryCode || '-') + '/' + (d.categoryName || '-') +
                    '，建议=' + (d.suggestedCode || '-') +
                    '，评分=' + (d.score != null ? d.score : '-') +
                    '，说明=' + (d.reason || '-') + '\n';
            });
        } else {
            ctx += '未检出明显分类不匹配/存疑记录。\n';
        }
        return AI_CHAT_SYSTEM_PROMPT + '\n\n' + ctx;
    } catch (e) {
        console.warn('获取分类检测上下文失败:', e.message);
        return AI_CHAT_SYSTEM_PROMPT + '\n\n（调用 AI 辅助分类检测失败：' + e.message + '）请基于通用知识回答用户的分类问题。';
    }
}

// 与后端 AiChatController.DEFAULT_SYSTEM_PROMPT 保持一致
const AI_CHAT_SYSTEM_PROMPT = '你是一名专业的数据清洗分析助手，帮助用户解读数据统计看板中的指标、失败原因与分类匹配情况，用简洁、可操作的中文给出分析与建议。';

// 从用户输入中识别"某段文字属于哪一类"的意图，返回待分类的文字（无法识别则返回 null）。
// 支持：① "XXX属于/是/归到/归入哪类/哪个分类"；② "分类/归类/识别分类：XXX"。
function extractClassifyText(text) {
    const t = (text || '').trim();
    if (!t) return null;
    // 模式①：分类/归类/识别分类 + 冒号 后的内容（如"帮我分类：碳素铸钢件 规格Q235"）
    let m = t.match(/(?:请|帮(?:我)?|帮忙)?\s*(?:分类|归类|识别分类|判断分类)\s*[：:]\s*(.+)$/s);
    if (m) return m[1].trim();
    // 模式②：包含"归类问句"特征（属于/归到/是哪类/什么分类…），提取问句前的主体文字
    const askPattern = /(属于|归为|归到|归入|是哪|是什么|该归|应归|算).{0,4}(哪|什么|哪个).{0,4}(类|分类|类别|目)/;
    if (!askPattern.test(t)) return null;
    m = t.match(/^(.*?)\s*(?:属于|是|算|该归|应归|归为|归到|归入|归类)\s*(?:哪|什么|哪个).{0,4}(?:类|分类|类别)/s);
    if (m) {
        let subj = m[1].trim();
        subj = subj.replace(/^(?:请|请问|帮(?:我)?|帮忙|识别|判断|归类|分类|一下|帮我|想问|我想问|这段(?:文字)?[:：]?|这句(?:话)?[:：]?|以下文字[:：]?|如下[:：]?)\s*/, '');
        return subj;
    }
    return null;
}

// 将后端 classify-text 的返回格式化为聊天消息
function formatClassifyResult(data) {
    if (!data) return '未获得分类结果';
    if (data.message) return data.message;
    const name = data.recommendedName || '（未找到匹配分类）';
    const code = data.recommendedCode || '—';
    const reason = data.reason || '—';
    let s = '【分类识别结果】\n';
    s += '推荐分类：' + name + '（分类编码：' + code + '）\n';
    if (data.score != null) s += '置信度评分：' + data.score + '\n';
    s += '理由：' + reason + '\n';
    s += '（识别方式：' + (data.useAi ? 'AI 识别' : '关键词匹配') + '）';
    return s;
}

// 带超时的「文本分类识别」调用（复用 AI 辅助分类检测逻辑，useAi=true 触发 AI 识别）
async function classifyTextWithTimeout(text, timeoutMs = 180000) {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeoutMs);
    try {
        const headers = { 'Content-Type': 'application/json' };
        const token = getToken();
        if (token) headers['Authorization'] = 'Bearer ' + token;
        const url = API + '/cleaning/classify-text?text=' + encodeURIComponent(text);
        const res = await fetch(url, { method: 'POST', headers, signal: ctrl.signal });
        if (res.status === 401) { redirectToLogin(); throw new Error('登录已过期，请重新登录'); }
        const body = await res.text();
        if (!body || !body.trim()) throw new Error('分类识别未返回数据，可能请求超时');
        const data = JSON.parse(body);
        if (data.code === 401) { redirectToLogin(); throw new Error(data.msg || '登录已过期'); }
        if (data.code !== 200) throw new Error(data.msg || '分类识别失败');
        return data.data;
    } finally {
        clearTimeout(timer);
    }
}

function clearChat() {
    chatHistory = [];
    renderAiChatTips();
}

// 切换 AI 对话模式（通用问答 / 标准分类查询）
function setAiChatMode(mode) {
    aiChatMode = mode;
    $$('.ai-mode-btn').forEach(b => b.classList.toggle('active', b.dataset.mode === mode));
    const input = $('#aiChatInput');
    if (input) {
        input.placeholder = mode === 'category'
            ? '例如：分类编码100101是什么？/ 有哪些一级分类？/ 铸钢件的标准编码是多少？'
            : mode === 'similar'
            ? '例如：推荐相似物料：碳素铸钢件 规格Q235 / 和螺纹钢类似的物料有哪些？'
            : '输入消息，或点击看板中的「问AI」把内容带过来…';
    }
}

// 识别用户问题是否为"标准分类查询"（即使处于通用模式也路由到标准分类问答）
function isCategoryQuestion(text) {
    const t = (text || '').toLowerCase();
    const keys = ['标准分类', '分类编码', '分类代码', '分类目录', '标准库', '类目',
        'main_data_category', '有哪些分类', '分类有哪些', '查分类', '分类查询',
        '分类信息', '分类表', '标准分类库', '分类层级', '分类说明',
        '一级分类', '二级分类', '三级分类', '全部分类', '所有分类', '列举分类',
        '有哪些一级', '有哪些二级', '有哪些三级', '分类有哪些'];
    return keys.some(k => t.includes(k.toLowerCase()));
}

// 识别用户问题是否为"相似物料推荐"（即使处于通用模式也路由到相似物料推荐）
function isSimilarMaterialQuestion(text) {
    const t = (text || '').toLowerCase();
    const keys = ['相似物料', '类似物料', '相近物料', '推荐物料', '相似材料', '类似材料',
        '找相似', '找类似', '类似的物料', '相似的物料', '推荐类似', '相似产品', '类似产品',
        '相似的产品', '类似的'];
    if (keys.some(k => t.includes(k))) return true;
    // 模式：和/跟/与/同 X 相似/类似/相近 （的）物料/材料/产品
    if (/(和|跟|与|同)\s*.{1,20}?\s*(相似|类似|相近|差不多)\s*(的)?\s*(物料|材料|产品)?/.test(text)) return true;
    return false;
}

// 相似物料推荐：基于 cleaned_data 召回相似物料并交由 AI 综述，展示命中来源
async function runSimilarMaterialChat(box, thinking, text) {
    thinking.textContent = '正在检索相似物料并生成推荐…';
    try {
        const res = await api('/ai/similar-materials', {
            method: 'POST',
            body: JSON.stringify({ messages: chatHistory })
        });
        if (box.contains(thinking)) box.removeChild(thinking);
        const reply = (res && res.reply) ? res.reply : '(无回复)';
        appendChatMessage('assistant', reply);
        chatHistory.push({ role: 'assistant', content: reply });
        if (res && res.materials && res.materials.length) {
            appendSimilarMaterials(res.materials);
        }
    } catch (e) {
        if (box.contains(thinking)) box.removeChild(thinking);
        appendChatMessage('assistant', '相似物料推荐失败：' + e.message);
    }
}

// 渲染召回的相似物料来源卡片
function appendSimilarMaterials(materials) {
    const box = $('#aiChatMessages');
    const wrap = document.createElement('div');
    wrap.className = 'ai-msg ai-msg-source';

    const title = document.createElement('div');
    title.className = 'ai-source-title';
    title.textContent = '相似物料推荐（' + materials.length + ' 条）';
    wrap.appendChild(title);

    const grid = document.createElement('div');
    grid.className = 'ai-source-grid';
    materials.slice(0, 8).forEach(m => {
        const card = document.createElement('div');
        card.className = 'ai-source-card';

        const code = document.createElement('div');
        code.className = 'ai-source-code';
        code.textContent = m.materialCode || (m.materialName || '-');

        const name = document.createElement('div');
        name.className = 'ai-source-name';
        name.textContent = m.materialName || '-';

        const path = document.createElement('div');
        path.className = 'ai-source-path';
        const sim = m.similarityScore != null ? (' · 相似度' + Math.round(m.similarityScore * 100) + '%') : '';
        path.textContent = (m.categoryName || '') + (m.categoryCode ? (' · ' + m.categoryCode) : '') + sim;

        card.appendChild(code);
        card.appendChild(name);
        card.appendChild(path);
        grid.appendChild(card);
    });
    wrap.appendChild(grid);
    box.appendChild(wrap);
    box.scrollTop = box.scrollHeight;
}


// 标准分类问答：基于 main_data_category 检索相关记录并交由 AI 回答，展示命中来源
async function runCategoryChat(box, thinking, text) {
    thinking.textContent = '正在检索标准分类库并生成回答…';
    try {
        const res = await api('/ai/category-chat', {
            method: 'POST',
            body: JSON.stringify({ messages: chatHistory })
        });
        if (box.contains(thinking)) box.removeChild(thinking);
        const reply = (res && res.reply) ? res.reply : '(无回复)';
        appendChatMessage('assistant', reply);
        chatHistory.push({ role: 'assistant', content: reply });
        if (res && res.sources && res.sources.length) {
            appendCategorySources(res.sources);
        }
    } catch (e) {
        if (box.contains(thinking)) box.removeChild(thinking);
        appendChatMessage('assistant', '标准分类问答失败：' + e.message);
    }
}

// 渲染命中的标准分类来源卡片
function appendCategorySources(sources) {
    const box = $('#aiChatMessages');
    const wrap = document.createElement('div');
    wrap.className = 'ai-msg ai-msg-source';

    const title = document.createElement('div');
    title.className = 'ai-source-title';
    title.textContent = '参考标准分类（' + sources.length + ' 条）';
    wrap.appendChild(title);

    const grid = document.createElement('div');
    grid.className = 'ai-source-grid';
    sources.slice(0, 8).forEach(s => {
        const card = document.createElement('div');
        card.className = 'ai-source-card';

        const code = document.createElement('div');
        code.className = 'ai-source-code';
        code.textContent = s.categoryCode || '-';

        const name = document.createElement('div');
        name.className = 'ai-source-name';
        name.textContent = s.categoryName || '-';

        const path = document.createElement('div');
        path.className = 'ai-source-path';
        path.textContent = (s.fullPath || '') + (s.unit ? (' · ' + s.unit) : '');

        card.appendChild(code);
        card.appendChild(name);
        card.appendChild(path);
        grid.appendChild(card);
    });
    wrap.appendChild(grid);
    box.appendChild(wrap);
    box.scrollTop = box.scrollHeight;
}


function copyToChat(text) {
    const input = $('#aiChatInput');
    input.value = (input.value ? input.value + '\n' : '') + text;
    toggleAiChat();
    input.focus();
}

document.addEventListener('DOMContentLoaded', () => {
    // 初始化下拉框
    const initSelects = async () => {
        await loadRulesForSelect('cleanRuleId');
        await loadTitlesForSelect('cleanTitleId');
        await loadTitlesForSelect('mapTitleId');
        await loadTitlesForSelect('resultTitleId', 'completed');
    };
    initSelects().catch(console.error);
    initAiChat().catch(console.error);
});

/* ============================================================
   动态 AI 特效引擎 —— 在 AI 介入运行时于卡片上叠加神经网络动画
   仅在 AI 任务真正执行期间激活，任务结束即淡出，避免常驻耗电。
   ============================================================ */
const AiFx = {
    instances: new Map(),

    // 激活某卡片的 AI 特效（注入神经网络画布 + 呼吸光晕）
    activate(card) {
        if (!card || this.instances.has(card)) return;
        card.classList.add('ai-active');
        const canvas = document.createElement('canvas');
        canvas.className = 'ai-fx-canvas';
        card.appendChild(canvas);
        const inst = { card, canvas, nodes: [], pulses: [], raf: null, t: 0, w: 0, h: 0, ro: null };
        this._initNodes(inst);
        this.instances.set(card, inst);
        this._resize(inst);
        try {
            inst.ro = new ResizeObserver(() => this._resize(inst));
            inst.ro.observe(card);
        } catch (e) { /* ResizeObserver 不可用时忽略，动画仍可运行 */ }
        this._loop(inst);
    },

    // 结束特效并移除画布
    deactivate(card) {
        const inst = this.instances.get(card);
        if (!inst) return;
        card.classList.remove('ai-active');
        if (inst.raf) cancelAnimationFrame(inst.raf);
        if (inst.ro) { try { inst.ro.disconnect(); } catch (e) {} }
        const cv = inst.canvas;
        setTimeout(() => { if (cv && cv.parentNode) cv.parentNode.removeChild(cv); }, 480);
        this.instances.delete(card);
    },

    _resize(inst) {
        const r = inst.card.getBoundingClientRect();
        const dpr = window.devicePixelRatio || 1;
        inst.w = Math.max(1, r.width);
        inst.h = Math.max(1, r.height);
        inst.canvas.width = inst.w * dpr;
        inst.canvas.height = inst.h * dpr;
        inst.canvas.style.width = inst.w + 'px';
        inst.canvas.style.height = inst.h + 'px';
        const ctx = inst.canvas.getContext('2d');
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    },

    _initNodes(inst) {
        const count = 18;
        inst.nodes = [];
        for (let i = 0; i < count; i++) {
            inst.nodes.push({
                x: Math.random(),
                y: Math.random(),
                vx: (Math.random() - 0.5) * 0.00055,
                vy: (Math.random() - 0.5) * 0.00055,
                r: 1.4 + Math.random() * 2.4
            });
        }
    },

    _loop(inst) {
        inst.raf = requestAnimationFrame(() => this._loop(inst));
        inst.t++;
        const ctx = inst.canvas.getContext('2d');
        const w = inst.w, h = inst.h;
        if (w < 2 || h < 2) return;
        ctx.clearRect(0, 0, w, h);

        const nodes = inst.nodes;
        for (const n of nodes) {
            n.x += n.vx; n.y += n.vy;
            if (n.x < 0 || n.x > 1) n.vx *= -1;
            if (n.y < 0 || n.y > 1) n.vy *= -1;
            n.x = Math.max(0, Math.min(1, n.x));
            n.y = Math.max(0, Math.min(1, n.y));
        }

        const maxD = Math.min(w, h) * 0.34;
        // 连线
        for (let i = 0; i < nodes.length; i++) {
            for (let j = i + 1; j < nodes.length; j++) {
                const a = nodes[i], b = nodes[j];
                const dx = (a.x - b.x) * w, dy = (a.y - b.y) * h;
                const dist = Math.hypot(dx, dy);
                if (dist < maxD) {
                    const alpha = (1 - dist / maxD) * 0.16;
                    ctx.strokeStyle = 'rgba(37,99,235,' + alpha + ')';
                    ctx.lineWidth = 1;
                    ctx.beginPath();
                    ctx.moveTo(a.x * w, a.y * h);
                    ctx.lineTo(b.x * w, b.y * h);
                    ctx.stroke();
                }
            }
        }

        // 节点（发光）
        for (const n of nodes) {
            const x = n.x * w, y = n.y * h;
            const g = ctx.createRadialGradient(x, y, 0, x, y, n.r * 3.2);
            g.addColorStop(0, 'rgba(99,102,241,0.85)');
            g.addColorStop(0.45, 'rgba(37,99,235,0.45)');
            g.addColorStop(1, 'rgba(37,99,235,0)');
            ctx.fillStyle = g;
            ctx.beginPath();
            ctx.arc(x, y, n.r * 3.2, 0, Math.PI * 2);
            ctx.fill();
            ctx.fillStyle = 'rgba(255,255,255,0.85)';
            ctx.beginPath();
            ctx.arc(x, y, n.r * 0.6, 0, Math.PI * 2);
            ctx.fill();
        }

        // 脉冲光点沿连线流动（体现 AI 推理/数据流动）
        if (inst.t % 7 === 0) {
            const i = Math.floor(Math.random() * nodes.length);
            const j = Math.floor(Math.random() * nodes.length);
            if (i !== j) {
                const a = nodes[i], b = nodes[j];
                const dx = (a.x - b.x) * w, dy = (a.y - b.y) * h;
                if (Math.hypot(dx, dy) < maxD) inst.pulses.push({ a, b, p: 0 });
            }
        }
        inst.pulses = inst.pulses.filter(pl => pl.p < 1);
        for (const pl of inst.pulses) {
            pl.p += 0.02;
            const x = (pl.a.x + (pl.b.x - pl.a.x) * pl.p) * w;
            const y = (pl.a.y + (pl.b.y - pl.a.y) * pl.p) * h;
            const g = ctx.createRadialGradient(x, y, 0, x, y, 4.5);
            g.addColorStop(0, 'rgba(6,182,212,0.95)');
            g.addColorStop(1, 'rgba(6,182,212,0)');
            ctx.fillStyle = g;
            ctx.beginPath();
            ctx.arc(x, y, 4.5, 0, Math.PI * 2);
            ctx.fill();
        }
    }
};

/* ===== AI 清洗特效弹窗：全屏动态原型图 =====
 * 启用 AI 后点击「开始一键清洗」弹出，描绘 AI 清洗进展与酷炫动态图。
 * 进度/步骤由 setOcOverall / setOcStep 自动同步。 */
const AiCleanOverlay = {
    el: null, canvas: null, ctx: null, raf: null,
    t: 0, w: 0, h: 0, dpr: 1, cx: 0, cy: 0, R: 0,
    nodes: [], stars: [], stream: [],
    pct: 0, pctTarget: 0, visible: false, onResize: null,
    tips: [
        'AI 清洗依赖 application.yml 中配置的模型服务，首次运行请确认 base-url / api-key / model 已正确填写。',
        '数据量越大，清洗耗时越长，当前进度会以百分比实时展示，请耐心等待。',
        '若某条数据无法判定分类，系统会自动跳过并记录，不影响整体任务的继续执行。',
        '属性补全基于已配置的标准字段表头，建议提前在「标准字段表头」中维护好标准模板。',
        '清洗完成后可切换到「智能分类」页查看逐条评分与最终结果，并可手动复核修正。',
        'AI 提取需要每行数据能确定分类编码（已执行智能分类或设置分类列），并在标准表头中有对应字段。'
    ],
    tipIdx: 0, tipTimer: null,

    show() {
        if (this.visible) return;
        this.visible = true;
        this.el = document.getElementById('aiCleanOverlay');
        this.canvas = document.getElementById('aiCleanCanvas');
        this.ctx = this.canvas.getContext('2d');
        this.t = 0; this.pct = 0; this.pctTarget = 0;
        const statusEl = document.getElementById('aiCleanStatus');
        if (statusEl) statusEl.textContent = '正在初始化 AI 清洗引擎…';
        const subEl = document.getElementById('aiCleanSubtitle');
        if (subEl) subEl.textContent = 'AI 正在对原始数据执行智能识别与结构化处理，请稍候，全程无需人工干预';
        // 重置流程说明高亮
        document.querySelectorAll('#aiCleanOverlay .ai-info-list li').forEach(li => {
            li.classList.remove('active', 'running');
        });
        this.el.classList.add('show');
        this._init();
        this._resize();
        this.onResize = () => this._resize();
        window.addEventListener('resize', this.onResize);
        this._startTips();
        this._loop();
    },

    hide() {
        if (!this.visible) return;
        this.visible = false;
        if (this.raf) cancelAnimationFrame(this.raf);
        this.raf = null;
        this._stopTips();
        if (this.onResize) window.removeEventListener('resize', this.onResize);
        if (this.el) this.el.classList.remove('show');
    },

    setProgress(pct, text) {
        this.pctTarget = Math.max(0, Math.min(100, Math.round(pct)));
        if (text) { const s = document.getElementById('aiCleanStatus'); if (s) s.textContent = text; }
    },

    setStep(name, state) {
        const el = document.querySelector('#aiCleanOverlay .ai-step[data-step="' + name + '"]');
        if (!el) return;
        el.setAttribute('data-state', state);
        const i = el.querySelector('i');
        const map = { running: '进行中…', done: '已完成', waiting: '等待中', error: '失败' };
        if (i && map[state]) i.textContent = map[state];

        // 同步高亮左侧流程说明
        const li = document.querySelector('#aiCleanOverlay .ai-info-list li[data-step="' + name + '"]');
        if (li) {
            if (state === 'running') { li.classList.add('active', 'running'); }
            else if (state === 'done') { li.classList.add('active'); li.classList.remove('running'); }
            else if (state === 'error') { li.classList.add('active', 'running'); }
            else { li.classList.remove('active', 'running'); }
        }

        // 更新副标题为当前阶段描述
        const sub = document.getElementById('aiCleanSubtitle');
        if (sub) {
            if (state === 'running') {
                const stageDesc = {
                    clean: '正在进行智能分类：AI 正在逐条判断数据所属的行业标准分类…',
                    extract: '正在进行属性提取：从原始文本中抽取关键字段与属性…',
                    map: '正在进行属性补全：将抽取结果对齐标准字段表头并补全缺失项…'
                };
                if (stageDesc[name]) sub.textContent = stageDesc[name];
            } else if (state === 'done' && name === 'map') {
                sub.textContent = '清洗即将完成，正在生成最终结果并写入数据表，请稍候…';
            }
        }
    },

    _startTips() {
        const box = document.getElementById('aiCleanTips');
        if (!box) return;
        this.tipIdx = 0;
        box.textContent = this.tips[0];
        this.tipTimer = setInterval(() => {
            if (!this.visible) return;
            box.classList.add('fade');
            setTimeout(() => {
                this.tipIdx = (this.tipIdx + 1) % this.tips.length;
                box.textContent = this.tips[this.tipIdx];
                box.classList.remove('fade');
            }, 400);
        }, 4500);
    },

    _stopTips() {
        if (this.tipTimer) { clearInterval(this.tipTimer); this.tipTimer = null; }
    },

    _init() {
        // 神经网络节点
        const cnt = 26;
        this.nodes = [];
        for (let i = 0; i < cnt; i++) {
            this.nodes.push({
                x: Math.random(), y: Math.random(),
                vx: (Math.random() - 0.5) * 0.0006,
                vy: (Math.random() - 0.5) * 0.0006,
                r: 1.5 + Math.random() * 3
            });
        }
        // 背景星点
        this.stars = [];
        for (let i = 0; i < 150; i++) {
            this.stars.push({
                x: Math.random(), y: Math.random(),
                r: Math.random() * 1.6 + 0.2,
                tw: Math.random() * 6.28,
                sp: 0.02 + Math.random() * 0.05
            });
        }
        this.stream = [];
    },

    _spawnStream() {
        if (this.stream.length > 90) return;
        const ang = Math.random() * Math.PI * 2;
        const rad = Math.max(this.w, this.h) * 0.55;
        this.stream.push({
            x: this.cx + Math.cos(ang) * rad,
            y: this.cy + Math.sin(ang) * rad,
            ang: Math.atan2(this.cy - Math.sin(ang) * rad, this.cx - Math.cos(ang) * rad),
            sp: 1.2 + Math.random() * 2.4
        });
    },

    _resize() {
        const r = this.el.getBoundingClientRect();
        this.dpr = window.devicePixelRatio || 1;
        this.w = Math.max(1, r.width);
        this.h = Math.max(1, r.height);
        this.canvas.width = this.w * this.dpr;
        this.canvas.height = this.h * this.dpr;
        this.canvas.style.width = this.w + 'px';
        this.canvas.style.height = this.h + 'px';
        this.ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
        this.cx = this.w / 2;
        this.cy = this.h / 2;
        this.R = Math.max(90, Math.min(Math.min(this.w, this.h) * 0.17, 150));
    },

    _loop() {
        this.raf = requestAnimationFrame(() => this._loop());
        this.t++;
        const ctx = this.ctx, w = this.w, h = this.h, t = this.t;
        if (w < 2 || h < 2) return;
        this.pct += (this.pctTarget - this.pct) * 0.06;
        ctx.clearRect(0, 0, w, h);

        const cx = this.cx, cy = this.cy, R = this.R;

        // 背景星点（呼吸闪烁）
        for (const s of this.stars) {
            s.tw += s.sp;
            const a = 0.2 + 0.55 * (0.5 + 0.5 * Math.sin(s.tw));
            ctx.fillStyle = 'rgba(150,180,255,' + a + ')';
            ctx.beginPath();
            ctx.arc(s.x * w, s.y * h, s.r, 0, Math.PI * 2);
            ctx.fill();
        }

        // 神经网络节点漂移
        for (const n of this.nodes) {
            n.x += n.vx; n.y += n.vy;
            if (n.x < 0 || n.x > 1) n.vx *= -1;
            if (n.y < 0 || n.y > 1) n.vy *= -1;
            n.x = Math.max(0, Math.min(1, n.x));
            n.y = Math.max(0, Math.min(1, n.y));
        }
        const maxD = Math.min(w, h) * 0.22;
        for (let i = 0; i < this.nodes.length; i++) {
            for (let j = i + 1; j < this.nodes.length; j++) {
                const a = this.nodes[i], b = this.nodes[j];
                const dx = (a.x - b.x) * w, dy = (a.y - b.y) * h;
                const d = Math.hypot(dx, dy);
                if (d < maxD) {
                    const al = (1 - d / maxD) * 0.22;
                    ctx.strokeStyle = 'rgba(80,140,255,' + al + ')';
                    ctx.lineWidth = 1;
                    ctx.beginPath();
                    ctx.moveTo(a.x * w, a.y * h);
                    ctx.lineTo(b.x * w, b.y * h);
                    ctx.stroke();
                }
            }
        }
        for (const n of this.nodes) {
            const x = n.x * w, y = n.y * h;
            const g = ctx.createRadialGradient(x, y, 0, x, y, n.r * 3);
            g.addColorStop(0, 'rgba(120,170,255,0.9)');
            g.addColorStop(1, 'rgba(120,170,255,0)');
            ctx.fillStyle = g;
            ctx.beginPath();
            ctx.arc(x, y, n.r * 3, 0, Math.PI * 2);
            ctx.fill();
        }

        // 数据粒子被吸入核心（体现数据被 AI 清洗吸收）
        if (t % 2 === 0) this._spawnStream();
        this.stream = this.stream.filter(p => {
            p.x += Math.cos(p.ang) * p.sp;
            p.y += Math.sin(p.ang) * p.sp;
            return Math.hypot(p.x - cx, p.y - cy) > R * 1.05;
        });
        for (const p of this.stream) {
            const g = ctx.createRadialGradient(p.x, p.y, 0, p.x, p.y, 5);
            g.addColorStop(0, 'rgba(6,220,255,0.95)');
            g.addColorStop(1, 'rgba(6,220,255,0)');
            ctx.fillStyle = g;
            ctx.beginPath();
            ctx.arc(p.x, p.y, 5, 0, Math.PI * 2);
            ctx.fill();
        }

        // 核心外发光（随心跳脉动）
        const pulse = 0.5 + 0.5 * Math.sin(t * 0.05);
        const og = ctx.createRadialGradient(cx, cy, 0, cx, cy, R * 1.6);
        og.addColorStop(0, 'rgba(40,90,230,' + (0.35 + 0.18 * pulse) + ')');
        og.addColorStop(0.5, 'rgba(99,102,241,0.18)');
        og.addColorStop(1, 'rgba(99,102,241,0)');
        ctx.fillStyle = og;
        ctx.beginPath();
        ctx.arc(cx, cy, R * 1.6, 0, Math.PI * 2);
        ctx.fill();

        // 旋转虚线环（多层）
        for (let k = 0; k < 3; k++) {
            ctx.save();
            ctx.translate(cx, cy);
            ctx.rotate(t * 0.01 * (k % 2 ? -1 : 1) + k);
            ctx.strokeStyle = 'rgba(120,180,255,' + (0.5 - k * 0.13) + ')';
            ctx.lineWidth = 2;
            ctx.setLineDash([10, 14]);
            ctx.beginPath();
            ctx.arc(0, 0, R * (0.7 + k * 0.22), 0, Math.PI * 2);
            ctx.stroke();
            ctx.restore();
        }

        // 六边形电路框
        ctx.save();
        ctx.translate(cx, cy);
        ctx.rotate(-t * 0.012);
        ctx.strokeStyle = 'rgba(6,220,255,0.55)';
        ctx.lineWidth = 1.5;
        ctx.beginPath();
        for (let i = 0; i <= 6; i++) {
            const a = i / 6 * Math.PI * 2;
            const x = Math.cos(a) * R * 0.5, y = Math.sin(a) * R * 0.5;
            if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
        }
        ctx.closePath();
        ctx.stroke();
        ctx.restore();

        // 旋转扫描扇区
        ctx.save();
        ctx.translate(cx, cy);
        ctx.rotate(t * 0.03);
        ctx.fillStyle = 'rgba(6,220,255,0.10)';
        ctx.beginPath();
        ctx.moveTo(0, 0);
        ctx.arc(0, 0, R * 1.18, -0.35, 0.35);
        ctx.closePath();
        ctx.fill();
        ctx.restore();

        // 进度环
        const prog = this.pct / 100;
        const start = -Math.PI / 2;
        const end = start + prog * Math.PI * 2;
        ctx.strokeStyle = 'rgba(120,180,255,0.18)';
        ctx.lineWidth = 8;
        ctx.beginPath();
        ctx.arc(cx, cy, R * 1.18, 0, Math.PI * 2);
        ctx.stroke();
        ctx.strokeStyle = 'rgba(6,220,255,0.95)';
        ctx.lineWidth = 8;
        ctx.lineCap = 'round';
        ctx.beginPath();
        ctx.arc(cx, cy, R * 1.18, start, end);
        ctx.stroke();
        ctx.lineCap = 'butt';

        // 核心球体
        const cg = ctx.createRadialGradient(cx, cy - R * 0.2, 0, cx, cy, R * 0.6);
        cg.addColorStop(0, 'rgba(180,210,255,0.95)');
        cg.addColorStop(0.4, 'rgba(70,130,255,0.9)');
        cg.addColorStop(1, 'rgba(40,70,200,0.5)');
        ctx.fillStyle = cg;
        ctx.beginPath();
        ctx.arc(cx, cy, R * 0.45, 0, Math.PI * 2);
        ctx.fill();

        // 百分比文字
        ctx.fillStyle = '#eaf2ff';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.font = '700 ' + Math.round(R * 0.42) + 'px Inter, system-ui, sans-serif';
        ctx.fillText(Math.round(this.pct) + '%', cx, cy);
    }
};

// ==================== 外部数据清洗 ====================
let ecTaskPage = 1;
const EC_TASK_SIZE = 10;
let ecSortField = 'createdAt'; // 默认按创建时间
let ecSortOrder = 'desc';      // 默认倒序
let ecCurrentTaskId = null;
let ecCurrentTaskStatus = null; // 当前查看任务的外部状态，用于判断是否需要拉取进展
let ecResultLoadedTaskId = null; // 本会话已为哪个任务加载过完整明细（终态后不再重复拉取）
let ecRowPage = 1;
const EC_ROW_SIZE = 20;
let ecOnlyReview = false;
let ecResultOpen = false;
let ecRowsData = [];
let ecAutoTimer = null;
let ecCorrectRowIndex = null;

// 提交外部清洗任务
// mode: 未传时取下拉框值；'sync'/'async' 强制该模式；'auto' 表示由服务端按行数自动判定
async function ecSubmitTask(mode) {
    const titleId = $('#ecTitleId').value;
    if (!titleId) { showToast('请先选择数据文件', 'error'); return; }
    if (!mode) {
        mode = $('#ecMode') ? $('#ecMode').value : 'auto';
    }
    const options = {
        threshold: parseFloat($('#ecThreshold').value) || 0.7,
        maxCandidates: parseInt($('#ecMaxCandidates').value, 10) || 10,
        model: $('#ecModel').value || 'default'
    };
    const btn = event && event.target ? event.target : null;
    if (btn) { btn.disabled = true; btn.textContent = '提交中…'; }

    // 仅同步任务需要拆分：单次外部上限 20 条，超出则拆成每批 20 条、复用同一任务追加提交
    if (mode === 'sync') {
        await ecSubmitTaskInBatches(titleId, options, btn);
    } else {
        // 异步 / 自动：保持原有一次性提交（由后端按行数自动判定模式）
        const body = { tempDataTitleId: Number(titleId), options: options };
        if (mode && mode !== 'auto') { body.mode = mode; }
        try {
            const task = await api('/external-clean/tasks', { method: 'POST', body: body });
            const modeText = (task && task.mode === 'sync') ? '同步' : '异步';
            showToast('任务已提交[' + modeText + ']：' + (task && task.taskId), 'success');
            ecLoadTasks(1);
        } catch (e) {
            showToast('提交失败：' + e.message, 'error');
        } finally {
            if (btn) { btn.disabled = false; btn.textContent = '提交清洗任务'; }
        }
    }
}

// 同步任务分批提交：每批 20 条，前批阻塞完成后再以同一任务追加下一批
async function ecSubmitTaskInBatches(titleId, options, btn) {
    const EC_SYNC_BATCH = 10;
    // 拉取该文件全部行ID，用于按批次拆分
    let allRowIds = [];
    try {
        const rows = await api('/cleaning/temp-data/' + titleId);
        allRowIds = (rows || []).map(function (r) { return r.id; });
    } catch (e) {
        showToast('获取文件行列表失败：' + e.message, 'error');
        if (btn) { btn.disabled = false; btn.textContent = '同步提交清洗任务'; }
        return;
    }
    if (allRowIds.length === 0) {
        showToast('该文件没有可清洗的数据行', 'error');
        if (btn) { btn.disabled = false; btn.textContent = '同步提交清洗任务'; }
        return;
    }

    // 按每批 20 条拆分
    const batches = [];
    for (let i = 0; i < allRowIds.length; i += EC_SYNC_BATCH) {
        batches.push(allRowIds.slice(i, i + EC_SYNC_BATCH));
    }

    try {
        let taskId = null;
        for (let b = 0; b < batches.length; b++) {
            const body = {
                tempDataTitleId: Number(titleId),
                options: options,
                mode: 'sync',
                rowIds: batches[b]
            };
            // 首批创建任务；后续批次追加到同一任务（复用 taskId）
            if (taskId) {
                body.appendTaskId = taskId;
            }
            if (btn) { btn.textContent = '提交中…(' + (b + 1) + '/' + batches.length + ')'; }
            const task = await api('/external-clean/tasks', { method: 'POST', body: body });
            if (!taskId && task && task.taskId) {
                taskId = task.taskId;
            }
        }
        if (taskId) {
            showToast('同步任务已分批提交[共' + batches.length + '批]：' + taskId + '（请稍候…）', 'success');
            ecLoadTasks(1);
        }
    } catch (e) {
        showToast('提交失败：' + e.message, 'error');
    } finally {
        if (btn) { btn.disabled = false; btn.textContent = '同步提交清洗任务'; }
    }
}

// 任务列表分页
async function ecLoadTasks(page) {
    if (page) ecTaskPage = page;
    const statusSel = $('#ecStatusFilter');
    const status = statusSel ? statusSel.value : '';
    let url = '/external-clean/tasks?page=' + ecTaskPage + '&size=' + EC_TASK_SIZE;
    if (status) url += '&status=' + encodeURIComponent(status);
    if (ecSortField) url += '&sortField=' + encodeURIComponent(ecSortField) + '&sortOrder=' + encodeURIComponent(ecSortOrder);
    try {
        const data = await api(url);
        const records = data.records || [];
        const total = data.total || 0;
        const pages = data.pages || 1;
        const tbody = $('#ecTaskTbody');
        if (records.length === 0) {
            tbody.innerHTML = '<tr><td colspan="8" class="empty-hint">暂无任务</td></tr>';
        } else {
            tbody.innerHTML = records.map(function (t) {
                return '<tr>' +
                    '<td>' + esc(t.taskId) + '</td>' +
                    '<td>' + esc(t.fileName || '-') + '</td>' +
                    '<td>' + ecTaskStatusBadge(t.status) + '</td>' +
                    '<td>' + ecAccText(t.estimatedAccuracy) + '</td>' +
                    '<td>' + formatDate(t.submittedAt) + '</td>' +
                    '<td>' + formatDate(t.completedAt) + '</td>' +
                    '<td>' + ecTaskActions(t) + '</td>' +
                    '<td>' + ecProgressBar(t) + '</td>' +
                    '</tr>';
            }).join('');
        }
        $('#ecTaskPageInfo').textContent = '共 ' + total + ' 条';
        ecRenderTaskPager(pages);
        ecUpdateSortHeaders();
        // 列表中有处于处理中/待提交/待处理的任务时，主动拉取外部进展并回写数据库，
        // 使列表的进度列（processedRows/totalRows）能够实时刷新
        ecRefreshProgressForList(records);
    } catch (e) {
        console.error('加载外部清洗任务失败', e);
    }
}

// 点击表头排序：同一字段再次点击切换正/倒序，不同字段则切换为该字段倒序
function ecSortTasks(field) {
    if (ecSortField === field) {
        ecSortOrder = (ecSortOrder === 'asc') ? 'desc' : 'asc';
    } else {
        ecSortField = field;
        ecSortOrder = 'desc';
    }
    ecUpdateSortHeaders();
    ecLoadTasks(1);
}

// 根据当前排序状态更新表头的三角符号与高亮样式
function ecUpdateSortHeaders() {
    document.querySelectorAll('#ecTaskTable th.sortable').forEach(function (th) {
        th.classList.remove('sorted-asc', 'sorted-desc');
        if (th.getAttribute('data-sort') === ecSortField) {
            th.classList.add(ecSortOrder === 'asc' ? 'sorted-asc' : 'sorted-desc');
        }
    });
}

function ecRenderTaskPager(pages) {
    let html = '';
    html += '<button class="btn btn-sm" ' + (ecTaskPage <= 1 ? 'disabled' : '') + ' onclick="ecLoadTasks(1)">首页</button>';
    html += '<button class="btn btn-sm" ' + (ecTaskPage <= 1 ? 'disabled' : '') + ' onclick="ecLoadTasks(' + (ecTaskPage - 1) + ')">上一页</button>';
    const maxBtns = 5;
    let sp = Math.max(1, ecTaskPage - Math.floor(maxBtns / 2));
    let ep = Math.min(pages, sp + maxBtns - 1);
    if (ep - sp < maxBtns - 1) sp = Math.max(1, ep - maxBtns + 1);
    for (let i = sp; i <= ep; i++) {
        html += '<button class="btn btn-sm ' + (i === ecTaskPage ? 'btn-primary' : '') + '" onclick="ecLoadTasks(' + i + ')">' + i + '</button>';
    }
    html += '<button class="btn btn-sm" ' + (ecTaskPage >= pages ? 'disabled' : '') + ' onclick="ecLoadTasks(' + (ecTaskPage + 1) + ')">下一页</button>';
    html += '<button class="btn btn-sm" ' + (ecTaskPage >= pages ? 'disabled' : '') + ' onclick="ecLoadTasks(' + pages + ')">末页</button>';
    html += ' <span style="font-size:12px;margin-left:8px">每页 ' + EC_TASK_SIZE + ' 条</span>';
    $('#ecTaskPageBtns').innerHTML = html;
}

function ecTaskActions(t) {
    // 状态不为「已完成」时，查看结果按钮不可点击
    const viewDisabled = (t.status !== 'completed') ? 'disabled' : '';
    let h = '<button class="btn btn-sm btn-info" ' + viewDisabled + ' onclick="ecViewRows(\'' + t.taskId + '\')">查看结果</button> ';
    // 进行中：显示「暂停」；已暂停：显示「继续」
    if (t.status === 'processing') {
        h += '<button class="btn btn-sm btn-warning" onclick="ecPauseTask(\'' + t.taskId + '\')">暂停</button> ';
    } else if (t.status === 'paused') {
        h += '<button class="btn btn-sm btn-success" onclick="ecResumeTask(\'' + t.taskId + '\')">继续</button> ';
    }
    if (t.status === 'processing' || t.status === 'submitting' || t.status === 'pending' || t.status === 'queued' || t.status === 'paused') {
        h += '<button class="btn btn-sm btn-default" onclick="ecCancelTask(\'' + t.taskId + '\')">取消</button> ';
    }
    if (t.status === 'failed' || t.status === 'callback_timeout') {
        h += '<button class="btn btn-sm btn-warning" onclick="ecRetryTask(\'' + t.taskId + '\')">重试</button> ';
        h += '<button class="btn btn-sm btn-default" onclick="ecShowError(\'' + t.taskId + '\')">失败原因</button> ';
    }
    h += '<button class="btn btn-sm btn-danger" onclick="ecDeleteTask(\'' + t.taskId + '\')">删除</button> ';
    return h;
}

// 查看任务失败原因（errorMessage）
async function ecShowError(taskId) {
    let msg = '';
    try {
        const detail = await api('/external-clean/tasks/' + encodeURIComponent(taskId));
        msg = (detail && detail.errorMessage) ? detail.errorMessage : '无失败原因信息';
    } catch (e) {
        msg = '获取失败原因失败：' + (e.message || e);
    }
    showModal('失败原因 - ' + taskId, '<div style="white-space:pre-wrap;word-break:break-all;padding:8px 0;color:var(--text-primary)">' + esc(msg) + '</div>');
}

// 查看任务结果（弹出页面）
async function ecViewRows(taskId) {
    ecCurrentTaskId = taskId;
    $('#ecResultTaskId').textContent = '（' + taskId + '）';
    const only = $('#ecResultOnlyReview');
    if (only) only.checked = false;
    ecOnlyReview = false;
    ecRowPage = 1;
    $('#ecResultTbody').innerHTML = '<tr><td colspan="7" class="empty-hint">正在加载…</td></tr>';
    $('#ecResultModal').classList.add('show');
    $('#ecResultOverlay').classList.add('show');
    ecResultOpen = true;
    // 记录当前任务状态，便于定时刷新时判断是否仍需向外部拉取进展
    try {
        const detail = await api('/external-clean/tasks/' + encodeURIComponent(taskId));
        ecCurrentTaskStatus = detail && detail.status ? detail.status : null;
        ecApplyTaskProgress(detail);
    } catch (e) { ecCurrentTaskStatus = null; }
    await ecResultLoadRows(1);
}

function closeEcResultModal() {
    $('#ecResultModal').classList.remove('show');
    $('#ecResultOverlay').classList.remove('show');
    ecResultOpen = false;
    ecResultLoadedTaskId = null; // 关闭后重置，重新打开时再次加载
}

// 结果行分页（弹窗内）
async function ecResultLoadRows(page) {
    if (!ecCurrentTaskId) return;
    if (page) ecRowPage = page;
    const onlyEl = $('#ecResultOnlyReview');
    ecOnlyReview = (onlyEl && onlyEl.checked) ? true : false;
    let url = '/external-clean/tasks/' + encodeURIComponent(ecCurrentTaskId) + '/rows?page=' + ecRowPage + '&size=' + EC_ROW_SIZE;
    if (ecOnlyReview) url += '&needsReview=1';
    try {
        const data = await api(url);
        const records = data.records || [];
        ecRowsData = records;
        const total = data.total || 0;
        const pages = data.pages || 1;
        const tbody = $('#ecResultTbody');
        if (records.length === 0) {
            tbody.innerHTML = '<tr><td colspan="7" class="empty-hint">暂无结果数据</td></tr>';
        } else {
            tbody.innerHTML = records.map(function (r, idx) {
                const seq = (ecRowPage - 1) * EC_ROW_SIZE + idx + 1;
                const cat = (r.categoryCode || '-') + (r.categoryName ? ' / ' + r.categoryName : '');
                return '<tr>' +
                    '<td>' + seq + '</td>' +
                    '<td>' + esc(cat) + '</td>' +
                    '<td>' + confidenceHtml(r.confidence) + '</td>' +
                    '<td>' + ecAttrsFriendlyHtml(r.extractedAttrsJson, r.missingAttrsJson) + '</td>' +
                    '<td>' + (r.needsReview === 1 ? '<span class="badge badge-warning">待复核</span>' : '<span class="badge badge-default">无需</span>') + '</td>' +
                    '<td>' + ecRowStatusBadge(r.rowStatus) + '</td>' +
                    '<td>' + ecResultRowActions(r) + '</td>' +
                    '</tr>';
            }).join('');
        }
        $('#ecResultPageInfo').textContent = '共 ' + total + ' 条';
        ecRenderResultPager(pages);
        // 标记本会话已为该任务加载过完整明细，终态后定时器不再重复拉取
        ecResultLoadedTaskId = ecCurrentTaskId;
    } catch (e) {
        console.error('加载外部清洗结果失败', e);
        $('#ecResultTbody').innerHTML = '<tr><td colspan="7" class="empty-hint">加载失败：' + esc(e.message) + '</td></tr>';
    }
}

function ecRenderResultPager(pages) {
    let html = '';
    html += '<button class="btn btn-sm" ' + (ecRowPage <= 1 ? 'disabled' : '') + ' onclick="ecResultLoadRows(1)">首页</button>';
    html += '<button class="btn btn-sm" ' + (ecRowPage <= 1 ? 'disabled' : '') + ' onclick="ecResultLoadRows(' + (ecRowPage - 1) + ')">上一页</button>';
    const maxBtns = 5;
    let sp = Math.max(1, ecRowPage - Math.floor(maxBtns / 2));
    let ep = Math.min(pages, sp + maxBtns - 1);
    if (ep - sp < maxBtns - 1) sp = Math.max(1, ep - maxBtns + 1);
    for (let i = sp; i <= ep; i++) {
        html += '<button class="btn btn-sm ' + (i === ecRowPage ? 'btn-primary' : '') + '" onclick="ecResultLoadRows(' + i + ')">' + i + '</button>';
    }
    html += '<button class="btn btn-sm" ' + (ecRowPage >= pages ? 'disabled' : '') + ' onclick="ecResultLoadRows(' + (ecRowPage + 1) + ')">下一页</button>';
    html += '<button class="btn btn-sm" ' + (ecRowPage >= pages ? 'disabled' : '') + ' onclick="ecResultLoadRows(' + pages + ')">末页</button>';
    $('#ecResultPageBtns').innerHTML = html;
}

function ecResultRowActions(r) {
    const idx = r.rowIndex;
    let hasMissing = false;
    try { hasMissing = Array.isArray(JSON.parse(r.missingAttrsJson || '[]')) && JSON.parse(r.missingAttrsJson || '[]').length > 0; } catch (e) {}
    let h = '<button class="btn btn-sm btn-default" onclick="ecOpenRawData(' + idx + ')">查看原始数据</button> ';
    if (hasMissing) h += '<button class="btn btn-sm btn-warning" onclick="ecOpenMissing(' + idx + ')">缺失属性</button> ';
    if (r.rowStatus === 'pending' || r.rowStatus === 'completed') {
        h += '<button class="btn btn-sm btn-success" onclick="ecAdoptRow(' + idx + ')">采纳</button> ';
        h += '<button class="btn btn-sm btn-default" onclick="ecRejectRow(' + idx + ')">驳回</button> ';
        h += '<button class="btn btn-sm btn-primary" onclick="ecOpenCorrect(' + idx + ')">修正</button>';
    } else {
        h += '<span style="font-size:12px;color:var(--text-tertiary)">' + esc(r.operatedBy || '') + '</span>';
    }
    return h;
}

// 采纳全部（弹窗内）
async function ecResultAdoptAll() {
    if (!ecCurrentTaskId) return;
    if (!confirm('确认采纳当前任务全部可采纳行？')) return;
    try {
        await api('/external-clean/tasks/' + encodeURIComponent(ecCurrentTaskId) + '/adopt-all', { method: 'POST' });
        showToast('已采纳全部', 'success');
        ecResultLoadRows(ecRowPage);
    } catch (e) { showToast('采纳失败：' + e.message, 'error'); }
}

// 查看原始数据（requestColumnsJson）
function ecOpenRawData(idx) {
    const r = (ecRowsData || []).find(function (x) { return x.rowIndex === idx; }) || {};
    const json = r.requestColumnsJson || '{}';
    let pretty = json;
    try { pretty = JSON.stringify(JSON.parse(json), null, 2); } catch (e) {}
    const body = '' +
        '<div style="margin-bottom:10px;font-size:13px;color:var(--text-secondary)">以下为提交清洗时的原始数据（requestColumnsJson）：</div>' +
        '<pre style="background:var(--bg-tertiary,#f5f6fa);border:1px solid var(--border-color,#e5e7eb);border-radius:8px;padding:14px;max-height:460px;overflow:auto;font-size:13px;line-height:1.6;white-space:pre-wrap;word-break:break-all">' + esc(pretty) + '</pre>' +
        '<div style="display:flex;justify-content:flex-end;margin-top:8px"><button class="btn btn-default" onclick="closeModal()">关闭</button></div>';
    showModal('原始数据 - 行 #' + idx, body);
}

// 缺失属性编辑弹窗
function ecOpenMissing(idx) {
    const r = (ecRowsData || []).find(function (x) { return x.rowIndex === idx; }) || {};
    let missing = [];
    try { missing = JSON.parse(r.missingAttrsJson || '[]'); } catch (e) {}
    if (!Array.isArray(missing) || missing.length === 0) { showToast('该行没有缺失属性', 'info'); return; }
    let existing = {};
    try { existing = JSON.parse(r.extractedAttrsJson || '{}'); } catch (e) {}
    const fields = missing.map(function (key, i) {
        const val = (existing[key] != null) ? existing[key] : '';
        return '<div style="display:flex;gap:10px;align-items:center;margin-bottom:8px">' +
            '<label style="flex:0 0 140px;font-size:13px;color:var(--text-secondary)">' + esc(key) + '</label>' +
            '<input id="ecMissing_' + i + '" data-key="' + esc(key) + '" value="' + esc(val) + '" style="flex:1;padding:6px 8px;border:1px solid var(--border-color,#e5e7eb);border-radius:6px;font-size:13px">' +
            '</div>';
    }).join('');
    const body = '' +
        '<div style="margin-bottom:10px;font-size:13px;color:var(--text-secondary)">请为以下缺失属性填入值，保存后将合并进清洗结果（extractedAttrsJson）。</div>' +
        fields +
        '<div style="display:flex;justify-content:flex-end;gap:8px;margin-top:12px">' +
        '<button class="btn btn-default" onclick="closeModal()">取消</button>' +
        '<button class="btn btn-primary" onclick="ecSubmitMissing(' + idx + ',' + missing.length + ')">保存</button>' +
        '</div>';
    showModal('缺失属性 - 行 #' + idx, body);
}

async function ecSubmitMissing(idx, count) {
    const filled = {};
    for (let i = 0; i < count; i++) {
        const el = $('#ecMissing_' + i);
        if (el) filled[el.getAttribute('data-key')] = el.value;
    }
    try {
        await api('/external-clean/tasks/' + encodeURIComponent(ecCurrentTaskId) + '/rows/' + idx + '/fill-missing', { method: 'POST', body: filled });
        showToast('已填充缺失属性', 'success');
        closeModal();
        ecResultLoadRows(ecRowPage);
    } catch (e) { showToast('保存失败：' + e.message, 'error'); }
}

// 下载结果（按分类分 Sheet 的 Excel）
async function ecDownloadResult() {
    if (!ecCurrentTaskId) return;
    try {
        showToast('正在生成下载文件…', 'info');
        const res = await fetch(API + '/external-clean/tasks/' + encodeURIComponent(ecCurrentTaskId) + '/export');
        if (!res.ok) {
            let msg = '导出失败(' + res.status + ')';
            try { const j = await res.json(); if (j && j.msg) msg = j.msg; } catch (e) {}
            throw new Error(msg);
        }
        const blob = await res.blob();
        if (!blob || blob.size === 0) throw new Error('导出内容为空');
        const ts = new Date().toISOString().slice(0, 19).replace(/[:T]/g, '');
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = 'external_clean_result_' + ecCurrentTaskId + '_' + ts + '.xlsx';
        document.body.appendChild(link);
        link.click();
        link.remove();
        URL.revokeObjectURL(url);
        showToast('下载已开始', 'success');
    } catch (e) { showToast('下载失败：' + e.message, 'error'); }
}

// extractedAttrsJson 用户友好展示；missingJson 为缺失属性数组（可选），用于标记缺失项
function ecAttrsFriendlyHtml(json, missingJson) {
    let m;
    try { m = JSON.parse(json || '{}'); } catch (e) { return '<span style="color:var(--text-tertiary)">' + esc(json || '-') + '</span>'; }
    if (!m || typeof m !== 'object' || Object.keys(m).length === 0) return '<span style="color:var(--text-tertiary)">-</span>';
    let missingSet = [];
    try { missingSet = JSON.parse(missingJson || '[]'); } catch (e) { missingSet = []; }
    const missing = Array.isArray(missingSet) ? missingSet : [];
    const rows = Object.keys(m).map(function (k) {
        const v = m[k];
        const val = (v == null) ? '' : (typeof v === 'object' ? JSON.stringify(v) : String(v));
        const isMissing = missing.indexOf(k) >= 0 || (val === '' && missing.indexOf(k) >= 0);
        const valHtml = isMissing
            ? '<span style="color:#e53e3e;font-weight:600">缺失</span>'
            : esc(val);
        return '<div style="display:flex;gap:8px;padding:3px 0;border-bottom:1px dashed var(--border-color,#eee);align-items:baseline">' +
            '<span style="flex:0 0 120px;color:var(--text-secondary);font-size:12px;word-break:break-all">' + esc(k) +
            (isMissing ? ' <span style="color:#e53e3e">*</span>' : '') + '</span>' +
            '<span style="flex:1;word-break:break-all">' + valHtml + '</span>' +
            '</div>';
    }).join('');
    return '<div style="max-height:200px;overflow:auto">' + rows + '</div>';
}

// 结果行分页
async function ecLoadRows(page) {
    if (!ecCurrentTaskId) return;
    if (page) ecRowPage = page;
    const onlyEl = $('#ecOnlyReview');
    ecOnlyReview = (onlyEl && onlyEl.checked) ? true : false;
    let url = '/external-clean/tasks/' + encodeURIComponent(ecCurrentTaskId) + '/rows?page=' + ecRowPage + '&size=' + EC_ROW_SIZE;
    if (ecOnlyReview) url += '&needsReview=1';
    try {
        const data = await api(url);
        const records = data.records || [];
        ecRowsData = records;
        const total = data.total || 0;
        const pages = data.pages || 1;
        const tbody = $('#ecRowTbody');
        if (records.length === 0) {
            tbody.innerHTML = '<tr><td colspan="9" class="empty-hint">暂无结果数据</td></tr>';
        } else {
            tbody.innerHTML = records.map(function (r, idx) {
                const seq = (ecRowPage - 1) * EC_ROW_SIZE + idx + 1;
                return '<tr>' +
                    '<td>' + seq + '</td>' +
                    '<td title="' + esc(ecRawColumns(r)) + '">' + esc(ecRawColumnsPreview(r)) + '</td>' +
                    '<td>' + esc(r.categoryCode || '-') + '</td>' +
                    '<td>' + esc(r.categoryName || '-') + '</td>' +
                    '<td>' + confidenceHtml(r.confidence) + '</td>' +
                    '<td title="' + esc(ecAttrsText(r.extractedAttrsJson)) + '">' + esc(ecAttrsPreview(r.extractedAttrsJson)) + '</td>' +
                    '<td>' + (r.needsReview === 1 ? '<span class="badge badge-warning">待复核</span>' : '<span class="badge badge-default">无需</span>') + '</td>' +
                    '<td>' + ecRowStatusBadge(r.rowStatus) + '</td>' +
                    '<td>' + ecRowActions(r) + '</td>' +
                    '</tr>';
            }).join('');
        }
        $('#ecRowPageInfo').textContent = '共 ' + total + ' 条';
        ecRenderRowPager(pages);
    } catch (e) {
        console.error('加载外部清洗结果失败', e);
    }
}

function ecRenderRowPager(pages) {
    let html = '';
    html += '<button class="btn btn-sm" ' + (ecRowPage <= 1 ? 'disabled' : '') + ' onclick="ecLoadRows(1)">首页</button>';
    html += '<button class="btn btn-sm" ' + (ecRowPage <= 1 ? 'disabled' : '') + ' onclick="ecLoadRows(' + (ecRowPage - 1) + ')">上一页</button>';
    const maxBtns = 5;
    let sp = Math.max(1, ecRowPage - Math.floor(maxBtns / 2));
    let ep = Math.min(pages, sp + maxBtns - 1);
    if (ep - sp < maxBtns - 1) sp = Math.max(1, ep - maxBtns + 1);
    for (let i = sp; i <= ep; i++) {
        html += '<button class="btn btn-sm ' + (i === ecRowPage ? 'btn-primary' : '') + '" onclick="ecLoadRows(' + i + ')">' + i + '</button>';
    }
    html += '<button class="btn btn-sm" ' + (ecRowPage >= pages ? 'disabled' : '') + ' onclick="ecLoadRows(' + (ecRowPage + 1) + ')">下一页</button>';
    html += '<button class="btn btn-sm" ' + (ecRowPage >= pages ? 'disabled' : '') + ' onclick="ecLoadRows(' + pages + ')">末页</button>';
    html += ' <span style="font-size:12px;margin-left:8px">每页 ' + EC_ROW_SIZE + ' 条</span>';
    $('#ecRowPageBtns').innerHTML = html;
}

function ecRowActions(r) {
    const idx = r.rowIndex;
    let h = '';
    if (r.rowStatus === 'pending' || r.rowStatus === 'completed') {
        h += '<button class="btn btn-sm btn-success" onclick="ecAdoptRow(' + idx + ')">采纳</button> ';
        h += '<button class="btn btn-sm btn-default" onclick="ecRejectRow(' + idx + ')">驳回</button> ';
        h += '<button class="btn btn-sm btn-primary" onclick="ecOpenCorrect(' + idx + ')">修正</button>';
    } else {
        h += '<span style="font-size:12px;color:var(--text-tertiary)">' + esc(r.operatedBy || '') + '</span>';
    }
    return h;
}

async function ecAdoptRow(idx) {
    try {
        await api('/external-clean/tasks/' + encodeURIComponent(ecCurrentTaskId) + '/rows/' + idx + '/adopt', { method: 'POST' });
        showToast('已采纳该行', 'success');
        ecResultLoadRows(ecRowPage);
    } catch (e) { showToast('采纳失败：' + e.message, 'error'); }
}

async function ecAdoptAll() {
    if (!ecCurrentTaskId) return;
    if (!confirm('确认采纳当前任务全部可采纳行？')) return;
    try {
        await api('/external-clean/tasks/' + encodeURIComponent(ecCurrentTaskId) + '/adopt-all', { method: 'POST' });
        showToast('已采纳全部', 'success');
        ecLoadRows(ecRowPage);
    } catch (e) { showToast('采纳失败：' + e.message, 'error'); }
}

async function ecRejectRow(idx) {
    const comment = prompt('请输入驳回原因（可选）：');
    if (comment === null) return;
    try {
        await api('/external-clean/tasks/' + encodeURIComponent(ecCurrentTaskId) + '/rows/' + idx + '/reject?comment=' + encodeURIComponent(comment), { method: 'POST' });
        showToast('已驳回', 'success');
        ecResultLoadRows(ecRowPage);
    } catch (e) { showToast('驳回失败：' + e.message, 'error'); }
}

function ecOpenCorrect(idx) {
    ecCorrectRowIndex = idx;
    const r = (ecRowsData || []).find(function (x) { return x.rowIndex === idx; }) || {};
    const code = r.correctedCategoryCode || r.categoryCode || '';
    const name = r.correctedCategoryName || r.categoryName || '';
    const path = r.correctedCategoryPath || '';
    const attrs = r.correctedAttrsJson || r.extractedAttrsJson || '';
    const comment = r.correctComment || '';
    const body = '' +
        '<div class="form-group" style="margin-bottom:12px"><label>修正分类编码</label><input id="ecCorrectCode" class="form-input" value="' + esc(code) + '"></div>' +
        '<div class="form-group" style="margin-bottom:12px"><label>修正分类名称</label><input id="ecCorrectName" class="form-input" value="' + esc(name) + '"></div>' +
        '<div class="form-group" style="margin-bottom:12px"><label>修正分类路径</label><input id="ecCorrectPath" class="form-input" value="' + esc(path) + '"></div>' +
        '<div class="form-group" style="margin-bottom:12px"><label>修正属性（JSON 对象）</label><textarea id="ecCorrectAttrs" class="cell-edit-input" rows="6" placeholder=\'{"key":"value"}\'>' + esc(attrs) + '</textarea></div>' +
        '<div class="form-group" style="margin-bottom:12px"><label>修正备注</label><input id="ecCorrectComment" class="form-input" value="' + esc(comment) + '"></div>' +
        '<div style="display:flex;justify-content:flex-end;gap:8px">' +
        '<button class="btn btn-default" onclick="closeModal()">取消</button>' +
        '<button class="btn btn-primary" onclick="ecSubmitCorrect()">保存修正</button>' +
        '</div>';
    showModal('修正结果 - 行 #' + idx, body);
}

async function ecSubmitCorrect() {
    const code = $('#ecCorrectCode').value.trim();
    const name = $('#ecCorrectName').value.trim();
    const path = $('#ecCorrectPath').value.trim();
    const attrsText = $('#ecCorrectAttrs').value.trim();
    const comment = $('#ecCorrectComment').value.trim();
    let attrs = null;
    if (attrsText) {
        try { attrs = JSON.parse(attrsText); }
        catch (e) { showToast('属性 JSON 解析失败：' + e.message, 'error'); return; }
    }
    const body = {
        correctedCategoryCode: code || null,
        correctedCategoryName: name || null,
        correctedCategoryPath: path || null,
        correctedAttrs: attrs,
        comment: comment || null
    };
    try {
        await api('/external-clean/tasks/' + encodeURIComponent(ecCurrentTaskId) + '/rows/' + ecCorrectRowIndex + '/correct', { method: 'POST', body: body });
        showToast('已修正', 'success');
        closeModal();
        ecResultLoadRows(ecRowPage);
    } catch (e) { showToast('修正失败：' + e.message, 'error'); }
}

async function ecCancelTask(taskId) {
    if (!confirm('确认取消任务 ' + taskId + '？')) return;
    try {
        await api('/external-clean/tasks/' + encodeURIComponent(taskId) + '/cancel', { method: 'POST' });
        showToast('已取消', 'success');
        ecLoadTasks(ecTaskPage);
    } catch (e) { showToast('取消失败：' + e.message, 'error'); }
}

async function ecRetryTask(taskId) {
    if (!confirm('确认重试任务 ' + taskId + '？')) return;
    try {
        await api('/external-clean/tasks/' + encodeURIComponent(taskId) + '/retry', { method: 'POST' });
        showToast('已重新提交', 'success');
        ecLoadTasks(ecTaskPage);
    } catch (e) { showToast('重试失败：' + e.message, 'error'); }
}

async function ecDeleteTask(taskId) {
    if (!confirm('确认删除任务 ' + taskId + '？该操作将一并删除其关联的结果行与回调日志，且不可恢复。')) return;
    try {
        await api('/external-clean/tasks/' + encodeURIComponent(taskId), { method: 'DELETE' });
        showToast('已删除', 'success');
        ecLoadTasks(ecTaskPage);
    } catch (e) { showToast('删除失败：' + e.message, 'error'); }
}

// 暂停清洗任务（调用外部接口 /api/v1/clean/{task_id}/pause）
async function ecPauseTask(taskId) {
    if (!confirm('确认暂停任务 ' + taskId + '？')) return;
    try {
        await api('/v1/clean/' + encodeURIComponent(taskId) + '/pause', { method: 'POST' });
        showToast('已暂停', 'success');
        ecLoadTasks(ecTaskPage);
    } catch (e) { showToast('暂停失败：' + e.message, 'error'); }
}

// 继续清洗任务（调用外部接口 /api/v1/clean/{task_id}/resume）
async function ecResumeTask(taskId) {
    if (!confirm('确认继续任务 ' + taskId + '？')) return;
    try {
        await api('/v1/clean/' + encodeURIComponent(taskId) + '/resume', { method: 'POST' });
        showToast('已继续', 'success');
        ecLoadTasks(ecTaskPage);
    } catch (e) { showToast('继续失败：' + e.message, 'error'); }
}

function ecStartAutoRefresh() {
    if (ecAutoTimer) clearInterval(ecAutoTimer);
    ecAutoTimer = setInterval(function () {
        const page = $('#page-externalclean');
        if (!page || !page.classList.contains('active')) return;
        ecLoadTasks(ecTaskPage);
        const modalOpen = $('#modal') && $('#modal').classList.contains('show');
        if (!ecCurrentTaskId || modalOpen) return;

        const isTerminal = ecCurrentTaskStatus === 'completed' || ecCurrentTaskStatus === 'failed'
            || ecCurrentTaskStatus === 'cancelled' || ecCurrentTaskStatus === 'callback_timeout';

        if (ecResultOpen) {
            // 结果视图：非终态不重复拉取行数据（进展由进度条体现）；
            // 终态且本会话尚未为该任务加载过完整明细时，补加载一次，之后停止轮询明细
            if (!isTerminal) {
                ecRefreshProgress(ecCurrentTaskId);
            } else if (ecResultLoadedTaskId !== ecCurrentTaskId) {
                ecResultLoadRows(ecRowPage);
            }
        } else {
            // 列表视图：ecLoadTasks 内部已对非终态任务批量拉取外部进展；
            // 仅当任务已终态时做一次本地行数据展示
            if (isTerminal) ecLoadRows(ecRowPage);
        }
    }, 5000);
}

// 主动触发外部进展查询（POST /tasks/{taskId}/progress），并将返回的统计应用到页面
async function ecRefreshProgress(taskId) {
    try {
        const task = await api('/external-clean/tasks/' + encodeURIComponent(taskId) + '/progress', { method: 'POST' });
        if (task) {
            ecCurrentTaskStatus = task.status || ecCurrentTaskStatus;
            ecApplyTaskProgress(task);
        }
    } catch (e) {
        // 拉取失败不影响正常行数据刷新
        console.warn('查询外部任务进展失败', e);
    }
}

// 列表视图：对处于非终态的任务批量触发外部进展查询（不阻塞列表渲染）
function ecRefreshProgressForList(records) {
    if (!records || !records.length) return;
    const pending = records.filter(function (t) {
        const s = t.status;
        return s === 'processing' || s === 'submitting' || s === 'pending'
            || s === 'submitted' || s === 'running';
    });
    pending.forEach(function (t) {
        // 异步触发，更新 DB 后下一轮 ecLoadTasks 会自动体现
        api('/external-clean/tasks/' + encodeURIComponent(t.taskId) + '/progress', { method: 'POST' })
            .catch(function () {});
    });
}

// 将任务统计（total/processed 等）回显到页面进度区域
function ecApplyTaskProgress(task) {
    if (!task) return;
    const totalEl = $('#ecTaskTotalRows');
    const doneEl = $('#ecTaskProcessedRows');
    const accEl = $('#ecTaskAccuracy');
    const barEl = $('#ecTaskProgressBar');
    if (totalEl) totalEl.textContent = (task.totalRows == null ? '-' : task.totalRows);
    if (doneEl) doneEl.textContent = (task.processedRows == null ? 0 : task.processedRows);
    if (accEl && task.estimatedAccuracy != null) accEl.textContent = (task.estimatedAccuracy * 100).toFixed(1) + '%';
    if (barEl) {
        const total = task.totalRows || 0;
        const done = task.processedRows || 0;
        const pct = total > 0 ? Math.min(100, Math.round(done * 100 / total)) : 0;
        barEl.style.width = pct + '%';
    }
}

// ===== 展示辅助 =====
function ecTaskStatusBadge(s) {
    const map = { pending: 'badge-default', submitting: 'badge-info', processing: 'badge-info', queued: 'badge-default', paused: 'badge-warning', completed: 'badge-success', failed: 'badge-danger', cancelled: 'badge-default', callback_timeout: 'badge-warning' };
    const label = { pending: '待提交', submitting: '提交中', processing: '处理中', queued: '队列中', paused: '已暂停', completed: '已完成', failed: '失败', cancelled: '已取消', callback_timeout: '回调超时' };
    return '<span class="badge ' + (map[s] || 'badge-default') + '">' + (label[s] || s) + '</span>';
}
function ecRowStatusBadge(s) {
    const map = { pending: 'badge-default', completed: 'badge-success', accepted: 'badge-success', corrected: 'badge-info', rejected: 'badge-danger', skipped: 'badge-default' };
    const label = { pending: '待处理', completed: '已完成', accepted: '已采纳', corrected: '已修正', rejected: '已驳回', skipped: '已跳过' };
    return '<span class="badge ' + (map[s] || 'badge-default') + '">' + (label[s] || s) + '</span>';
}
function ecProgressText(t) {
    if (t.totalRows == null) return '-';
    const done = t.processedRows == null ? 0 : t.processedRows;
    return done + ' / ' + t.totalRows;
}
function ecAccText(v) {
    if (v == null) return '-';
    if (v <= 1) return Math.round(v * 100) + '%';
    return v + '%';
}
// 任务列表「执行进度」列：可视化进度条，每个任务执行时均可看到
function ecProgressBar(t) {
    const total = t.totalRows || 0;
    const done = t.processedRows == null ? 0 : t.processedRows;
    // 终态且未拿到总数时按 100% 展示，避免空进度条
    const isTerminal = t.status === 'completed' || t.status === 'failed'
        || t.status === 'cancelled' || t.status === 'callback_timeout';
    const pct = total > 0 ? Math.min(100, Math.round(done * 100 / total))
        : (isTerminal ? 100 : 0);
    const label = total > 0 ? (done + ' / ' + total) : (isTerminal ? '完成' : '等待中');
    return '<div style="display:flex;align-items:center;gap:8px;min-width:120px">' +
        '<div style="flex:1;height:8px;background:var(--bg-tertiary);border-radius:6px;overflow:hidden">' +
        '<div style="height:100%;width:' + pct + '%;background:linear-gradient(90deg,#4f8cff,#42d392);transition:width .4s"></div>' +
        '</div>' +
        '<span style="font-size:12px;color:var(--text-secondary);white-space:nowrap">' + label + '</span>' +
        '</div>';
}
function ecRawColumns(r) {
    try {
        const m = JSON.parse(r.requestColumnsJson || '{}');
        return Object.keys(m).map(function (k) { return k + ': ' + m[k]; }).join('；');
    } catch (e) { return r.requestColumnsJson || '-'; }
}
function ecRawColumnsPreview(r) {
    const s = ecRawColumns(r);
    return s.length > 60 ? s.substring(0, 60) + '…' : s;
}
function ecAttrsText(json) {
    try {
        const m = JSON.parse(json || '{}');
        return Object.keys(m).map(function (k) { return k + '=' + m[k]; }).join('；');
    } catch (e) { return json || '-'; }
}
function ecAttrsPreview(json) {
    const s = ecAttrsText(json);
    return s.length > 50 ? s.substring(0, 50) + '…' : s;
}

// ==================== 角色管理 ====================
let rolePageState = { page: 1, size: 10, total: 0, pages: 1, keyword: '' };
let roleCache = {};
let editingRoleId = null;

function queryRoles(page) {
    const input = $('#roleSearchInput');
    rolePageState.keyword = input ? input.value.trim() : '';
    loadRoles(page || 1);
}

function resetRoleSearch() {
    const input = $('#roleSearchInput');
    if (input) input.value = '';
    rolePageState.keyword = '';
    loadRoles(1);
}

async function loadRoles(page) {
    if (page) rolePageState.page = page;
    const { page: curPage, size, keyword } = rolePageState;
    try {
        const qs = 'page=' + curPage + '&size=' + size
            + (keyword ? '&keyword=' + encodeURIComponent(keyword) : '');
        const data = await api('/roles?' + qs);
        rolePageState.total = data.total || 0;
        rolePageState.pages = data.pages || 1;
        renderRoleTable(data.records || []);
        updateRolePagination();
    } catch (e) {
        showToast('加载角色列表失败: ' + e.message, 'error');
    }
}

function renderRoleTable(roles) {
    const tbody = $('#roleTbody');
    const count = $('#roleRecordCount');
    if (!tbody) return;
    if (!roles || roles.length === 0) {
        tbody.innerHTML = '<tr><td colspan="9" class="empty-hint">暂无角色</td></tr>';
        if (count) count.textContent = '共 0 条';
        return;
    }
    if (count) count.textContent = '共 ' + rolePageState.total + ' 条';
    roleCache = {};
    const start = (rolePageState.page - 1) * rolePageState.size;
    tbody.innerHTML = roles.map(function (r, i) {
        roleCache[r.id] = r;
        const builtIn = r.builtIn === 1;
        const statusBadge = r.status === 1
            ? '<span class="badge badge-success">启用</span>'
            : '<span class="badge badge-danger">禁用</span>';
        const nameCell = esc(r.roleName) + (builtIn ? ' <span class="badge badge-info">内置</span>' : '');
        const toggleBtn = r.status === 1
            ? '<button class="btn btn-sm btn-warning" ' + (builtIn ? 'disabled title="内置角色不可禁用"' : '') + ' onclick="toggleRoleStatus(' + r.id + ',0)">禁用</button>'
            : '<button class="btn btn-sm btn-success" onclick="toggleRoleStatus(' + r.id + ',1)">启用</button>';
        const delBtn = builtIn
            ? '<button class="btn btn-sm btn-danger" disabled title="内置角色不可删除">删除</button>'
            : '<button class="btn btn-sm btn-danger" onclick="deleteRole(' + r.id + ')">删除</button>';
        return '<tr data-id="' + r.id + '">'
            + '<td style="text-align:center;color:var(--text-secondary)">' + (start + i + 1) + '</td>'
            + '<td>' + r.id + '</td>'
            + '<td><code>' + esc(r.roleCode) + '</code></td>'
            + '<td>' + nameCell + '</td>'
            + '<td style="text-align:center">' + (r.userCount || 0) + '</td>'
            + '<td style="text-align:center">' + (r.permCount || 0) + '</td>'
            + '<td>' + esc(r.description || '-') + '</td>'
            + '<td>' + statusBadge + '</td>'
            + '<td><div class="action-btn-group">'
            + '<button class="btn btn-sm btn-primary" onclick="openRoleModal(' + r.id + ')">编辑</button>'
            + '<button class="btn btn-sm btn-default" onclick="gotoRolePermission(\'' + esc(r.roleCode) + '\')">配置权限</button>'
            + '<button class="btn btn-sm btn-default" onclick="viewRoleUsers(\'' + esc(r.roleCode) + '\',\'' + esc(r.roleName) + '\')">查看用户</button>'
            + toggleBtn
            + delBtn
            + '</div></td>'
            + '</tr>';
    }).join('');
}

function updateRolePagination() {
    const { page, size, total, pages } = rolePageState;
    if ($('#rolePageInfo')) $('#rolePageInfo').textContent = '共 ' + total + ' 条';
    if ($('#roleCurPage')) $('#roleCurPage').textContent = page;
    if ($('#roleTotalPages')) $('#roleTotalPages').textContent = pages;
    const box = $('#rolePageBtns');
    if (!box) return;
    let html = '';
    html += '<button class="btn btn-sm" ' + (page <= 1 ? 'disabled' : '') + ' onclick="loadRoles(1)">首页</button>';
    html += '<button class="btn btn-sm" ' + (page <= 1 ? 'disabled' : '') + ' onclick="loadRoles(' + (page - 1) + ')">上一页</button>';
    const maxBtns = 5;
    let startPage = Math.max(1, page - Math.floor(maxBtns / 2));
    let endPage = Math.min(pages, startPage + maxBtns - 1);
    if (endPage - startPage < maxBtns - 1) startPage = Math.max(1, endPage - maxBtns + 1);
    for (let i = startPage; i <= endPage; i++) {
        html += '<button class="btn btn-sm ' + (i === page ? 'btn-primary' : '') + '" onclick="loadRoles(' + i + ')">' + i + '</button>';
    }
    html += '<button class="btn btn-sm" ' + (page >= pages ? 'disabled' : '') + ' onclick="loadRoles(' + (page + 1) + ')">下一页</button>';
    html += '<button class="btn btn-sm" ' + (page >= pages ? 'disabled' : '') + ' onclick="loadRoles(' + pages + ')">末页</button>';
    html += ' <span style="font-size:12px;margin-left:8px">每页 ' + size + ' 条</span>';
    box.innerHTML = html;
}

// 新建/编辑角色弹窗
function openRoleModal(id) {
    editingRoleId = id || null;
    const r = id ? roleCache[id] : null;
    const isEdit = !!r;
    const builtIn = isEdit && r.builtIn === 1;
    const html = ''
        + '<div class="form-group" style="margin-bottom:14px">'
        + '<label>角色编码</label>'
        + '<input type="text" id="rRoleCode" class="form-input" value="' + (isEdit ? esc(r.roleCode) : '') + '"'
        + ' placeholder="字母开头，可含数字下划线，如 auditor" ' + (builtIn ? 'readonly' : '') + '>'
        + (builtIn ? '<small style="color:var(--text-tertiary)">内置角色编码不可修改</small>' : '')
        + '</div>'
        + '<div class="form-group" style="margin-bottom:14px">'
        + '<label>角色名称</label>'
        + '<input type="text" id="rRoleName" class="form-input" value="' + (isEdit ? esc(r.roleName) : '') + '" placeholder="如 审核员">'
        + '</div>'
        + '<div class="form-group" style="margin-bottom:14px">'
        + '<label>角色描述</label>'
        + '<input type="text" id="rDescription" class="form-input" value="' + (isEdit ? esc(r.description || '') : '') + '" placeholder="角色职责说明">'
        + '</div>'
        + '<div style="display:flex;gap:12px;margin-bottom:20px">'
        + '<div class="form-group" style="flex:1;margin:0"><label>排序号</label>'
        + '<input type="number" id="rSort" class="form-input" value="' + (isEdit ? (r.sort == null ? 99 : r.sort) : 99) + '"></div>'
        + '<div class="form-group" style="flex:1;margin:0"><label>状态</label>'
        + '<select id="rStatus" class="form-input">'
        + '<option value="1" ' + (!isEdit || r.status === 1 ? 'selected' : '') + '>启用</option>'
        + '<option value="0" ' + (isEdit && r.status === 0 ? 'selected' : '') + '>禁用</option>'
        + '</select></div>'
        + '</div>'
        + '<div style="display:flex;justify-content:flex-end;gap:8px">'
        + '<button class="btn btn-default" onclick="closeModal()">取消</button>'
        + '<button class="btn btn-primary" onclick="saveRole()">确定</button>'
        + '</div>';
    showModal(isEdit ? '编辑角色' : '新建角色', html);
}

async function saveRole() {
    const roleCode = $('#rRoleCode').value.trim();
    const roleName = $('#rRoleName').value.trim();
    const description = $('#rDescription').value.trim();
    const sort = parseInt($('#rSort').value) || 99;
    const status = parseInt($('#rStatus').value);
    if (!roleCode) { showToast('请输入角色编码', 'error'); return; }
    if (!roleName) { showToast('请输入角色名称', 'error'); return; }
    const body = { roleCode, roleName, description, sort, status };
    try {
        if (editingRoleId) {
            await api('/roles/' + editingRoleId, { method: 'PUT', body });
            showToast('更新成功');
        } else {
            await api('/roles', { method: 'POST', body });
            showToast('创建成功');
        }
        closeModal();
        loadRoles(editingRoleId ? rolePageState.page : 1);
    } catch (e) {
        showToast(e.message || '保存失败', 'error');
    }
}

async function deleteRole(id) {
    if (!confirm('确定删除该角色？删除后其权限配置也会一并清除。')) return;
    try {
        await api('/roles/' + id, { method: 'DELETE' });
        showToast('删除成功');
        loadRoles(rolePageState.page);
    } catch (e) {
        showToast(e.message || '删除失败', 'error');
    }
}

async function toggleRoleStatus(id, status) {
    try {
        await api('/roles/' + id + '/status?status=' + status, { method: 'POST' });
        showToast(status === 1 ? '已启用' : '已禁用');
        loadRoles(rolePageState.page);
    } catch (e) {
        showToast(e.message || '操作失败', 'error');
    }
}

// 从角色列表跳转到权限配置页，并自动选中该角色
// 用 _pendingPermRole 传递目标角色，避免 switchPage 的 loader 与此处重复加载
let _pendingPermRole = null;
function gotoRolePermission(roleCode) {
    _pendingPermRole = roleCode;
    switchPage('permission');
}

// 查看某角色下已分配的用户
async function viewRoleUsers(roleCode, roleName) {
    try {
        const users = await api('/roles/' + encodeURIComponent(roleCode) + '/users') || [];
        let body;
        if (users.length === 0) {
            body = '<p class="empty-hint" style="padding:24px">该角色下暂无用户</p>';
        } else {
            body = '<div class="table-container"><table class="data-table"><thead><tr>'
                + '<th style="width:70px">用户ID</th><th>用户名</th><th>姓名</th><th style="width:80px">状态</th>'
                + '</tr></thead><tbody>'
                + users.map(function (u) {
                    return '<tr><td>' + u.userId + '</td><td>' + esc(u.username) + '</td>'
                        + '<td>' + esc(u.realName || '-') + '</td>'
                        + '<td>' + (u.status === 1
                            ? '<span class="badge badge-success">启用</span>'
                            : '<span class="badge badge-danger">禁用</span>') + '</td></tr>';
                }).join('')
                + '</tbody></table></div>';
        }
        body += '<div style="display:flex;justify-content:flex-end;margin-top:16px">'
            + '<button class="btn btn-default" onclick="closeModal()">关闭</button></div>';
        showModal('角色「' + esc(roleName) + '」下的用户（' + users.length + '）', body);
    } catch (e) {
        showToast('查询失败: ' + e.message, 'error');
    }
}

// 给用户分配角色弹窗（从用户管理页调用）
async function openAssignRoleModal(userId) {
    const u = userCache[userId];
    try {
        const roles = await api('/roles/enabled') || [];
        const owned = await api('/users/' + userId + '/roles') || [];
        if (roles.length === 0) {
            showModal('分配角色', '<p class="empty-hint" style="padding:24px">暂无可用角色，请先到「角色管理」创建。</p>'
                + '<div style="display:flex;justify-content:flex-end"><button class="btn btn-default" onclick="closeModal()">关闭</button></div>');
            return;
        }
        const list = roles.map(function (r) {
            return '<label class="perm-item" style="display:flex;align-items:center;gap:8px;padding:8px 0">'
                + '<input type="checkbox" class="assign-role-cb" value="' + esc(r.roleCode) + '" '
                + (owned.indexOf(r.roleCode) >= 0 ? 'checked' : '') + '>'
                + '<span style="min-width:120px">' + esc(r.roleName) + '</span>'
                + '<code class="perm-code">' + esc(r.roleCode) + '</code>'
                + '<span style="color:var(--text-tertiary);font-size:12px">' + esc(r.description || '') + '</span>'
                + '</label>';
        }).join('');
        const html = '<p style="color:var(--text-secondary);font-size:13px;margin-bottom:8px">'
            + '为用户 <strong>' + esc(u ? u.username : userId) + '</strong> 选择角色（可多选，保存后立即生效，用户需重新登录刷新权限）</p>'
            + '<div style="max-height:320px;overflow-y:auto;border:1px solid var(--border-color);border-radius:6px;padding:8px">'
            + list + '</div>'
            + '<div style="display:flex;justify-content:flex-end;gap:8px;margin-top:16px">'
            + '<button class="btn btn-default" onclick="closeModal()">取消</button>'
            + '<button class="btn btn-primary" onclick="submitAssignRoles(' + userId + ')">保存</button>'
            + '</div>';
        showModal('分配角色', html);
    } catch (e) {
        showToast('加载角色失败: ' + e.message, 'error');
    }
}

async function submitAssignRoles(userId) {
    const codes = Array.from(document.querySelectorAll('.assign-role-cb:checked')).map(function (cb) { return cb.value; });
    if (codes.length === 0) { showToast('请至少选择一个角色', 'error'); return; }
    try {
        await api('/roles/user/' + userId + '/assign', { method: 'POST', body: codes });
        showToast('角色已分配');
        closeModal();
        loadUsers(userPageState.page);
    } catch (e) {
        showToast(e.message || '分配失败', 'error');
    }
}

// ==================== 权限配置 ====================
let permissionModuleMap = {};            // 全部权限（模块 -> 权限列表）
let permissionRoleChecked = new Set();   // 当前角色已勾选的权限ID

// 初始化权限配置页：填充角色下拉框后加载权限
// @param preferRoleCode 可选，指定默认选中的角色编码
async function initPermissionPage(preferRoleCode) {
    const sel = $('#permRoleSelect');
    if (!sel) return;
    try {
        const roles = await api('/permissions/roles') || [];
        if (roles.length === 0) {
            sel.innerHTML = '<option value="">暂无角色</option>';
            $('#permissionConfigArea').innerHTML =
                '<p class="empty-hint" style="padding:40px">暂无角色，请先到「角色管理」创建角色。</p>';
            return;
        }
        sel.innerHTML = roles.map(function (r) {
            return '<option value="' + escapeHtml(r.roleCode) + '">'
                + escapeHtml(r.roleName) + '（' + escapeHtml(r.roleCode) + '）</option>';
        }).join('');
        // 优先选中指定角色，否则保留原选中项
        if (preferRoleCode && roles.some(function (r) { return r.roleCode === preferRoleCode; })) {
            sel.value = preferRoleCode;
        }
        await loadPermissionConfig();
    } catch (e) {
        sel.innerHTML = '<option value="">加载失败</option>';
        showToast('加载角色列表失败：' + e.message, 'error');
    }
}

// 加载权限配置：先拉全部权限，再拉当前角色已分配权限
async function loadPermissionConfig() {
    const sel = $('#permRoleSelect');
    const roleCode = sel ? sel.value : '';
    const area = $('#permissionConfigArea');
    if (!area) return;
    if (!roleCode) {
        area.innerHTML = '<p class="empty-hint" style="padding:40px">请先选择角色</p>';
        return;
    }
    area.innerHTML = '<p class="empty-hint" style="padding:40px">加载中…</p>';
    try {
        const all = await api('/permissions/all');
        permissionModuleMap = all || {};
        const checkedIds = await api('/permissions/role?roleCode=' + encodeURIComponent(roleCode));
        permissionRoleChecked = new Set(checkedIds || []);
        renderPermissionConfig(roleCode);
    } catch (e) {
        area.innerHTML = '<p style="text-align:center;padding:40px;color:var(--danger-color,#e54545)">加载失败：' + escapeHtml(e.message) + '</p>';
    }
}

// 全选 / 清空当前角色的权限勾选
function togglePermAll(checked) {
    document.querySelectorAll('#permissionConfigArea .perm-checkbox').forEach(function (cb) {
        cb.checked = !!checked;
    });
    updatePermCheckedCount();
}

// 更新"已选 N 项"计数
function updatePermCheckedCount() {
    const el = $('#permCheckedCount');
    if (!el) return;
    const n = document.querySelectorAll('#permissionConfigArea .perm-checkbox:checked').length;
    const total = document.querySelectorAll('#permissionConfigArea .perm-checkbox').length;
    el.textContent = '已选 ' + n + ' / ' + total + ' 项';
}

// 渲染按模块分组的权限表格
function renderPermissionConfig(roleCode) {
    const area = $('#permissionConfigArea');
    const modules = Object.keys(permissionModuleMap);
    if (modules.length === 0) {
        area.innerHTML = '<p class="empty-hint" style="padding:40px">暂无权限数据，请先执行 permission-init SQL 初始化权限点。</p>';
        return;
    }
    let html = '<div class="table-container"><table class="data-table"><thead><tr>'
        + '<th style="width:160px">模块</th>'
        + '<th>权限功能</th>'
        + '</tr></thead><tbody>';
    modules.forEach(function (module) {
        const perms = permissionModuleMap[module] || [];
        html += '<tr><td rowspan="' + perms.length + '" style="font-weight:600;vertical-align:top">' + escapeHtml(module) + '</td>';
        perms.forEach(function (p, idx) {
            if (idx > 0) html += '<tr>';
            const checked = permissionRoleChecked.has(p.id) ? 'checked' : '';
            html += '<td>'
                + '<label class="perm-item">'
                + '<input type="checkbox" class="perm-checkbox" value="' + p.id + '" ' + checked + '> '
                + '<span>' + escapeHtml(p.permName || '') + '</span>'
                + '<code class="perm-code">' + escapeHtml(p.permCode || '') + '</code>'
                + '</label></td></tr>';
        });
    });
    html += '</tbody></table></div>';
    area.innerHTML = html;

    // 勾选变化时实时更新计数
    area.querySelectorAll('.perm-checkbox').forEach(function (cb) {
        cb.addEventListener('change', updatePermCheckedCount);
    });
    updatePermCheckedCount();
}

// 保存当前角色的权限配置
async function savePermissionConfig() {
    const roleCode = $('#permRoleSelect') ? $('#permRoleSelect').value : '';
    if (!roleCode) { showToast('请先选择角色', 'error'); return; }
    const boxes = document.querySelectorAll('#permissionConfigArea .perm-checkbox:checked');
    const permIds = Array.from(boxes).map(function (b) { return Number(b.value); });
    try {
        await api('/permissions/role/assign?roleCode=' + encodeURIComponent(roleCode), {
            method: 'POST',
            body: permIds
        });
        showToast('权限配置已保存', 'success');
    } catch (e) {
        showToast('保存失败：' + e.message, 'error');
    }
}

// ==================== 操作日志 ====================
let opLogPageState = { page: 1, size: 15, total: 0, pages: 1 };

async function loadOpLogs(page) {
    if (page) opLogPageState.page = page;
    const { page: curPage, size } = opLogPageState;
    const username = $('#opLogUsername') ? $('#opLogUsername').value.trim() : '';
    const action = $('#opLogAction') ? $('#opLogAction').value : '';
    const module = $('#opLogModule') ? $('#opLogModule').value : '';
    const startTime = $('#opLogStartTime') ? $('#opLogStartTime').value.trim() : '';
    const endTime = $('#opLogEndTime') ? $('#opLogEndTime').value.trim() : '';
    const params = [];
    params.push('page=' + curPage);
    params.push('size=' + size);
    if (username) params.push('username=' + encodeURIComponent(username));
    if (action) params.push('action=' + encodeURIComponent(action));
    if (module) params.push('module=' + encodeURIComponent(module));
    if (startTime) params.push('startTime=' + encodeURIComponent(startTime));
    if (endTime) params.push('endTime=' + encodeURIComponent(endTime));
    try {
        const data = await api('/operation-logs?' + params.join('&'));
        renderOpLogTable(data.records || []);
        opLogPageState.total = data.total || 0;
        opLogPageState.pages = data.pages || 1;
        $('#opLogPageInfo').textContent = '共 ' + opLogPageState.total + ' 条';
        renderOpLogPagination();
    } catch (e) {
        showToast('加载操作日志失败：' + e.message, 'error');
    }
}

function renderOpLogTable(list) {
    const tbody = $('#opLogTbody');
    if (!list || list.length === 0) {
        tbody.innerHTML = '<tr><td colspan="8" class="empty-hint">暂无数据</td></tr>';
        return;
    }
    const actionMap = {
        'upload': '文件上传', 'delete': '删除数据', 'clean': '启动清洗', 'export': '导出结果',
        'user:create': '新增用户', 'user:update': '编辑用户', 'user:delete': '删除用户',
        'user:assignRole': '分配角色', 'role:create': '新建角色', 'role:update': '编辑角色',
        'role:delete': '删除角色', 'perm:assign': '分配权限'
    };
    let html = '';
    list.forEach(function (log) {
        const status = log.status === 1
            ? '<span class="badge badge-success">成功</span>'
            : '<span class="badge badge-danger">失败</span>';
        const duration = log.duration != null ? (log.duration + ' ms') : '-';
        const actionLabel = actionMap[log.action] || escapeHtml(log.action || '-');
        html += '<tr>'
            + '<td>' + escapeHtml(formatDate(log.operateTime)) + '</td>'
            + '<td>' + escapeHtml(log.realName || log.username || '-') + '</td>'
            + '<td>' + escapeHtml(log.module || '-') + '</td>'
            + '<td>' + actionLabel + '</td>'
            + '<td>' + escapeHtml(log.actionDesc || '-') + '</td>'
            + '<td>' + status + '</td>'
            + '<td>' + duration + '</td>'
            + '<td>' + (log.errorMsg ? escapeHtml(log.errorMsg) : '-') + '</td>'
            + '</tr>';
    });
    tbody.innerHTML = html;
}

function renderOpLogPagination() {
    const btns = $('#opLogPageBtns');
    if (!btns) return;
    const { page, pages, size, total } = opLogPageState;
    let html = '<button class="page-btn" ' + (page <= 1 ? 'disabled' : '') + ' onclick="loadOpLogs(1)">首页</button>';
    html += '<button class="page-btn" ' + (page <= 1 ? 'disabled' : '') + ' onclick="loadOpLogs(' + (page - 1) + ')">上一页</button>';
    html += '<span class="page-now">第 ' + page + ' / ' + pages + ' 页</span>';
    html += '<button class="page-btn" ' + (page >= pages ? 'disabled' : '') + ' onclick="loadOpLogs(' + (page + 1) + ')">下一页</button>';
    html += '<button class="page-btn" ' + (page >= pages ? 'disabled' : '') + ' onclick="loadOpLogs(' + pages + ')">尾页</button>';
    btns.innerHTML = html;
}

function resetOpLogFilter() {
    if ($('#opLogUsername')) $('#opLogUsername').value = '';
    if ($('#opLogAction')) $('#opLogAction').value = '';
    if ($('#opLogModule')) $('#opLogModule').value = '';
    if ($('#opLogStartTime')) $('#opLogStartTime').value = '';
    if ($('#opLogEndTime')) $('#opLogEndTime').value = '';
    opLogPageState.page = 1;
    loadOpLogs(1);
}
