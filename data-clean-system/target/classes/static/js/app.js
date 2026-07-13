/**
 * AI Clean 数据清洗系统 - 前端应用
 */
const API = '/api';
let currentTitleId = null;
let currentExtraTitleId = null;

// ==================== 工具函数 ====================

function $(sel) { return document.querySelector(sel); }
function $$(sel) { return document.querySelectorAll(sel); }

async function api(url, options = {}) {
    const config = {
        headers: { 'Content-Type': 'application/json' },
        ...options,
    };
    if (config.body && typeof config.body === 'object' && !(config.body instanceof FormData)) {
        config.body = JSON.stringify(config.body);
    }
    if (config.body instanceof FormData) {
        delete config.headers['Content-Type'];
    }
    const res = await fetch(API + url, config);
    const data = await res.json();
    if (data.code !== 200) {
        throw new Error(data.msg || '请求失败');
    }
    return data.data;
}

function showToast(msg, type = 'success') {
    const toast = $('#toast');
    toast.textContent = msg;
    toast.className = `toast ${type} show`;
    setTimeout(() => toast.classList.remove('show'), 3000);
}

function showModal(title, bodyHtml) {
    $('#modalTitle').textContent = title;
    $('#modalBody').innerHTML = bodyHtml;
    $('#modal').classList.add('show');
    $('#modalOverlay').classList.add('show');
}

function closeModal() {
    $('#modal').classList.remove('show');
    $('#modalOverlay').classList.remove('show');
}

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
        'draft':'badge-default','needs_review':'badge-warning','reviewing':'badge-info',
        'approved':'badge-success','rejected':'badge-danger','modified':'badge-info',
        'export_ready':'badge-success','processed':'badge-info','completed':'badge-success',
    };
    return `<span class="badge ${map[status]||'badge-default'}">${status}</span>`;
}

function confidenceHtml(val) {
    if (!val && val !== 0) return '-';
    const pct = Math.round(val * 100);
    const cls = pct >= 80 ? 'confidence-high' : pct >= 50 ? 'confidence-mid' : 'confidence-low';
    return `<div style="display:flex;align-items:center;gap:6px"><span>${pct}%</span><div class="confidence-bar" style="flex:1"><div class="confidence-fill ${cls}" style="width:${pct}%"></div></div></div>`;
}

// ==================== 页面切换 ====================

function switchPage(name) {
    $$('.page').forEach(p => p.classList.remove('active'));
    $$('.nav-item').forEach(n => n.classList.remove('active'));
    $(`#page-${name}`).classList.add('active');
    $(`.nav-item[data-page="${name}"]`).classList.add('active');

    const loaders = {
        'import': () => { loadTitles(); },                          // 刷新导入文件列表
        'rule': () => { loadRules(); },                               // 刷新解析规则列表
        'extract': () => { loadTitles(); loadRules(); loadExtraTitles(); loadTitlesForSelect('extractTitleId'); loadRulesForSelect('extractRuleId'); },  // 刷新提取相关数据
        'clean': () => { loadTitles(); loadRules(); loadCleanStats(); loadTitlesForSelect('cleanTitleId'); loadRulesForSelect('cleanRuleId'); },     // 刷新清洗相关数据
        'mapping': () => { 
            loadTitlesForSelect('mapTitleId'); 
            loadExtraTitlesForSelect('mapExtraTitleId'); 
            loadStandardTitles('mapStandardTitleId'); 
        },
        'result': async () => { 
            // 先填充文件下拉框，再联动过滤标准/补充表头，避免读到空值而加载全部
            await loadTitlesForSelect('resultTitleId'); 
            await onResultTitleChange(); 
            // 如果已有选中数据，自动刷新结果数据
            const standardTitleId = $('#resultStandardTitleId').value;
            if (standardTitleId) reloadResultData(standardTitleId);
        },
        'search': () => { loadCategories(); },
        'standard': () => { loadStandardTitleList(); },              // 刷新标准字段表头列表
        'unmapped': () => { loadTitlesForUnmapped(); },
    };
    if (loaders[name]) loaders[name]();
}

