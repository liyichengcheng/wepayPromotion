const app = getApp()
const transferAuth = require("../../utils/transferAuth")
Page({
  data: {
    totalMoney: "0.00",
    todayIncome: "0.00",
    withdrawMoney: "0.00",
    todayCount: 0,
    price: 6,
    comm: "2.00",
    myUid: "",
    statusBarHeight: 20,
    showWithdrawModal: false,
    withdrawInput: "",
    transferAuthEffective: false,
    transferAuthChecking: true,
    showAuthGuideModal: false
  },

  onLoad() {
    const sysInfo = wx.getSystemInfoSync()
    const statusBarHeight = sysInfo.statusBarHeight || 20
    this.setData({ statusBarHeight })
    const uid = app.globalData.userId
    const price = app.globalData.currentPrice || 6
    const comm = app.calcCommission(price) || 2
    this.setData({
      myUid: uid,
      price,
      comm: comm.toFixed(2),
      todayIncome: comm.toFixed(2)
    })
    this.getUserIncome()
    this.checkTransferAuth()
  },

  onShow() {
    if (!this.data.transferAuthChecking) {
      this.checkTransferAuth()
    }
  },

  goBack() {
    wx.navigateBack({
      fail: () => {
        wx.redirectTo({ url: "/pages/articlePreview/articlePreview" })
      }
    })
  },

  async getUserIncome() {
    try {
      const res = await app.$request({
        url: "/income/getUserIncome",
        data: {}
      })
      this.setData({
        totalMoney: res.data.totalIncome || "0.00",
        todayIncome: res.data.todayIncome || this.data.todayIncome,
        withdrawMoney: res.data.withdrawable || "0.00",
        todayCount: res.data.todayCount || 0
      })
    } catch (e) {
      console.error("获取佣金失败", e)
    }
  },

  copyLink() {
    const link = app.globalData.shareLink
    wx.setClipboardData({
      data: link,
      success: () => {
        wx.showToast({ title: "链接已复制，快去分享吧" })
      }
    })
  },

  onShareAppMessage() {
    console.log("shareLink1:"+app.globalData.shareLink)
    return {
      title: "学渣逆袭：高考倒状元到百万年薪，快来看看吧！",
      path: app.globalData.shareLink,
      imageUrl: "http://www.chsl.xyz/xlrz/images/sharePreview.png"
    }
  },

  async checkTransferAuth() {
    try {
      const res = await app.$request({ url: "/income/transferAuthStatus" })
      this.setData({
        transferAuthEffective: res.data.effective,
        transferAuthChecking: false
      })
    } catch (e) {
      this.setData({ transferAuthChecking: false })
    }
  },

  openWithdrawModal() {
    if (this.data.transferAuthChecking) {
      wx.showToast({ title: "正在检查授权状态，请稍候", icon: "none" })
      return
    }
    if (this.data.transferAuthEffective) {
      this.setData({ showWithdrawModal: true, withdrawInput: "" })
      return
    }
    // 可能刚从微信授权页回来, 后端异步通知尚未同步, 重新检查一次再决定
    this.setData({ transferAuthChecking: true })
    wx.showLoading({ title: "检查授权状态" })
    this.checkTransferAuth().then(() => {
      wx.hideLoading()
      if (this.data.transferAuthEffective) {
        this.setData({ showWithdrawModal: true, withdrawInput: "" })
      } else {
        this.setData({ showAuthGuideModal: true })
      }
    })
  },

  closeAuthGuideModal() {
    this.setData({ showAuthGuideModal: false })
  },

  async goAuthorize() {
    wx.showLoading({ title: "准备授权" })
    try {
      const res = await app.$request({
        url: "/income/applyTransferAuth",
        method: "POST",
        silent: true
      })
      const state = res.data.state
      wx.hideLoading()
      if (state === "TAKING_EFFECT") {
        this.setData({
          transferAuthEffective: true,
          showAuthGuideModal: false,
          showWithdrawModal: true,
          withdrawInput: ""
        })
        return
      }

      if (state === "WAIT_USER_CONFIRM") {
        const { package_info, mchId, appId } = res.data
        if (!package_info || !mchId || !appId) {
          wx.showToast({ title: "授权参数缺失，请重试", icon: "none" })
          return
        }
        this.requestTransferAuth({ package_info, mchId, appId })
        return
      }
      wx.showToast({ title: res.data.message || "授权状态异常，请重试", icon: "none" })
    } catch (err) {
      wx.hideLoading()
      if (err && err.msg && err.msg.indexOf("已授权成功") !== -1) {
        this.setData({
          transferAuthEffective: true,
          showAuthGuideModal: false,
          showWithdrawModal: true,
          withdrawInput: ""
        })
        return
      }
      wx.showToast({ title: (err && err.msg) || "授权异常，请重试", icon: "none" })
    }
  },

  requestTransferAuth({ package_info, mchId, appId }) {
    wx.requestMerchantTransfer({
      mchId,
      appId,
      package: package_info,
      success: () => {
        this.setData({ showAuthGuideModal: false })
        this.pollTransferAuthStatus(0)
      },
      fail: () => {
        wx.showToast({ title: "授权已取消", icon: "none" })
      }
    })
  },

  pollTransferAuthStatus(attempt) {
    // 后端收到微信异步通知可能存在延迟, 增加重试次数与间隔
    // 总耗时约 30s (10次 * 3s), 覆盖微信回调延迟
    const maxAttempts = 10
    const interval = 3000
    if (attempt === 0) {
      wx.showLoading({ title: "检查授权状态" })
    }
    setTimeout(() => {
      this.checkTransferAuth().then(() => {
        if (this.data.transferAuthEffective) {
          wx.hideLoading()
          wx.showToast({ title: "授权成功" })
          this.setData({ showWithdrawModal: true, withdrawInput: "" })
        } else if (attempt < maxAttempts - 1) {
          this.pollTransferAuthStatus(attempt + 1)
        } else {
          wx.hideLoading()
          wx.showModal({
            title: "授权结果未同步",
            content: "若您已在微信内点击确认授权, 请稍后返回此页面再次点击提现; 若未确认, 请重新点击去授权。",
            showCancel: false,
            confirmText: "我知道了"
          })
        }
      }).catch(() => {
        if (attempt < maxAttempts - 1) {
          this.pollTransferAuthStatus(attempt + 1)
        } else {
          wx.hideLoading()
          wx.showToast({ title: "授权状态查询失败, 请稍后重试", icon: "none" })
        }
      })
    }, interval)
  },

  closeWithdrawModal() {
    this.setData({ showWithdrawModal: false })
  },

  terminateTransferAuth() {
    transferAuth.terminateTransferAuth(this)
  },

  onWithdrawInput(e) {
    this.setData({ withdrawInput: e.detail.value })
  },

  async confirmWithdraw() {
    const amount = parseFloat(this.data.withdrawInput)
    const maxAmount = parseFloat(this.data.withdrawMoney)

    if (!amount || amount <= 0) {
      wx.showToast({ title: "请输入提现金额", icon: "none" })
      return
    }

    if (amount < 0.1) {
      wx.showToast({ title: "最低提现 0.1 元", icon: "none" })
      return
    }
    if (amount > maxAmount) {
      wx.showToast({ title: "提现金额不能超过可提现金额", icon: "none" })
      return
    }

    this.setData({ showWithdrawModal: false })
    wx.showLoading({ title: "提交提现申请" })
    try {
      const res = await app.$request({
        url: "/income/applyWithdraw",
        method: "POST",
        data: { amount: Math.round(amount * 100) },
        silent: true
      })
      wx.hideLoading()
      // 后端风控: 累计提现达阈值需先实名认证 (正常200响应, data为提示文案)
      const dataMsg = typeof res.data === "string" ? res.data : ""
      if (dataMsg && dataMsg.indexOf("实名认证") !== -1) {
        wx.showModal({
          title: "需要实名认证",
          content: dataMsg,
          confirmText: "去认证",
          cancelText: "稍后",
          success: (m) => {
            if (m.confirm) {
              wx.navigateTo({ url: "/pages/realNameAuth/realNameAuth" })
            }
          }
        })
        this.getUserIncome()
        return
      }
      wx.showToast({
        title: dataMsg,
        icon: 'none', 
        duration: 2000
      });
      // 根据授权状态展示不同提示
      if (!this.data.transferAuthEffective) {
        // 未授权用户确认模式: 需要在微信内点击确认收款
        wx.showModal({
          title: "提现申请已提交",
          content: "请在微信服务通知中点击确认收款, 完成后资金将到账零钱。建议授权免确认收款以简化后续提现。",
          showCancel: false,
          confirmText: "我知道了"
        })
      }
      this.getUserIncome()
    } catch (err) {
      wx.hideLoading()
      // 限流: 每小时只能提现一次
      const msg = (err && err.msg) || "提现失败, 请稍后重试"
      if (msg.indexOf("每小时") !== -1 || msg.indexOf("频繁") !== -1) {
        wx.showToast({ title: "每小时只能提现一次, 请稍后再试", icon: "none" })
      } else if (msg.indexOf("余额") !== -1 || msg.indexOf("可提现") !== -1) {
        wx.showToast({ title: "可提现余额不足", icon: "none" })
      } else if (msg.indexOf("审核") !== -1) {
        wx.showToast({ title: "提现申请已提交, 等待审核", icon: "none" })
      } else {
        wx.showToast({ title: msg, icon: "none" })
      }
      this.getUserIncome()
    }
  }
})
