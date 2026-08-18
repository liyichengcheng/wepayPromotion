/* 管理员后台主逻辑 */

const API_PREFIX = '';  // context-path 已含 /api

/* ========== 登录态 ========== */
function getAuthHeader() {
    const creds = localStorage.getItem('admin_auth');
    if (!creds) {
        location.href = './login.html';
        throw new Error('not logged in');
    }
    return 'Basic ' + creds;
}

function doLogout() {
    if (confirm('确定退出登录？')) {
        localStorage.removeItem('admin_auth');
        location.href = './login.html';
    }
}

/* ========== 通用 AJAX ========== */
function api(path, options = {}) {
    const headers = Object.assign({
        'Authorization': getAuthHeader(),
        'Content-Type': 'application/json'
    }, options.headers || {});
    const init = Object.assign({ headers }, options);
    if (init.body && typeof init.body === 'object') {
        init.body = JSON.stringify(init.body);
    }
    return fetch(API_PREFIX + path, init).then(async res => {
        const text = await res.text();
        let data;
        try { data = JSON.parse(text); } catch (_) { data = { _raw: text }; }
        if (res.status === 401) {
            localStorage.removeItem('admin_auth');
            location.href = './login.html';
            return Promise.reject(new Error('未登录或登录已过期'));
        }
        if (!res.ok) {
            const msg = (data && data.message) || (data && data.msg) || ('请求失败 ' + res.status + ': ' + (data && data._raw ? data._raw : ''));
            return Promise.reject(new Error(msg));
        }
        return data;
    });
}

function showToast(msg, type) {
    const old = document.querySelector('.toast');
    if (old) old.remove();
    const toast = document.createElement('div');
    toast.className = 'toast toast-' + (type || 'info');
    toast.textContent = msg;
    document.body.appendChild(toast);
    setTimeout(() => toast.remove(), 2500);
}

/* ========== Tab 切换 ========== */
let currentTab = 'pending';

function switchTab(tab) {
    currentTab = tab;
    document.querySelectorAll('.tab-item').forEach(el => {
        el.classList.toggle('active', el.dataset.tab === tab);
    });
    document.querySelectorAll('.tab-pane').forEach(el => {
        el.style.display = 'none';
    });
    document.getElementById('tab-' + tab).style.display = '';
    if (tab === 'pending') loadPendingList();
    if (tab === 'all') loadAllList();
    if (tab === 'query') loadProcessingList();
}

/* ========== 状态渲染 ========== */
function statusText(status) {
    const map = {
        0: '待处理',
        1: '处理中',
        2: '已成功',
        3: '已失败',
        4: '待审核'
    };
    return map[status] !== undefined ? map[status] : ('状态' + status);
}

function fenToYuan(fen) {
    if (fen === null || fen === undefined) return '0.00';
    return (Number(fen) / 100).toFixed(2);
}

function formatDate(str) {
    if (!str) return '-';
    return new Date(str).toLocaleString('zh-CN', { hour12: false });
}

/* ========== 表格渲染 ========== */
function renderTable(containerId, list, opts) {
    opts = opts || {};
    const showReview = !!opts.showReview;
    const showQuery = !!opts.showQuery;
    const showTrace = !!opts.showTrace; // 每条后面：重查微信状态 + 重新发起提现

    const box = document.getElementById(containerId);
    if (!list || list.length === 0) {
        box.innerHTML = `<div class="empty"><div class="empty-icon">📭</div>暂无数据</div>`;
        return;
    }
    let html = `<div class="table-wrap"><table><thead><tr>
        <th>ID</th>
        <th>用户openid</th>
        <th>金额</th>
        <th>状态</th>
        <th>transferNo</th>
        <th>申请时间</th>
        <th>更新时间</th>
        <th>操作</th>
    </tr></thead><tbody>`;
    for (const item of list) {
        html += `<tr>
            <td>${item.id}</td>
            <td style="max-width:160px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-family:monospace;font-size:12px;" title="${item.openid}">${item.openid || '-'}</td>
            <td><span class="amount-text">${fenToYuan(item.amount)}</span></td>
            <td><span class="status-tag status-${item.status}">${statusText(item.status)}</span></td>
            <td style="max-width:180px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-family:monospace;font-size:12px;" title="${item.transferNo || ''}">${item.transferNo || '-'}</td>
            <td>${formatDate(item.applyTime)}</td>
            <td>${formatDate(item.updateTime)}</td>
            <td>
                <div class="btn-group">
                    ${showReview ? renderReviewButtons(item) : ''}
                    ${showQuery ? renderQueryButtons(item) : ''}
                    ${showTrace ? renderTraceButtons(item) : ''}
                </div>
            </td>
        </tr>`;
    }
    html += '</tbody></table></div>';
    box.innerHTML = html;
}