// 数据文件变更时的联动过滤
async function onResultTitleChange() {
    const titleId = $('#resultTitleId').value;
    if (!titleId) {
        // 未选择数据文件，加载全部
        loadStandardTitles('resultStandardTitleId');
        loadExtraTitlesForSelect('resultExtraTitleId');
        return;
    }
    // 根据选中的数据文件过滤标准字段表头
    await loadStandardTitles('resultStandardTitleId', titleId);
    // 根据选中的数据文件过滤补充数据表头
    await loadExtraTitlesForSelect('resultExtraTitleId', titleId);
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
    const area = $('#uploadArea');
    area.addEventListener('dragover', e => { e.preventDefault(); area.classList.add('drag-over'); });
    area.addEventListener('dragleave', () => area.classList.remove('drag-over'));
    area.addEventListener('drop', e => {
        e.preventDefault();
        area.classList.remove('drag-over');
        const file = e.dataTransfer.files[0];
        if (file) uploadFile(file);
    });
    switchPage('import');
});

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

// ==================== 解析规则 ====================

async function loadRules() {
    try {
        const rules = await api('/cleaning/parse-rules/active');
        const tbody = $('#ruleTbody');
        if (!rules || rules.length === 0) {
            tbody.innerHTML = '<tr><td colspan="6" class="empty-hint">暂无规则，请创建</td></tr>';
            return;
        }
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
    } catch (e) {
        showToast('加载规则失败: ' + e.message, 'error');
    }
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
        loadRules();
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
        loadRules();
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

async function loadTitlesForSelect(selId) {
    try {
        const titles = await api('/import/titles');
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
            filtered.map(et => `<option value="${et.id}">补充表头#${et.id} (关联数据ID:${et.tempDataTitleId})</option>`).join('');
    } catch (e) {
        console.error('加载补充数据表头失败:', e);
        sel.innerHTML = '<option value="">-- 请选择 --</option>';
    }
}

async function loadStandardTitles(selId, titleId) {
    const sel = $(`#${selId}`);
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
    } catch (e) {
        console.error('加载标准字段表头失败:', e);
        sel.innerHTML = '<option value="">-- 请选择 --</option>';
    }
}

// 初始化下拉框
async function loadTitlesForExtract() {
    await loadTitlesForSelect('extractTitleId');
    await loadRulesForSelect('extractRuleId');
}

async function loadExtraTitles() {
    try {
        const extraTitles = await api('/cleaning/extra-titles');
        const tbody = $('#extraTbody');
        if (!extraTitles || extraTitles.length === 0) {
            tbody.innerHTML = '<tr><td colspan="5" class="empty-hint">暂无提取结果，请先进行全描述属性提取</td></tr>';
            return;
        }
        tbody.innerHTML = extraTitles.map(et => `
            <tr>
                <td>${et.id}</td>
                <td>${et.tempDataTitleId || '-'}</td>
                <td>${et.parseRuleId || '-'}</td>
                <td>${buildExtraColSummary(et)}</td>
                <td><button class="btn btn-sm btn-primary" onclick="viewExtraData(${et.id})">查看</button>
                    <button class="btn btn-sm btn-danger" onclick="deleteExtraTitle(${et.id})">删除</button></td>
            </tr>
        `).join('');
    } catch (e) {
        console.error('加载提取结果失败:', e);
    }
}

function buildExtraColSummary(et) {
    const cols = [];
    for (let i = 1; i <= 20; i++) {
        const title = et['col' + i + 'Title'];
        if (title) cols.push(title);
    }
    return cols.length > 0 ? cols.slice(0, 5).join(', ') + (cols.length > 5 ? '...' : '') : '-';
}

