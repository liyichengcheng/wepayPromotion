// utils/api.js - 接口请求封装

const BASE_URL = 'https://api.example.com'; // 替换为真实后端地址

/**
 * 通用请求方法
 */
function request(options) {
  const app = getApp();
  return new Promise((resolve, reject) => {
    wx.request({
      url: BASE_URL + options.url,
      method: options.method || 'GET',
      data: options.data || {},
      header: {
        'Content-Type': 'application/json',
        'Authorization': app.globalData.token || '',
        ...options.header
      },
      success: (res) => {
        if (res.statusCode === 200) {
          if (res.data.code === 0) {
            resolve(res.data.data);
          } else {
            wx.showToast({ title: res.data.msg || '请求失败', icon: 'none' });
            reject(res.data);
          }
        } else {
          wx.showToast({ title: '网络异常', icon: 'none' });
          reject(res);
        }
      },
      fail: (err) => {
        wx.showToast({ title: '网络请求失败', icon: 'none' });
        reject(err);
      }
    });
  });
}

// ==================== 业务接口 ====================

/**
 * 获取文章详情（含当前价格）
 */
function getArticleDetail(articleId) {
  return request({
    url: `/article/${articleId}`,
    method: 'GET'
  });
}

/**
 * 创建订单（返回微信支付参数）
 */
function createOrder(articleId, inviterId) {
  return request({
    url: '/order/create',
    method: 'POST',
    data: { articleId, inviterId }
  });
}

/**
 * 查询订单状态
 */
function queryOrder(orderNo) {
  return request({
    url: `/order/query/${orderNo}`,
    method: 'GET'
  });
}

/**
 * 申请提现
 */
function applyWithdraw(amount) {
  return request({
    url: '/wallet/withdraw',
    method: 'POST',
    data: { amount }
  });
}

/**
 * 查询钱包余额
 */
function getWalletBalance() {
  return request({
    url: '/wallet/balance',
    method: 'GET'
  });
}

module.exports = {
  request,
  getArticleDetail,
  createOrder,
  queryOrder,
  applyWithdraw,
  getWalletBalance
};