function renderReviewButtons(item) {
    if (item.status === 4) {
        return `
            <button class="btn-action btn-approve" onclick="openApprove(${item.id}, '${item.openid}', ${item.amount})">审核通过</button>
            <button class="btn-action btn-reject" onclick="openReject(${item.id}, '${item.openid}', ${item.amount})">拒绝</button>
        `;
    }
    return '';
}

function renderQueryButtons(item) {
    let html = '';
    if ((item.status === 1 || item.status === 0) && item.transferNo) {
        html += `<button class="btn-action btn-query" onclick="quickQueryTransfer(${item.id}, '${item.openid}')">查状态</button>`;
    }
    return html;
}

/** 转账状态追踪列表里每行的两个按钮 */
function renderTraceButtons(item) {
    return `
        <button class="btn-action btn-query" onclick="quickQueryAndShow(${item.id}, '${item.openid}')">重查微信状态</button>
    `;
}

/* ========== 加载列表 ========== */
function loadPendingList() {
    api('/api/admin/withdraw/pending').then(data => {
        const list = (data && data.data) || data || [];
        document.getElementById('pendingCount').textContent = list.length;
        renderTable('pendingTable', list, { showReview: true, showQuery: true });
    }).catch(err => {
        document.getElementById('pendingTable').innerHTML =
            `<div class="empty" style="color:#b91c1c;">加载失败：${err.message}</div>`;
        showToast(err.message, 'error');
    });
}

function loadAllList() {
    api('/api/admin/withdraw/list').then(data => {
        const list = (data && data.data) || data || [];
        renderTable('allTable', list, { showReview: true, showQuery: true });
    }).catch(err => {
        document.getElementById('allTable').innerHTML =
            `<div class="empty" style="color:#b91c1c;">加载失败：${err.message}</div>`;
        showToast(err.message, 'error');
    });
}

function loadProcessingList() {
    api('/api/admin/withdraw/processing').then(data => {
        const list = (data && data.data) || data || [];
        document.getElementById('processingCount').textContent = list.length;
        // 转账状态追踪Tab: 每行2个按钮 重查微信状态 + 重新发起提现
        renderTable('processingTable', list, { showTrace: true });
    }).catch(err => {
        document.getElementById('processingTable').innerHTML =
            `<div class="empty" style="color:#b91c1c;">加载失败：${err.message}</div>`;
        showToast(err.message, 'error');
    });
}

/* ========== 弹窗 ========== */
function openModal(title, bodyHtml, footerActions) {
    const container = document.getElementById('modalContainer');
    container.innerHTML = `<div class="modal-mask" onclick="if(event.target===this)closeModal()">
        <div class="modal-box">
            <div class="modal-header"><div class="modal-title">${title}</div></div>
            <div class="modal-body">${bodyHtml}</div>
            <div class="modal-footer">
                <button class="modal-btn modal-btn-cancel" onclick="closeModal()">取消</button>
                ${footerActions}
            </div>
        </div>
    </div>`;
}

function closeModal() {
    document.getElementById('modalContainer').innerHTML = '';
}