async function deleteExtraTitle(id) {
    if (!confirm('确定要删除该全描述提取结果及其所有补充数据吗？此操作不可恢复。')) return;
    showLoading('正在删除提取结果…');
    try {
        await api(`/cleaning/extra-title/${id}`, { method: 'DELETE' });
        showToast('已删除全描述提取结果');
        loadExtraTitles();
    } catch (e) {
        showToast('删除失败: ' + e.message, 'error');
    } finally {
        hideLoading();
    }
}

async function viewExtraData(extraTitleId) {
    showModal('查看补充数据', '<p style="text-align:center;padding:40px;color:var(--text-secondary)">加载中...</p>');
    try {
        const list = await api(`/cleaning/extra-data/${extraTitleId}`);
        const extraTitle = await api(`/cleaning/extra-titles`);
        const titleInfo = extraTitle.find(et => et.id === extraTitleId);

        const headers = [];
        for (let i = 1; i <= 20; i++) {
            const ct = titleInfo ? titleInfo['col' + i + 'Title'] : null;
            if (ct) headers.push(ct);
        }

        let html = '';
        if (titleInfo) {
            html += `<div class="view-data-info"><span><strong>关联数据ID:</strong> ${titleInfo.tempDataTitleId || '-'}</span><span><strong>总行数:</strong> ${list.length}</span></div>`;
        }

        if (!list || list.length === 0) {
            html += '<p class="empty-hint">暂无补充数据</p>';
        } else {
            html += '<div class="table-scroll"><table class="data-table"><thead><tr><th>行号</th>';
            headers.forEach(h => { html += `<th>${h || '-'}</th>`; });
            html += '</tr></thead><tbody>';
            list.forEach((row, idx) => {
                html += `<tr><td>${idx + 1}</td>`;
                for (let i = 1; i <= headers.length; i++) {
                    html += `<td>${(row['col' + i] || '')}</td>`;
                }
                html += '</tr>';
            });
            html += '</tbody></table></div>';
        }

        $('#modalBody').innerHTML = html;
    } catch (e) {
        $('#modalBody').innerHTML = `<p style="text-align:center;padding:40px;color:var(--danger)">加载失败: ${e.message}</p>`;
    }
}

