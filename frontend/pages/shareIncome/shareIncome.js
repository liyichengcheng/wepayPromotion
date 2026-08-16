const app = getApp()
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
    withdrawInput: ""
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
    return {
      title: "学渣逆袭：高考倒状元到百万年薪，快来看看吧！",
      path: app.globalData.shareLink,
      imageUrl: ""
    }
  },

  openWithdrawModal() {
    this.setData({ showWithdrawModal: true, withdrawInput: "" })
  },

  closeWithdrawModal() {
    this.setData({ showWithdrawModal: false })
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
    if (amount > maxAmount) {
      wx.showToast({ title: "提现金额不能超过可提现金额", icon: "none" })
      return
    }

    this.setData({ showWithdrawModal: false })
    wx.showLoading({ title: "提交提现申请" })
    try {
      await app.$request({
        url: "/income/applyWithdraw",
        method: "POST",
        data: { amount: Math.round(amount * 100) }
      })
      wx.hideLoading()
      wx.showToast({ title: "提现申请提交成功" })
      this.getUserIncome()
    } catch (err) {
      wx.hideLoading()
    }
  }
})