/* ========== 审核 ========== */
function openApprove(id, openid, amount) {
    openModal('审核通过 - 确认打款',
        `<div class="modal-notice">⚠️ 审核通过后将立即调用微信企业付款接口，将 ¥${fenToYuan(amount)} 转账到该用户微信零钱。</div>
         <div class="modal-info">
            <div class="modal-info-row"><span class="modal-info-label">提现单ID</span><span class="modal-info-value">#${id}</span></div>
            <div class="modal-info-row"><span class="modal-info-label">用户openid</span><span class="modal-info-value" style="font-family:monospace;font-size:12px;">${openid}</span></div>
            <div class="modal-info-row"><span class="modal-info-label">提现金额</span><span class="modal-info-value" style="color:#07c160;font-weight:bold;">¥${fenToYuan(amount)}</span></div>
         </div>`,
        `<button class="modal-btn modal-btn-confirm" onclick="doApprove(${id}, '${openid}')">确认审核通过并打款</button>`
    );
}

function doApprove(id, openid) {
    api('/api/admin/withdraw/approve', { method: 'POST', body: { openid, withdrawId: id } })
        .then(() => {
            showToast('审核通过，打款操作已执行', 'success');
            closeModal();
            loadPendingList();
            if (currentTab === 'all') loadAllList();
            if (currentTab === 'query') loadProcessingList();
        }).catch(err => {
            showToast(err.message, 'error');
        });
}

function openReject(id, openid, amount) {
    openModal('拒绝提现',
        `<div class="modal-notice">拒绝后，冻结的佣金将返还到用户可提现余额中。</div>
         <div class="modal-info">
            <div class="modal-info-row"><span class="modal-info-label">提现单ID</span><span class="modal-info-value">#${id}</span></div>
            <div class="modal-info-row"><span class="modal-info-label">用户openid</span><span class="modal-info-value" style="font-family:monospace;font-size:12px;">${openid}</span></div>
            <div class="modal-info-row"><span class="modal-info-label">提现金额</span><span class="modal-info-value" style="color:#07c160;font-weight:bold;">¥${fenToYuan(amount)}</span></div>
         </div>`,
        `<button class="modal-btn modal-btn-confirm danger" onclick="doReject(${id}, '${openid}')">确认拒绝</button>`
    );
}

function doReject(id, openid) {
    api('/api/admin/withdraw/reject', { method: 'POST', body: { openid, withdrawId: id } })
        .then(() => {
            showToast('已拒绝该提现申请', 'success');
            closeModal();
            loadPendingList();
            if (currentTab === 'all') loadAllList();
        }).catch(err => {
            showToast(err.message, 'error');
        });
}

/* ========== 查询/重试 ========== */
function quickQueryTransfer(withdrawId, openid) {
    document.getElementById('queryOpenid').value = openid;
    document.getElementById('queryWithdrawId').value = withdrawId;
    switchTab('query');
    queryTransfer();
}

function queryTransfer() {
    const openid = document.getElementById('queryOpenid').value.trim();
    const withdrawId = document.getElementById('queryWithdrawId').value.trim();
    if (!openid || !withdrawId) {
        showToast('请输入 openid 和提现单ID', 'error');
        return;
    }
    showToast('查询中(含退避重试, 可能需数秒)...', 'info');
    api('/api/admin/withdraw/queryTransfer?openid=' + encodeURIComponent(openid) + '&withdrawId=' + encodeURIComponent(withdrawId))
        .then(data => {
            const res = (data && data.data) || data || '';
            document.getElementById('queryResult').innerHTML =
                `<div class="result-panel" style="color:#07c160;">✅ ${res || '查询完成'}</div>`;
            showToast('查询完成, 列表已刷新', 'success');
            loadProcessingList();
        }).catch(err => {
            document.getElementById('queryResult').innerHTML =
                `<div class="empty" style="color:#b91c1c;padding:20px;">查询失败：${err.message}</div>`;
            showToast(err.message, 'error');
        });
}