async function extractExtraData() {
    const titleId = $('#extractTitleId').value;
    const ruleId = $('#extractRuleId').value;
    if (!titleId || !ruleId) { showToast('请选择数据文件和解析规则', 'warning'); return; }

    showLoading('正在提取全描述属性…');
    try {
        const formData = new FormData();
        formData.append('titleId', titleId);
        formData.append('parseRuleId', ruleId);
        const res = await fetch(API + `/cleaning/extract-extra?titleId=${titleId}&parseRuleId=${ruleId}`, { method: 'POST' });
        const data = await res.json();
        if (data.code !== 200) throw new Error(data.msg);

        currentExtraTitleId = data.data.id;
        $('#extractResult').style.display = 'block';
        $('#extractResult').innerHTML = `<p class="badge badge-success">提取成功！提取到 ${data.data.id ? '多个' : '0个'} 属性</p>`;
        showToast('全描述属性提取完成');
        loadExtraTitles();
        // 刷新其他页面的下拉框
        loadExtraTitlesForSelect('mapExtraTitleId');
    } catch (e) {
        showToast('提取失败: ' + e.message, 'error');
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

async function startCleaning() {
    const titleId = $('#cleanTitleId').value;
    const ruleId = $('#cleanRuleId').value;
    if (!titleId || !ruleId) { showToast('请选择数据文件和解析规则', 'warning'); return; }

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
        $('#cleanLiveTbody').innerHTML = '<tr><td colspan="7" class="empty-hint">连接中…</td></tr>';
    $('#cleanStatus').style.display = 'block';
    $('#cleanStatus').innerHTML = '<p style="font-size:13px;color:var(--text-secondary)">正在连接清洗服务…</p>';

    // 先连接 WebSocket
    connectWebSocket(titleId, function connected() {
        // WebSocket 连接成功后，调用清洗 API
        $('#cleanStatus').innerHTML = '<p style="font-size:13px;color:var(--accent)">清洗任务已启动，正在处理…</p>';
        fetch(API + `/cleaning/start?titleId=${titleId}&parseRuleId=${ruleId}`, { method: 'POST' })
            .then(res => res.json())
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
        $('#cleanLiveTbody').innerHTML = '<tr><td colspan="7" class="empty-hint">实时连接失败，清洗在后台进行中…</td></tr>';
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
        $('#cleanLiveTbody').innerHTML = '';
    } else if (type === 'progress') {
        // 追加一行清洗结果
        const rowData = msg.data;
        if (rowData) {
            appendCleanRow(current, rowData);
        }
        $('#cleanStatus').innerHTML = '<p style="color:var(--accent);font-size:13px">清洗中… ' + current + '/' + total + ' (成功 ' + success + ', 失败 ' + error + ')</p>';
    } else if (type === 'complete') {
        $('#cleanStatus').innerHTML = '<p style="color:var(--success);font-size:13px">清洗完成，共处理 ' + total + ' 条 (成功 ' + success + ', 失败 ' + error + ')</p>';
        showToast('数据清洗完成');
        loadCleanStats();
        // 延迟断开
        setTimeout(disconnectWebSocket, 2000);
    } else if (type === 'error') {
        $('#cleanStatus').innerHTML = '<p style="color:var(--danger);font-size:13px">清洗异常终止，已处理 ' + current + '/' + total + ' 条</p>';
        showToast('清洗异常终止', 'error');
        loadCleanStats();
        setTimeout(disconnectWebSocket, 2000);
    }
}

function appendCleanRow(index, data) {
    const tbody = $('#cleanLiveTbody');
    // 移除空提示行
    if (tbody.firstElementChild && tbody.firstElementChild.querySelector('.empty-hint')) {
        tbody.innerHTML = '';
    }

    const tr = document.createElement('tr');
    const score = data.qualityScore != null ? data.qualityScore.toFixed(1) : '-';
    const statusText = statusCleanText(data.status);
    const scoreClass = data.qualityScore != null ? (data.qualityScore >= 80 ? 'badge-success' : data.qualityScore >= 60 ? 'badge-warning' : 'badge-danger') : '';

    tr.innerHTML = 
        '<td>' + index + '</td>' +
        '<td>' + (data.materialCode || '-') + '</td>' +
        '<td title="' + (data.materialName || '') + '">' + (data.materialName ? (data.materialName.length > 20 ? data.materialName.substring(0, 20) + '...' : data.materialName) : '-') + '</td>' +
        '<td>' + (data.specification || '-') + '</td>' +
        '<td>' + (data.categoryCode || '-') + '</td>' +
        '<td><span class="badge ' + scoreClass + '">' + score + '</span></td>' +
        '<td>' + statusBadge(statusText) + '</td>';

    // 最新数据插到顶部
    tbody.insertBefore(tr, tbody.firstChild);
}

function statusCleanText(status) {
    const map = {
        'EXPORT_READY': '可导出', 'APPROVED': '已审核',
        'NEEDS_REVIEW': '待审核', 'PROCESSED': '已处理',
        'REJECTED': '已驳回', 'DRAFT': '草稿'
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

        // Step 2: 通过 WebSocket 显示填充进度
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
                .then(fillRes => fillRes.json())
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
                .then(fillRes => fillRes.json())
                .then(fillData => {
                    if (fillData.code !== 200) throw new Error(fillData.msg);
                    showToast('所有标准表头结果数据填充完成！');
                    loadFieldMappings();
                })
                .catch(e2 => {
                    showToast('填充失败: ' + e2.message, 'error');
                });
        });
    } catch (e) {
        showToast('映射或填充失败: ' + e.message, 'error');
        hideLoading();
    }
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
            .then(res => res.json())
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
    if (!standardTitleId) { showToast('请先选择标准字段表头', 'warning'); return; }

    showLoading('正在下载数据…');
    try {
        // 先获取总数
        const condition = { page: 1, pageSize: 1, standardTitleId: parseInt(standardTitleId) };
        const total = await api('/cleaning/result-data/count', { method: 'POST', body: condition });
        if (!total || total === 0) {
            showToast('没有可下载的数据', 'warning');
            hideLoading();
            return;
        }

        // 一次性获取全部数据
        const allCondition = { page: 1, pageSize: total, standardTitleId: parseInt(standardTitleId) };
        const allResults = await api('/cleaning/result-data/search', { method: 'POST', body: allCondition });

        if (!allResults || allResults.length === 0) {
            showToast('没有可下载的数据', 'warning');
            hideLoading();
            return;
        }

        // 仅获取当前选中的标准字段表头（避免全量请求，全量接口为 N+1 查询，慢）
        let selectedStandard = null;
        if (_standardTitlesCache) {
            selectedStandard = _standardTitlesCache.find(s => s.id == standardTitleId) || null;
        }
        if (!selectedStandard) {
            selectedStandard = await api(`/cleaning/standard-title/${standardTitleId}`).catch(() => null);
        }
        let standardCols = [];
        if (selectedStandard) {
            for (let i = 1; i <= 20; i++) {
                const title = selectedStandard['colTitle' + i];
                if (title) standardCols.push({ key: 'col' + i, title: title });
            }
        }

        if (standardCols.length === 0) {
            for (let i = 1; i <= 20; i++) {
                standardCols.push({ key: 'col' + i, title: '列' + i });
            }
        }

        // 构建CSV内容
        const headers = ['ID', '标准表头', '状态', ...standardCols.map(c => c.title)];
        const rows = [headers];

        // 加载标准表头映射
        const standardTitleMap = {};
        if (_standardTitlesCache) {
            _standardTitlesCache.forEach(s => {
                standardTitleMap[s.id] = s.categoryCode || ('标准表头#' + s.id);
            });
        }
        if (selectedStandard) standardTitleMap[selectedStandard.id] = selectedStandard.categoryCode || ('标准表头#' + selectedStandard.id);

        allResults.forEach(r => {
            const row = [
                r.id || '',
                standardTitleMap[r.standardTitleId] || '',
                r.status || '',
                ...standardCols.map(c => (r[c.key] || '').toString())
            ];
            rows.push(row);
        });

        // 生成CSV字符串
        const csvContent = rows.map(row =>
            row.map(cell => {
                const val = String(cell);
                if (val.includes(',') || val.includes('"') || val.includes('\n')) {
                    return '"' + val.replace(/"/g, '""') + '"';
                }
                return val;
            }).join(',')
        ).join('\n');

        // 添加BOM以确保Excel正确识别中文
        const blob = new Blob(['\uFEFF' + csvContent], { type: 'text/csv;charset=utf-8;' });
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        const now = new Date();
        const timestamp = now.getFullYear() + ('0' + (now.getMonth() + 1)).slice(-2) + ('0' + now.getDate()).slice(-2) + '_' +
            ('0' + now.getHours()).slice(-2) + ('0' + now.getMinutes()).slice(-2) + ('0' + now.getSeconds()).slice(-2);
        link.download = 'result_data_' + timestamp + '.csv';
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        URL.revokeObjectURL(url);

        showToast('下载完成，共 ' + allResults.length + ' 条数据');
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

async function openManualFillModal() {
    const standardTitleId = $('#resultStandardTitleId').value;
    const titleId = $('#resultTitleId').value;

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

    // 构建下拉选项 HTML
    const noneOption = '<option value="">-- 不映射 --</option>';
    let dataFileOptions = '';
    dataFileCols.forEach(c => {
        dataFileOptions += `<option value="temp_data|${c.title.replace(/"/g, '&quot;')}">${c.title}</option>`;
    });
    let extraDataOptions = '';
    extraDataCols.forEach(c => {
        extraDataOptions += `<option value="extra_data|${c.title.replace(/"/g, '&quot;')}">${c.title}</option>`;
    });

    const allSourceOptions = noneOption;
    const groupedOptions = dataFileOptions + extraDataOptions;

    // 构建映射查找表：targetField -> {sourceField, sourceType}
    const mappingMap = {};
    existingMappings.forEach(m => {
        if (m.targetField) {
            mappingMap[m.targetField] = { sourceField: m.sourceField, sourceType: m.sourceType };
        }
    });

    let html = '<table class="mf-table"><thead><tr>';
    html += '<th style="width:180px">标准字段</th>';
    html += '<th>映射来源</th>';
    html += '</tr></thead><tbody>';

    standardFields.forEach(sf => {
        const existing = mappingMap[sf.title];
        html += '<tr>';
        html += `<td><strong>${sf.title}</strong>${sf.isMust ? '<span class="mf-required-badge">*必填</span>' : ''}</td>`;
        html += '<td><select class="mf-source-select" data-target="' + sf.title.replace(/"/g, '&quot;') + '">';
        html += noneOption;

        // 数据文件列分组
        if (dataFileCols.length > 0) {
            html += '<optgroup label="── 数据文件列 ──">';
            dataFileCols.forEach(c => {
                const val = 'temp_data|' + c.title.replace(/"/g, '&quot;');
                const selected = existing && existing.sourceType === 'temp_data' && existing.sourceField === c.title ? ' selected' : '';
                html += `<option value="${val}"${selected}>${c.title}</option>`;
            });
            html += '</optgroup>';
        }

        // 补充数据列分组
        if (extraDataCols.length > 0) {
            html += '<optgroup label="── 补充数据列 ──">';
            extraDataCols.forEach(c => {
                const val = 'extra_data|' + c.title.replace(/"/g, '&quot;');
                const selected = existing && existing.sourceType === 'extra_data' && existing.sourceField === c.title ? ' selected' : '';
                html += `<option value="${val}"${selected}>${c.title}</option>`;
            });
            html += '</optgroup>';
        }

        html += '</select></td>';
        html += '</tr>';
    });

    html += '</tbody></table>';
    $('#mfTableContainer').innerHTML = html;
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

    const selects = $$('.mf-source-select');
    let exactCount = 0;
    let containsCount = 0;
    let fuzzyCount = 0;
    let skippedCount = 0;

    selects.forEach(sel => {
        const targetField = sel.getAttribute('data-target');
        if (!targetField) return;

        const normalizedTarget = normalizeForMatch(targetField);

        // 1. 精确匹配（去除空格后忽略大小写），与后端 findBestFieldMatch 一致
        const exactMatch = allSourceCols.find(c => c.normalized === normalizedTarget);
        if (exactMatch) {
            sel.value = `${exactMatch.sourceType}|${exactMatch.title}`;
            exactCount++;
            return;
        }

        // 2. 包含匹配：源字段包含目标字段 或 目标字段包含源字段（与后端一致）
        const containsMatch = allSourceCols.find(c =>
            c.normalized.length > 0 && (
                c.normalized.includes(normalizedTarget) || normalizedTarget.includes(c.normalized)
            )
        );
        if (containsMatch) {
            sel.value = `${containsMatch.sourceType}|${containsMatch.title}`;
            containsCount++;
            return;
        }

        // 3. 前两步都未匹配 → 用编辑距离做模糊推荐（仅限前端自动映射的补充能力）
        let bestMatch = null;
        let bestScore = 0;

        for (const col of allSourceCols) {
            const score = stringSimilarity(targetField, col.title);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = col;
            }
        }

        // 相似度阈值 >= 0.4 才推荐，避免乱匹配
        if (bestMatch && bestScore >= 0.4) {
            sel.value = `${bestMatch.sourceType}|${bestMatch.title}`;
            fuzzyCount++;
        } else {
            // 无法匹配时保留现有的映射值，不覆盖
            skippedCount++;
        }
    });

    let msg = `自动映射完成：完全匹配 ${exactCount} 个`;
    if (containsCount > 0) msg += `，包含匹配 ${containsCount} 个`;
    if (fuzzyCount > 0) msg += `，相似推荐 ${fuzzyCount} 个`;
    if (skippedCount > 0) msg += `，未匹配 ${skippedCount} 个（保留原映射）`;
    showToast(msg);
}

function collectMappingsFromSelects() {
    const selects = $$('.mf-source-select');
    const mappings = [];
    selects.forEach(sel => {
        const targetField = sel.getAttribute('data-target');
        const val = sel.value;
        if (val) {
            const parts = val.split('|');
            mappings.push({
                sourceType: parts[0],
                sourceField: parts.slice(1).join('|'),
                targetField: targetField,
            });
        }
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

    if (mappings.length === 0) {
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
        const [standardTitles, allMappings] = await Promise.all([
            api('/cleaning/standard-titles'),
            loadExistingMappings(null, titleId, null),
        ]);

        // 按 standardTitleId 分组映射
        const mappingMap = {};
        allMappings.forEach(m => {
            if (m.standardTitleId) {
                if (!mappingMap[m.standardTitleId]) mappingMap[m.standardTitleId] = [];
                mappingMap[m.standardTitleId].push(m);
            }
        });

        $('#mfStatusCard').style.display = 'block';
        const tbody = $('#mfStatusTbody');

        if (!standardTitles || standardTitles.length === 0) {
            tbody.innerHTML = '<tr><td colspan="5" class="empty-hint">暂无标准字段表头</td></tr>';
            return;
        }

        // 只显示与当前数据文件有关联映射的标准表头（避免用户对无关标准表头配置后填充全部跳过）
        const relevantIds = Object.keys(mappingMap);
        const relevantTitles = relevantIds.length > 0
            ? standardTitles.filter(st => mappingMap[st.id])
            : [];

        if (relevantTitles.length === 0) {
            tbody.innerHTML = `<tr><td colspan="5" class="empty-hint">
                暂无关联映射，请先在<a href="javascript:void(0)" onclick="switchPage('mapping')" style="color:var(--accent);text-decoration:underline">字段映射页面</a>执行"自动映射字段"
            </td></tr>`;
            // 仍然展示所有标准表头供参考（折叠）
            return;
        }

        tbody.innerHTML = relevantTitles.map(st => {
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

    $('#resultThead').innerHTML = '<tr><th>ID</th><th>标准表头</th><th>状态</th>' +
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
            ${standardCols.map((c, i) => `<td class="editable-cell" ondblclick="editResultCell(${r.id}, ${i+1}, '${(r[c.key]||'').replace(/'/g,"\\'")}')">${r[c.key] || ''}</td>`).join('')}
            <td><button class="btn btn-sm btn-primary" onclick="reviewResult(${r.id})">审核</button></td>
        </tr>
    `).join('');
}

function editResultCell(id, colIndex, currentValue) {
    const newValue = prompt('修改数据:', currentValue);
    if (newValue === null) return;
    updateResultData(id, colIndex, newValue);
}

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

async function searchData() {
    const condition = {
        materialCode: $('#searchCode').value || null,
        materialName: $('#searchName').value || null,
        specification: $('#searchSpec').value || null,
        qualityScoreMin: $('#searchScoreMin').value ? parseFloat($('#searchScoreMin').value) : null,
        page: parseInt($('#searchPage').value) || 1,
        pageSize: 20,
    };
    if ($('#searchCatCode').value) {
        condition.categoryPathPrefix = $('#searchCatCode').value;
    }

    showLoading('正在搜索数据…');
    try {
        const [results, count] = await Promise.all([
            api('/cleaning/cleaned-data/search', { method: 'POST', body: condition }),
            api('/cleaning/cleaned-data/count', { method: 'POST', body: condition }),
        ]);

        $('#searchResultCard').style.display = 'block';
        $('#searchCount').textContent = count || 0;

        if (!results || results.length === 0) {
            $('#searchTbody').innerHTML = '<tr><td colspan="9" class="empty-hint">未找到匹配数据</td></tr>';
            return;
        }
        $('#searchTbody').innerHTML = results.map(r => `
            <tr>
                <td>${r.id}</td>
                <td>${r.materialCode || '-'}</td>
                <td>${r.materialName || '-'}</td>
                <td>${r.specification || '-'}</td>
                <td>${r.categoryCode || '-'}</td>
                <td>${r.matchSource || '-'}</td>
                <td>${r.matchConfidence != null ? (r.matchConfidence * 100).toFixed(0) + '%' : '-'}</td>
                <td>${r.qualityScore != null ? r.qualityScore.toFixed(1) : '-'}</td>
                <td>${statusBadge(r.status)}</td>
            </tr>
        `).join('');
    } catch (e) {
        showToast('搜索失败: ' + e.message, 'error');
    } finally {
        hideLoading();
    }
}

// ==================== 标准字段表头管理 ====================

const MAX_STD_COLS = 20;
let standardTitlesCache = []; // 缓存标准字段列表用于搜索

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
    loadStandardTitlesTable();
}

async function loadStandardTitlesTable() {
    try {
        const titles = await api('/cleaning/standard-titles');
        standardTitlesCache = titles || [];
        renderStandardTable(standardTitlesCache);
    } catch (e) {
        showToast('加载标准字段表头失败: ' + e.message, 'error');
    }
}

function renderStandardTable(titles) {
    const tbody = document.getElementById('standardTbody');
    const recordCount = document.getElementById('standardRecordCount');
    
    if (!titles || titles.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6" class="empty-hint">暂无数据，请点击"新建表头"创建</td></tr>';
        if (recordCount) recordCount.textContent = '共 0 条记录';
        return;
    }
    
    if (recordCount) recordCount.textContent = `共 ${titles.length} 条记录`;
    
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
            <td style="text-align:center;color:var(--text-secondary)">${index + 1}</td>
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

function filterStandardList() {
    const keyword = document.getElementById('standardSearchInput').value.toLowerCase().trim();
    if (!keyword) {
        renderStandardTable(standardTitlesCache);
        return;
    }
    const filtered = standardTitlesCache.filter(st => 
        (st.categoryCode && st.categoryCode.toLowerCase().includes(keyword)) ||
        (st.id && st.id.toString().includes(keyword))
    );
    renderStandardTable(filtered);
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
        loadStandardTitlesTable();
        // 刷新其他页面的下拉框
        invalidateStandardTitlesCache();
        loadStandardTitles('mapStandardTitleId');
        loadStandardTitles('resultStandardTitleId');
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
        loadStandardTitlesTable();
        // 刷新下拉框
        invalidateStandardTitlesCache();
        loadStandardTitles('mapStandardTitleId');
        loadStandardTitles('resultStandardTitleId');
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

// ==================== 初始化 ====================

document.addEventListener('DOMContentLoaded', () => {
    // 初始化下拉框
    const initSelects = async () => {
        await loadRulesForSelect('extractRuleId');
        await loadRulesForSelect('cleanRuleId');
        await loadTitlesForSelect('extractTitleId');
        await loadTitlesForSelect('cleanTitleId');
        await loadTitlesForSelect('mapTitleId');
        await loadTitlesForSelect('resultTitleId');
    };
    initSelects().catch(console.error);
});