/** 查询tab每行: 重查微信状态按钮 (调用 queryTransfer 接口, 后端含退避重试并自动更新状态) */
function quickQueryAndShow(withdrawId, openid) {
    showToast('查询中(含退避重试, 可能需数秒)...', 'info');
    api('/api/admin/withdraw/queryTransfer?openid=' + encodeURIComponent(openid) + '&withdrawId=' + encodeURIComponent(withdrawId))
        .then(data => {
            const res = (data && data.data) || data || '';
            openModal(`重查结果 - 提现单#${withdrawId}`,
                `<div class="modal-info">
                    <div class="modal-info-row"><span class="modal-info-label">执行结果</span><span class="modal-info-value" style="color:#07c160;font-weight:bold;">${res || '查询完成'}</span></div>
                 </div>
                 <div class="modal-notice" style="margin:0;">系统已根据微信返回状态自动更新提现单, 请关闭后查看最新状态。</div>`,
                `<button class="modal-btn modal-btn-confirm" onclick="closeModal();loadProcessingList();if(currentTab==='pending')loadPendingList();if(currentTab==='all')loadAllList();">关闭并刷新</button>`
            );
        }).catch(err => {
            showToast(err.message, 'error');
        });
}

function openRetry(id, openid) {
    openModal('重试对账 (仅查状态更新)',
        `<div class="modal-notice">根据已有的transferNo查询微信状态，并自动更新提现单状态，不会调用新的转账请求。</div>
         <div class="modal-info">
            <div class="modal-info-row"><span class="modal-info-label">提现单ID</span><span class="modal-info-value">#${id}</span></div>
            <div class="modal-info-row"><span class="modal-info-label">用户openid</span><span class="modal-info-value" style="font-family:monospace;font-size:12px;">${openid}</span></div>
         </div>`,
        `<button class="modal-btn modal-btn-confirm" onclick="doRetry(${id}, '${openid}')">确认重试对账</button>`
    );
}

function doRetry(id, openid) {
    api('/api/admin/withdraw/retry', { method: 'POST', body: { openid, withdrawId: id } })
        .then(data => {
            const res = (data && data.data) || data || {};
            openModal('重试对账结果',
                `<div class="modal-info">
                    <div class="modal-info-row"><span class="modal-info-label">执行动作</span><span class="modal-info-value">${res.action || '-'}</span></div>
                 </div>
                 <div class="result-panel">${JSON.stringify(res, null, 2)}</div>`,
                `<button class="modal-btn modal-btn-confirm" onclick="closeModal();loadPendingList();loadProcessingList();if(currentTab==='all')loadAllList();">关闭并刷新</button>`
            );
        }).catch(err => {
            showToast(err.message, 'error');
        });
}

function retryWithdraw() {
    const openid = document.getElementById('retryOpenid').value.trim();
    const id = Number(document.getElementById('retryWithdrawId').value);
    if (!openid || !id) {
        showToast('请输入openid和提现单ID', 'error');
        return;
    }
    doRetry(id, openid);
}

/** 查询tab每行: 重新发起提现按钮 */
function openReInitiate(id, openid, amount, transferNo) {
    openModal('重新发起提现',
        `<div class="modal-notice">系统会先用当前 transferNo 查询微信最终状态，若已成功则直接标记；若失败/不存在则生成新的 transferNo 再调用微信转账接口。</div>
         <div class="modal-info">
            <div class="modal-info-row"><span class="modal-info-label">提现单ID</span><span class="modal-info-value">#${id}</span></div>
            <div class="modal-info-row"><span class="modal-info-label">用户openid</span><span class="modal-info-value" style="font-family:monospace;font-size:12px;">${openid}</span></div>
            <div class="modal-info-row"><span class="modal-info-label">提现金额</span><span class="modal-info-value" style="color:#07c160;font-weight:bold;">¥${fenToYuan(amount)}</span></div>
            <div class="modal-info-row"><span class="modal-info-label">当前transferNo</span><span class="modal-info-value" style="font-family:monospace;font-size:12px;">${transferNo || '(无)'}</span></div>
         </div>`,
        `<button class="modal-btn modal-btn-confirm" onclick="doReInitiate(${id}, '${openid}')">确认重新发起</button>`
    );
}

function doReInitiate(id, openid) {
    showToast('处理中...', 'info');
    api('/api/admin/withdraw/reInitiate', { method: 'POST', body: { openid, withdrawId: id } })
        .then(data => {
            const res = (data && data.data) || data || {};
            openModal(`重新发起结果 - 提现单#${id}`,
                `<div class="modal-info">
                    <div class="modal-info-row"><span class="modal-info-label">执行动作</span><span class="modal-info-value" style="color:#07c160;font-weight:bold;">${res.action || '-'}</span></div>
                    ${res.newTransferNo ? `<div class="modal-info-row"><span class="modal-info-label">新transferNo</span><span class="modal-info-value" style="font-family:monospace;font-size:12px;">${res.newTransferNo}</span></div>` : ''}
                 </div>
                 <div class="result-panel">${JSON.stringify(res, null, 2)}</div>`,
                `<button class="modal-btn modal-btn-confirm" onclick="closeModal();loadProcessingList();if(currentTab==='pending')loadPendingList();if(currentTab==='all')loadAllList();">关闭并刷新</button>`
            );
        }).catch(err => {
            showToast(err.message, 'error');
        });
}

/* ========== 修改密码 ========== */
function openChangePwd() {
    openModal('修改管理员密码',
        `<div class="form-group">
            <label class="form-label">原密码</label>
            <input class="form-input" type="password" id="cp_old" placeholder="请输入当前密码" autocomplete="current-password">
        </div>
        <div class="form-group">
            <label class="form-label">新密码 (至少6位)</label>
            <input class="form-input" type="password" id="cp_new" placeholder="请输入新密码" autocomplete="new-password">
        </div>
        <div class="form-group">
            <label class="form-label">确认新密码</label>
            <input class="form-input" type="password" id="cp_confirm" placeholder="再次输入新密码" autocomplete="new-password">
        </div>
        <div class="modal-notice" style="margin:0;">🔒 新密码将使用 RSA 公钥加密后传输</div>`,
        `<button class="modal-btn modal-btn-confirm" onclick="doChangePwd()">确认修改</button>`
    );
}

function doChangePwd() {
    const oldPwd = document.getElementById('cp_old').value;
    const newPwd = document.getElementById('cp_new').value;
    const confirmPwd = document.getElementById('cp_confirm').value;

    if (!oldPwd || !newPwd) {
        showToast('请输入密码', 'error');
        return;
    }
    if (newPwd.length < 6) {
        showToast('新密码长度至少6位', 'error');
        return;
    }
    if (newPwd !== confirmPwd) {
        showToast('两次输入的新密码不一致', 'error');
        return;
    }

    showToast('正在加密并提交...', 'info');
    // 1. 先获取 RSA 公钥
    api('/api/admin/publicKey').then(data => {
        const pubKey = (data && data.data) || data;
        if (!pubKey) throw new Error('获取公钥失败');

        // 2. 前端用 RSA 加密新密码
        const encrypt = new JSEncrypt();
        encrypt.setPublicKey(pubKey);
        const encrypted = encrypt.encrypt(newPwd);
        if (!encrypted) {
            throw new Error('RSA加密失败');
        }

        // 3. 提交修改
        return api('/api/admin/changePassword', {
            method: 'POST',
            body: {
                oldPassword: oldPwd,
                encryptedNewPassword: encrypted
            }
        });
    }).then(() => {
        closeModal();
        showToast('密码修改成功, 请重新登录', 'success');
        setTimeout(() => {
            localStorage.removeItem('admin_auth');
            location.href = './login.html';
        }, 1500);
    }).catch(err => {
        showToast(err.message, 'error');
    });
}

/* ========== 初始化 ========== */
(function init() {
    if (!localStorage.getItem('admin_auth')) {
        location.href = './login.html';
        return;
    }
    loadPendingList();
})();
